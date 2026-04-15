package com.opensource.i2pradio.auto

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioDatabase
import com.opensource.i2pradio.data.RadioStation
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Android Auto-facing media library service.
 *
 * This is an **opt-in** service: it is declared `android:enabled="false"`
 * in the manifest and only becomes active when the user explicitly enables
 * Android Auto support in Settings (see [AndroidAutoComponentManager]).
 * Until then, Android Auto cannot bind to the app and the app does not
 * appear in the car's media source list.
 *
 * Privacy constraints carried over from the rest of the app:
 *
 *  - Only stations with [ProxyType.NONE] (no Tor / I2P / custom proxy) are
 *    exposed in the browse tree. Privacy-routed stations stay invisible to
 *    Android Auto so that enabling AA never downgrades the anonymity of a
 *    station the user configured for Tor or I2P.
 *  - Playback from Android Auto uses a dedicated, browse-only ExoPlayer
 *    instance with a default HTTP stack. It deliberately does not share
 *    state with the main [com.opensource.i2pradio.RadioService], which
 *    continues to handle in-app playback with the full Tor / I2P / proxy
 *    stack.
 */
class DeutsiaMediaLibraryService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null

    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DeutsiaAuto-DB").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exoPlayer

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaLibrarySession.Builder(this, exoPlayer, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(buildRoot(), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val callable = Callable<LibraryResult<MediaItem>> {
                val item = when {
                    mediaId == ROOT_ID -> buildRoot()
                    mediaId == NODE_FAVORITES -> buildBrowsableFolder(
                        id = NODE_FAVORITES,
                        title = getString(R.string.auto_node_favorites)
                    )
                    mediaId == NODE_ALL -> buildBrowsableFolder(
                        id = NODE_ALL,
                        title = getString(R.string.auto_node_all_stations)
                    )
                    mediaId.startsWith(STATION_PREFIX) -> loadStationItem(mediaId)
                    else -> null
                }
                if (item == null) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItem(item, null)
                }
            }
            return Futures.submit(callable, backgroundExecutor)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val callable = Callable<LibraryResult<ImmutableList<MediaItem>>> {
                val children = when (parentId) {
                    ROOT_ID -> rootChildren()
                    NODE_FAVORITES -> stationsAsItems(favoritesOnly = true)
                    NODE_ALL -> stationsAsItems(favoritesOnly = false)
                    else -> emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            }
            return Futures.submit(callable, backgroundExecutor)
        }

        /**
         * Convert play requests into real media items. Android Auto hands us
         * back the [MediaItem]s we emitted in the browse tree; we use the
         * station id in [MediaItem.mediaId] to re-resolve the stream URL
         * from the database so that we never trust stream URLs that might
         * have been cached stale in a MediaItem.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val callable = Callable<MutableList<MediaItem>> {
                mediaItems.mapNotNull { item ->
                    val mediaId = item.mediaId
                    if (mediaId.startsWith(STATION_PREFIX)) loadStationItem(mediaId) else null
                }.toMutableList()
            }
            return Futures.submit(callable, backgroundExecutor)
        }
    }

    // -----------------------------------------------------------------------
    // Browse tree construction
    // -----------------------------------------------------------------------

    private fun buildRoot(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(getString(R.string.app_name))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun buildBrowsableFolder(id: String, title: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun rootChildren(): List<MediaItem> = listOf(
        buildBrowsableFolder(NODE_FAVORITES, getString(R.string.auto_node_favorites)),
        buildBrowsableFolder(NODE_ALL, getString(R.string.auto_node_all_stations))
    )

    private fun stationsAsItems(favoritesOnly: Boolean): List<MediaItem> {
        val dao = RadioDatabase.getDatabase(this).radioDao()
        val stations = kotlinx.coroutines.runBlocking { dao.getAllStationsSync() }
        return stations
            .asSequence()
            .filter { it.getProxyTypeEnum() == ProxyType.NONE }
            .filter { !favoritesOnly || it.isLiked }
            .map { stationToMediaItem(it) }
            .toList()
    }

    private fun loadStationItem(mediaId: String): MediaItem? {
        val stationId = mediaId.removePrefix(STATION_PREFIX).toLongOrNull() ?: return null
        val dao = RadioDatabase.getDatabase(this).radioDao()
        val station = kotlinx.coroutines.runBlocking { dao.getStationById(stationId) } ?: return null
        // Honor the same privacy rule as the browse tree: refuse to resolve a
        // proxied station even if Android Auto somehow asked for one by id.
        if (station.getProxyTypeEnum() != ProxyType.NONE) return null
        return stationToMediaItem(station)
    }

    private fun stationToMediaItem(station: RadioStation): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(station.name)
            .setArtist(station.genre.ifBlank { null })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        station.coverArtUri?.takeIf { it.isNotBlank() }?.let { uri ->
            metadataBuilder.setArtworkUri(android.net.Uri.parse(uri))
        }
        return MediaItem.Builder()
            .setMediaId("$STATION_PREFIX${station.id}")
            .setUri(station.streamUrl)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    companion object {
        private const val ROOT_ID = "root"
        private const val NODE_FAVORITES = "favorites"
        private const val NODE_ALL = "all_stations"
        private const val STATION_PREFIX = "station:"
    }
}

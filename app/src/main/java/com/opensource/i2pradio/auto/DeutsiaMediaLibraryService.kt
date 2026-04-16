package com.opensource.i2pradio.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import com.opensource.i2pradio.ui.PreferencesHelper
import okhttp3.OkHttpClient
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 *  - By default, only stations with [ProxyType.NONE] (no Tor / I2P /
 *    custom proxy) are exposed in the browse tree. Privacy-routed stations
 *    stay invisible to Android Auto so that enabling AA never silently
 *    downgrades the anonymity of a station the user configured for Tor or
 *    I2P. The user can opt in to exposing proxy stations via the "Show
 *    proxy stations in Android Auto" toggle in Settings (with its own
 *    metadata-leak warning); when that toggle is on, this service also
 *    routes the AA player through the matching per-station proxy via
 *    [AndroidAutoProxyHttp].
 *  - The AA-facing ExoPlayer is dedicated to this service and does not
 *    share state with the main [com.opensource.i2pradio.RadioService].
 *
 * Two-moment opt-in flow:
 *
 *  1. **Settings opt-in (Moment 1)** — handled in `SettingsFragment`. User
 *     reads the explainer and flips the AA switch. Until they do, this
 *     service is disabled at the manifest/PackageManager level.
 *  2. **First connect (Moment 2)** — handled here. The first time Android
 *     Auto actually binds to this service (detected via the controller's
 *     package name in [LibraryCallback.onConnect]), we post a one-time
 *     notification on the phone offering to manage the proxy-stations
 *     setting. Persists until tapped or dismissed; safe defaults stay in
 *     effect either way.
 */
class DeutsiaMediaLibraryService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var dataSourceFactory: StationProxyDataSourceFactory? = null

    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DeutsiaAuto-DB").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        val proxyAwareFactory = StationProxyDataSourceFactory(applicationContext)
        dataSourceFactory = proxyAwareFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(applicationContext)
            .setDataSourceFactory(proxyAwareFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
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
        dataSourceFactory = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /**
         * The first thing Android Auto does after binding is request the
         * library root, so this is the cleanest hook for "AA just connected
         * for the first time". We piggy-back the Moment-2 notification on
         * it. Identifying the caller by package name distinguishes AA from
         * the in-app player, the DHU test harness, etc.
         */
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (isAndroidAutoPackage(browser.packageName)) {
                maybePostFirstConnectNotification()
            }
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
         *
         * As a side effect, we *also* tell the [StationProxyDataSourceFactory]
         * which station is about to play, so that the OkHttpClient it
         * vends to ExoPlayer is configured for that station's proxy
         * (Tor / I2P / custom / direct). This is the wiring that makes
         * proxy-station playback actually route correctly from AA — without
         * it, proxy stations would either not play or, worse, leak through
         * the default HTTP stack.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val callable = Callable<MutableList<MediaItem>> {
                val factory = dataSourceFactory
                mediaItems.mapNotNull { item ->
                    val mediaId = item.mediaId
                    if (!mediaId.startsWith(STATION_PREFIX)) return@mapNotNull null
                    val station = loadStation(mediaId) ?: return@mapNotNull null
                    if (factory == null) return@mapNotNull null
                    factory.setStation(station)
                    stationToMediaItem(station)
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
        val allowProxy = PreferencesHelper.isAndroidAutoProxyStationsAllowed(this)
        return stations
            .asSequence()
            .filter { allowProxy || it.getProxyTypeEnum() == ProxyType.NONE }
            .filter { !favoritesOnly || it.isLiked }
            .map { stationToMediaItem(it) }
            .toList()
    }

    /**
     * Re-resolve a station from the DB by mediaId, applying the same
     * privacy filter as the browse tree: refuse to return a proxy station
     * unless the user has opted in to proxy stations in AA. This is the
     * defense-in-depth check for the play path — even if a stale browse
     * entry pointed at a proxy station that is now disallowed, we won't
     * play it.
     */
    private fun loadStation(mediaId: String): RadioStation? {
        val stationId = mediaId.removePrefix(STATION_PREFIX).toLongOrNull() ?: return null
        val dao = RadioDatabase.getDatabase(this).radioDao()
        val station = kotlinx.coroutines.runBlocking { dao.getStationById(stationId) } ?: return null
        if (station.getProxyTypeEnum() != ProxyType.NONE &&
            !PreferencesHelper.isAndroidAutoProxyStationsAllowed(this)
        ) {
            return null
        }
        return station
    }

    private fun loadStationItem(mediaId: String): MediaItem? =
        loadStation(mediaId)?.let { stationToMediaItem(it) }

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

    // -----------------------------------------------------------------------
    // First-connect notification (Moment 2)
    // -----------------------------------------------------------------------

    private fun isAndroidAutoPackage(pkg: String): Boolean {
        // Both the production AA app and the desktop head-unit (DHU) used
        // for testing connect under one of these package names.
        return pkg == AA_PROJECTION_PACKAGE ||
            pkg == AA_EMBEDDED_PACKAGE ||
            pkg == AA_DHU_PACKAGE
    }

    private fun maybePostFirstConnectNotification() {
        val ctx = applicationContext
        if (PreferencesHelper.hasAndroidAutoFirstConnectBeenHandled(ctx)) return
        // Mark as handled *before* posting so that even if the post fails
        // (notifications disabled, etc.) we don't keep trying every connect.
        PreferencesHelper.setAndroidAutoFirstConnectHandled(ctx, true)
        postFirstConnectNotification()
    }

    private fun postFirstConnectNotification() {
        ensureNotificationChannel()
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SCROLL_TO_TAB, MainActivity.TAB_SETTINGS)
            putExtra(MainActivity.EXTRA_SETTINGS_SECTION, MainActivity.SETTINGS_SECTION_ANDROID_AUTO)
            // Bring an existing MainActivity to the front rather than stacking.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            FIRST_CONNECT_REQUEST_CODE,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, AA_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(getString(R.string.aa_first_connect_title))
            .setContentText(getString(R.string.aa_first_connect_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.aa_first_connect_text))
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID_FIRST_CONNECT, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied (Android 13+). Nothing to do — the
            // safe defaults (proxy stations hidden) already apply.
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(AA_NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            AA_NOTIFICATION_CHANNEL_ID,
            getString(R.string.aa_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.aa_notification_channel_description)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val ROOT_ID = "root"
        private const val NODE_FAVORITES = "favorites"
        private const val NODE_ALL = "all_stations"
        private const val STATION_PREFIX = "station:"

        // Known package names for clients we treat as "Android Auto" for
        // the first-connect detection. Production AA on the phone, AA on
        // an embedded car head unit, and the desktop head-unit harness
        // developers use for testing.
        private const val AA_PROJECTION_PACKAGE = "com.google.android.projection.gearhead"
        private const val AA_EMBEDDED_PACKAGE = "com.google.android.embedded.projection"
        private const val AA_DHU_PACKAGE = "com.google.android.projection.gearhead.emulator"

        private const val AA_NOTIFICATION_CHANNEL_ID = "deutsia_android_auto"
        private const val NOTIFICATION_ID_FIRST_CONNECT = 4101
        private const val FIRST_CONNECT_REQUEST_CODE = 4102
    }
}

/**
 * A [DataSource.Factory] whose underlying OkHttpClient can be re-configured
 * per-station. The Android Auto service calls [setStation] in
 * `onAddMediaItems` (right before ExoPlayer starts requesting the stream),
 * so the very next [createDataSource] call returns a DataSource whose
 * OkHttpClient is wired for that station's proxy.
 *
 * This is intentionally simple: it assumes only one station is being
 * prepared at a time, which is the typical AA usage pattern (tap a station
 * → it plays). Queueing multiple stations with different proxy
 * configurations is not supported and is unnecessary for the current AA
 * surface.
 */
private class StationProxyDataSourceFactory(
    private val context: Context
) : DataSource.Factory {

    @Volatile
    private var currentClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun setStation(station: RadioStation) {
        currentClient = AndroidAutoProxyHttp.buildClient(context, station)
    }

    override fun createDataSource(): DataSource {
        return OkHttpDataSource.Factory(currentClient)
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
            .createDataSource()
    }

    companion object {
        private const val USER_AGENT = "DeutsiaRadio/1.0 (AA)"
    }
}

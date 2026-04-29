package com.opensource.i2pradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import com.opensource.i2pradio.audio.EqualizerManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.metadata.icy.IcyInfo
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.i2p.I2PManager
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import com.opensource.i2pradio.util.BandwidthTrackingInterceptor
import com.opensource.i2pradio.util.SecureImageLoader
import com.opensource.i2pradio.utils.DigestAuthenticator
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Dns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RadioService : Service() {
    private var player: ExoPlayer? = null
    private val binder = RadioBinder()
    @Volatile
    private var currentStreamUrl: String? = null
    private var currentProxyHost: String? = null
    private var currentProxyPort: Int = 4444
    private var currentProxyType: ProxyType = ProxyType.NONE
    private var currentCustomProxyProtocol: String = "HTTP"
    private var currentProxyUsername: String = ""
    private var currentProxyPassword: String = ""
    private var currentProxyAuthType: String = "NONE"
    private var currentProxyConnectionTimeout: Int = 30
    // Hints from Radio Browser. Used for media-source selection, FLV
    // rejection, and recording extension. Default values match manually-added
    // stations (no hint available, behaviour identical to previous versions).
    private var currentHlsHint: Boolean = false
    private var currentCodecHint: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var currentOkHttpClient: OkHttpClient? = null
    private val okHttpClientLock = Any()

    private val isStartingNewStream = AtomicBoolean(false)

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    @Volatile private var hasAudioFocus = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                android.util.Log.d("RadioService", "Audio focus gained")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                android.util.Log.d("RadioService", "Audio focus lost permanently")
                handler.post {
                    stopStream()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                android.util.Log.d("RadioService", "Audio focus lost transiently")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                android.util.Log.d("RadioService", "Audio focus ducking")
            }
        }
    }
// Broadcast receiver to pause playback when audio output device disconnects (e.g., Bluetooth)
private val becomingNoisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            android.util.Log.d("RadioService", "Audio becoming noisy - pausing playback")
            player?.let { exoPlayer ->
                if (exoPlayer.isPlaying) {
                    pauseOrStopForLive()
                }
            }
        }
    }
}
    private var becomingNoisyReceiverRegistered = false

    private var mediaSession: MediaSessionCompat? = null
    private var currentCoverArtUri: String? = null
    private val sessionDeactivateHandler = Handler(Looper.getMainLooper())
    private var sessionDeactivateRunnable: Runnable? = null
    private val SESSION_DEACTIVATE_DELAY = 5 * 60 * 1000L // 5 minutes

    private var isRecording = false
    private var currentStationName: String = "Unknown Station"
    private var recordingCall: Call? = null
    private var recordingThread: Thread? = null
    private var recordingOutputStream: OutputStream? = null
    private val isRecordingActive = AtomicBoolean(false)
    private var recordingFile: File? = null
    private var recordingMediaStoreUri: Uri? = null  // For Android 10+ MediaStore recordings

    @Volatile private var isLiveStream: Boolean = false
    @Volatile private var pendingRecordingStreamUrl: String? = null
    @Volatile private var pendingRecordingProxyHost: String? = null
    @Volatile private var pendingRecordingProxyPort: Int = 0
    @Volatile private var pendingRecordingProxyType: ProxyType = ProxyType.NONE
    private val switchStreamRequested = AtomicBoolean(false)

    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerEndTime: Long = 0L

    private var reconnectRunnable: Runnable? = null

    private var currentMetadata: String? = null
    private var currentArtist: String? = null
    private var currentTitle: String? = null
    private var currentBitrate: Int = 0
    private var currentCodec: String? = null

    private var playbackStartTimeMillis: Long = 0L
    private var playbackTimeUpdateRunnable: Runnable? = null
    private val playbackTimeUpdateInterval = 1000L // Update every second

    private var equalizerManager: EqualizerManager? = null

    // Playback queue. Populated from EXTRA_CONTEXT_STATION_IDS / _INDEX when
    // the user starts playback from a list (Library, Favorites, etc.) and
    // walked by skip-next / skip-previous from the notification, lock
    // screen, Bluetooth / AVRCP, and the in-app Now Playing UI.
    private val playbackQueue = com.opensource.i2pradio.playback.Queue()
    // The station currently playing, kept in sync with the queue so skip
    // actions can compare candidates against it for the "same station"
    // dedupe in DiscoverEngine.
    @Volatile
    private var currentQueueStation: com.opensource.i2pradio.data.RadioStation? = null

    companion object {
        const val ACTION_PLAY = "com.opensource.i2pradio.PLAY"
        const val ACTION_PAUSE = "com.opensource.i2pradio.PAUSE"
        const val ACTION_STOP = "com.opensource.i2pradio.STOP"
        const val ACTION_START_RECORDING = "com.opensource.i2pradio.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.opensource.i2pradio.STOP_RECORDING"
        const val ACTION_SWITCH_RECORDING_STREAM = "com.opensource.i2pradio.SWITCH_RECORDING_STREAM"
        const val ACTION_SET_SLEEP_TIMER = "com.opensource.i2pradio.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.opensource.i2pradio.CANCEL_SLEEP_TIMER"
        // Queue actions. ACTION_PLAY accepts optional extras
        // EXTRA_CONTEXT_STATION_IDS (LongArray) and EXTRA_CONTEXT_INDEX (Int)
        // to seed the playback context the user was browsing. Skip-next /
        // skip-previous walk that context; "play next" and "add to queue"
        // insert into the manual queue ahead of the context.
        const val ACTION_SKIP_NEXT = "com.opensource.i2pradio.SKIP_NEXT"
        const val ACTION_SKIP_PREVIOUS = "com.opensource.i2pradio.SKIP_PREVIOUS"
        const val ACTION_PLAY_NEXT = "com.opensource.i2pradio.PLAY_NEXT"
        const val ACTION_ADD_TO_QUEUE = "com.opensource.i2pradio.ADD_TO_QUEUE"
        const val ACTION_CLEAR_QUEUE = "com.opensource.i2pradio.CLEAR_QUEUE"
        const val EXTRA_CONTEXT_STATION_IDS = "context_station_ids"
        const val EXTRA_CONTEXT_INDEX = "context_index"
        const val EXTRA_QUEUE_STATION_ID = "queue_station_id"
        const val BROADCAST_QUEUE_CHANGED = "com.opensource.i2pradio.QUEUE_CHANGED"
        const val CHANNEL_ID = "DeutsiaRadioChannel"
        const val NOTIFICATION_ID = 1

        const val BROADCAST_METADATA_CHANGED = "com.opensource.i2pradio.METADATA_CHANGED"
        const val BROADCAST_STREAM_INFO_CHANGED = "com.opensource.i2pradio.STREAM_INFO_CHANGED"
        const val BROADCAST_PLAYBACK_STATE_CHANGED = "com.opensource.i2pradio.PLAYBACK_STATE_CHANGED"
        const val BROADCAST_RECORDING_ERROR = "com.opensource.i2pradio.RECORDING_ERROR"
        const val BROADCAST_RECORDING_COMPLETE = "com.opensource.i2pradio.RECORDING_COMPLETE"
        const val EXTRA_METADATA = "metadata"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_TITLE = "title"

        // Common delimiters used in ICY metadata to separate artist and title
        private val METADATA_DELIMITERS = listOf(" - ", " – ", " — ", " | ", " / ", " • ", " : ")
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_IS_BUFFERING = "is_buffering"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_SIZE = "file_size"

        const val BROADCAST_COVER_ART_CHANGED = "com.opensource.i2pradio.COVER_ART_CHANGED"
        const val EXTRA_COVER_ART_URI = "cover_art_uri"
        const val EXTRA_STATION_ID = "station_id"

        const val BROADCAST_PLAYBACK_TIME_UPDATE = "com.opensource.i2pradio.PLAYBACK_TIME_UPDATE"
        const val EXTRA_PLAYBACK_ELAPSED_MS = "playback_elapsed_ms"
        const val EXTRA_BUFFERED_POSITION_MS = "buffered_position_ms"
        const val EXTRA_CURRENT_POSITION_MS = "current_position_ms"

        const val BROADCAST_STREAM_ERROR = "com.opensource.i2pradio.STREAM_ERROR"
        const val EXTRA_STREAM_ERROR_TYPE = "stream_error_type"
        const val ERROR_TYPE_TOR_NOT_CONNECTED = "tor_not_connected"
        const val ERROR_TYPE_I2P_NOT_CONNECTED = "i2p_not_connected"
        const val ERROR_TYPE_CUSTOM_PROXY_NOT_CONFIGURED = "custom_proxy_not_configured"
        const val ERROR_TYPE_MAX_RETRIES = "max_retries"
        const val ERROR_TYPE_STREAM_FAILED = "stream_failed"
        const val ERROR_TYPE_UNSUPPORTED_CODEC = "unsupported_codec"
        const val ERROR_TYPE_PLAYLIST_UNREADABLE = "playlist_unreadable"
        const val EXTRA_UNSUPPORTED_CODEC_NAME = "unsupported_codec_name"
        // Distinct error types for specific HTTP response codes. These let
        // the UI show a targeted message instead of the generic
        // "reconnect loop" that an opaque STREAM_FAILED would trigger.
        const val ERROR_TYPE_STATION_GEOBLOCKED = "station_geoblocked"   // 403 / 451
        const val ERROR_TYPE_STATION_GONE = "station_gone"               // 404 / 410
        const val ERROR_TYPE_STATION_AUTH_REQUIRED = "station_auth_required"  // 401

        /**
         * Custom DNS resolver that forces DNS resolution through SOCKS5 proxy.
         *
         * By default, OkHttp resolves DNS locally BEFORE connecting through SOCKS,
         * which leaks DNS queries to clearnet. This resolver returns a placeholder
         * address, forcing the SOCKS5 proxy (Tor) to handle DNS resolution.
         */
        private val SOCKS5_DNS = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                android.util.Log.d("RadioService", "DNS lookup for '$hostname' - delegating to SOCKS5 proxy")
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
            }
        }
    }

    inner class RadioBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        equalizerManager = EqualizerManager(this)
        initializeMediaSession()

        // Initialize I2P proxy availability monitoring (background health checks)
        I2PManager.initialize()

        // Register receiver to pause playback when audio output device disconnects
        val becomingNoisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, becomingNoisyFilter)
        becomingNoisyReceiverRegistered = true

        // Pull the saved queue back into memory so skip-next/prev work
        // immediately if AVRCP fires before any new ACTION_PLAY does. A
        // fresh ACTION_PLAY with explicit context still overwrites this.
        restoreQueueFromPrefs()
    }

    private fun initializeMediaSession() {
    mediaSession = MediaSessionCompat(this, "DeutsiaRadioSession").apply {
        val sessionActivityIntent = Intent(this@RadioService, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this@RadioService,
            99,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setSessionActivity(sessionActivityPendingIntent)

        @Suppress("DEPRECATION")
        setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)

        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                player?.play()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }

            override fun onPause() {
                pauseOrStopForLive()
            }

            override fun onStop() {
                val stopIntent = Intent(this@RadioService, RadioService::class.java).apply {
                    action = ACTION_STOP
                }
                startService(stopIntent)
            }

            override fun onSkipToNext() {
                val skipIntent = Intent(this@RadioService, RadioService::class.java).apply {
                    action = ACTION_SKIP_NEXT
                }
                startService(skipIntent)
            }

            override fun onSkipToPrevious() {
                val skipIntent = Intent(this@RadioService, RadioService::class.java).apply {
                    action = ACTION_SKIP_PREVIOUS
                }
                startService(skipIntent)
            }
        })
    }
}
    private fun updatePlaybackState(state: Int) {
        // Skip actions are only advertised when the queue can actually move
        // in that direction. AVRCP / lock-screen UIs use the action mask to
        // enable or grey out their next/previous buttons; advertising skip
        // when the queue is at the end would give the user a button that
        // does nothing.
        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        if (playbackQueue.hasNext() || isDiscoverEnabled()) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        }
        if (playbackQueue.hasPrevious()) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }

        val playbackStateBuilder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)

        mediaSession?.setPlaybackState(playbackStateBuilder.build())
        lastPlaybackStateForActionRefresh = state
    }

    private fun isDiscoverEnabled(): Boolean =
        com.opensource.i2pradio.ui.PreferencesHelper.isDiscoverEnabled(this)

    private fun updateMediaMetadata(stationName: String, coverArtUri: String?, isPrivacyStation: Boolean = false) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stationName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stationName)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "deutsia radio")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "deutsia radio")

        if (!coverArtUri.isNullOrEmpty()) {
            mediaSession?.setMetadata(metadataBuilder.build())
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(this@RadioService)
                        .data(coverArtUri)
                        .allowHardware(false)
                        .build()

                    val result = SecureImageLoader.execute(this@RadioService, request, isPrivacyStation)
                    if (result is SuccessResult) {
                        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            val updatedMetadata = metadataBuilder
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                                .build()

                            handler.post {
                                mediaSession?.setMetadata(updatedMetadata)
                            }
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RadioService", "Failed to load cover art for media session", e)
                }

                handler.post {
                    mediaSession?.setMetadata(metadataBuilder.build())
                }
            }
        } else {
            mediaSession?.setMetadata(metadataBuilder.build())
        }
    }

    private fun activateMediaSession() {
        cancelSessionDeactivation()
        mediaSession?.isActive = true
        android.util.Log.d("RadioService", "MediaSession activated")
    }

    private fun deactivateMediaSession() {
        mediaSession?.isActive = false
        android.util.Log.d("RadioService", "MediaSession deactivated")
    }

    private fun scheduleSessionDeactivation() {
        cancelSessionDeactivation()
        sessionDeactivateRunnable = Runnable {
            deactivateMediaSession()
        }
        sessionDeactivateRunnable?.let { runnable ->
            sessionDeactivateHandler.postDelayed(runnable, SESSION_DEACTIVATE_DELAY)
            android.util.Log.d("RadioService", "Scheduled MediaSession deactivation in ${SESSION_DEACTIVATE_DELAY / 1000}s")
        }
    }

    private fun cancelSessionDeactivation() {
        sessionDeactivateRunnable?.let {
            sessionDeactivateHandler.removeCallbacks(it)
            sessionDeactivateRunnable = null
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
        ACTION_PLAY -> {
            val streamUrl = intent.getStringExtra("stream_url") ?: return START_NOT_STICKY
            val proxyHost = intent.getStringExtra("proxy_host") ?: ""
            val proxyPort = intent.getIntExtra("proxy_port", 4444)
            val proxyTypeStr = intent.getStringExtra("proxy_type") ?: ProxyType.NONE.name
            val proxyType = ProxyType.fromString(proxyTypeStr)
            val stationName = intent.getStringExtra("station_name") ?: "Unknown Station"
            val coverArtUri = intent.getStringExtra("cover_art_uri")

            // Custom proxy fields
            val customProxyProtocol = intent.getStringExtra("custom_proxy_protocol") ?: "HTTP"
            val proxyUsername = intent.getStringExtra("proxy_username") ?: ""
            val proxyPassword = intent.getStringExtra("proxy_password") ?: ""
            val proxyAuthType = intent.getStringExtra("proxy_auth_type") ?: "NONE"
            val proxyConnectionTimeout = intent.getIntExtra("proxy_connection_timeout", 30)
            // Hints from the station catalog. These are authoritative
            // signals from Radio Browser used to pick the media source
            // type and recording extension before we've seen any traffic.
            val hlsHint = intent.getBooleanExtra("hls_hint", false)
            val codecHint = intent.getStringExtra("codec_hint") ?: ""

            currentStreamUrl = streamUrl
            currentProxyHost = proxyHost
            currentProxyPort = proxyPort
            currentProxyType = proxyType
            currentCustomProxyProtocol = customProxyProtocol
            currentProxyUsername = proxyUsername
            currentProxyPassword = proxyPassword
            currentProxyAuthType = proxyAuthType
            currentProxyConnectionTimeout = proxyConnectionTimeout
            currentHlsHint = hlsHint
            currentCodecHint = codecHint
            currentStationName = stationName
            currentCoverArtUri = coverArtUri
            reconnectAttempts = 0

            startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_connecting)))
            broadcastPlaybackStateChanged(isBuffering = true, isPlaying = false)
            activateMediaSession()
            updateMediaMetadata(stationName, coverArtUri, proxyType == ProxyType.TOR || proxyType == ProxyType.I2P)
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)

            // Reject FLV streams up front - ExoPlayer can't decode them and
            // users will otherwise hit a confusing playback error.
            if (isFlvCodec(codecHint)) {
                rejectUnsupportedCodec("FLV")
                return START_STICKY
            }

            startPlayAfterResolve(
                streamUrl, proxyHost, proxyPort, proxyType,
                customProxyProtocol, proxyUsername, proxyPassword,
                proxyAuthType, proxyConnectionTimeout,
                hlsHint, codecHint
            )

            // Seed the playback queue with the context list (if any) and
            // record the current station so skip-next/prev and Discover have
            // something to work with. Done async because RadioStation lookups
            // hit the DB and we don't want to block startup of the player.
            val playStationId = intent.getLongExtra("station_id", 0L)
            val contextIds = intent.getLongArrayExtra(EXTRA_CONTEXT_STATION_IDS)
            val contextIndex = intent.getIntExtra(EXTRA_CONTEXT_INDEX, -1)
            seedQueueAsync(playStationId, contextIds, contextIndex)
        }
        ACTION_PAUSE -> {
            pauseOrStopForLive()
        }
        ACTION_STOP -> {
            stopRecording()
            currentStreamUrl = null
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
            deactivateMediaSession()
            stopStream()
            stopForeground(true)
            stopSelf()
        }
        ACTION_START_RECORDING -> {
            val stationName = intent.getStringExtra("station_name") ?: "Unknown Station"
            startRecording(stationName)
        }
        ACTION_STOP_RECORDING -> {
            stopRecording()
        }
        ACTION_SWITCH_RECORDING_STREAM -> {
            val newStreamUrl = intent.getStringExtra("stream_url") ?: return START_NOT_STICKY
            val newProxyHost = intent.getStringExtra("proxy_host") ?: ""
            val newProxyPort = intent.getIntExtra("proxy_port", 4444)
            val newProxyTypeStr = intent.getStringExtra("proxy_type") ?: ProxyType.NONE.name
            val newProxyType = ProxyType.fromString(newProxyTypeStr)
            val newStationName = intent.getStringExtra("station_name") ?: "Unknown Station"
            val newHlsHint = intent.getBooleanExtra("hls_hint", false)
            val newCodecHint = intent.getStringExtra("codec_hint") ?: ""

            serviceScope.launch(Dispatchers.IO) {
                val resolution = resolveStreamUrlBlocking(
                    newStreamUrl, newProxyHost, newProxyPort, newProxyType,
                    newHlsHint, newCodecHint
                )
                val resolvedUrl = resolution?.url ?: newStreamUrl
                val effectiveHlsHint = newHlsHint || (resolution?.hlsDetected == true)
                handler.post {
                    switchRecordingStream(
                        resolvedUrl, newProxyHost, newProxyPort, newProxyType,
                        newStationName, effectiveHlsHint, newCodecHint
                    )
                }
            }
        }
        ACTION_SET_SLEEP_TIMER -> {
            val minutes = intent.getIntExtra("minutes", 0)
            setSleepTimer(minutes)
        }
        ACTION_CANCEL_SLEEP_TIMER -> {
            cancelSleepTimer()
        }
        ACTION_SKIP_NEXT -> {
            handleSkipNext()
        }
        ACTION_SKIP_PREVIOUS -> {
            handleSkipPrevious()
        }
        ACTION_PLAY_NEXT -> {
            val stationId = intent.getLongExtra(EXTRA_QUEUE_STATION_ID, 0L)
            if (stationId > 0L) enqueueStationAsync(stationId, asNext = true)
        }
        ACTION_ADD_TO_QUEUE -> {
            val stationId = intent.getLongExtra(EXTRA_QUEUE_STATION_ID, 0L)
            if (stationId > 0L) enqueueStationAsync(stationId, asNext = false)
        }
        ACTION_CLEAR_QUEUE -> {
            playbackQueue.clearManual()
            broadcastQueueChanged()
            // Refresh playback state action mask in case the previously
            // advertised SKIP_TO_NEXT no longer applies.
            updatePlaybackState(currentMediaSessionState())
        }
    }
    return START_STICKY
}

    /**
     * Update the playback queue when ACTION_PLAY arrives. Two distinct
     * cases:
     *
     *  - **Caller supplied a context list** (`contextStationIds` non-null
     *    and non-empty): replace the existing context with the new list and
     *    point the cursor at the user's tap. This is the common case when
     *    the user clicks a station in Library / Favorites / Genre view.
     *  - **Caller did not supply a context** (`contextStationIds` is null
     *    or empty): preserve the existing context and only refresh the
     *    "currently playing" pointer. This is what happens when skip-next
     *    re-fires ACTION_PLAY internally — the queue stays intact and the
     *    cursor advance has already happened on the in-memory queue.
     *
     * Runs on IO because Room lookups would otherwise block the play path.
     */
    private fun seedQueueAsync(
        currentStationId: Long,
        contextStationIds: LongArray?,
        contextIndex: Int
    ) {
        val replaceContext = contextStationIds != null && contextStationIds.isNotEmpty()
        if (currentStationId <= 0L && !replaceContext) {
            // Nothing to do — the play came from a path that doesn't know
            // about the queue (legacy retry, ad-hoc Browse). Leave the queue
            // alone and let the next ACTION_PLAY with context populate it.
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            val dao = com.opensource.i2pradio.data.RadioDatabase
                .getDatabase(this@RadioService)
                .radioDao()
            val current: com.opensource.i2pradio.data.RadioStation? =
                if (currentStationId > 0L) dao.getStationById(currentStationId) else null
            // Resolve the new context list (or null when caller didn't supply
            // one). Explicit type so smart-cast survives use across the
            // when-block below.
            val newContext: List<com.opensource.i2pradio.data.RadioStation>? = if (replaceContext) {
                // Preserve the order the caller supplied — SQLite's IN clause
                // doesn't guarantee row order, so re-index by id after load.
                val ids = contextStationIds!!
                val rows = dao.getStationsByIds(ids.toList())
                val byId = rows.associateBy { it.id }
                ids.mapNotNull { id -> byId[id] }
            } else null
            val resolvedIndex: Int = if (newContext == null || newContext.isEmpty()) {
                -1
            } else if (contextIndex in newContext.indices) {
                contextIndex
            } else if (current != null) {
                val currentId = current.id
                newContext.indexOfFirst { s -> s.id == currentId }.coerceAtLeast(0)
            } else {
                0
            }
            handler.post {
                if (newContext != null) {
                    playbackQueue.setContext(newContext, resolvedIndex)
                }
                if (current != null) currentQueueStation = current
                broadcastQueueChanged()
                updatePlaybackState(currentMediaSessionState())
            }
        }
    }

    /**
     * Remember the most recently set MediaSession state so we can re-emit it
     * after the action mask changes (e.g. after the queue gains or loses a
     * "next" entry). Falls back to BUFFERING which is safe at startup.
     */
    @Volatile
    private var lastPlaybackStateForActionRefresh: Int = PlaybackStateCompat.STATE_BUFFERING

    private fun currentMediaSessionState(): Int = lastPlaybackStateForActionRefresh

    /**
     * Resolve the next station the user wants to hear: pop from manual
     * queue, walk the context cursor, or — if Discover is enabled and the
     * queue is exhausted — pick a similar station from the user's library.
     * Then dispatch a fresh ACTION_PLAY for it.
     */
    private fun handleSkipNext() {
        val direct = playbackQueue.next()
        if (direct != null) {
            playStationFromQueue(direct)
            return
        }
        if (!isDiscoverEnabled()) {
            android.util.Log.d("RadioService", "Skip-next: queue exhausted, Discover off — no-op")
            return
        }
        val current = currentQueueStation
        if (current == null) {
            android.util.Log.d("RadioService", "Skip-next: no current station, can't run Discover")
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            val dao = com.opensource.i2pradio.data.RadioDatabase
                .getDatabase(this@RadioService)
                .radioDao()
            val library = dao.getAllStationsSync()
            val pick = com.opensource.i2pradio.playback.DiscoverEngine
                .suggestNext(current, library)
            handler.post {
                if (pick != null) {
                    playbackQueue.appendToContext(pick)
                    val next = playbackQueue.next()
                    if (next != null) {
                        android.util.Log.d("RadioService", "Discover picked '${next.name}'")
                        playStationFromQueue(next)
                    }
                } else {
                    android.util.Log.d("RadioService", "Discover found no suitable candidate")
                }
            }
        }
    }

    private fun handleSkipPrevious() {
        val prev = playbackQueue.previous()
        if (prev != null) {
            playStationFromQueue(prev)
        } else {
            android.util.Log.d("RadioService", "Skip-previous: at start of context, no-op")
        }
    }

    /**
     * Build an ACTION_PLAY intent for [station] and dispatch it to ourselves.
     * Goes through the same pipeline as a user-initiated play so proxy,
     * resolution, recording, and metadata all behave identically. Decrypts
     * the proxy password on the way through; the existing play handler
     * expects plaintext just like LibraryFragment.playStation does.
     */
    private fun playStationFromQueue(station: com.opensource.i2pradio.data.RadioStation) {
        currentQueueStation = station
        val password = com.opensource.i2pradio.data.RadioStationPasswordHelper
            .getDecryptedPassword(this, station)
        val intent = Intent(this, RadioService::class.java).apply {
            action = ACTION_PLAY
            putExtra("stream_url", station.streamUrl)
            putExtra("station_name", station.name)
            putExtra("station_id", station.id)
            putExtra("proxy_host", if (station.useProxy) station.proxyHost else "")
            putExtra("proxy_port", station.proxyPort)
            putExtra("proxy_type", station.getProxyTypeEnum().name)
            putExtra("cover_art_uri", station.coverArtUri)
            putExtra("custom_proxy_protocol", station.customProxyProtocol)
            putExtra("proxy_username", station.proxyUsername)
            putExtra("proxy_password", password)
            putExtra("proxy_auth_type", station.proxyAuthType)
            putExtra("proxy_connection_timeout", station.proxyConnectionTimeout)
            putExtra("hls_hint", station.hlsHint)
            putExtra("codec_hint", station.codecHint)
            // Skip seeding the queue again — onStartCommand will skip the
            // seed when EXTRA_CONTEXT_STATION_IDS is absent, so the existing
            // queue state is preserved.
        }
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
        broadcastQueueChanged()
    }

    private fun enqueueStationAsync(stationId: Long, asNext: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            val dao = com.opensource.i2pradio.data.RadioDatabase
                .getDatabase(this@RadioService)
                .radioDao()
            val station = dao.getStationById(stationId) ?: return@launch
            handler.post {
                if (asNext) playbackQueue.playNext(station)
                else playbackQueue.addToQueue(station)
                broadcastQueueChanged()
                updatePlaybackState(currentMediaSessionState())
            }
        }
    }

    private fun broadcastQueueChanged() {
        // Persist the queue so that if Android kills us between the user's
        // action and the next play attempt, we can rehydrate. Only stations
        // with non-zero ids survive — ad-hoc Browse rows don't exist in the
        // DB, so the queue UI loses them on restart, which is acceptable.
        val contextIds = playbackQueue.context.map { it.id }
        val manualIds = playbackQueue.manualSnapshot().map { it.id }
        com.opensource.i2pradio.ui.PreferencesHelper.saveQueueState(
            this, contextIds, playbackQueue.contextIndex, manualIds
        )
        val intent = Intent(BROADCAST_QUEUE_CHANGED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Pull the persisted queue out of prefs and seed [playbackQueue] from it.
     * Called once at service create — if a subsequent ACTION_PLAY arrives
     * with an explicit context, it overwrites this restored state, which is
     * what we want (a fresh user click should win over the previous
     * session's queue).
     */
    private fun restoreQueueFromPrefs() {
        val contextIds = com.opensource.i2pradio.ui.PreferencesHelper
            .getQueueContextIds(this)
        val manualIds = com.opensource.i2pradio.ui.PreferencesHelper
            .getQueueManualIds(this)
        val savedIndex = com.opensource.i2pradio.ui.PreferencesHelper
            .getQueueContextIndex(this)
        if (contextIds.isEmpty() && manualIds.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            val dao = com.opensource.i2pradio.data.RadioDatabase
                .getDatabase(this@RadioService)
                .radioDao()
            val allIds = (contextIds + manualIds).distinct()
            val rows = if (allIds.isNotEmpty()) dao.getStationsByIds(allIds) else emptyList()
            val byId = rows.associateBy { it.id }
            val contextRows = contextIds.mapNotNull { byId[it] }
            val manualRows = manualIds.mapNotNull { byId[it] }
            handler.post {
                if (contextRows.isNotEmpty()) {
                    val resolvedIndex = if (savedIndex in contextRows.indices) savedIndex else 0
                    playbackQueue.setContext(contextRows, resolvedIndex)
                    currentQueueStation = contextRows.getOrNull(resolvedIndex)
                }
                manualRows.forEach { playbackQueue.addToQueue(it) }
                if (contextRows.isNotEmpty() || manualRows.isNotEmpty()) {
                    android.util.Log.d(
                        "RadioService",
                        "Restored queue: context=${contextRows.size} manual=${manualRows.size}"
                    )
                    // Don't broadcast yet — UI isn't bound at onCreate time.
                    updatePlaybackState(currentMediaSessionState())
                }
            }
        }
    }

    /**
     * Snapshot of the manual queue for UI rendering. Returned as a list copy
     * so callers can render without worrying about concurrent mutation.
     */
    fun getManualQueue(): List<com.opensource.i2pradio.data.RadioStation> =
        playbackQueue.manualSnapshot()

    /** True if skip-next would do something. Cheap; safe to call from UI. */
    fun queueHasNext(): Boolean =
        playbackQueue.hasNext() || (isDiscoverEnabled() && currentQueueStation != null)

    /** True if skip-previous would do something. */
    fun queueHasPrevious(): Boolean = playbackQueue.hasPrevious()

    private fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return

        sleepTimerEndTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
        sleepTimerRunnable = Runnable {
            android.util.Log.d("RadioService", "Sleep timer triggered, stopping playback")
            val stopIntent = Intent(this, RadioService::class.java).apply {
                action = ACTION_STOP
            }
            startService(stopIntent)
        }
        handler.postDelayed(sleepTimerRunnable!!, minutes * 60 * 1000L)
        android.util.Log.d("RadioService", "Sleep timer set for $minutes minutes")
    }

    private fun cancelSleepTimer() {
        sleepTimerRunnable?.let {
            handler.removeCallbacks(it)
            sleepTimerRunnable = null
        }
        sleepTimerEndTime = 0L
    }

    fun getSleepTimerRemainingMillis(): Long {
        return if (sleepTimerEndTime > 0) {
            maxOf(0L, sleepTimerEndTime - System.currentTimeMillis())
        } else 0L
    }

    private fun startRecording(stationName: String) {
        if (isRecording) {
            android.util.Log.w("RadioService", "Recording already in progress")
            return
        }

        val streamUrl = currentStreamUrl ?: run {
            android.util.Log.e("RadioService", "Cannot start recording: no stream URL")
            broadcastRecordingError(getString(R.string.recording_error_no_stream))
            return
        }

        currentStationName = stationName
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // HLS / DASH detection combines URL extension signals with the hls
        // hint from Radio Browser so extensionless HLS streams are captured
        // with the correct container.
        val isHls = isHlsStream(streamUrl) || currentHlsHint
        val isDash = isDashStream(streamUrl)
        // detectRecordingFormat uses codecHint first, URL extension as
        // fallback. Content-Type from the HTTP response is applied inside
        // the recording thread if the codec hint wasn't authoritative.
        val initialFormat = when {
            isHls -> "ts"
            isDash -> "m4a"
            else -> detectRecordingFormat(currentCodecHint, streamUrl)
        }
        val sanitizedName = stationName.replace(Regex("[^a-zA-Z0-9\\s]"), "").replace(Regex("\\s+"), "_")
        val fileName = "${sanitizedName}_$timestamp.$initialFormat"
        val format = initialFormat

        android.util.Log.d("RadioService", "Starting recording for: $stationName, URL: $streamUrl (HLS=$isHls, DASH=$isDash, codecHint=$currentCodecHint, format=$format)")

        val mimeType = when {
            isHls -> HlsRecorder.mimeTypeForExtension(format)
            isDash -> DashRecorder.mimeTypeForExtension()
            else -> mimeTypeForFormat(format)
        }

        val customDirUri = PreferencesHelper.getRecordingDirectoryUri(this)
        val useCustomDir = customDirUri != null
        val useMediaStore = !useCustomDir && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var recordingsDir: File? = null

        if (!useCustomDir && !useMediaStore) {
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            recordingsDir = File(musicDir, "deutsia radio")
            if (!recordingsDir.exists()) {
                val created = recordingsDir.mkdirs()
                android.util.Log.d("RadioService", "Created recordings directory: $created, path: ${recordingsDir.absolutePath}")
                if (!created && !recordingsDir.exists()) {
                    android.util.Log.e("RadioService", "Failed to create recordings directory")
                    broadcastRecordingError(getString(R.string.recording_error_cannot_create_directory))
                    return
                }
            }
        }

        android.util.Log.d("RadioService", "Recording will use custom dir: $useCustomDir, MediaStore: $useMediaStore, format: $format, mimeType: $mimeType")

        isRecordingActive.set(true)
        isRecording = true

        val initialCall: Call? = if (!isHls && !isDash) {
            val recordingClient = buildRecordingHttpClient() ?: run {
                android.util.Log.e("RadioService", "Recording refused: proxy requirement not met")
                isRecordingActive.set(false)
                isRecording = false
                broadcastRecordingError(getString(R.string.recording_error_proxy_blocked))
                return
            }
            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "DeutsiaRadio-Recorder/1.0")
                .header("Icy-MetaData", "0")
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .build()
            recordingClient.newCall(request).also { recordingCall = it }
        } else {
            // HLS/DASH: the provider-based path handles null internally.
            // Verify up front so we surface a user-facing error before the
            // recording thread starts, instead of silently ending with an
            // empty output file.
            if (buildRecordingHttpClient() == null) {
                android.util.Log.e("RadioService", "Recording refused: proxy requirement not met")
                isRecordingActive.set(false)
                isRecording = false
                broadcastRecordingError(getString(R.string.recording_error_proxy_blocked))
                return
            }
            recordingCall = null
            null
        }

        val finalFileName = fileName
        val finalRecordingsDir = recordingsDir
        val finalStreamUrl = streamUrl
        val finalMimeType = mimeType
        val finalUseMediaStore = useMediaStore
        val finalUseCustomDir = useCustomDir
        val finalCustomDirUri = customDirUri
        val finalFormat = format
        val finalCodecHint = currentCodecHint
        val finalIsHls = isHls
        val finalIsDash = isDash

        recordingThread = Thread({
            var response: Response? = null
            var outputStream: BufferedOutputStream? = null
            var totalBytesWritten = 0L
            var lastFlushBytes = 0L
            var lastLogTime = System.currentTimeMillis()
            val flushInterval = 64 * 1024L
            val logInterval = 10_000L
            var file: File? = null
            var mediaStoreUri: Uri? = null
            var filePath: String? = null
            var connectionRetries = 0
            val maxConnectionRetries = 3

            // These may be updated for HLS streams after format detection
            var actualFileName = finalFileName
            var actualMimeType = finalMimeType

            try {
                android.util.Log.d("RadioService", "Recording thread started, connecting to: $finalStreamUrl (HLS=$finalIsHls, DASH=$finalIsDash)")

                if (!finalIsHls && !finalIsDash) {
                    val call = initialCall!!
                    while (connectionRetries < maxConnectionRetries && isRecordingActive.get()) {
                        try {
                            response = call.execute()
                            break
                        } catch (e: java.net.SocketTimeoutException) {
                            connectionRetries++
                            if (connectionRetries < maxConnectionRetries && isRecordingActive.get()) {
                                android.util.Log.w("RadioService", "Recording connection timeout, retry $connectionRetries/$maxConnectionRetries")
                                Thread.sleep(1000L * connectionRetries)
                            } else {
                                throw e
                            }
                        } catch (e: java.net.ConnectException) {
                            connectionRetries++
                            if (connectionRetries < maxConnectionRetries && isRecordingActive.get()) {
                                android.util.Log.w("RadioService", "Recording connection failed, retry $connectionRetries/$maxConnectionRetries")
                                Thread.sleep(1000L * connectionRetries)
                            } else {
                                throw e
                            }
                        }
                    }

                    if (response == null) {
                        android.util.Log.e("RadioService", "Recording: no response after retries")
                        handler.post {
                            broadcastRecordingError(getString(R.string.recording_error_connection_failed))
                            cleanupRecording()
                        }
                        return@Thread
                    }

                    android.util.Log.d("RadioService", "Recording response received: ${response!!.code}")

                    if (!response!!.isSuccessful) {
                        val errorMsg = "HTTP ${response!!.code}: ${response!!.message}"
                        android.util.Log.e("RadioService", "Recording request failed: $errorMsg")
                        handler.post {
                            broadcastRecordingError(errorMsg)
                            cleanupRecording()
                        }
                        return@Thread
                    }

                    val responseBody = response!!.body
                    if (responseBody == null) {
                        android.util.Log.e("RadioService", "Recording response has no body")
                        handler.post {
                            broadcastRecordingError(getString(R.string.recording_error_connection_failed))
                            cleanupRecording()
                        }
                        return@Thread
                    }

                    // Refine extension / MIME from the Content-Type header
                    // before the first byte is written. We only override the
                    // URL-based guess if the server told us something more
                    // specific; the codec hint (when present) has already
                    // been applied via finalFormat/finalMimeType.
                    val contentTypeHeader = response!!.header("Content-Type").orEmpty()
                    val ctFormat = formatFromContentType(contentTypeHeader)
                    if (ctFormat != null && ctFormat != finalFormat) {
                        // Trust the server only when the codec hint didn't
                        // already resolve the format (i.e. we fell through to
                        // URL-extension detection).
                        val hintResolved = detectRecordingFormatFromCodecHint(finalCodecHint) != null
                        if (!hintResolved) {
                            val baseName = actualFileName.substringBeforeLast('.', actualFileName)
                            actualFileName = "$baseName.$ctFormat"
                            actualMimeType = mimeTypeForFormat(ctFormat)
                            android.util.Log.d(
                                "RadioService",
                                "Recording format upgraded from Content-Type '$contentTypeHeader' to $ctFormat"
                            )
                        }
                    }
                }

                // Pre-detect fMP4/CMAF format for HLS streams before creating output file
                if (finalIsHls && isRecordingActive.get()) {
                    try {
                        // If the required proxy disappeared between
                        // startRecording's up-front check and now, abort
                        // here rather than probe over a non-proxied client.
                        // The provider path downstream also returns null,
                        // so the recorder would halt either way - fail fast
                        // with a user-visible error.
                        val detectClient = buildRecordingHttpClient() ?: run {
                            android.util.Log.e("RadioService", "HLS format detection aborted: proxy requirement not met")
                            handler.post {
                                broadcastRecordingError(getString(R.string.recording_error_proxy_blocked))
                                cleanupRecording()
                            }
                            return@Thread
                        }
                        val detectRequest = Request.Builder()
                            .url(finalStreamUrl)
                            .header("User-Agent", "DeutsiaRadio-Recorder/1.0")
                            .build()
                        val detectCall = detectClient.newCall(detectRequest)
                        val playlistText = detectCall.execute().use { r ->
                            if (r.isSuccessful) r.body?.string() else null
                        }
                        if (playlistText != null) {
                            var parsed = HlsRecorder.parsePlaylist(playlistText)
                            // If master playlist, fetch the best variant's media playlist
                            if (parsed.isMaster) {
                                val variant = parsed.variants.maxByOrNull { it.bandwidth }
                                if (variant != null) {
                                    val mediaUrl = HlsRecorder.resolveUrl(finalStreamUrl, variant.uri)
                                    val mediaRequest = Request.Builder()
                                        .url(mediaUrl)
                                        .header("User-Agent", "DeutsiaRadio-Recorder/1.0")
                                        .build()
                                    val mediaText = detectClient.newCall(mediaRequest).execute().use { r ->
                                        if (r.isSuccessful) r.body?.string() else null
                                    }
                                    if (mediaText != null) {
                                        parsed = HlsRecorder.parsePlaylist(mediaText)
                                    }
                                }
                            }
                            // Detect fMP4/CMAF by init segment or segment extension
                            val isFmp4 = parsed.initSegmentUri != null ||
                                parsed.segments.firstOrNull()?.let { seg ->
                                    val ext = HlsRecorder.detectSegmentExtension(seg)
                                    ext == "m4s" || ext == "mp4" || ext == "m4a"
                                } == true
                            if (isFmp4) {
                                actualFileName = finalFileName.replace(".ts", ".m4a")
                                actualMimeType = "audio/mp4"
                                android.util.Log.d("RadioService", "HLS stream uses fMP4/CMAF, recording as: $actualFileName")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("RadioService", "HLS format detection failed, defaulting to .ts: ${e.message}")
                    }
                }

                if (finalUseCustomDir && finalCustomDirUri != null) {
                    try {
                        val treeUri = Uri.parse(finalCustomDirUri)
                        val docFile = DocumentFile.fromTreeUri(this@RadioService, treeUri)
                        if (docFile == null || !docFile.canWrite()) {
                            android.util.Log.e("RadioService", "Cannot write to custom directory")
                            handler.post {
                                broadcastRecordingError(getString(R.string.recording_error_cannot_write_directory))
                                cleanupRecording()
                            }
                            return@Thread
                        }

                        val newFile = docFile.createFile(actualMimeType, actualFileName.substringBeforeLast("."))
                        if (newFile == null) {
                            android.util.Log.e("RadioService", "Failed to create file in custom directory")
                            handler.post {
                                broadcastRecordingError(getString(R.string.recording_error_cannot_create_file))
                                cleanupRecording()
                            }
                            return@Thread
                        }

                        filePath = newFile.name ?: actualFileName
                        android.util.Log.d("RadioService", "Recording stream connected, writing to custom dir: $filePath")

                        val rawOutputStream = contentResolver.openOutputStream(newFile.uri)
                        if (rawOutputStream == null) {
                            android.util.Log.e("RadioService", "Failed to open custom directory output stream")
                            newFile.delete()
                            handler.post {
                                broadcastRecordingError(getString(R.string.recording_error_cannot_open_file))
                                cleanupRecording()
                            }
                            return@Thread
                        }

                        outputStream = BufferedOutputStream(rawOutputStream, 64 * 1024)
                    } catch (e: Exception) {
                        android.util.Log.e("RadioService", "Custom directory error: ${e.message}", e)
                        handler.post {
                            broadcastRecordingError(getString(R.string.recording_error_directory_access, e.message))
                            cleanupRecording()
                        }
                        return@Thread
                    }
                } else if (finalUseMediaStore) {
                    // HLS recordings (MPEG-TS containers) can't be inserted into
                    // MediaStore.Audio because MediaProvider's audio MIME
                    // allowlist doesn't include mp2t. Route HLS/DASH recordings
                    // through MediaStore.Downloads instead, which accepts any
                    // MIME type. Progressive audio still goes to Music/.
                    val useDownloadsStore = finalIsHls || finalIsDash
                    val collectionUri = if (useDownloadsStore) {
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                    val relativePath = if (useDownloadsStore) {
                        "Download/deutsia_radio"
                    } else {
                        "Music/deutsia radio"
                    }
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, actualFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, actualMimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val resolver = contentResolver
                    mediaStoreUri = resolver.insert(collectionUri, contentValues)

                    if (mediaStoreUri == null) {
                        android.util.Log.e("RadioService", "Failed to create MediaStore entry")
                        handler.post {
                            broadcastRecordingError(getString(R.string.recording_error_cannot_create_file))
                            cleanupRecording()
                        }
                        return@Thread
                    }

                    recordingMediaStoreUri = mediaStoreUri
                    filePath = "$relativePath/$actualFileName"
                    android.util.Log.d("RadioService", "Recording stream connected, writing to MediaStore: $filePath (URI: $mediaStoreUri)")

                    val rawOutputStream = resolver.openOutputStream(mediaStoreUri)
                    if (rawOutputStream == null) {
                        android.util.Log.e("RadioService", "Failed to open MediaStore output stream")
                        resolver.delete(mediaStoreUri, null, null)
                        handler.post {
                            broadcastRecordingError(getString(R.string.recording_error_cannot_open_file))
                            cleanupRecording()
                        }
                        return@Thread
                    }

                    outputStream = BufferedOutputStream(rawOutputStream, 64 * 1024)
                } else {
                    file = File(finalRecordingsDir!!, actualFileName)
                    recordingFile = file
                    filePath = file!!.absolutePath
                    android.util.Log.d("RadioService", "Recording stream connected, writing to: $filePath")

                    outputStream = BufferedOutputStream(
                        FileOutputStream(file),
                        64 * 1024
                    )
                }

                recordingOutputStream = outputStream
                handler.post { updateNotificationWithRecording() }

                if (finalIsHls) {
                    val hlsOutput = outputStream!!
                    val hlsRecorder = HlsRecorder(
                        httpClientProvider = { buildRecordingHttpClient() },
                        isActive = isRecordingActive,
                        switchRequested = switchStreamRequested,
                        newStreamUrlProvider = {
                            val url = pendingRecordingStreamUrl
                            pendingRecordingStreamUrl = null
                            url
                        },
                        activeCallSetter = { c -> recordingCall = c },
                    )
                    val result = hlsRecorder.record(finalStreamUrl, hlsOutput) { bytes ->
                        totalBytesWritten = bytes
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime >= logInterval) {
                            android.util.Log.d("RadioService", "Recording: ${totalBytesWritten / 1024}KB written to ${filePath ?: file?.name ?: "unknown"} (HLS)")
                            lastLogTime = now
                        }
                    }
                    totalBytesWritten = result.bytesWritten
                    android.util.Log.d("RadioService", "HLS recording finished: ${totalBytesWritten / 1024}KB (ext=${result.segmentExtension}, normal=${result.completedNormally})")
                    if (!result.completedNormally && result.bytesWritten == 0L && isRecordingActive.get()) {
                        handler.post {
                            broadcastRecordingError(getString(R.string.recording_error_hls_playlist))
                        }
                    }
                } else if (finalIsDash) {
                    val dashOutput = outputStream!!
                    val dashRecorder = DashRecorder(
                        httpClientProvider = { buildRecordingHttpClient() },
                        isActive = isRecordingActive,
                        switchRequested = switchStreamRequested,
                        newStreamUrlProvider = {
                            val url = pendingRecordingStreamUrl
                            pendingRecordingStreamUrl = null
                            url
                        },
                        activeCallSetter = { c -> recordingCall = c },
                    )
                    val result = dashRecorder.record(finalStreamUrl, dashOutput) { bytes ->
                        totalBytesWritten = bytes
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime >= logInterval) {
                            android.util.Log.d("RadioService", "Recording: ${totalBytesWritten / 1024}KB written to ${filePath ?: file?.name ?: "unknown"} (DASH)")
                            lastLogTime = now
                        }
                    }
                    totalBytesWritten = result.bytesWritten
                    android.util.Log.d("RadioService", "DASH recording finished: ${totalBytesWritten / 1024}KB (ext=${result.segmentExtension}, normal=${result.completedNormally})")
                    if (!result.completedNormally && result.bytesWritten == 0L && isRecordingActive.get()) {
                        handler.post {
                            broadcastRecordingError(getString(R.string.recording_error_hls_playlist))
                        }
                    }
                } else {
                    var currentInputStream = response!!.body!!.byteStream()
                    var currentResponse: Response? = response
                    val buffer = ByteArray(8192)

                    outerLoop@ while (isRecordingActive.get() && !Thread.currentThread().isInterrupted) {
                        while (isRecordingActive.get() && !Thread.currentThread().isInterrupted) {
                            val bytesRead = try {
                                currentInputStream.read(buffer)
                            } catch (e: java.io.IOException) {
                                if (switchStreamRequested.get()) {
                                    android.util.Log.d("RadioService", "Recording read interrupted for stream switch")
                                    -1
                                } else if (isRecordingActive.get()) {
                                    android.util.Log.e("RadioService", "Recording read error: ${e.message}")
                                    -1
                                } else {
                                    -1
                                }
                            }

                            if (bytesRead == -1) {
                                if (switchStreamRequested.compareAndSet(true, false)) {
                                    val newStreamUrl = pendingRecordingStreamUrl
                                    if (newStreamUrl != null) {
                                        android.util.Log.d("RadioService", "Switching recording to new stream: $newStreamUrl")

                                        try {
                                            outputStream.flush()
                                        } catch (e: Exception) {
                                            android.util.Log.w("RadioService", "Error flushing before stream switch: ${e.message}")
                                        }

                                        try {
                                            currentResponse?.close()
                                        } catch (e: Exception) {
                                            android.util.Log.w("RadioService", "Error closing old response: ${e.message}")
                                        }

                                        val newRecordingClient = buildRecordingHttpClient()
                                        if (newRecordingClient == null) {
                                            android.util.Log.e("RadioService", "Stream-switch aborted: proxy requirement not met (fail-closed)")
                                            handler.post {
                                                broadcastRecordingError(getString(R.string.recording_error_proxy_blocked))
                                            }
                                            pendingRecordingStreamUrl = null
                                            break@outerLoop
                                        }
                                        val newRequest = Request.Builder()
                                            .url(newStreamUrl)
                                            .header("User-Agent", "DeutsiaRadio-Recorder/1.0")
                                            .header("Icy-MetaData", "0")
                                            .header("Accept", "*/*")
                                            .header("Connection", "keep-alive")
                                            .build()

                                        try {
                                            val newCall = newRecordingClient.newCall(newRequest)
                                            recordingCall = newCall
                                            val newResponse = newCall.execute()

                                            if (newResponse.isSuccessful && newResponse.body != null) {
                                                currentResponse = newResponse
                                                currentInputStream = newResponse.body!!.byteStream()
                                                android.util.Log.d("RadioService", "Recording switched to new stream successfully")
                                                pendingRecordingStreamUrl = null
                                                continue@outerLoop
                                            } else {
                                                android.util.Log.e("RadioService", "Failed to connect to new stream: ${newResponse.code}")
                                                newResponse.close()
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("RadioService", "Error switching to new stream: ${e.message}")
                                        }

                                        pendingRecordingStreamUrl = null
                                    }
                                }

                                android.util.Log.d("RadioService", "Recording stream ended (EOF)")
                                break@outerLoop
                            }

                            if (bytesRead > 0) {
                                try {
                                    outputStream.write(buffer, 0, bytesRead)
                                    totalBytesWritten += bytesRead

                                    if (totalBytesWritten - lastFlushBytes >= flushInterval) {
                                        outputStream.flush()
                                        lastFlushBytes = totalBytesWritten
                                    }

                                    val now = System.currentTimeMillis()
                                    if (now - lastLogTime >= logInterval) {
                                        android.util.Log.d("RadioService", "Recording: ${totalBytesWritten / 1024}KB written to ${filePath ?: file?.name ?: "unknown"}")
                                        lastLogTime = now
                                    }
                                } catch (e: java.io.IOException) {
                                    android.util.Log.e("RadioService", "Recording write error: ${e.message}")
                                    break@outerLoop
                                }
                            }
                        }
                    }

                    try {
                        outputStream.flush()
                    } catch (e: Exception) {
                        android.util.Log.w("RadioService", "Error during final flush: ${e.message}")
                    }

                    try {
                        currentResponse?.close()
                    } catch (e: Exception) {
                        android.util.Log.w("RadioService", "Error closing final response: ${e.message}")
                    }
                }

            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.e("RadioService", "Recording connection timed out", e)
                handler.post {
                    broadcastRecordingError(getString(R.string.recording_error_connection_timeout))
                    cleanupRecording()
                }
            } catch (e: java.net.ConnectException) {
                android.util.Log.e("RadioService", "Recording connection refused", e)
                handler.post {
                    broadcastRecordingError(getString(R.string.recording_error_connection_refused))
                    cleanupRecording()
                }
            } catch (e: java.io.IOException) {
                if (isRecordingActive.get()) {
                    android.util.Log.e("RadioService", "Recording I/O error: ${e.message}", e)
                    handler.post { broadcastRecordingError(getString(R.string.recording_error_io, e.message)) }
                } else {
                    android.util.Log.d("RadioService", "Recording stopped normally (${totalBytesWritten / 1024}KB saved)")
                }
            } catch (e: InterruptedException) {
                android.util.Log.d("RadioService", "Recording thread interrupted")
            } catch (e: Exception) {
                android.util.Log.e("RadioService", "Recording error: ${e.javaClass.simpleName}: ${e.message}", e)
                handler.post { broadcastRecordingError(getString(R.string.recording_error_generic, e.message)) }
            } finally {
                try {
                    outputStream?.flush()
                    outputStream?.close()
                } catch (e: Exception) {
                    android.util.Log.w("RadioService", "Error closing output: ${e.message}")
                }
                try {
                    response?.close()
                } catch (e: Exception) {
                    android.util.Log.w("RadioService", "Error closing response: ${e.message}")
                }

                recordingOutputStream = null

                if (mediaStoreUri != null) {
                    val resolver = contentResolver
                    val sizeKB = totalBytesWritten / 1024
                    if (sizeKB > 0) {
                        val updateValues = ContentValues().apply {
                            put(MediaStore.Audio.Media.IS_PENDING, 0)
                        }
                        resolver.update(mediaStoreUri, updateValues, null, null)
                        android.util.Log.d("RadioService", "Recording saved to MediaStore: $filePath (${sizeKB}KB)")
                        val savedPath = filePath ?: "Music/i2pradio/unknown"
                        handler.post { broadcastRecordingComplete(savedPath, totalBytesWritten) }
                    } else {
                        android.util.Log.w("RadioService", "Recording is empty, deleting MediaStore entry: $filePath")
                        resolver.delete(mediaStoreUri, null, null)
                    }
                    recordingMediaStoreUri = null
                } else if (file != null) {
                    file?.let { f ->
                        if (f.exists()) {
                            val sizeKB = f.length() / 1024
                            if (sizeKB > 0) {
                                android.util.Log.d("RadioService", "Recording saved: ${f.absolutePath} (${sizeKB}KB)")
                                handler.post { broadcastRecordingComplete(f.absolutePath, totalBytesWritten) }
                            } else {
                                android.util.Log.w("RadioService", "Recording file is empty, deleting: ${f.absolutePath}")
                                f.delete()
                            }
                        } else {
                            android.util.Log.e("RadioService", "Recording file not found: ${f.absolutePath}")
                        }
                    }
                } else if (finalUseCustomDir && filePath != null) {
                    val sizeKB = totalBytesWritten / 1024
                    if (sizeKB > 0) {
                        android.util.Log.d("RadioService", "Recording saved to custom dir: $filePath (${sizeKB}KB)")
                        handler.post { broadcastRecordingComplete(filePath!!, totalBytesWritten) }
                    } else {
                        android.util.Log.w("RadioService", "Recording is empty: $filePath")
                    }
                }
            }
        }, "RecordingThread-${System.currentTimeMillis()}").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = false
        }

        recordingThread?.start()
        android.util.Log.d("RadioService", "Recording thread started for: $stationName")
    }

    private fun broadcastRecordingError(message: String) {
        android.util.Log.e("RadioService", "Recording error: $message")
        val intent = Intent(BROADCAST_RECORDING_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastRecordingComplete(filePath: String, fileSize: Long) {
        android.util.Log.d("RadioService", "Recording complete: $filePath (${fileSize / 1024}KB)")
        val intent = Intent(BROADCAST_RECORDING_COMPLETE).apply {
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_FILE_SIZE, fileSize)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Build an OkHttp client configured for recording traffic.
     *
     * Returns null when the user has a force-proxy mode enabled but the
     * required proxy isn't currently reachable - we fail CLOSED rather
     * than silently rebuilding with a direct connection (which would leak
     * clearnet traffic every time an HLS/DASH segment is fetched, or
     * whenever the recording thread reconnects). Callers MUST treat null
     * as a hard abort.
     */
    private fun buildRecordingHttpClient(): OkHttpClient? {
        val forceTorAll = PreferencesHelper.isForceTorAll(this)
        val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(this)
        val forceCustomProxy = PreferencesHelper.isForceCustomProxy(this)
        val forceCustomProxyExceptTorI2P = PreferencesHelper.isForceCustomProxyExceptTorI2P(this)
        val isI2PStream = currentProxyType == ProxyType.I2P || currentStreamUrl?.contains(".i2p") == true
        val isTorStream = currentProxyType == ProxyType.TOR || currentStreamUrl?.contains(".onion") == true

        // Fail-closed safeguards BEFORE the routing selection runs.
        // Each of these short-circuits any code path that would fall
        // through to Triple("", 0, ProxyType.NONE) - i.e. a direct
        // connection - while a force-* mode is active.
        if (forceTorAll && !TorManager.isConnected()) {
            android.util.Log.e("RadioService", "Recording refused: force-Tor-all enabled but Tor not connected")
            return null
        }
        if (forceTorExceptI2P && !isI2PStream && !TorManager.isConnected()) {
            android.util.Log.e("RadioService", "Recording refused: force-Tor-except-I2P enabled, non-I2P stream, Tor not connected")
            return null
        }
        if (forceCustomProxy) {
            val customProxyHost = PreferencesHelper.getCustomProxyHost(this)
            if (customProxyHost.isEmpty()) {
                android.util.Log.e("RadioService", "Recording refused: force-custom-proxy enabled but not configured")
                return null
            }
        }
        if (forceCustomProxyExceptTorI2P && !isI2PStream && !isTorStream) {
            val customProxyHost = PreferencesHelper.getCustomProxyHost(this)
            if (customProxyHost.isEmpty()) {
                android.util.Log.e("RadioService", "Recording refused: force-custom-proxy-except-Tor-I2P enabled, clearnet stream, custom proxy not configured")
                return null
            }
        }

        android.util.Log.d("RadioService", "===== RECORDING CONNECTION REQUEST =====")
        android.util.Log.d("RadioService", "Recording URL: $currentStreamUrl")
        android.util.Log.d("RadioService", "Force Tor All: $forceTorAll")
        android.util.Log.d("RadioService", "Force Tor Except I2P: $forceTorExceptI2P")
        android.util.Log.d("RadioService", "Force Custom Proxy: $forceCustomProxy")
        android.util.Log.d("RadioService", "Force Custom Proxy Except Tor/I2P: $forceCustomProxyExceptTorI2P")
        android.util.Log.d("RadioService", "Is I2P stream: $isI2PStream")
        android.util.Log.d("RadioService", "Is Tor stream: $isTorStream")
        android.util.Log.d("RadioService", "Tor connected: ${TorManager.isConnected()}")

        val (effectiveProxyHost, effectiveProxyPort, effectiveProxyType) = when {
            forceTorAll && TorManager.isConnected() -> {
                android.util.Log.d("RadioService", "FORCE TOR ALL (recording): Routing through Tor")
                Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
            }
            forceTorExceptI2P && isI2PStream -> {
                if (currentProxyHost?.isNotEmpty() == true && currentProxyType == ProxyType.I2P) {
                    android.util.Log.d("RadioService", "FORCE TOR (except I2P) recording: Using I2P proxy")
                    Triple(currentProxyHost!!, currentProxyPort, ProxyType.I2P)
                } else {
                    android.util.Log.d("RadioService", "FORCE TOR (except I2P) recording: Using default I2P proxy")
                    Triple("127.0.0.1", 4444, ProxyType.I2P)
                }
            }
            forceTorExceptI2P && !isI2PStream && TorManager.isConnected() -> {
                android.util.Log.d("RadioService", "FORCE TOR (except I2P) recording: Routing through Tor")
                Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
            }
            forceCustomProxy -> {
                val customProxyHost = PreferencesHelper.getCustomProxyHost(this)
                val customProxyPort = PreferencesHelper.getCustomProxyPort(this)
                android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (recording): Routing through custom proxy")
                Triple(customProxyHost, customProxyPort, ProxyType.CUSTOM)
            }
            forceCustomProxyExceptTorI2P && isI2PStream -> {
                if (currentProxyHost?.isNotEmpty() == true && currentProxyType == ProxyType.I2P) {
                    android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P) recording: Using I2P proxy")
                    Triple(currentProxyHost!!, currentProxyPort, ProxyType.I2P)
                } else {
                    android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P) recording: Using default I2P proxy")
                    Triple("127.0.0.1", 4444, ProxyType.I2P)
                }
            }
            forceCustomProxyExceptTorI2P && isTorStream && TorManager.isConnected() -> {
                // In "except Tor/I2P" mode, Tor integration is implicitly enabled for .onion streams
                android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P) recording: Routing through Tor")
                Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
            }
            forceCustomProxyExceptTorI2P && isTorStream -> {
                // Embedded Tor not connected - fall back to default Tor SOCKS port for external proxies (InviZible Pro, Orbot)
                if (currentProxyHost?.isNotEmpty() == true && currentProxyType == ProxyType.TOR) {
                    android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P) recording: Using station Tor proxy config")
                    Triple(currentProxyHost!!, currentProxyPort, ProxyType.TOR)
                } else {
                    android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P) recording: Using default Tor proxy (127.0.0.1:9050)")
                    Triple("127.0.0.1", 9050, ProxyType.TOR)
                }
            }
            forceCustomProxyExceptTorI2P && !isI2PStream && !isTorStream -> {
                val customProxyHost = PreferencesHelper.getCustomProxyHost(this)
                val customProxyPort = PreferencesHelper.getCustomProxyPort(this)
                android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P) recording: Routing through custom proxy")
                Triple(customProxyHost, customProxyPort, ProxyType.CUSTOM)
            }
            currentProxyType == ProxyType.TOR &&
            PreferencesHelper.isEmbeddedTorEnabled(this) &&
            TorManager.isConnected() -> {
                Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
            }
            currentProxyHost?.isNotEmpty() == true && currentProxyType != ProxyType.NONE -> {
                if (currentProxyType == ProxyType.TOR && !PreferencesHelper.isEmbeddedTorEnabled(this)) {
                    android.util.Log.w("RadioService", "Recording requires Tor but Tor integration is disabled - using direct connection")
                    Triple("", 0, ProxyType.NONE)
                } else {
                    Triple(currentProxyHost!!, currentProxyPort, currentProxyType)
                }
            }
            else -> Triple("", 0, ProxyType.NONE)
        }

        // When using force custom proxy modes, load all settings from global preferences
        // instead of using station-specific values (which may be empty)
        val useGlobalCustomProxySettings = effectiveProxyType == ProxyType.CUSTOM &&
            (forceCustomProxy || forceCustomProxyExceptTorI2P)

        val effectiveCustomProxyProtocol = if (useGlobalCustomProxySettings) {
            PreferencesHelper.getCustomProxyProtocol(this)
        } else {
            currentCustomProxyProtocol
        }

        val effectiveProxyUsername = if (useGlobalCustomProxySettings) {
            PreferencesHelper.getCustomProxyUsername(this)
        } else {
            currentProxyUsername
        }

        val effectiveProxyPassword = if (useGlobalCustomProxySettings) {
            PreferencesHelper.getCustomProxyPassword(this)
        } else {
            currentProxyPassword
        }

        val effectiveProxyAuthType = if (useGlobalCustomProxySettings) {
            PreferencesHelper.getCustomProxyAuthType(this)
        } else {
            currentProxyAuthType
        }

        android.util.Log.d("RadioService", "===== RECORDING ROUTING DECISION =====")
        android.util.Log.d("RadioService", "Recording proxy: $effectiveProxyHost:$effectiveProxyPort (${effectiveProxyType.name})")
        android.util.Log.d("RadioService", "Using global custom proxy settings: $useGlobalCustomProxySettings")
        when (effectiveProxyType) {
            ProxyType.TOR -> android.util.Log.d("RadioService", "RECORDING ROUTING: Through TOR SOCKS proxy")
            ProxyType.I2P -> android.util.Log.d("RadioService", "RECORDING ROUTING: Through I2P HTTP proxy")
            ProxyType.CUSTOM -> android.util.Log.d("RadioService", "RECORDING ROUTING: Through CUSTOM $effectiveCustomProxyProtocol proxy")
            ProxyType.NONE -> android.util.Log.w("RadioService", "RECORDING ROUTING: DIRECT - No proxy!")
        }
        android.util.Log.d("RadioService", "======================================")

        val recordingConnectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 1,
            keepAliveDuration = 30,
            timeUnit = TimeUnit.SECONDS
        )

        val recordingDispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 1
            maxRequestsPerHost = 1
        }

        val builder = OkHttpClient.Builder()
            .connectionPool(recordingConnectionPool)
            .dispatcher(recordingDispatcher)
            .addInterceptor(BandwidthTrackingInterceptor(this))
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (effectiveProxyHost.isNotEmpty() && effectiveProxyType != ProxyType.NONE) {
            val javaProxyType = when (effectiveProxyType) {
                ProxyType.TOR -> Proxy.Type.SOCKS
                ProxyType.I2P -> Proxy.Type.HTTP
                ProxyType.CUSTOM -> {
                    when (effectiveCustomProxyProtocol.uppercase()) {
                        "SOCKS4", "SOCKS5" -> Proxy.Type.SOCKS
                        "HTTP", "HTTPS" -> Proxy.Type.HTTP
                        else -> Proxy.Type.HTTP
                    }
                }
                ProxyType.NONE -> Proxy.Type.DIRECT
            }
            builder.proxy(Proxy(javaProxyType, InetSocketAddress(effectiveProxyHost, effectiveProxyPort)))

            // CRITICAL: Force DNS through SOCKS5 to prevent DNS leaks
            if (javaProxyType == Proxy.Type.SOCKS) {
                builder.dns(SOCKS5_DNS)
                android.util.Log.d("RadioService", "Recording DNS resolution will be handled by SOCKS proxy")
            }

            if (effectiveProxyType == ProxyType.CUSTOM && effectiveProxyUsername.isNotEmpty() && effectiveProxyPassword.isNotEmpty()) {
                android.util.Log.d("RadioService", "Adding proxy authentication for recording client (auth type: $effectiveProxyAuthType)")
                builder.proxyAuthenticator { route, response ->
                    android.util.Log.d("RadioService", "Recording client authentication challenge received (${response.code})")

                    val previousAuth = response.request.header("Proxy-Authorization")
                    if (previousAuth != null) {
                        android.util.Log.w("RadioService", "Recording client authentication already attempted - credentials may be incorrect")
                        return@proxyAuthenticator null
                    }

                    when (effectiveProxyAuthType.uppercase()) {
                        "BASIC" -> {
                            android.util.Log.d("RadioService", "Recording client using Basic authentication")
                            val credential = okhttp3.Credentials.basic(effectiveProxyUsername, effectiveProxyPassword)
                            response.request.newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        }
                        "DIGEST" -> {
                            android.util.Log.d("RadioService", "Recording client using Digest authentication")
                            DigestAuthenticator.authenticate(response, effectiveProxyUsername, effectiveProxyPassword)
                        }
                        else -> {
                            android.util.Log.w("RadioService", "Recording client unknown auth type: $effectiveProxyAuthType, falling back to Basic")
                            val credential = okhttp3.Credentials.basic(effectiveProxyUsername, effectiveProxyPassword)
                            response.request.newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        }
                    }
                }
            }

            android.util.Log.d("RadioService", "Recording client using ${effectiveProxyType.name} proxy: $effectiveProxyHost:$effectiveProxyPort")
        } else {
            android.util.Log.d("RadioService", "Recording client using direct connection")
        }

        return builder.build()
    }

    private fun detectStreamFormat(streamUrl: String): String {
        val urlLower = streamUrl.lowercase()
        return when {
            urlLower.contains(".ogg") || urlLower.contains("/ogg") || urlLower.contains("vorbis") -> "ogg"
            urlLower.contains(".opus") || urlLower.contains("/opus") -> "opus"
            urlLower.contains(".aac") || urlLower.contains("/aac") -> "aac"
            urlLower.contains(".flac") || urlLower.contains("/flac") -> "flac"
            urlLower.contains(".m4a") -> "m4a"
            else -> "mp3"
        }
    }

    /**
     * Pick a recording file extension, preferring the Radio Browser codec
     * hint (most reliable) and falling back to URL extension detection
     * (the legacy behaviour). The content-type from the first HTTP response
     * is applied in the recording thread as an additional refinement.
     */
    private fun detectRecordingFormat(codecHint: String, streamUrl: String): String {
        return detectRecordingFormatFromCodecHint(codecHint) ?: detectStreamFormat(streamUrl)
    }

    /**
     * Map a Radio Browser codec string to a file extension, or return null
     * if the hint is missing / ambiguous ("UNKNOWN", empty). Video+audio
     * containers are kept as their real container (.ts for MPEG-TS, .mp4 for
     * MP4) so the file is actually playable; ExoPlayer's audio renderer can
     * still decode audio-only playback from them.
     */
    private fun detectRecordingFormatFromCodecHint(codecHint: String): String? {
        if (codecHint.isBlank()) return null
        val upper = codecHint.uppercase()
        // UNKNOWN alone (or UNKNOWN,H.264 etc.) means Radio Browser doesn't
        // trust its own probe - fall through to other signals.
        if (upper.startsWith("UNKNOWN")) return null
        // Video+audio containers. Tokens split by ',' contain a video codec.
        if (upper.contains("H.264") || upper.contains("H264") ||
            upper.contains("HEVC") || upper.contains("H.265")) {
            // AAC,H.264 → MPEG-TS (.ts); MP4,H.264 → .mp4
            return if (upper.startsWith("MP4")) "mp4" else "ts"
        }
        return when (upper.substringBefore(',').trim()) {
            "MP3" -> "mp3"
            "AAC", "AAC+" -> "aac"
            "OGG" -> "ogg"
            "FLAC" -> "flac"
            "MP4" -> "m4a"
            "FLV" -> null  // FLV is rejected before recording can start
            else -> null
        }
    }

    /**
     * Best-effort MIME type for a given file extension.
     */
    private fun mimeTypeForFormat(format: String): String {
        return when (format) {
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "ts" -> "video/mp2t"
            "webm" -> "audio/webm"
            else -> "audio/mpeg"
        }
    }

    /**
     * Derive a file extension from a Content-Type response header. Returns
     * null for unrecognised values so callers can keep their existing guess.
     */
    private fun formatFromContentType(contentType: String): String? {
        if (contentType.isBlank()) return null
        val ct = contentType.substringBefore(';').trim().lowercase()
        return when (ct) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/aac", "audio/aacp" -> "aac"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/webm" -> "webm"
            "video/mp4" -> "mp4"
            "video/mp2t" -> "ts"
            else -> null
        }
    }

    /**
     * Whether a codec hint identifies an FLV stream (audio- or AV-container
     * FLV). Checked before playback and recording - ExoPlayer's default
     * renderers cannot decode FLV so we surface a clear error to the user
     * instead of a generic decoder failure.
     */
    private fun isFlvCodec(codecHint: String): Boolean {
        if (codecHint.isBlank()) return false
        val upper = codecHint.uppercase()
        return upper == "FLV" || upper.startsWith("FLV,")
    }

    /**
     * Inspect a [PlaybackException] for a well-known HTTP status code and
     * broadcast a targeted error type to the UI. Called on every
     * onPlayerError so the user gets an accurate toast even during the
     * reconnect loop (each attempt surfaces the same classification).
     *
     * Walks the cause chain because ExoPlayer usually wraps the
     * InvalidResponseCodeException inside a higher-level PlaybackException.
     */
    private fun classifyAndBroadcastHttpError(error: PlaybackException) {
        var cause: Throwable? = error
        var httpException: HttpDataSource.InvalidResponseCodeException? = null
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                httpException = cause
                break
            }
            cause = cause.cause
        }
        val responseCode = httpException?.responseCode ?: return
        val errorType = when (responseCode) {
            401 -> ERROR_TYPE_STATION_AUTH_REQUIRED
            403, 451 -> ERROR_TYPE_STATION_GEOBLOCKED
            404, 410 -> ERROR_TYPE_STATION_GONE
            else -> return
        }
        android.util.Log.w(
            "RadioService",
            "HTTP $responseCode on stream - broadcasting $errorType"
        )
        broadcastStreamError(errorType)
    }

    /**
     * Abort playback with a clear "unsupported codec" error. Used for FLV
     * and potentially future codecs we know we can't decode.
     */
    private fun rejectUnsupportedCodec(codecName: String) {
        android.util.Log.w("RadioService", "Rejecting stream with unsupported codec: $codecName")
        isStartingNewStream.set(false)
        broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
        broadcastStreamError(ERROR_TYPE_UNSUPPORTED_CODEC, codecName)
        startForeground(
            NOTIFICATION_ID,
            createNotification(getString(R.string.notification_unsupported_codec, codecName))
        )
    }

    private fun isHlsStream(streamUrl: String): Boolean {
        val path = try {
            Uri.parse(streamUrl).path?.lowercase() ?: ""
        } catch (e: Exception) {
            streamUrl.substringBefore('?').lowercase()
        }
        return path.endsWith(".m3u8") || path.contains(".m3u8")
    }

    private fun isDashStream(streamUrl: String): Boolean {
        val path = try {
            Uri.parse(streamUrl).path?.lowercase() ?: ""
        } catch (e: Exception) {
            streamUrl.substringBefore('?').lowercase()
        }
        return path.endsWith(".mpd") || path.contains(".mpd")
    }

    private fun switchRecordingStream(
        newStreamUrl: String,
        newProxyHost: String,
        newProxyPort: Int,
        newProxyType: ProxyType,
        newStationName: String,
        newHlsHint: Boolean = false,
        newCodecHint: String = ""
    ) {
        if (!isRecording) {
            android.util.Log.d("RadioService", "switchRecordingStream called but not recording")
            return
        }

        android.util.Log.d("RadioService", "Switching recording stream to: $newStreamUrl (station: $newStationName)")

        currentStreamUrl = newStreamUrl
        currentProxyHost = newProxyHost
        currentProxyPort = newProxyPort
        currentProxyType = newProxyType
        currentStationName = newStationName
        currentHlsHint = newHlsHint
        currentCodecHint = newCodecHint

        pendingRecordingStreamUrl = newStreamUrl
        pendingRecordingProxyHost = newProxyHost
        pendingRecordingProxyPort = newProxyPort
        pendingRecordingProxyType = newProxyType

        switchStreamRequested.set(true)
        recordingCall?.cancel()
    }

    private fun stopRecording() {
        if (!isRecording) {
            android.util.Log.d("RadioService", "stopRecording called but not recording")
            return
        }

        android.util.Log.d("RadioService", "Stopping recording...")

        val currentCall = recordingCall
        val currentThread = recordingThread
        val currentFile = recordingFile

        isRecordingActive.set(false)
        isRecording = false

        Thread({
            try {
                Thread.sleep(200)

                try {
                    currentCall?.cancel()
                } catch (e: Exception) {
                    android.util.Log.w("RadioService", "Error canceling recording call: ${e.message}")
                }

                currentThread?.let { thread ->
                    try {
                        thread.join(5000)
                        if (thread.isAlive) {
                            android.util.Log.w("RadioService", "Recording thread still alive after 5s, interrupting")
                            thread.interrupt()
                            thread.join(1000)
                        }
                    } catch (e: InterruptedException) {
                        android.util.Log.w("RadioService", "Interrupted while waiting for recording thread")
                        Thread.currentThread().interrupt()
                    }
                }

                currentFile?.let { file ->
                    if (file.exists()) {
                        val sizeKB = file.length() / 1024
                        if (sizeKB > 0) {
                            android.util.Log.d("RadioService", "Recording complete: ${file.absolutePath} (${sizeKB}KB)")
                        } else {
                            android.util.Log.w("RadioService", "Recording file is empty: ${file.absolutePath}")
                            file.delete()
                        }
                    } else {
                        android.util.Log.e("RadioService", "Recording file not found: ${file.absolutePath}")
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("RadioService", "Error during recording cleanup: ${e.message}", e)
            } finally {
                handler.post {
                    cleanupRecording()
                    if (player?.isPlaying == true) {
                        startForeground(NOTIFICATION_ID, createNotification("Playing"))
                    }
                    android.util.Log.d("RadioService", "Recording stopped and cleaned up")
                }
            }
        }, "RecordingCleanup").start()
    }

    private fun cleanupRecording() {
        try {
            recordingOutputStream?.close()
        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Error closing output stream", e)
        }

        recordingMediaStoreUri?.let { uri ->
            try {
                contentResolver.delete(uri, null, null)
                android.util.Log.d("RadioService", "Deleted pending MediaStore entry: $uri")
            } catch (e: Exception) {
                android.util.Log.w("RadioService", "Error deleting MediaStore entry: ${e.message}")
            }
        }

        recordingCall = null
        recordingThread = null
        recordingOutputStream = null
        recordingMediaStoreUri = null
        isRecording = false
        isRecordingActive.set(false)
    }

    private fun updateNotificationWithRecording() {
        if (player?.isPlaying == true) {
            startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_playing_recording)))
        }
    }

    /**
     * Resolve a playlist pointer (if any) on a worker thread and then call
     * [playStream] on the main thread with the final direct URL.
     *
     * URL resolution is kept strictly separate from format/codec detection.
     * If resolution fails we surface [ERROR_TYPE_PLAYLIST_UNREADABLE] rather
     * than silently falling back to the original URL: in practice, pointer
     * URLs never play back directly and pretending they might would just
     * waste reconnect attempts on something that can't work.
     */
    private fun startPlayAfterResolve(
        streamUrl: String,
        proxyHost: String,
        proxyPort: Int,
        proxyType: ProxyType,
        customProxyProtocol: String,
        proxyUsername: String,
        proxyPassword: String,
        proxyAuthType: String,
        proxyConnectionTimeout: Int,
        hlsHint: Boolean,
        codecHint: String
    ) {
        serviceScope.launch(Dispatchers.IO) {
            val resolution = resolveStreamUrlBlocking(
                streamUrl, proxyHost, proxyPort, proxyType, hlsHint, codecHint
            )
            handler.post {
                if (resolution == null) {
                    android.util.Log.e("RadioService", "Playlist pointer resolution failed for $streamUrl")
                    isStartingNewStream.set(false)
                    broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                    broadcastStreamError(ERROR_TYPE_PLAYLIST_UNREADABLE)
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification(getString(R.string.notification_playlist_unreadable))
                    )
                    return@post
                }
                val resolved = resolution.url
                // OR the catalog hlsHint with what the probe actually saw
                // on the wire. This fixes URLs whose extension lies (.pls
                // serving an HLS manifest, .m3u serving #EXTM3U, etc.).
                val effectiveHlsHint = hlsHint || resolution.hlsDetected
                if (resolved != streamUrl) {
                    android.util.Log.d("RadioService", "Resolved pointer $streamUrl -> $resolved")
                    // Update currentStreamUrl so recording picks up the direct
                    // URL (pointers would otherwise confuse the recording
                    // thread, which expects raw audio bytes).
                    currentStreamUrl = resolved
                }
                if (resolution.hlsDetected && !hlsHint) {
                    android.util.Log.d(
                        "RadioService",
                        "Probe detected HLS on $resolved - overriding media source"
                    )
                    // Persist so reconnect / recording uses HlsMediaSource too.
                    currentHlsHint = true
                }
                playStream(
                    resolved, proxyHost, proxyPort, proxyType,
                    customProxyProtocol, proxyUsername, proxyPassword,
                    proxyAuthType, proxyConnectionTimeout, effectiveHlsHint, codecHint
                )
            }
        }
    }

    /**
     * Outcome of blocking URL resolution: the final direct URL plus whether
     * the probe revealed HLS content. [hlsDetected] overrides a missing
     * catalog hlsHint when the URL extension lied about the real format
     * (classic case: .pls URLs that serve an HLS manifest directly).
     */
    private data class ResolvedStream(val url: String, val hlsDetected: Boolean)

    /**
     * Resolve a playlist pointer using an OkHttpClient that matches the
     * effective proxy configuration (honouring force-* modes). Returns
     * [ResolvedStream] with the final URL and an HLS-detected flag, or
     * null if the pointer couldn't be parsed/fetched.
     *
     * Fail-safe is important here: this runs BEFORE playStream's force-*
     * checks, so if we built a direct client for a clearnet station under
     * force-Tor-all, the resolver's own fetch would leak before playback
     * got a chance to refuse. If the required proxy isn't reachable we
     * return the original URL untouched so that playStream can apply its
     * proxy gate and surface the correct error (tor-not-connected etc.)
     * without ever opening a socket from the resolver.
     *
     * Runs on the caller's thread - callers must ensure they're off the
     * main thread when invoking.
     */
    private fun resolveStreamUrlBlocking(
        streamUrl: String,
        proxyHost: String,
        proxyPort: Int,
        proxyType: ProxyType,
        hlsHint: Boolean = false,
        codecHint: String = ""
    ): ResolvedStream? {
        // Fast path: if Radio Browser's catalog hints are authoritative for
        // this URL (codec is a direct audio codec, or HLS flag is set), skip
        // the probe entirely. This saves a full round-trip (painful over
        // Tor/I2P) for the majority of extensionless Shoutcast/Icecast URLs
        // that previously triggered a content-type probe.
        //
        // This does NOT bypass the proxy - it just skips OUR probe request.
        // ExoPlayer's subsequent stream fetch still goes through the proper
        // proxy pipeline in playStream().
        if (com.opensource.i2pradio.util.PlaylistResolver
                .canSkipWithHints(streamUrl, hlsHint, codecHint)) {
            return ResolvedStream(streamUrl, hlsDetected = hlsHint)
        }
        return try {
            val forceTorAll = PreferencesHelper.isForceTorAll(this)
            val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(this)
            val forceCustomProxy = PreferencesHelper.isForceCustomProxy(this)
            val forceCustomProxyExceptTorI2P = PreferencesHelper.isForceCustomProxyExceptTorI2P(this)
            val isI2PStream = proxyType == ProxyType.I2P || streamUrl.contains(".i2p")
            val isTorStream = proxyType == ProxyType.TOR || streamUrl.contains(".onion")

            // Compute effective proxy the same way playStream and
            // buildRecordingHttpClient do. If a force-* mode is set but the
            // required proxy isn't reachable, skip the resolver's network
            // fetch entirely - return the URL unchanged so playStream can
            // reject with the correct error message. This prevents any
            // clearnet leak from the resolver itself.
            val skipResolution = ResolvedStream(streamUrl, hlsDetected = false)
            val effective: Triple<String, Int, ProxyType>? = when {
                forceTorAll -> {
                    if (!TorManager.isConnected()) {
                        android.util.Log.w("RadioService", "Resolver skipping: force-Tor-all but Tor not connected - deferring to playStream gate")
                        return skipResolution
                    }
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                forceTorExceptI2P && isI2PStream ->
                    Triple(
                        if (proxyHost.isNotEmpty() && proxyType == ProxyType.I2P) proxyHost else "127.0.0.1",
                        if (proxyHost.isNotEmpty() && proxyType == ProxyType.I2P) proxyPort else 4444,
                        ProxyType.I2P
                    )
                forceTorExceptI2P && !isI2PStream -> {
                    if (!TorManager.isConnected()) {
                        android.util.Log.w("RadioService", "Resolver skipping: force-Tor-except-I2P, non-I2P, Tor not connected")
                        return skipResolution
                    }
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                forceCustomProxy -> {
                    val customHost = PreferencesHelper.getCustomProxyHost(this)
                    if (customHost.isEmpty()) {
                        android.util.Log.w("RadioService", "Resolver skipping: force-custom-proxy not configured")
                        return skipResolution
                    }
                    Triple(customHost, PreferencesHelper.getCustomProxyPort(this), ProxyType.CUSTOM)
                }
                forceCustomProxyExceptTorI2P && isI2PStream ->
                    Triple(
                        if (proxyHost.isNotEmpty() && proxyType == ProxyType.I2P) proxyHost else "127.0.0.1",
                        if (proxyHost.isNotEmpty() && proxyType == ProxyType.I2P) proxyPort else 4444,
                        ProxyType.I2P
                    )
                forceCustomProxyExceptTorI2P && isTorStream -> {
                    if (TorManager.isConnected()) {
                        Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                    } else if (proxyHost.isNotEmpty() && proxyType == ProxyType.TOR) {
                        Triple(proxyHost, proxyPort, ProxyType.TOR)
                    } else {
                        Triple("127.0.0.1", 9050, ProxyType.TOR)
                    }
                }
                forceCustomProxyExceptTorI2P && !isI2PStream && !isTorStream -> {
                    val customHost = PreferencesHelper.getCustomProxyHost(this)
                    if (customHost.isEmpty()) {
                        android.util.Log.w("RadioService", "Resolver skipping: force-custom-proxy-except-Tor-I2P, clearnet stream, not configured")
                        return skipResolution
                    }
                    Triple(customHost, PreferencesHelper.getCustomProxyPort(this), ProxyType.CUSTOM)
                }
                proxyHost.isNotEmpty() && proxyType != ProxyType.NONE ->
                    Triple(proxyHost, proxyPort, proxyType)
                else -> null  // no proxy required, safe to resolve direct
            }

            // Short timeouts on purpose: a slow pointer host shouldn't stall
            // playback start for tens of seconds. ExoPlayer's own timeouts
            // still govern the actual audio fetch downstream.
            val builder = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)

            if (effective != null) {
                val (effHost, effPort, effType) = effective
                val customProtocol = PreferencesHelper.getCustomProxyProtocol(this)
                val javaProxyType = when (effType) {
                    ProxyType.TOR -> Proxy.Type.SOCKS
                    ProxyType.I2P -> Proxy.Type.HTTP
                    ProxyType.CUSTOM -> when (customProtocol.uppercase()) {
                        "SOCKS4", "SOCKS5" -> Proxy.Type.SOCKS
                        "HTTP", "HTTPS" -> Proxy.Type.HTTP
                        else -> Proxy.Type.HTTP
                    }
                    ProxyType.NONE -> Proxy.Type.DIRECT
                }

                builder.proxy(Proxy(javaProxyType, InetSocketAddress(effHost, effPort)))
                if (effType == ProxyType.CUSTOM) {
                    val username = PreferencesHelper.getCustomProxyUsername(this)
                    val password = PreferencesHelper.getCustomProxyPassword(this)
                    val authType = PreferencesHelper.getCustomProxyAuthType(this)
                    if (username.isNotEmpty() && password.isNotEmpty()) {
                        builder.proxyAuthenticator { _, response ->
                            // Avoid infinite auth loops
                            if (response.request.header("Proxy-Authorization") != null) {
                                return@proxyAuthenticator null
                            }
                            when (authType.uppercase()) {
                                "DIGEST" -> DigestAuthenticator.authenticate(
                                    response, username, password
                                )
                                else -> {
                                    val credential = okhttp3.Credentials.basic(username, password)
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", credential)
                                        .build()
                                }
                            }
                        }
                    }
                }

                if (javaProxyType == Proxy.Type.SOCKS) {
                    builder.dns(SOCKS5_DNS)
                }
            }

            val client = builder.build()
            when (val result = com.opensource.i2pradio.util.PlaylistResolver.resolve(streamUrl, client)) {
                is com.opensource.i2pradio.util.PlaylistResolver.Result.Resolved ->
                    ResolvedStream(result.url, result.hlsDetected)
                is com.opensource.i2pradio.util.PlaylistResolver.Result.Failed -> {
                    android.util.Log.w("RadioService", "Playlist resolution failed: ${result.reason}")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("RadioService", "Unexpected error during playlist resolution: ${e.message}", e)
            // Fall through - caller will treat null as a failure.
            null
        }
    }

    private fun playStream(
        streamUrl: String,
        proxyHost: String,
        proxyPort: Int,
        proxyType: ProxyType = ProxyType.NONE,
        customProxyProtocol: String = "HTTP",
        proxyUsername: String = "",
        proxyPassword: String = "",
        proxyAuthType: String = "NONE",
        proxyConnectionTimeout: Int = 30,
        hlsHint: Boolean = false,
        codecHint: String = ""
    ) {
        try {
            // Reject FLV up front - ExoPlayer's default renderers can't decode
            // it. Checked here (in addition to the ACTION_PLAY handler) so
            // reconnects on an FLV station also short-circuit cleanly.
            if (isFlvCodec(codecHint)) {
                rejectUnsupportedCodec("FLV")
                return
            }

            val forceTorAll = PreferencesHelper.isForceTorAll(this)
            val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(this)
            val forceCustomProxy = PreferencesHelper.isForceCustomProxy(this)
            val forceCustomProxyExceptTorI2P = PreferencesHelper.isForceCustomProxyExceptTorI2P(this)
            val isI2PStream = proxyType == ProxyType.I2P || streamUrl.contains(".i2p")
            val isTorStream = proxyType == ProxyType.TOR || streamUrl.contains(".onion")

            android.util.Log.d("RadioService", "===== STREAM CONNECTION REQUEST =====")
            android.util.Log.d("RadioService", "Stream URL: $streamUrl")
            android.util.Log.d("RadioService", "Requested proxy: $proxyHost:$proxyPort (${proxyType.name})")
            android.util.Log.d("RadioService", "Force Tor All: $forceTorAll")
            android.util.Log.d("RadioService", "Force Tor Except I2P: $forceTorExceptI2P")
            android.util.Log.d("RadioService", "Force Custom Proxy: $forceCustomProxy")
            android.util.Log.d("RadioService", "Force Custom Proxy Except Tor/I2P: $forceCustomProxyExceptTorI2P")
            android.util.Log.d("RadioService", "Is I2P stream: $isI2PStream")
            android.util.Log.d("RadioService", "Is Tor stream: $isTorStream")
            android.util.Log.d("RadioService", "Tor connected: ${TorManager.isConnected()}")
            android.util.Log.d("RadioService", "Tor SOCKS: ${TorManager.getProxyHost()}:${TorManager.getProxyPort()}")

            if (forceTorAll && !TorManager.isConnected()) {
                android.util.Log.e("RadioService", "FORCE TOR ALL: Tor not connected - BLOCKING stream to prevent leak")
                isStartingNewStream.set(false)
                broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                broadcastStreamError(ERROR_TYPE_TOR_NOT_CONNECTED)
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_tor_blocked)))
                return
            }

            if (forceTorExceptI2P && !isI2PStream && !TorManager.isConnected()) {
                android.util.Log.e("RadioService", "FORCE TOR (except I2P): Tor not connected - BLOCKING non-I2P stream")
                isStartingNewStream.set(false)
                broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                broadcastStreamError(ERROR_TYPE_TOR_NOT_CONNECTED)
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_tor_blocked)))
                return
            }

            if (forceCustomProxy) {
                val customProxyHost = PreferencesHelper.getCustomProxyHost(this)
                if (customProxyHost.isEmpty()) {
                    android.util.Log.e("RadioService", "FORCE CUSTOM PROXY: Custom proxy not configured - BLOCKING stream")
                    isStartingNewStream.set(false)
                    broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                    broadcastStreamError(ERROR_TYPE_CUSTOM_PROXY_NOT_CONFIGURED)
                    startForeground(NOTIFICATION_ID, createNotification(getString(R.string.custom_proxy_not_configured_notification)))
                    return
                }
            }

            if (forceCustomProxyExceptTorI2P && !isI2PStream && !isTorStream) {
                val customProxyHost = PreferencesHelper.getCustomProxyHost(this)
                if (customProxyHost.isEmpty()) {
                    android.util.Log.e("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P): Custom proxy not configured - BLOCKING clearnet stream")
                    isStartingNewStream.set(false)
                    broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                    broadcastStreamError(ERROR_TYPE_CUSTOM_PROXY_NOT_CONFIGURED)
                    startForeground(NOTIFICATION_ID, createNotification(getString(R.string.custom_proxy_not_configured_notification)))
                    return
                }
            }

            // Warn user if I2P is not detected but still attempt to play
            // (detection may fail for external proxies or unusual configurations)
            if (isI2PStream && !I2PManager.isAvailable()) {
                android.util.Log.w("RadioService", "I2P not detected - warning user but attempting stream anyway")
                broadcastStreamError(ERROR_TYPE_I2P_NOT_CONNECTED)
            }

            // Warn user if Tor is not detected but still attempt to play
            // (detection may fail for external proxies or unusual configurations)
            if (isTorStream && !TorManager.isConnected()) {
                android.util.Log.w("RadioService", "Tor not detected - warning user but attempting stream anyway")
                broadcastStreamError(ERROR_TYPE_TOR_NOT_CONNECTED)
            }

            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, handler)
                    .build()
                audioManager.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                android.util.Log.w("RadioService", "Failed to gain audio focus")
                isStartingNewStream.set(false)
                broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_audio_focus_denied)))
                return
            }
            hasAudioFocus = true
            isStartingNewStream.set(true)

            stopStream()

            val (effectiveProxyHost, effectiveProxyPort, effectiveProxyType) = when {
                forceTorAll && TorManager.isConnected() -> {
                    android.util.Log.d("RadioService", "FORCE TOR ALL: Routing ALL traffic through Tor")
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                forceTorExceptI2P && isI2PStream -> {
                    if (proxyHost.isNotEmpty() && proxyType == ProxyType.I2P) {
                        android.util.Log.d("RadioService", "FORCE TOR (except I2P): Using I2P proxy for .i2p stream")
                        Triple(proxyHost, proxyPort, ProxyType.I2P)
                    } else {
                        android.util.Log.d("RadioService", "FORCE TOR (except I2P): Using default I2P proxy (127.0.0.1:4444)")
                        Triple("127.0.0.1", 4444, ProxyType.I2P)
                    }
                }
                forceTorExceptI2P && !isI2PStream && TorManager.isConnected() -> {
                    android.util.Log.d("RadioService", "FORCE TOR (except I2P): Routing non-I2P stream through Tor")
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                forceCustomProxy -> {
                    val customProxyHost = PreferencesHelper.getCustomProxyHost(this)
                    val customProxyPort = PreferencesHelper.getCustomProxyPort(this)
                    android.util.Log.d("RadioService", "FORCE CUSTOM PROXY: Routing ALL traffic through custom proxy")
                    Triple(customProxyHost, customProxyPort, ProxyType.CUSTOM)
                }
                forceCustomProxyExceptTorI2P && isI2PStream -> {
                    if (proxyHost.isNotEmpty() && proxyType == ProxyType.I2P) {
                        android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P): Using I2P proxy for .i2p stream")
                        Triple(proxyHost, proxyPort, ProxyType.I2P)
                    } else {
                        android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P): Using default I2P proxy (127.0.0.1:4444)")
                        Triple("127.0.0.1", 4444, ProxyType.I2P)
                    }
                }
                forceCustomProxyExceptTorI2P && isTorStream && TorManager.isConnected() -> {
                    // In "except Tor/I2P" mode, Tor integration is implicitly enabled for .onion streams
                    android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P): Routing .onion stream through Tor")
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                forceCustomProxyExceptTorI2P && isTorStream -> {
                    // Embedded Tor not connected - fall back to default Tor SOCKS port for external proxies (InviZible Pro, Orbot)
                    if (proxyHost.isNotEmpty() && proxyType == ProxyType.TOR) {
                        android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P): Using station Tor proxy config")
                        Triple(proxyHost, proxyPort, ProxyType.TOR)
                    } else {
                        android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P): Using default Tor proxy (127.0.0.1:9050)")
                        Triple("127.0.0.1", 9050, ProxyType.TOR)
                    }
                }
                forceCustomProxyExceptTorI2P && !isI2PStream && !isTorStream -> {
                    val customProxyHost = PreferencesHelper.getCustomProxyHost(this)
                    val customProxyPort = PreferencesHelper.getCustomProxyPort(this)
                    android.util.Log.d("RadioService", "FORCE CUSTOM PROXY (except Tor/I2P): Routing clearnet stream through custom proxy")
                    Triple(customProxyHost, customProxyPort, ProxyType.CUSTOM)
                }
                proxyType == ProxyType.TOR &&
                PreferencesHelper.isEmbeddedTorEnabled(this) &&
                TorManager.isConnected() -> {
                    android.util.Log.d("RadioService", "Using embedded Tor proxy for .onion stream")
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                proxyHost.isNotEmpty() && proxyType != ProxyType.NONE -> {
                    if (proxyType == ProxyType.TOR && !PreferencesHelper.isEmbeddedTorEnabled(this)) {
                        android.util.Log.w("RadioService", "Station requires Tor but Tor integration is disabled - using direct connection")
                        Triple("", 0, ProxyType.NONE)
                    } else {
                        Triple(proxyHost, proxyPort, proxyType)
                    }
                }
                else -> Triple("", 0, ProxyType.NONE)
            }

            // When using force custom proxy modes, load all settings from global preferences
            // instead of using station-specific parameters (which may be empty)
            val useGlobalCustomProxySettings = effectiveProxyType == ProxyType.CUSTOM &&
                (forceCustomProxy || forceCustomProxyExceptTorI2P)

            val effectiveCustomProxyProtocol = if (useGlobalCustomProxySettings) {
                PreferencesHelper.getCustomProxyProtocol(this)
            } else {
                customProxyProtocol
            }

            val effectiveProxyUsername = if (useGlobalCustomProxySettings) {
                PreferencesHelper.getCustomProxyUsername(this)
            } else {
                proxyUsername
            }

            val effectiveProxyPassword = if (useGlobalCustomProxySettings) {
                PreferencesHelper.getCustomProxyPassword(this)
            } else {
                proxyPassword
            }

            val effectiveProxyAuthType = if (useGlobalCustomProxySettings) {
                PreferencesHelper.getCustomProxyAuthType(this)
            } else {
                proxyAuthType
            }

            val effectiveProxyConnectionTimeout = if (useGlobalCustomProxySettings) {
                PreferencesHelper.getCustomProxyConnectionTimeout(this)
            } else {
                proxyConnectionTimeout
            }

            android.util.Log.d("RadioService", "===== FINAL ROUTING DECISION =====")
            android.util.Log.d("RadioService", "Effective proxy: $effectiveProxyHost:$effectiveProxyPort (${effectiveProxyType.name})")
            android.util.Log.d("RadioService", "Using global custom proxy settings: $useGlobalCustomProxySettings")
            when (effectiveProxyType) {
                ProxyType.TOR -> android.util.Log.d("RadioService", "ROUTING: Traffic will go through TOR SOCKS proxy")
                ProxyType.I2P -> android.util.Log.d("RadioService", "ROUTING: Traffic will go through I2P HTTP proxy")
                ProxyType.CUSTOM -> {
                    android.util.Log.d("RadioService", "ROUTING: Traffic will go through CUSTOM $effectiveCustomProxyProtocol proxy")
                    if (effectiveProxyUsername.isNotEmpty()) {
                        android.util.Log.d("RadioService", "CUSTOM PROXY: Using authentication (username: [REDACTED], auth type: $effectiveProxyAuthType)")
                    }
                }
                ProxyType.NONE -> android.util.Log.w("RadioService", "ROUTING: DIRECT CONNECTION - No proxy! (potential leak if unintended)")
            }
            android.util.Log.d("RadioService", "==================================")

            val okHttpClient = synchronized(okHttpClientLock) {
                currentOkHttpClient = if (effectiveProxyHost.isNotEmpty() && effectiveProxyType != ProxyType.NONE) {
                    val javaProxyType = when (effectiveProxyType) {
                        ProxyType.TOR -> Proxy.Type.SOCKS
                        ProxyType.I2P -> Proxy.Type.HTTP
                        ProxyType.CUSTOM -> {
                            when (effectiveCustomProxyProtocol.uppercase()) {
                                "SOCKS4", "SOCKS5" -> Proxy.Type.SOCKS
                                "HTTP", "HTTPS" -> Proxy.Type.HTTP
                                else -> Proxy.Type.HTTP
                            }
                        }
                        ProxyType.NONE -> Proxy.Type.DIRECT
                    }
                    android.util.Log.d("RadioService", "Using ${effectiveProxyType.name} proxy: $effectiveProxyHost:$effectiveProxyPort")

                    val builder = OkHttpClient.Builder()
                        .proxy(Proxy(javaProxyType, InetSocketAddress(effectiveProxyHost, effectiveProxyPort)))

                    // CRITICAL: Force DNS through SOCKS5 to prevent DNS leaks
                    // Only applies to SOCKS proxies (Tor, SOCKS4, SOCKS5) - HTTP proxies handle DNS differently
                    if (javaProxyType == Proxy.Type.SOCKS) {
                        builder.dns(SOCKS5_DNS)
                        android.util.Log.d("RadioService", "DNS resolution will be handled by SOCKS proxy")
                    }

                    builder.addInterceptor(BandwidthTrackingInterceptor(this))
                        .addInterceptor { chain ->
                            val request = chain.request()
                            android.util.Log.d("RadioService", "PROXY REQUEST: ${request.method} ${request.url}")
                            android.util.Log.d("RadioService", "PROXY TYPE: ${effectiveProxyType.name} ($javaProxyType)")
                            android.util.Log.d("RadioService", "PROXY ADDRESS: $effectiveProxyHost:$effectiveProxyPort")
                            try {
                                val startTime = System.currentTimeMillis()
                                val response = chain.proceed(request)
                                val duration = System.currentTimeMillis() - startTime
                                android.util.Log.d("RadioService", "PROXY RESPONSE: ${response.code} ${response.message} (${duration}ms)")
                                android.util.Log.d("RadioService", "PROXY HANDSHAKE: ${response.handshake?.let { "TLS ${it.tlsVersion}" } ?: "None"}")
                                android.util.Log.d("RadioService", "RESPONSE HEADERS: ${response.headers}")
                                response
                            } catch (e: Exception) {
                                android.util.Log.e("RadioService", "PROXY CONNECTION ERROR: ${e.javaClass.simpleName}: ${e.message}", e)
                                when (e) {
                                    is java.net.ConnectException -> android.util.Log.e("RadioService", "└─ Proxy not reachable - check if proxy is running on $effectiveProxyHost:$effectiveProxyPort")
                                    is java.net.SocketTimeoutException -> android.util.Log.e("RadioService", "└─ Proxy connection timeout - proxy may be slow or unresponsive")
                                    is java.net.UnknownHostException -> android.util.Log.e("RadioService", "└─ Cannot resolve proxy host '$effectiveProxyHost'")
                                    is javax.net.ssl.SSLException -> android.util.Log.e("RadioService", "└─ SSL/TLS error - check proxy protocol settings")
                                    is java.io.EOFException -> android.util.Log.e("RadioService", "└─ Proxy closed connection unexpectedly")
                                }
                                throw e
                            }
                        }

                    // Add proxy authentication if custom proxy with credentials
                    if (effectiveProxyType == ProxyType.CUSTOM && effectiveProxyUsername.isNotEmpty() && effectiveProxyPassword.isNotEmpty()) {
                        android.util.Log.d("RadioService", "Adding proxy authentication for custom proxy (user: [REDACTED], auth type: $effectiveProxyAuthType)")
                        builder.proxyAuthenticator { route, response ->
                            android.util.Log.d("RadioService", "Proxy authentication challenge received (${response.code})")

                            // Check if we've already tried authentication to avoid infinite loops
                            val previousAuth = response.request.header("Proxy-Authorization")
                            if (previousAuth != null) {
                                android.util.Log.w("RadioService", "Authentication already attempted - credentials may be incorrect")
                                return@proxyAuthenticator null
                            }

                            when (effectiveProxyAuthType.uppercase()) {
                                "BASIC" -> {
                                    android.util.Log.d("RadioService", "Using Basic authentication")
                                    val credential = okhttp3.Credentials.basic(effectiveProxyUsername, effectiveProxyPassword)
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", credential)
                                        .build()
                                }
                                "DIGEST" -> {
                                    android.util.Log.d("RadioService", "Using Digest authentication")
                                    DigestAuthenticator.authenticate(response, effectiveProxyUsername, effectiveProxyPassword)
                                }
                                else -> {
                                    android.util.Log.w("RadioService", "Unknown auth type: $effectiveProxyAuthType, falling back to Basic")
                                    val credential = okhttp3.Credentials.basic(effectiveProxyUsername, effectiveProxyPassword)
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", credential)
                                        .build()
                                }
                            }
                        }
                    }

                    val timeout = if (effectiveProxyType == ProxyType.CUSTOM && effectiveProxyConnectionTimeout > 0) {
                        effectiveProxyConnectionTimeout.toLong()
                    } else {
                        60L
                    }

                    builder
                        .connectTimeout(timeout, TimeUnit.SECONDS)
                        .readTimeout(0, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(true)
                        .build()
                } else {
                    OkHttpClient.Builder()
                        .addInterceptor(BandwidthTrackingInterceptor(this))
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(0, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(true)
                        .build()
                }
                currentOkHttpClient!!
            }

            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("DeutsiaRadio/1.0")
                .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
                .build()

            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            player = ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttributes, true)
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                .build().apply {
                // Combine URL extension signals with the hlsHint from Radio
                // Browser. This fixes streams with extensionless URLs (e.g.
                // /listen?sid=1) that are actually HLS - the catalog tells us
                // truthfully what they are even when the URL doesn't.
                val useHls = isHlsStream(streamUrl) || hlsHint
                val useDash = isDashStream(streamUrl)
                isLiveStream = !useHls && !useDash
                
                val mediaSource: MediaSource = when {
                    useHls -> {
                        HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(streamUrl))
                    }
                    useDash -> {
                        DashMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(streamUrl))
                    }
                    else -> {
                        ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(streamUrl))
                    }
                }

                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true

                isStartingNewStream.set(false)

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e("RadioService", "Playback error: ${error.message}")
                        updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
                        // Surface distinct errors for well-known HTTP status
                        // codes so the UI can show something actionable
                        // (geo-block vs station removed vs auth needed)
                        // instead of the generic "stream failed" toast a
                        // reconnect loop eventually yields.
                        classifyAndBroadcastHttpError(error)
                        scheduleReconnect()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                reconnectAttempts = 0
                                val status = if (isRecording) getString(R.string.notification_playing_recording) else getString(R.string.notification_playing)
                                startForeground(NOTIFICATION_ID, createNotification(status))
                                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                activateMediaSession()
                                player?.audioSessionId?.let { sessionId ->
                                    if (sessionId != 0) {
                                        broadcastAudioSessionOpen(sessionId)
                                        equalizerManager?.initialize(sessionId)
                                    }
                                }
                                extractStreamInfo()
                                startPlaybackTimeUpdates()
                                broadcastPlaybackStateChanged(isBuffering = false, isPlaying = true)
                                android.util.Log.d("RadioService", "Stream playing successfully")
                            }
                            Player.STATE_BUFFERING -> {
                                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_buffering)))
                                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                                broadcastPlaybackStateChanged(isBuffering = true, isPlaying = false)
                                android.util.Log.d("RadioService", "Buffering stream...")
                            }
                            Player.STATE_ENDED -> {
                                android.util.Log.d("RadioService", "Stream ended, reconnecting...")
                                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                                broadcastPlaybackStateChanged(isBuffering = true, isPlaying = false)
                                scheduleReconnect()
                            }
                            Player.STATE_IDLE -> {
                                android.util.Log.d("RadioService", "Player idle")
                                if (!isStartingNewStream.get()) {
                                    broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                                }
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                            cancelSessionDeactivation()
                            broadcastPlaybackStateChanged(isBuffering = false, isPlaying = true)
                            if (playbackStartTimeMillis > 0 && playbackTimeUpdateRunnable == null) {
                                playbackTimeUpdateRunnable = object : Runnable {
                                    override fun run() {
                                        if (player?.isPlaying == true) {
                                            broadcastPlaybackTimeUpdate()
                                            handler.postDelayed(this, playbackTimeUpdateInterval)
                                        }
                                    }
                                }
                                handler.post(playbackTimeUpdateRunnable!!)
                            }
                        } else if (player?.playbackState == Player.STATE_READY) {
                            // Player paused (e.g., via notification, headphone disconnect, audio focus loss)
                            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                            broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                            playbackTimeUpdateRunnable?.let { handler.removeCallbacks(it) }
                            playbackTimeUpdateRunnable = null
                        }
                    }

                    override fun onMetadata(metadata: Metadata) {
                        // Extract ICY metadata (artist/track info)
                        for (i in 0 until metadata.length()) {
                            val entry = metadata.get(i)
                            if (entry is IcyInfo) {
                                entry.title?.let { rawTitle ->
                                    // Trim whitespace from the metadata
                                    val trimmedTitle = rawTitle.trim()
                                    if (trimmedTitle.isNotBlank() && trimmedTitle != currentMetadata) {
                                        currentMetadata = trimmedTitle

                                        // Parse artist and title from metadata
                                        val (artist, title) = parseMetadata(trimmedTitle)
                                        currentArtist = artist
                                        currentTitle = title

                                        broadcastMetadataChanged(trimmedTitle, artist, title)
                                        android.util.Log.d("RadioService", "ICY metadata: $trimmedTitle (Artist: $artist, Title: $title)")
                                    }
                                }
                            }
                        }
                    }
                })
            }

            // Note: startForeground is called in onStartCommand before playStream()

        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Error playing stream", e)
            isStartingNewStream.set(false)  // Reset flag on exception
            // Broadcast failure to UI
            broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (currentStreamUrl == null) {
            android.util.Log.d("RadioService", "No stream to reconnect to")
            broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
            return
        }

        if (reconnectAttempts >= maxReconnectAttempts) {
            android.util.Log.e("RadioService", "Max reconnection attempts reached")
            // Broadcast final failure to UI
            broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
            broadcastStreamError(ERROR_TYPE_MAX_RETRIES)
            startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_connection_failed)))
            return
        }

        val delay = minOf(1000L * reconnectAttempts, 5000L)
        reconnectAttempts++

        android.util.Log.d("RadioService", "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        // Keep buffering state while reconnecting
        broadcastPlaybackStateChanged(isBuffering = true, isPlaying = false)
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_reconnecting)))

        // Cancel any pending reconnect before scheduling a new one
        reconnectRunnable?.let { handler.removeCallbacks(it) }

        reconnectRunnable = Runnable {
            currentStreamUrl?.let { url ->
                playStream(url, currentProxyHost ?: "", currentProxyPort, currentProxyType,
                    currentCustomProxyProtocol, currentProxyUsername, currentProxyPassword,
                    currentProxyAuthType, currentProxyConnectionTimeout,
                    currentHlsHint, currentCodecHint)
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }
    
    private fun pauseOrStopForLive() {
    if (isLiveStream) {
        val stopIntent = Intent(this, RadioService::class.java).apply {
            action = ACTION_STOP
        }
        startService(stopIntent)
    } else {
        player?.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
        scheduleSessionDeactivation()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_paused)))
    }
}
    private fun stopStream() {
        // Stop playback time updates first
        stopPlaybackTimeUpdates()
        isLiveStream = false

        // Cancel only the reconnect runnable, not ALL callbacks
        // Using removeCallbacksAndMessages(null) is too aggressive and can
        // interfere with other pending operations
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null

        // Clear metadata, bitrate, and codec when stopping stream
        // This prevents stale metadata from appearing when switching stations
        // or when the UI is recreated (e.g., Material You toggle)
        currentMetadata = null
        currentArtist = null
        currentTitle = null
        currentBitrate = 0
        currentCodec = null

        // Broadcast audio session close before releasing player
        player?.audioSessionId?.let { sessionId ->
            if (sessionId != 0) {
                broadcastAudioSessionClose(sessionId)
            }
        }

        player?.apply {
            stop()
            release()
        }
        player = null

        // Clean up OkHttp client resources to prevent memory leaks
        // Synchronized to prevent race conditions when rapidly switching streams
        synchronized(okHttpClientLock) {
            currentOkHttpClient?.let { client ->
                try {
                    // Shutdown dispatcher to stop background threads
                    client.dispatcher.executorService.shutdown()
                    // Evict all connections from the pool
                    client.connectionPool.evictAll()
                    // Close the cache if present
                    client.cache?.close()
                    android.util.Log.d("RadioService", "OkHttp client resources cleaned up")
                } catch (e: Exception) {
                    android.util.Log.w("RadioService", "Error cleaning up OkHttp client: ${e.message}")
                }
            }
            currentOkHttpClient = null
        }

        // Properly abandon audio focus with the correct listener/request
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = Intent(this, RadioService::class.java).apply {
            action = ACTION_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, RadioService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build media style with session token for Now Playing card integration
        val mediaStyle = MediaNotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1)

        // Set session token for media notification integration
        mediaSession?.sessionToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentStationName)
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_pause, getString(R.string.notification_action_pause), playPausePendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_action_stop), stopPendingIntent)
            .setStyle(mediaStyle)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * Extract stream format info (bitrate, codec) from ExoPlayer
     */
    private fun extractStreamInfo() {
        player?.let { exoPlayer ->
            val format = exoPlayer.audioFormat
            if (format != null) {
                val newBitrate = format.bitrate.takeIf { it != Format.NO_VALUE } ?: 0
                val newCodec = format.codecs ?: format.sampleMimeType?.replace("audio/", "")?.uppercase() ?: "Unknown"

                if (newBitrate != currentBitrate || newCodec != currentCodec) {
                    currentBitrate = newBitrate
                    currentCodec = newCodec
                    broadcastStreamInfoChanged(currentBitrate, currentCodec ?: "Unknown")
                    android.util.Log.d("RadioService", "Stream info: ${currentBitrate / 1000}kbps, $currentCodec")
                }
            }
        }
    }

    /**
     * Parse metadata string to extract artist and title.
     * Tries common delimiters like " - ", " | ", etc.
     * Returns Pair(artist, title) or Pair(null, originalMetadata) if no delimiter found.
     */
    private fun parseMetadata(metadata: String): Pair<String?, String?> {
        // Try each delimiter in order of preference
        for (delimiter in METADATA_DELIMITERS) {
            val index = metadata.indexOf(delimiter)
            if (index > 0 && index < metadata.length - delimiter.length) {
                val artist = metadata.substring(0, index).trim()
                val title = metadata.substring(index + delimiter.length).trim()
                // Only accept if both parts are non-empty
                if (artist.isNotBlank() && title.isNotBlank()) {
                    return Pair(artist, title)
                }
            }
        }
        // No delimiter found - return the whole thing as the title
        return Pair(null, metadata)
    }

    /**
     * Broadcast metadata change to UI with parsed artist and title
     */
    private fun broadcastMetadataChanged(metadata: String, artist: String?, title: String?) {
        val intent = Intent(BROADCAST_METADATA_CHANGED).apply {
            putExtra(EXTRA_METADATA, metadata)
            putExtra(EXTRA_ARTIST, artist)
            putExtra(EXTRA_TITLE, title)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Broadcast stream info change to UI
     */
    private fun broadcastStreamInfoChanged(bitrate: Int, codec: String) {
        val intent = Intent(BROADCAST_STREAM_INFO_CHANGED).apply {
            putExtra(EXTRA_BITRATE, bitrate)
            putExtra(EXTRA_CODEC, codec)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Broadcast playback state change to UI (buffering, playing, etc.)
     */
    private fun broadcastPlaybackStateChanged(isBuffering: Boolean, isPlaying: Boolean) {
        val intent = Intent(BROADCAST_PLAYBACK_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_BUFFERING, isBuffering)
            putExtra(EXTRA_IS_PLAYING, isPlaying)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Broadcast cover art change to UI for real-time updates
     */
    fun broadcastCoverArtChanged(coverArtUri: String?, stationId: Long = -1L) {
        currentCoverArtUri = coverArtUri
        val intent = Intent(BROADCAST_COVER_ART_CHANGED).apply {
            putExtra(EXTRA_COVER_ART_URI, coverArtUri)
            putExtra(EXTRA_STATION_ID, stationId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        android.util.Log.d("RadioService", "Broadcast cover art changed: $coverArtUri")

        // Also update the media session with new cover art
        updateMediaMetadata(currentStationName, coverArtUri, currentProxyType == ProxyType.TOR || currentProxyType == ProxyType.I2P)
    }

    /**
     * Broadcast playback time update for buffer bar UI
     */
    private fun broadcastPlaybackTimeUpdate() {
        val elapsedMs = if (playbackStartTimeMillis > 0) {
            System.currentTimeMillis() - playbackStartTimeMillis
        } else 0L

        val bufferedPositionMs = player?.bufferedPosition ?: 0L
        val currentPositionMs = player?.currentPosition ?: 0L

        val intent = Intent(BROADCAST_PLAYBACK_TIME_UPDATE).apply {
            putExtra(EXTRA_PLAYBACK_ELAPSED_MS, elapsedMs)
            putExtra(EXTRA_BUFFERED_POSITION_MS, bufferedPositionMs)
            putExtra(EXTRA_CURRENT_POSITION_MS, currentPositionMs)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Broadcast stream error to UI for toast notification
     */
    private fun broadcastStreamError(errorType: String, codecName: String? = null) {
        val intent = Intent(BROADCAST_STREAM_ERROR).apply {
            putExtra(EXTRA_STREAM_ERROR_TYPE, errorType)
            if (codecName != null) {
                putExtra(EXTRA_UNSUPPORTED_CODEC_NAME, codecName)
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Start periodic playback time updates
     */
    private fun startPlaybackTimeUpdates() {
        stopPlaybackTimeUpdates()
        playbackStartTimeMillis = System.currentTimeMillis()
        playbackTimeUpdateRunnable = object : Runnable {
            override fun run() {
                if (player?.isPlaying == true) {
                    broadcastPlaybackTimeUpdate()
                    handler.postDelayed(this, playbackTimeUpdateInterval)
                }
            }
        }
        handler.post(playbackTimeUpdateRunnable!!)
    }

    /**
     * Stop periodic playback time updates
     */
    private fun stopPlaybackTimeUpdates() {
        playbackTimeUpdateRunnable?.let { handler.removeCallbacks(it) }
        playbackTimeUpdateRunnable = null
        playbackStartTimeMillis = 0L
    }

    /**
     * Get current cover art URI
     */
    fun getCurrentCoverArtUri(): String? = currentCoverArtUri

    /**
     * Get current metadata (for UI binding)
     */
    fun getCurrentMetadata(): String? = currentMetadata

    /**
     * Get current parsed artist (for UI binding)
     */
    fun getCurrentArtist(): String? = currentArtist

    /**
     * Get current parsed title (for UI binding)
     */
    fun getCurrentTitle(): String? = currentTitle

    /**
     * Get current bitrate in bps (for UI binding)
     */
    fun getCurrentBitrate(): Int = currentBitrate

    /**
     * Get current codec (for UI binding)
     */
    fun getCurrentCodec(): String? = currentCodec

    /**
     * Get ExoPlayer's audio session ID for equalizer
     */
    fun getAudioSessionId(): Int = player?.audioSessionId ?: 0

    /**
     * Get the built-in equalizer manager
     */
    fun getEqualizerManager(): EqualizerManager? = equalizerManager

    /**
     * Set the player volume (0.0 to 1.0)
     * This only affects the radio stream, not system-wide audio
     */
    fun setPlayerVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * Get the current player volume (0.0 to 1.0)
     */
    fun getPlayerVolume(): Float = player?.volume ?: 1f

    /**
     * Check if the player is currently playing
     */
    fun isPlaying(): Boolean = player?.isPlaying == true

    /**
     * Check if the player is currently buffering
     */
    fun isBuffering(): Boolean = player?.playbackState == Player.STATE_BUFFERING

    /**
     * Broadcast audio session open to allow equalizer apps to attach.
     * This follows the standard Android audio effect protocol.
     */
    private fun broadcastAudioSessionOpen(audioSessionId: Int) {
        if (audioSessionId == 0) return

        val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        sendBroadcast(intent)
        android.util.Log.d("RadioService", "Broadcast audio session open: $audioSessionId")
    }

    /**
     * Broadcast audio session close to notify equalizer apps to detach.
     */
    private fun broadcastAudioSessionClose(audioSessionId: Int) {
        if (audioSessionId == 0) return

        val intent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        }
        sendBroadcast(intent)
        android.util.Log.d("RadioService", "Broadcast audio session close: $audioSessionId")
    }

    /**
     * Called when the user swipes the app away from recent apps.
     * Stop playback and clean up resources.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("RadioService", "App task removed, stopping playback")
        stopRecording()
        cancelSleepTimer()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        deactivateMediaSession()
        stopStream()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister audio becoming noisy receiver
        if (becomingNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }
        stopRecording()
        cancelSleepTimer()
        equalizerManager?.release()
        equalizerManager = null
        stopStream()
        cancelSessionDeactivation()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        // Cancel service-scoped coroutines to prevent leaks
        serviceScope.cancel()
    }
}

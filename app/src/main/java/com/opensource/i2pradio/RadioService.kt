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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
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
                        exoPlayer.pause()
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                        scheduleSessionDeactivation()
                        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_paused)))
                        broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
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

    @Volatile private var pendingRecordingStreamUrl: String? = null
    @Volatile private var pendingRecordingProxyHost: String? = null
    @Volatile private var pendingRecordingProxyPort: Int = 0
    @Volatile private var pendingRecordingProxyType: ProxyType = ProxyType.NONE
    private val switchStreamRequested = AtomicBoolean(false)

    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerEndTime: Long = 0L

    private var reconnectRunnable: Runnable? = null

    private var currentMetadata: String? = null
    private var currentBitrate: Int = 0
    private var currentCodec: String? = null

    private var playbackStartTimeMillis: Long = 0L
    private var playbackTimeUpdateRunnable: Runnable? = null
    private val playbackTimeUpdateInterval = 1000L // Update every second

    private var equalizerManager: EqualizerManager? = null

    companion object {
        const val ACTION_PLAY = "com.opensource.i2pradio.PLAY"
        const val ACTION_PAUSE = "com.opensource.i2pradio.PAUSE"
        const val ACTION_STOP = "com.opensource.i2pradio.STOP"
        const val ACTION_START_RECORDING = "com.opensource.i2pradio.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.opensource.i2pradio.STOP_RECORDING"
        const val ACTION_SWITCH_RECORDING_STREAM = "com.opensource.i2pradio.SWITCH_RECORDING_STREAM"
        const val ACTION_SET_SLEEP_TIMER = "com.opensource.i2pradio.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.opensource.i2pradio.CANCEL_SLEEP_TIMER"
        const val CHANNEL_ID = "DeutsiaRadioChannel"
        const val NOTIFICATION_ID = 1

        const val BROADCAST_METADATA_CHANGED = "com.opensource.i2pradio.METADATA_CHANGED"
        const val BROADCAST_STREAM_INFO_CHANGED = "com.opensource.i2pradio.STREAM_INFO_CHANGED"
        const val BROADCAST_PLAYBACK_STATE_CHANGED = "com.opensource.i2pradio.PLAYBACK_STATE_CHANGED"
        const val BROADCAST_RECORDING_ERROR = "com.opensource.i2pradio.RECORDING_ERROR"
        const val BROADCAST_RECORDING_COMPLETE = "com.opensource.i2pradio.RECORDING_COMPLETE"
        const val EXTRA_METADATA = "metadata"
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
                    player?.pause()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    scheduleSessionDeactivation()
                }

                override fun onStop() {
                    val stopIntent = Intent(this@RadioService, RadioService::class.java).apply {
                        action = ACTION_STOP
                    }
                    startService(stopIntent)
                }
            })
        }
    }

    private fun updatePlaybackState(state: Int) {
        val playbackStateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)

        mediaSession?.setPlaybackState(playbackStateBuilder.build())
    }

    private fun updateMediaMetadata(stationName: String, coverArtUri: String?) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stationName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stationName)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "deutsia radio")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "deutsia radio")

        if (!coverArtUri.isNullOrEmpty()) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(this@RadioService)
                        .data(coverArtUri)
                        .allowHardware(false)
                        .build()

                    val result = SecureImageLoader.execute(this@RadioService, request)
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

                currentStreamUrl = streamUrl
                currentProxyHost = proxyHost
                currentProxyPort = proxyPort
                currentProxyType = proxyType
                currentCustomProxyProtocol = customProxyProtocol
                currentProxyUsername = proxyUsername
                currentProxyPassword = proxyPassword
                currentProxyAuthType = proxyAuthType
                currentProxyConnectionTimeout = proxyConnectionTimeout
                currentStationName = stationName
                currentCoverArtUri = coverArtUri
                reconnectAttempts = 0

                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_connecting)))
                broadcastPlaybackStateChanged(isBuffering = true, isPlaying = false)
                activateMediaSession()
                updateMediaMetadata(stationName, coverArtUri)
                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)

                playStream(streamUrl, proxyHost, proxyPort, proxyType, customProxyProtocol, proxyUsername, proxyPassword, proxyAuthType, proxyConnectionTimeout)
            }
            ACTION_PAUSE -> {
                player?.pause()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                scheduleSessionDeactivation()
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_paused)))
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

                switchRecordingStream(newStreamUrl, newProxyHost, newProxyPort, newProxyType, newStationName)
            }
            ACTION_SET_SLEEP_TIMER -> {
                val minutes = intent.getIntExtra("minutes", 0)
                setSleepTimer(minutes)
            }
            ACTION_CANCEL_SLEEP_TIMER -> {
                cancelSleepTimer()
            }
        }
        return START_STICKY
    }

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
        val format = detectStreamFormat(streamUrl)
        val sanitizedName = stationName.replace(Regex("[^a-zA-Z0-9\\s]"), "").replace(Regex("\\s+"), "_")
        val fileName = "${sanitizedName}_$timestamp.$format"

        android.util.Log.d("RadioService", "Starting recording for: $stationName, URL: $streamUrl")

        val mimeType = when (format) {
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            else -> "audio/mpeg"
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

        val recordingClient = buildRecordingHttpClient()
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "DeutsiaRadio-Recorder/1.0")
            .header("Icy-MetaData", "0")
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        isRecordingActive.set(true)
        isRecording = true

        val call = recordingClient.newCall(request)
        recordingCall = call

        val finalFileName = fileName
        val finalRecordingsDir = recordingsDir
        val finalStreamUrl = streamUrl
        val finalMimeType = mimeType
        val finalUseMediaStore = useMediaStore
        val finalUseCustomDir = useCustomDir
        val finalCustomDirUri = customDirUri
        val finalFormat = format

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

            try {
                android.util.Log.d("RadioService", "Recording thread started, connecting to: $finalStreamUrl")

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

                        val newFile = docFile.createFile(finalMimeType, finalFileName.substringBeforeLast("."))
                        if (newFile == null) {
                            android.util.Log.e("RadioService", "Failed to create file in custom directory")
                            handler.post {
                                broadcastRecordingError(getString(R.string.recording_error_cannot_create_file))
                                cleanupRecording()
                            }
                            return@Thread
                        }

                        filePath = newFile.name ?: finalFileName
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
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, finalFileName)
                        put(MediaStore.Audio.Media.MIME_TYPE, finalMimeType)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/deutsia radio")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }

                    val resolver = contentResolver
                    mediaStoreUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)

                    if (mediaStoreUri == null) {
                        android.util.Log.e("RadioService", "Failed to create MediaStore entry")
                        handler.post {
                            broadcastRecordingError(getString(R.string.recording_error_cannot_create_file))
                            cleanupRecording()
                        }
                        return@Thread
                    }

                    recordingMediaStoreUri = mediaStoreUri
                    filePath = "Music/deutsia_radio/$finalFileName"
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
                    file = File(finalRecordingsDir!!, finalFileName)
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

                var currentInputStream = responseBody.byteStream()
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

    private fun buildRecordingHttpClient(): OkHttpClient {
        val forceTorAll = PreferencesHelper.isForceTorAll(this)
        val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(this)
        val forceCustomProxy = PreferencesHelper.isForceCustomProxy(this)
        val forceCustomProxyExceptTorI2P = PreferencesHelper.isForceCustomProxyExceptTorI2P(this)
        val isI2PStream = currentProxyType == ProxyType.I2P || currentStreamUrl?.contains(".i2p") == true
        val isTorStream = currentProxyType == ProxyType.TOR || currentStreamUrl?.contains(".onion") == true

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

    private fun switchRecordingStream(
        newStreamUrl: String,
        newProxyHost: String,
        newProxyPort: Int,
        newProxyType: ProxyType,
        newStationName: String
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

    private fun playStream(
        streamUrl: String,
        proxyHost: String,
        proxyPort: Int,
        proxyType: ProxyType = ProxyType.NONE,
        customProxyProtocol: String = "HTTP",
        proxyUsername: String = "",
        proxyPassword: String = "",
        proxyAuthType: String = "NONE",
        proxyConnectionTimeout: Int = 30
    ) {
        try {
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
                        android.util.Log.d("RadioService", "CUSTOM PROXY: Using authentication (username: $effectiveProxyUsername, auth type: $effectiveProxyAuthType)")
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
                        android.util.Log.d("RadioService", "Adding proxy authentication for custom proxy (user: $effectiveProxyUsername, auth type: $effectiveProxyAuthType)")
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
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamUrl))

                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true

                isStartingNewStream.set(false)

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e("RadioService", "Playback error: ${error.message}")
                        updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
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
                                entry.title?.let { title ->
                                    if (title.isNotBlank() && title != currentMetadata) {
                                        currentMetadata = title
                                        broadcastMetadataChanged(title)
                                        android.util.Log.d("RadioService", "ICY metadata: $title")
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
                    currentProxyAuthType, currentProxyConnectionTimeout)
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun stopStream() {
        // Stop playback time updates first
        stopPlaybackTimeUpdates()

        // Cancel only the reconnect runnable, not ALL callbacks
        // Using removeCallbacksAndMessages(null) is too aggressive and can
        // interfere with other pending operations
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null

        // Clear metadata, bitrate, and codec when stopping stream
        // This prevents stale metadata from appearing when switching stations
        // or when the UI is recreated (e.g., Material You toggle)
        currentMetadata = null
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
     * Broadcast metadata change to UI
     */
    private fun broadcastMetadataChanged(metadata: String) {
        val intent = Intent(BROADCAST_METADATA_CHANGED).apply {
            putExtra(EXTRA_METADATA, metadata)
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
        updateMediaMetadata(currentStationName, coverArtUri)
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
    private fun broadcastStreamError(errorType: String) {
        val intent = Intent(BROADCAST_STREAM_ERROR).apply {
            putExtra(EXTRA_STREAM_ERROR_TYPE, errorType)
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

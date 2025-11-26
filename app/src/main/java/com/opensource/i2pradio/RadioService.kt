package com.opensource.i2pradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import com.opensource.i2pradio.util.SecureImageLoader
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
import java.net.InetSocketAddress
import java.net.Proxy
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
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    // Service-scoped coroutine scope that gets cancelled in onDestroy
    // Prevents coroutine leaks when service is destroyed
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Store OkHttp client for proper cleanup to prevent memory leaks
    // @Volatile ensures visibility across threads when rapidly switching streams
    @Volatile private var currentOkHttpClient: OkHttpClient? = null
    private val okHttpClientLock = Any()

    // Flag to track when we're switching between streams
    // Prevents old player's IDLE state from clearing buffering animation during stream switch
    // AtomicBoolean ensures thread-safe access from player callbacks and UI thread
    private val isStartingNewStream = AtomicBoolean(false)

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    @Volatile private var hasAudioFocus = false

    // Audio focus change listener to handle interruptions gracefully
    // This prevents static/scratches when other apps briefly request audio focus
    // Note: ExoPlayer now handles audio focus automatically, but this listener
    // provides additional logging and ensures proper cleanup on permanent loss
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Regained focus - ExoPlayer handles this automatically
                hasAudioFocus = true
                android.util.Log.d("RadioService", "Audio focus gained - ExoPlayer handling playback")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss (e.g., Spotify, YouTube starts) - stop playback entirely
                // ExoPlayer pauses automatically, but we should clean up properly
                hasAudioFocus = false
                android.util.Log.d("RadioService", "Audio focus lost permanently - stopping playback")
                // Post to handler to avoid blocking the audio focus callback
                handler.post {
                    stopStream()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss (e.g., phone call, notification) - ExoPlayer pauses automatically
                hasAudioFocus = false
                android.util.Log.d("RadioService", "Audio focus lost transiently - ExoPlayer handling pause")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Can duck - ExoPlayer handles volume reduction automatically
                android.util.Log.d("RadioService", "Audio focus ducking - ExoPlayer handling volume")
            }
        }
    }

    // MediaSession for Now Playing card on TV
    private var mediaSession: MediaSessionCompat? = null
    private var currentCoverArtUri: String? = null
    private val sessionDeactivateHandler = Handler(Looper.getMainLooper())
    private var sessionDeactivateRunnable: Runnable? = null
    private val SESSION_DEACTIVATE_DELAY = 5 * 60 * 1000L // 5 minutes

    // Recording - uses separate network request to avoid affecting audio pipeline
    private var isRecording = false
    private var currentStationName: String = "Unknown Station"
    private var recordingCall: Call? = null
    private var recordingThread: Thread? = null
    private var recordingOutputStream: OutputStream? = null
    private val isRecordingActive = AtomicBoolean(false)
    private var recordingFile: File? = null
    private var recordingMediaStoreUri: Uri? = null  // For Android 10+ MediaStore recordings

    // Stream switching for "Record All Stations" feature
    @Volatile private var pendingRecordingStreamUrl: String? = null
    @Volatile private var pendingRecordingProxyHost: String? = null
    @Volatile private var pendingRecordingProxyPort: Int = 0
    @Volatile private var pendingRecordingProxyType: ProxyType = ProxyType.NONE
    private val switchStreamRequested = AtomicBoolean(false)

    // Sleep timer
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerEndTime: Long = 0L

    // Reconnect runnable tracking
    private var reconnectRunnable: Runnable? = null

    // Stream metadata
    private var currentMetadata: String? = null
    private var currentBitrate: Int = 0
    private var currentCodec: String? = null

    // Playback time tracking
    private var playbackStartTimeMillis: Long = 0L
    private var playbackTimeUpdateRunnable: Runnable? = null
    private val playbackTimeUpdateInterval = 1000L // Update every second

    // Built-in equalizer manager
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

        // Broadcast actions for metadata updates
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

        // Cover art update broadcast
        const val BROADCAST_COVER_ART_CHANGED = "com.opensource.i2pradio.COVER_ART_CHANGED"
        const val EXTRA_COVER_ART_URI = "cover_art_uri"
        const val EXTRA_STATION_ID = "station_id"

        // Playback time tracking
        const val BROADCAST_PLAYBACK_TIME_UPDATE = "com.opensource.i2pradio.PLAYBACK_TIME_UPDATE"
        const val EXTRA_PLAYBACK_ELAPSED_MS = "playback_elapsed_ms"
        const val EXTRA_BUFFERED_POSITION_MS = "buffered_position_ms"
        const val EXTRA_CURRENT_POSITION_MS = "current_position_ms"
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
    }

    private fun initializeMediaSession() {
        // Create MediaSession for Now Playing card on TV
        mediaSession = MediaSessionCompat(this, "DeutsiaRadioSession").apply {
            // Set session activity - opens app when Now Playing card is selected
            val sessionActivityIntent = Intent(this@RadioService, MainActivity::class.java)
            val sessionActivityPendingIntent = PendingIntent.getActivity(
                this@RadioService,
                99,
                sessionActivityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            setSessionActivity(sessionActivityPendingIntent)

            // Set flags for transport controls (needed for backwards compatibility)
            @Suppress("DEPRECATION")
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)

            // Set callback for transport controls
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

    /**
     * Update the playback state in MediaSession
     */
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

    /**
     * Update media metadata for the Now Playing card
     */
    private fun updateMediaMetadata(stationName: String, coverArtUri: String?) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stationName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stationName)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "deutsia radio")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "deutsia radio")

        // Load cover art if available
        if (!coverArtUri.isNullOrEmpty()) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    // Use SecureImageLoader to route remote URLs through Tor when Force Tor is enabled
                    // Local content URIs (file://, content://) bypass the proxy automatically
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

                // Set metadata without cover art if loading failed
                handler.post {
                    mediaSession?.setMetadata(metadataBuilder.build())
                }
            }
        } else {
            mediaSession?.setMetadata(metadataBuilder.build())
        }
    }

    /**
     * Activate the media session
     */
    private fun activateMediaSession() {
        cancelSessionDeactivation()
        mediaSession?.isActive = true
        android.util.Log.d("RadioService", "MediaSession activated")
    }

    /**
     * Deactivate the media session
     */
    private fun deactivateMediaSession() {
        mediaSession?.isActive = false
        android.util.Log.d("RadioService", "MediaSession deactivated")
    }

    /**
     * Schedule session deactivation after a delay (for pause state)
     */
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

    /**
     * Cancel any pending session deactivation
     */
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

                currentStreamUrl = streamUrl
                currentProxyHost = proxyHost
                currentProxyPort = proxyPort
                currentProxyType = proxyType
                currentStationName = stationName
                currentCoverArtUri = coverArtUri
                reconnectAttempts = 0

                // CRITICAL: Start foreground immediately to comply with Android 8+ requirements
                // This must be called within 5 seconds of startForegroundService()
                startForeground(NOTIFICATION_ID, createNotification("Connecting..."))

                // Broadcast buffering state to UI immediately
                broadcastPlaybackStateChanged(isBuffering = true, isPlaying = false)

                // Activate media session and set metadata
                activateMediaSession()
                updateMediaMetadata(stationName, coverArtUri)
                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)

                playStream(streamUrl, proxyHost, proxyPort, proxyType)
            }
            ACTION_PAUSE -> {
                player?.pause()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                scheduleSessionDeactivation()
                startForeground(NOTIFICATION_ID, createNotification("Paused"))
            }
            ACTION_STOP -> {
                stopRecording()
                currentStreamUrl = null
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
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
                // Switch recording to a new stream URL (for "Record All Stations" feature)
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

    /**
     * Start recording using a COMPLETELY SEPARATE network request.
     * This is critical: the recording stream uses its own OkHttpClient instance
     * that is completely independent from the playback stream.
     * This ensures recording cannot cause any audio glitches or affect playback.
     */
    private fun startRecording(stationName: String) {
        if (isRecording) {
            android.util.Log.w("RadioService", "Recording already in progress")
            return
        }

        val streamUrl = currentStreamUrl ?: run {
            android.util.Log.e("RadioService", "Cannot start recording: no stream URL")
            broadcastRecordingError("No stream playing")
            return
        }

        currentStationName = stationName
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val format = detectStreamFormat(streamUrl)
        val sanitizedName = stationName.replace(Regex("[^a-zA-Z0-9\\s]"), "").replace(Regex("\\s+"), "_")
        val fileName = "${sanitizedName}_$timestamp.$format"

        android.util.Log.d("RadioService", "Starting recording for: $stationName, URL: $streamUrl")

        // Determine MIME type for the format
        val mimeType = when (format) {
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            else -> "audio/mpeg"
        }

        // Check for custom recording directory first
        val customDirUri = PreferencesHelper.getRecordingDirectoryUri(this)
        val useCustomDir = customDirUri != null

        // For Android 10+ (API 29+), use MediaStore to save to public Music directory
        // For older versions, use legacy file access
        // Custom directory overrides both
        val useMediaStore = !useCustomDir && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var recordingsDir: File? = null

        if (!useCustomDir && !useMediaStore) {
            // Legacy: Use public Music directory for Android 9 and below
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            recordingsDir = File(musicDir, "deutsia radio")
            if (!recordingsDir.exists()) {
                val created = recordingsDir.mkdirs()
                android.util.Log.d("RadioService", "Created recordings directory: $created, path: ${recordingsDir.absolutePath}")
                if (!created && !recordingsDir.exists()) {
                    android.util.Log.e("RadioService", "Failed to create recordings directory")
                    broadcastRecordingError("Cannot create directory")
                    return
                }
            }
        }

        android.util.Log.d("RadioService", "Recording will use custom dir: $useCustomDir, MediaStore: $useMediaStore, format: $format, mimeType: $mimeType")

        // Build a COMPLETELY SEPARATE OkHttp client for recording
        // This is a NEW instance, independent from the playback client
        val recordingClient = buildRecordingHttpClient()

        // Create request for the stream - using separate connection
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "DeutsiaRadio-Recorder/1.0") // Different user agent to distinguish
            .header("Icy-MetaData", "0") // Don't need metadata for recording
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        // Set flags before starting
        isRecordingActive.set(true)
        isRecording = true

        // Create and store the call
        val call = recordingClient.newCall(request)
        recordingCall = call

        // Capture values for thread
        val finalFileName = fileName
        val finalRecordingsDir = recordingsDir
        val finalStreamUrl = streamUrl
        val finalMimeType = mimeType
        val finalUseMediaStore = useMediaStore
        val finalUseCustomDir = useCustomDir
        val finalCustomDirUri = customDirUri
        val finalFormat = format

        // Start recording on a dedicated background thread
        recordingThread = Thread({
            var response: Response? = null
            var outputStream: BufferedOutputStream? = null
            var totalBytesWritten = 0L
            var lastFlushBytes = 0L
            var lastLogTime = System.currentTimeMillis()
            val flushInterval = 64 * 1024L // Flush every 64KB for data safety
            val logInterval = 10_000L // Log every 10 seconds
            var file: File? = null
            var mediaStoreUri: Uri? = null
            var filePath: String? = null
            var connectionRetries = 0
            val maxConnectionRetries = 3

            try {
                android.util.Log.d("RadioService", "Recording thread started, connecting to: $finalStreamUrl")

                // Retry loop for connection
                while (connectionRetries < maxConnectionRetries && isRecordingActive.get()) {
                    try {
                        // Execute the request on this thread (blocking)
                        response = call.execute()
                        break // Success, exit retry loop
                    } catch (e: java.net.SocketTimeoutException) {
                        connectionRetries++
                        if (connectionRetries < maxConnectionRetries && isRecordingActive.get()) {
                            android.util.Log.w("RadioService", "Recording connection timeout, retry $connectionRetries/$maxConnectionRetries")
                            Thread.sleep(1000L * connectionRetries) // Exponential backoff
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
                        broadcastRecordingError("Connection failed")
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
                        broadcastRecordingError("Empty response")
                        cleanupRecording()
                    }
                    return@Thread
                }

                // NOW create the file - only after we have a successful connection
                if (finalUseCustomDir && finalCustomDirUri != null) {
                    // Custom directory: Use Storage Access Framework
                    try {
                        val treeUri = Uri.parse(finalCustomDirUri)
                        val docFile = DocumentFile.fromTreeUri(this@RadioService, treeUri)
                        if (docFile == null || !docFile.canWrite()) {
                            android.util.Log.e("RadioService", "Cannot write to custom directory")
                            handler.post {
                                broadcastRecordingError("Cannot write to selected directory")
                                cleanupRecording()
                            }
                            return@Thread
                        }

                        val newFile = docFile.createFile(finalMimeType, finalFileName.substringBeforeLast("."))
                        if (newFile == null) {
                            android.util.Log.e("RadioService", "Failed to create file in custom directory")
                            handler.post {
                                broadcastRecordingError("Cannot create recording file")
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
                                broadcastRecordingError("Cannot open file for writing")
                                cleanupRecording()
                            }
                            return@Thread
                        }

                        outputStream = BufferedOutputStream(rawOutputStream, 64 * 1024)
                    } catch (e: Exception) {
                        android.util.Log.e("RadioService", "Custom directory error: ${e.message}", e)
                        handler.post {
                            broadcastRecordingError("Directory access error: ${e.message}")
                            cleanupRecording()
                        }
                        return@Thread
                    }
                } else if (finalUseMediaStore) {
                    // Android 10+: Use MediaStore to save to public Music/deutsia radio directory
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, finalFileName)
                        put(MediaStore.Audio.Media.MIME_TYPE, finalMimeType)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/deutsia radio")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)  // Mark as pending while writing
                    }

                    val resolver = contentResolver
                    mediaStoreUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)

                    if (mediaStoreUri == null) {
                        android.util.Log.e("RadioService", "Failed to create MediaStore entry")
                        handler.post {
                            broadcastRecordingError("Cannot create recording file")
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
                            broadcastRecordingError("Cannot open file for writing")
                            cleanupRecording()
                        }
                        return@Thread
                    }

                    outputStream = BufferedOutputStream(rawOutputStream, 64 * 1024)
                } else {
                    // Legacy: Direct file access for Android 9 and below
                    file = File(finalRecordingsDir!!, finalFileName)
                    recordingFile = file
                    filePath = file!!.absolutePath
                    android.util.Log.d("RadioService", "Recording stream connected, writing to: $filePath")

                    outputStream = BufferedOutputStream(
                        FileOutputStream(file),
                        64 * 1024 // 64KB write buffer
                    )
                }

                recordingOutputStream = outputStream

                // Update notification now that we're actually recording
                handler.post { updateNotificationWithRecording() }

                var currentInputStream = responseBody.byteStream()
                var currentResponse: Response? = response
                val buffer = ByteArray(8192) // 8KB read buffer - smaller for more responsive stopping

                // Outer loop for stream switching - continues until stopped
                outerLoop@ while (isRecordingActive.get() && !Thread.currentThread().isInterrupted) {
                    // Inner read loop - reads from current stream
                    while (isRecordingActive.get() && !Thread.currentThread().isInterrupted) {
                        val bytesRead = try {
                            currentInputStream.read(buffer)
                        } catch (e: java.io.IOException) {
                            // Check if this is a stream switch request
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
                            // Stream ended or error - check if we should switch streams
                            if (switchStreamRequested.compareAndSet(true, false)) {
                                val newStreamUrl = pendingRecordingStreamUrl
                                if (newStreamUrl != null) {
                                    android.util.Log.d("RadioService", "Switching recording to new stream: $newStreamUrl")

                                    // Flush before switching
                                    try {
                                        outputStream.flush()
                                    } catch (e: Exception) {
                                        android.util.Log.w("RadioService", "Error flushing before stream switch: ${e.message}")
                                    }

                                    // Close old response
                                    try {
                                        currentResponse?.close()
                                    } catch (e: Exception) {
                                        android.util.Log.w("RadioService", "Error closing old response: ${e.message}")
                                    }

                                    // Build new client and request
                                    val newRecordingClient = buildRecordingHttpClient()
                                    val newRequest = Request.Builder()
                                        .url(newStreamUrl)
                                        .header("User-Agent", "DeutsiaRadio-Recorder/1.0")
                                        .header("Icy-MetaData", "0")
                                        .header("Accept", "*/*")
                                        .header("Connection", "keep-alive")
                                        .build()

                                    // Connect to new stream
                                    try {
                                        val newCall = newRecordingClient.newCall(newRequest)
                                        recordingCall = newCall
                                        val newResponse = newCall.execute()

                                        if (newResponse.isSuccessful && newResponse.body != null) {
                                            currentResponse = newResponse
                                            currentInputStream = newResponse.body!!.byteStream()
                                            android.util.Log.d("RadioService", "Recording switched to new stream successfully")
                                            pendingRecordingStreamUrl = null

                                            // Continue with the outer loop (new stream)
                                            continue@outerLoop
                                        } else {
                                            android.util.Log.e("RadioService", "Failed to connect to new stream: ${newResponse.code}")
                                            newResponse.close()
                                            // Continue with same stream or exit
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("RadioService", "Error switching to new stream: ${e.message}")
                                    }

                                    // Clear pending on failure
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

                                // Periodic flush for data safety
                                if (totalBytesWritten - lastFlushBytes >= flushInterval) {
                                    outputStream.flush()
                                    lastFlushBytes = totalBytesWritten
                                }

                                // Periodic logging (not too frequent)
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

                // Final flush before closing
                try {
                    outputStream.flush()
                } catch (e: Exception) {
                    android.util.Log.w("RadioService", "Error during final flush: ${e.message}")
                }

                // Close the current response
                try {
                    currentResponse?.close()
                } catch (e: Exception) {
                    android.util.Log.w("RadioService", "Error closing final response: ${e.message}")
                }

                android.util.Log.d("RadioService", "Recording loop ended, total: ${totalBytesWritten / 1024}KB")

            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.e("RadioService", "Recording connection timed out", e)
                handler.post {
                    broadcastRecordingError("Connection timed out")
                    cleanupRecording()
                }
            } catch (e: java.net.ConnectException) {
                android.util.Log.e("RadioService", "Recording connection refused", e)
                handler.post {
                    broadcastRecordingError("Connection refused")
                    cleanupRecording()
                }
            } catch (e: java.io.IOException) {
                if (isRecordingActive.get()) {
                    android.util.Log.e("RadioService", "Recording I/O error: ${e.message}", e)
                    handler.post { broadcastRecordingError("I/O error: ${e.message}") }
                } else {
                    android.util.Log.d("RadioService", "Recording stopped normally (${totalBytesWritten / 1024}KB saved)")
                }
            } catch (e: InterruptedException) {
                android.util.Log.d("RadioService", "Recording thread interrupted")
            } catch (e: Exception) {
                android.util.Log.e("RadioService", "Recording error: ${e.javaClass.simpleName}: ${e.message}", e)
                handler.post { broadcastRecordingError("Error: ${e.message}") }
            } finally {
                // Close streams in reverse order
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

                // Handle recording completion based on storage method
                if (mediaStoreUri != null) {
                    // Android 10+: Finalize or delete MediaStore entry
                    val resolver = contentResolver
                    val sizeKB = totalBytesWritten / 1024
                    if (sizeKB > 0) {
                        // Finalize the recording by clearing IS_PENDING flag
                        val updateValues = ContentValues().apply {
                            put(MediaStore.Audio.Media.IS_PENDING, 0)
                        }
                        resolver.update(mediaStoreUri, updateValues, null, null)
                        android.util.Log.d("RadioService", "Recording saved to MediaStore: $filePath (${sizeKB}KB)")
                        val savedPath = filePath ?: "Music/i2pradio/unknown"
                        handler.post { broadcastRecordingComplete(savedPath, totalBytesWritten) }
                    } else {
                        // Delete empty recording
                        android.util.Log.w("RadioService", "Recording is empty, deleting MediaStore entry: $filePath")
                        resolver.delete(mediaStoreUri, null, null)
                    }
                    recordingMediaStoreUri = null
                } else if (file != null) {
                    // Legacy: Handle file directly
                    file?.let { f ->
                        if (f.exists()) {
                            val sizeKB = f.length() / 1024
                            if (sizeKB > 0) {
                                android.util.Log.d("RadioService", "Recording saved: ${f.absolutePath} (${sizeKB}KB)")
                                // Broadcast success
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
                    // Custom directory: File was created via SAF, just broadcast completion
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
            priority = Thread.MIN_PRIORITY // Low priority to not affect audio playback
            isDaemon = false // Ensure thread completes even if app is closing
        }

        recordingThread?.start()
        // Don't update notification until we have a successful connection (done in thread)
        android.util.Log.d("RadioService", "Recording thread started for: $stationName")
    }

    /**
     * Broadcast a recording error to the UI
     */
    private fun broadcastRecordingError(message: String) {
        android.util.Log.e("RadioService", "Recording error: $message")
        val intent = Intent(BROADCAST_RECORDING_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Broadcast recording complete to the UI
     */
    private fun broadcastRecordingComplete(filePath: String, fileSize: Long) {
        android.util.Log.d("RadioService", "Recording complete: $filePath (${fileSize / 1024}KB)")
        val intent = Intent(BROADCAST_RECORDING_COMPLETE).apply {
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_FILE_SIZE, fileSize)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Build a COMPLETELY SEPARATE OkHttp client for recording.
     * This client has its own connection pool, dispatcher, and settings
     * to ensure it cannot interfere with the playback OkHttp client.
     *
     * BULLETPROOF: This method also enforces Force Tor settings for recording traffic.
     */
    private fun buildRecordingHttpClient(): OkHttpClient {
        // Check Force Tor settings - bulletproof mode for recording too
        val forceTorAll = PreferencesHelper.isForceTorAll(this)
        val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(this)
        val isI2PStream = currentProxyType == ProxyType.I2P || currentStreamUrl?.contains(".i2p") == true

        // RECORDING NETWORK ROUTING LOG
        android.util.Log.d("RadioService", "===== RECORDING CONNECTION REQUEST =====")
        android.util.Log.d("RadioService", "Recording URL: $currentStreamUrl")
        android.util.Log.d("RadioService", "Force Tor All: $forceTorAll")
        android.util.Log.d("RadioService", "Force Tor Except I2P: $forceTorExceptI2P")
        android.util.Log.d("RadioService", "Is I2P stream: $isI2PStream")
        android.util.Log.d("RadioService", "Tor connected: ${TorManager.isConnected()}")

        val (effectiveProxyHost, effectiveProxyPort, effectiveProxyType) = when {
            // FORCE TOR ALL: Everything goes through Tor, no exceptions
            forceTorAll && TorManager.isConnected() -> {
                android.util.Log.d("RadioService", "FORCE TOR ALL (recording): Routing through Tor")
                Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
            }
            // FORCE TOR EXCEPT I2P: I2P streams use I2P proxy, everything else through Tor
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
            // Standard proxy logic when Force Tor is disabled
            currentProxyType == ProxyType.TOR &&
            PreferencesHelper.isEmbeddedTorEnabled(this) &&
            TorManager.isConnected() -> {
                Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
            }
            currentProxyHost?.isNotEmpty() == true && currentProxyType != ProxyType.NONE -> {
                Triple(currentProxyHost!!, currentProxyPort, currentProxyType)
            }
            else -> Triple("", 0, ProxyType.NONE)
        }

        // LOG RECORDING ROUTING DECISION
        android.util.Log.d("RadioService", "===== RECORDING ROUTING DECISION =====")
        android.util.Log.d("RadioService", "Recording proxy: $effectiveProxyHost:$effectiveProxyPort (${effectiveProxyType.name})")
        when (effectiveProxyType) {
            ProxyType.TOR -> android.util.Log.d("RadioService", "RECORDING ROUTING: Through TOR SOCKS proxy")
            ProxyType.I2P -> android.util.Log.d("RadioService", "RECORDING ROUTING: Through I2P HTTP proxy")
            ProxyType.NONE -> android.util.Log.w("RadioService", "RECORDING ROUTING: DIRECT - No proxy!")
        }
        android.util.Log.d("RadioService", "======================================")

        // Create a completely new connection pool for recording
        // This ensures recording uses separate TCP connections from playback
        val recordingConnectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 1,
            keepAliveDuration = 30,
            timeUnit = TimeUnit.SECONDS
        )

        // Create a separate dispatcher for recording
        val recordingDispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 1
            maxRequestsPerHost = 1
        }

        val builder = OkHttpClient.Builder()
            .connectionPool(recordingConnectionPool)
            .dispatcher(recordingDispatcher)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // No read timeout for streaming
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Apply proxy if needed
        if (effectiveProxyHost.isNotEmpty() && effectiveProxyType != ProxyType.NONE) {
            val javaProxyType = when (effectiveProxyType) {
                ProxyType.TOR -> Proxy.Type.SOCKS
                ProxyType.I2P -> Proxy.Type.HTTP
                ProxyType.NONE -> Proxy.Type.DIRECT
            }
            builder.proxy(Proxy(javaProxyType, InetSocketAddress(effectiveProxyHost, effectiveProxyPort)))
            android.util.Log.d("RadioService", "Recording client using ${effectiveProxyType.name} proxy: $effectiveProxyHost:$effectiveProxyPort")
        } else {
            android.util.Log.d("RadioService", "Recording client using direct connection")
        }

        return builder.build()
    }

    /**
     * Detect audio format from stream URL for proper file extension.
     */
    private fun detectStreamFormat(streamUrl: String): String {
        val urlLower = streamUrl.lowercase()
        return when {
            urlLower.contains(".ogg") || urlLower.contains("/ogg") || urlLower.contains("vorbis") -> "ogg"
            urlLower.contains(".opus") || urlLower.contains("/opus") -> "opus"
            urlLower.contains(".aac") || urlLower.contains("/aac") -> "aac"
            urlLower.contains(".flac") || urlLower.contains("/flac") -> "flac"
            urlLower.contains(".m4a") -> "m4a"
            else -> "mp3" // Default to mp3 as most common
        }
    }

    /**
     * Switch the recording stream to a new URL without stopping the recording.
     * This allows recording content from multiple stations into the same file.
     */
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

        // Update current station info for proxy routing
        currentStreamUrl = newStreamUrl
        currentProxyHost = newProxyHost
        currentProxyPort = newProxyPort
        currentProxyType = newProxyType
        currentStationName = newStationName

        // Set pending stream info
        pendingRecordingStreamUrl = newStreamUrl
        pendingRecordingProxyHost = newProxyHost
        pendingRecordingProxyPort = newProxyPort
        pendingRecordingProxyType = newProxyType

        // Signal the recording thread to switch
        switchStreamRequested.set(true)

        // Cancel the current recording call to unblock the read
        recordingCall?.cancel()
    }

    /**
     * Stop recording and close the separate network connection.
     * This method is safe to call from any thread.
     */
    private fun stopRecording() {
        if (!isRecording) {
            android.util.Log.d("RadioService", "stopRecording called but not recording")
            return
        }

        android.util.Log.d("RadioService", "Stopping recording...")

        // Capture references before cleanup
        val currentCall = recordingCall
        val currentThread = recordingThread
        val currentFile = recordingFile

        // Signal the recording thread to stop FIRST
        // This allows the thread to finish its current write and flush
        isRecordingActive.set(false)

        // Mark as not recording immediately to prevent double-stop
        isRecording = false

        // Run cleanup in background to avoid blocking UI
        Thread({
            try {
                // Give the thread a moment to notice the flag
                Thread.sleep(200)

                // Cancel the network call (this causes the read to fail and exit)
                try {
                    currentCall?.cancel()
                } catch (e: Exception) {
                    android.util.Log.w("RadioService", "Error canceling recording call: ${e.message}")
                }

                // Wait for the thread to finish (up to 5 seconds for proper cleanup)
                currentThread?.let { thread ->
                    try {
                        thread.join(5000) // Wait up to 5 seconds
                        if (thread.isAlive) {
                            android.util.Log.w("RadioService", "Recording thread still alive after 5s, interrupting")
                            thread.interrupt()
                            thread.join(1000) // Wait 1 more second after interrupt
                        }
                    } catch (e: InterruptedException) {
                        android.util.Log.w("RadioService", "Interrupted while waiting for recording thread")
                        Thread.currentThread().interrupt()
                    }
                }

                // Log the recorded file info
                currentFile?.let { file ->
                    if (file.exists()) {
                        val sizeKB = file.length() / 1024
                        if (sizeKB > 0) {
                            android.util.Log.d("RadioService", "Recording complete: ${file.absolutePath} (${sizeKB}KB)")
                        } else {
                            android.util.Log.w("RadioService", "Recording file is empty: ${file.absolutePath}")
                            // Delete empty files
                            file.delete()
                        }
                    } else {
                        android.util.Log.e("RadioService", "Recording file not found: ${file.absolutePath}")
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("RadioService", "Error during recording cleanup: ${e.message}", e)
            } finally {
                // Final cleanup on main thread for notification update
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

    /**
     * Clean up recording resources.
     */
    private fun cleanupRecording() {
        try {
            recordingOutputStream?.close()
        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Error closing output stream", e)
        }

        // Clean up any pending MediaStore entry (for aborted recordings on Android 10+)
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
            startForeground(NOTIFICATION_ID, createNotification("Playing • Recording"))
        }
    }

    private fun playStream(streamUrl: String, proxyHost: String, proxyPort: Int, proxyType: ProxyType = ProxyType.NONE) {
        try {
            // Check Force Tor settings FIRST - bulletproof mode
            val forceTorAll = PreferencesHelper.isForceTorAll(this)
            val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(this)
            val isI2PStream = proxyType == ProxyType.I2P || streamUrl.contains(".i2p")

            // NETWORK ROUTING LOG - For debugging Tor/leak detection
            android.util.Log.d("RadioService", "===== STREAM CONNECTION REQUEST =====")
            android.util.Log.d("RadioService", "Stream URL: $streamUrl")
            android.util.Log.d("RadioService", "Requested proxy: $proxyHost:$proxyPort (${proxyType.name})")
            android.util.Log.d("RadioService", "Force Tor All: $forceTorAll")
            android.util.Log.d("RadioService", "Force Tor Except I2P: $forceTorExceptI2P")
            android.util.Log.d("RadioService", "Is I2P stream: $isI2PStream")
            android.util.Log.d("RadioService", "Tor connected: ${TorManager.isConnected()}")
            android.util.Log.d("RadioService", "Tor SOCKS: ${TorManager.getProxyHost()}:${TorManager.getProxyPort()}")

            // BULLETPROOF: If Force Tor All is enabled, Tor MUST be connected or we fail
            if (forceTorAll && !TorManager.isConnected()) {
                android.util.Log.e("RadioService", "FORCE TOR ALL: Tor not connected - BLOCKING stream to prevent leak")
                isStartingNewStream = false  // Reset flag on early return
                broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                startForeground(NOTIFICATION_ID, createNotification("Tor not connected - stream blocked"))
                return
            }

            // BULLETPROOF: If Force Tor Except I2P is enabled and this is NOT an I2P stream, Tor MUST be connected
            if (forceTorExceptI2P && !isI2PStream && !TorManager.isConnected()) {
                android.util.Log.e("RadioService", "FORCE TOR (except I2P): Tor not connected - BLOCKING non-I2P stream")
                isStartingNewStream = false  // Reset flag on early return
                broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                startForeground(NOTIFICATION_ID, createNotification("Tor not connected - stream blocked"))
                return
            }

            // Request audio focus with proper listener to handle interruptions
            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use AudioFocusRequest for API 26+
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
                // Legacy audio focus request for older APIs
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                android.util.Log.w("RadioService", "Failed to gain audio focus")
                isStartingNewStream.set(false)  // Reset flag on early return
                // Broadcast failure to UI so it can update the play button state
                broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
                startForeground(NOTIFICATION_ID, createNotification("Audio focus denied"))
                return
            }
            hasAudioFocus = true

            // Set flag to prevent old player's IDLE state from clearing buffering animation
            isStartingNewStream.set(true)

            stopStream()

            // Determine proxy configuration with Force Tor enforcement
            // BULLETPROOF: Force Tor settings override normal proxy logic
            val (effectiveProxyHost, effectiveProxyPort, effectiveProxyType) = when {
                // FORCE TOR ALL: Everything goes through Tor, no exceptions
                forceTorAll && TorManager.isConnected() -> {
                    android.util.Log.d("RadioService", "FORCE TOR ALL: Routing ALL traffic through Tor")
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                // FORCE TOR EXCEPT I2P: I2P streams use I2P proxy, everything else through Tor
                forceTorExceptI2P && isI2PStream -> {
                    // I2P stream - use I2P proxy if provided, otherwise use default I2P settings
                    if (proxyHost.isNotEmpty() && proxyType == ProxyType.I2P) {
                        android.util.Log.d("RadioService", "FORCE TOR (except I2P): Using I2P proxy for .i2p stream")
                        Triple(proxyHost, proxyPort, ProxyType.I2P)
                    } else {
                        // Default I2P proxy settings
                        android.util.Log.d("RadioService", "FORCE TOR (except I2P): Using default I2P proxy (127.0.0.1:4444)")
                        Triple("127.0.0.1", 4444, ProxyType.I2P)
                    }
                }
                forceTorExceptI2P && !isI2PStream && TorManager.isConnected() -> {
                    android.util.Log.d("RadioService", "FORCE TOR (except I2P): Routing non-I2P stream through Tor")
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                // Use embedded Tor for Tor streams if enabled and connected
                proxyType == ProxyType.TOR &&
                PreferencesHelper.isEmbeddedTorEnabled(this) &&
                TorManager.isConnected() -> {
                    android.util.Log.d("RadioService", "Using embedded Tor proxy for .onion stream")
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                // Use manual proxy configuration
                proxyHost.isNotEmpty() && proxyType != ProxyType.NONE -> {
                    Triple(proxyHost, proxyPort, proxyType)
                }
                // Direct connection (only if Force Tor modes are disabled)
                else -> Triple("", 0, ProxyType.NONE)
            }

            // LOG FINAL ROUTING DECISION - Critical for leak detection
            android.util.Log.d("RadioService", "===== FINAL ROUTING DECISION =====")
            android.util.Log.d("RadioService", "Effective proxy: $effectiveProxyHost:$effectiveProxyPort (${effectiveProxyType.name})")
            when (effectiveProxyType) {
                ProxyType.TOR -> android.util.Log.d("RadioService", "ROUTING: Traffic will go through TOR SOCKS proxy")
                ProxyType.I2P -> android.util.Log.d("RadioService", "ROUTING: Traffic will go through I2P HTTP proxy")
                ProxyType.NONE -> android.util.Log.w("RadioService", "ROUTING: DIRECT CONNECTION - No proxy! (potential leak if unintended)")
            }
            android.util.Log.d("RadioService", "==================================")

            // Simple, clean OkHttp client - let ExoPlayer handle buffering
            // Store the client for proper cleanup to prevent memory leaks
            // Synchronized to prevent race conditions when rapidly switching streams
            val okHttpClient = synchronized(okHttpClientLock) {
                currentOkHttpClient = if (effectiveProxyHost.isNotEmpty() && effectiveProxyType != ProxyType.NONE) {
                    val javaProxyType = when (effectiveProxyType) {
                        ProxyType.TOR -> Proxy.Type.SOCKS
                        ProxyType.I2P -> Proxy.Type.HTTP
                        ProxyType.NONE -> Proxy.Type.DIRECT
                    }
                    android.util.Log.d("RadioService", "Using ${effectiveProxyType.name} proxy: $effectiveProxyHost:$effectiveProxyPort")
                    OkHttpClient.Builder()
                        .proxy(Proxy(javaProxyType, InetSocketAddress(effectiveProxyHost, effectiveProxyPort)))
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build()
                } else {
                    OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                }
                currentOkHttpClient!!
            }

            // Direct data source - no wrapper, no middleware
            // This is the key simplification: let the stream go directly to ExoPlayer
            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("DeutsiaRadio/1.0")

            // Simple load control - trust ExoPlayer's defaults with minor tuning
            // Key insight: smaller buffers = less latency = fewer sync issues
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    5_000,   // Min buffer (5s) - enough for network jitter
                    15_000,  // Max buffer (15s) - don't over-buffer live streams
                    1_000,   // Buffer for playback to start (1s) - fast start
                    2_000    // Buffer for playback after rebuffer (2s) - quick recovery
                )
                .build()

            // Simple audio attributes
            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            // Use ExoPlayer's default renderers and audio sink - they're highly optimized
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

                // Reset flag now that new player is set up
                // The new player's state changes will handle buffering broadcasts from now on
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
                                val status = if (isRecording) "Playing • Recording" else "Playing"
                                startForeground(NOTIFICATION_ID, createNotification(status))
                                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                activateMediaSession()
                                // Initialize built-in equalizer with audio session
                                player?.audioSessionId?.let { sessionId ->
                                    if (sessionId != 0) {
                                        // Broadcast for external equalizer apps (compatibility)
                                        broadcastAudioSessionOpen(sessionId)
                                        // Initialize built-in equalizer
                                        equalizerManager?.initialize(sessionId)
                                    }
                                }
                                // Extract stream info when ready
                                extractStreamInfo()
                                // Start playback time updates for buffer bar
                                startPlaybackTimeUpdates()
                                // Broadcast to UI that we're no longer buffering
                                broadcastPlaybackStateChanged(isBuffering = false, isPlaying = true)
                                android.util.Log.d("RadioService", "Stream playing successfully")
                            }
                            Player.STATE_BUFFERING -> {
                                startForeground(NOTIFICATION_ID, createNotification("Buffering..."))
                                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                                // Broadcast to UI that we're buffering
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
                                // Don't clear buffering state if we're switching to a new stream
                                // (the old player going IDLE shouldn't affect new stream's loading animation)
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
                            // Resume playback time updates if we have a start time
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
                            // Player is paused but ready - stop time updates but keep start time
                            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
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
            startForeground(NOTIFICATION_ID, createNotification("Connection failed"))
            return
        }

        reconnectAttempts++
        val delay = minOf(1000L * reconnectAttempts, 5000L)

        android.util.Log.d("RadioService", "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        // Keep buffering state while reconnecting
        broadcastPlaybackStateChanged(isBuffering = true, isPlaying = false)
        startForeground(NOTIFICATION_ID, createNotification("Reconnecting..."))

        // Cancel any pending reconnect before scheduling a new one
        reconnectRunnable?.let { handler.removeCallbacks(it) }

        reconnectRunnable = Runnable {
            currentStreamUrl?.let { url ->
                playStream(url, currentProxyHost ?: "", currentProxyPort, currentProxyType)
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
                "deutsia radio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows radio playback status"
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
            .addAction(R.drawable.ic_pause, "Pause", playPausePendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
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

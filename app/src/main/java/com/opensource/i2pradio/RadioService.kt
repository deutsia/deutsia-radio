package com.opensource.i2pradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.metadata.icy.IcyInfo
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RadioService : Service() {
    private var player: ExoPlayer? = null
    private val binder = RadioBinder()
    private var currentStreamUrl: String? = null
    private var currentProxyHost: String? = null
    private var currentProxyPort: Int = 4444
    private var currentProxyType: ProxyType = ProxyType.NONE
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    private lateinit var audioManager: AudioManager

    // MediaSession for Now Playing card on TV
    private var mediaSession: MediaSessionCompat? = null
    private var currentCoverArtUri: String? = null
    private val sessionDeactivateHandler = Handler(Looper.getMainLooper())
    private var sessionDeactivateRunnable: Runnable? = null
    private val SESSION_DEACTIVATE_DELAY = 5 * 60 * 1000L // 5 minutes

    // Recording - using async write queue to prevent audio hitching
    private var isRecording = false
    private val recordingOutputStreamHolder = AtomicReference<OutputStream?>(null)
    private val recordingWriteQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(1000)
    private val isRecordingActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private var writerThread: Thread? = null
    private var recordingFile: File? = null
    private var recordingUri: android.net.Uri? = null  // For Android 10+ MediaStore finalization
    private var currentStationName: String = "Unknown Station"

    // Sleep timer
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerEndTime: Long = 0L

    // Stream metadata
    private var currentMetadata: String? = null
    private var currentBitrate: Int = 0
    private var currentCodec: String? = null

    companion object {
        const val ACTION_PLAY = "com.opensource.i2pradio.PLAY"
        const val ACTION_PAUSE = "com.opensource.i2pradio.PAUSE"
        const val ACTION_STOP = "com.opensource.i2pradio.STOP"
        const val ACTION_START_RECORDING = "com.opensource.i2pradio.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.opensource.i2pradio.STOP_RECORDING"
        const val ACTION_SET_SLEEP_TIMER = "com.opensource.i2pradio.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.opensource.i2pradio.CANCEL_SLEEP_TIMER"
        const val CHANNEL_ID = "I2PRadioChannel"
        const val NOTIFICATION_ID = 1

        // Broadcast actions for metadata updates
        const val BROADCAST_METADATA_CHANGED = "com.opensource.i2pradio.METADATA_CHANGED"
        const val BROADCAST_STREAM_INFO_CHANGED = "com.opensource.i2pradio.STREAM_INFO_CHANGED"
        const val EXTRA_METADATA = "metadata"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_CODEC = "codec"
    }

    inner class RadioBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initializeMediaSession()
    }

    private fun initializeMediaSession() {
        // Create MediaSession for Now Playing card on TV
        mediaSession = MediaSessionCompat(this, "I2PRadioSession").apply {
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
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "I2P Radio")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "I2P Radio")

        // Load cover art if available
        if (!coverArtUri.isNullOrEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val imageLoader = ImageLoader(this@RadioService)
                    val request = ImageRequest.Builder(this@RadioService)
                        .data(coverArtUri)
                        .allowHardware(false)
                        .build()

                    val result = imageLoader.execute(request)
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
        sessionDeactivateHandler.postDelayed(sessionDeactivateRunnable!!, SESSION_DEACTIVATE_DELAY)
        android.util.Log.d("RadioService", "Scheduled MediaSession deactivation in ${SESSION_DEACTIVATE_DELAY / 1000}s")
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
        if (isRecording) return

        currentStationName = stationName
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        // Detect format from current stream URL to avoid corruption
        // Recording writes raw stream bytes, so we must use the stream's actual format
        val detectedFormat = detectStreamFormat(currentStreamUrl)
        val format = detectedFormat.first
        val mimeType = detectedFormat.second
        android.util.Log.d("RadioService", "Recording format detected: $format (mime: $mimeType) from URL: $currentStreamUrl")

        val fileName = "${stationName.replace(Regex("[^a-zA-Z0-9]"), "_")}_$timestamp.$format"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/I2P Radio")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    recordingUri = it
                    val outputStream = contentResolver.openOutputStream(it)
                    // Set the output stream and start the writer thread
                    recordingOutputStreamHolder.set(outputStream)
                    isRecordingActive.set(true)
                    // Start the async writer thread
                    writerThread = RecordingDataSource.startWriterThread(
                        recordingWriteQueue,
                        recordingOutputStreamHolder,
                        isRecordingActive
                    )
                    writerThread?.start()
                    isRecording = true
                    android.util.Log.d("RadioService", "Recording started (no reconnect): $fileName")
                    updateNotificationWithRecording()
                }
            } else {
                // Legacy storage for older Android versions
                val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "I2P Radio")
                if (!musicDir.exists()) musicDir.mkdirs()

                recordingFile = File(musicDir, fileName)
                val outputStream = FileOutputStream(recordingFile)
                // Set the output stream and start the writer thread
                recordingOutputStreamHolder.set(outputStream)
                isRecordingActive.set(true)
                // Start the async writer thread
                writerThread = RecordingDataSource.startWriterThread(
                    recordingWriteQueue,
                    recordingOutputStreamHolder,
                    isRecordingActive
                )
                writerThread?.start()
                isRecording = true
                android.util.Log.d("RadioService", "Recording started (no reconnect): $fileName")
                updateNotificationWithRecording()
            }
        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Failed to start recording", e)
            cleanupRecording()
        }
    }

    private fun cleanupRecording() {
        // Stop the writer thread
        isRecordingActive.set(false)
        writerThread?.interrupt()
        writerThread = null

        try {
            recordingOutputStreamHolder.get()?.close()
        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Error closing recording stream", e)
        }
        recordingOutputStreamHolder.set(null)
        recordingWriteQueue.clear()
        recordingFile = null
        recordingUri = null
        isRecording = false
    }

    /**
     * Detects the audio format from a stream URL.
     * Since recording writes raw stream bytes, we must save with the correct format
     * to avoid file corruption (e.g., saving OGG stream as MP3).
     *
     * @return Pair of (file extension, MIME type)
     */
    private fun detectStreamFormat(streamUrl: String?): Pair<String, String> {
        if (streamUrl == null) {
            return Pair("mp3", "audio/mpeg") // Default to MP3 as most universally compatible
        }

        val urlLower = streamUrl.lowercase()

        // Check URL path for format hints - order matters, check specific patterns first
        return when {
            // OGG/Vorbis detection - check this before mp3 to avoid false positives on URLs like "stream.ogg?format=mp3"
            urlLower.endsWith(".ogg") || urlLower.contains("/ogg/") || urlLower.contains("type=ogg") ||
            urlLower.contains("vorbis") || urlLower.contains("/vorbis") || urlLower.contains("codec=vorbis") ->
                Pair("ogg", "audio/ogg")
            // Opus detection
            urlLower.endsWith(".opus") || urlLower.contains("/opus/") || urlLower.contains("type=opus") ||
            urlLower.contains("codec=opus") ->
                Pair("opus", "audio/opus")
            // AAC detection - check before mp3 as some URLs might have both hints
            urlLower.endsWith(".aac") || urlLower.contains("/aac/") || urlLower.contains("type=aac") ||
            urlLower.contains("codec=aac") || urlLower.contains("/;stream.nsv") ->
                Pair("aac", "audio/aac")
            // M4A detection
            urlLower.endsWith(".m4a") || urlLower.contains("/m4a/") ->
                Pair("m4a", "audio/mp4")
            // FLAC detection
            urlLower.endsWith(".flac") || urlLower.contains("/flac/") || urlLower.contains("type=flac") ->
                Pair("flac", "audio/flac")
            // WAV detection
            urlLower.endsWith(".wav") || urlLower.contains("/wav/") ->
                Pair("wav", "audio/wav")
            // MP3 detection - most common streaming format
            urlLower.endsWith(".mp3") || urlLower.contains("/mp3") || urlLower.contains("type=mp3") ||
            urlLower.contains("codec=mp3") || urlLower.contains(";stream.mp3") ||
            urlLower.contains("/stream") || urlLower.contains("/listen") ||
            urlLower.contains(":8000") || urlLower.contains(":8080") ->
                Pair("mp3", "audio/mpeg")
            // Default to MP3 for unknown formats as it's the most common and universally compatible
            // Most players can auto-detect the actual format from the file header even if extension differs
            else -> Pair("mp3", "audio/mpeg")
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        try {
            // Signal the writer thread to stop accepting new data
            isRecordingActive.set(false)

            // Wait for writer thread to finish draining the queue and flush
            writerThread?.let { thread ->
                try {
                    thread.join(2000) // Wait up to 2 seconds for queue to drain
                    if (thread.isAlive) {
                        android.util.Log.w("RadioService", "Writer thread still alive after timeout, interrupting")
                        thread.interrupt()
                    }
                } catch (e: InterruptedException) {
                    android.util.Log.w("RadioService", "Interrupted while waiting for writer thread")
                }
            }
            writerThread = null

            // Now close the output stream
            recordingOutputStreamHolder.get()?.close()
            recordingOutputStreamHolder.set(null)

            // Clear the write queue
            recordingWriteQueue.clear()

            // Finalize MediaStore entry for Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && recordingUri != null) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                contentResolver.update(recordingUri!!, contentValues, null, null)
                android.util.Log.d("RadioService", "Recording finalized: $recordingUri")
            }

            recordingUri = null
            recordingFile = null
            isRecording = false
            android.util.Log.d("RadioService", "Recording stopped (no reconnect)")

            // Update notification without restarting player
            if (player?.isPlaying == true) {
                startForeground(NOTIFICATION_ID, createNotification("Playing"))
            }
        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Failed to stop recording", e)
            cleanupRecording()
        }
    }

    private fun updateNotificationWithRecording() {
        if (player?.isPlaying == true) {
            startForeground(NOTIFICATION_ID, createNotification("Playing • Recording"))
        }
    }

    private fun playStream(streamUrl: String, proxyHost: String, proxyPort: Int, proxyType: ProxyType = ProxyType.NONE) {
        try {
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )

            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                android.util.Log.w("RadioService", "Failed to gain audio focus")
                return
            }

            stopStream()

            // Determine proxy configuration
            // Priority: 1. Embedded Tor (if enabled and connected for Tor streams)
            //           2. Tor for clearnet streams (anonymity/censorship bypass)
            //           3. Manual proxy configuration
            //           4. Direct connection
            val (effectiveProxyHost, effectiveProxyPort, effectiveProxyType) = when {
                // Use embedded Tor for Tor streams if enabled and connected
                proxyType == ProxyType.TOR &&
                PreferencesHelper.isEmbeddedTorEnabled(this) &&
                TorManager.isConnected() -> {
                    android.util.Log.d("RadioService", "Using embedded Tor proxy for .onion stream")
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                // Route clearnet streams through Tor for anonymity (when enabled and Tor is connected)
                proxyType == ProxyType.NONE &&
                PreferencesHelper.isEmbeddedTorEnabled(this) &&
                PreferencesHelper.isTorForClearnetEnabled(this) &&
                TorManager.isConnected() -> {
                    android.util.Log.d("RadioService", "Routing clearnet stream through Tor for anonymity")
                    Triple(TorManager.getProxyHost(), TorManager.getProxyPort(), ProxyType.TOR)
                }
                // Use manual proxy configuration
                proxyHost.isNotEmpty() && proxyType != ProxyType.NONE -> {
                    Triple(proxyHost, proxyPort, proxyType)
                }
                // Direct connection
                else -> Triple("", 0, ProxyType.NONE)
            }

            val okHttpClient = if (effectiveProxyHost.isNotEmpty() && effectiveProxyType != ProxyType.NONE) {
                // Use SOCKS5 for Tor, HTTP for I2P
                val javaProxyType = when (effectiveProxyType) {
                    ProxyType.TOR -> Proxy.Type.SOCKS
                    ProxyType.I2P -> Proxy.Type.HTTP
                    ProxyType.NONE -> Proxy.Type.DIRECT
                }
                android.util.Log.d("RadioService", "Using ${effectiveProxyType.name} proxy: $effectiveProxyHost:$effectiveProxyPort (${javaProxyType.name})")
                OkHttpClient.Builder()
                    .proxy(Proxy(javaProxyType, InetSocketAddress(effectiveProxyHost, effectiveProxyPort)))
                    .connectTimeout(90, TimeUnit.SECONDS) // Longer timeout for Tor/I2P
                    .readTimeout(90, TimeUnit.SECONDS)
                    .build()
            } else {
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            }

            val baseDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("I2PRadio/1.0")

            // Always wrap with recording data source using async write queue
            // This allows recording to be toggled without recreating the player
            // and prevents audio hitching by writing to disk on a background thread
            val dataSourceFactory = RecordingDataSource.Factory(baseDataSourceFactory, recordingWriteQueue, isRecordingActive)

            player = ExoPlayer.Builder(this).build().apply {
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamUrl))

                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true

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
                                // Extract stream info when ready
                                extractStreamInfo()
                                android.util.Log.d("RadioService", "Stream playing successfully")
                            }
                            Player.STATE_BUFFERING -> {
                                startForeground(NOTIFICATION_ID, createNotification("Buffering..."))
                                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                                android.util.Log.d("RadioService", "Buffering stream...")
                            }
                            Player.STATE_ENDED -> {
                                android.util.Log.d("RadioService", "Stream ended, reconnecting...")
                                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                                scheduleReconnect()
                            }
                            Player.STATE_IDLE -> {
                                android.util.Log.d("RadioService", "Player idle")
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                            cancelSessionDeactivation()
                        } else if (player?.playbackState == Player.STATE_READY) {
                            // Player is paused but ready
                            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
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

            startForeground(NOTIFICATION_ID, createNotification("Connecting..."))

        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Error playing stream", e)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (currentStreamUrl == null) {
            android.util.Log.d("RadioService", "No stream to reconnect to")
            return
        }

        if (reconnectAttempts >= maxReconnectAttempts) {
            android.util.Log.e("RadioService", "Max reconnection attempts reached")
            startForeground(NOTIFICATION_ID, createNotification("Connection failed"))
            return
        }

        reconnectAttempts++
        val delay = minOf(1000L * reconnectAttempts, 5000L)

        android.util.Log.d("RadioService", "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        startForeground(NOTIFICATION_ID, createNotification("Reconnecting..."))

        handler.postDelayed({
            currentStreamUrl?.let { url ->
                playStream(url, currentProxyHost ?: "", currentProxyPort, currentProxyType)
            }
        }, delay)
    }

    private fun stopStream() {
        handler.removeCallbacksAndMessages(null)
        player?.apply {
            stop()
            release()
        }
        player = null
        audioManager.abandonAudioFocus(null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "I2P Radio Playback",
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

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        cancelSleepTimer()
        stopStream()
        cancelSessionDeactivation()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
    }
}

package com.opensource.i2pradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RadioService : Service() {
    private var player: ExoPlayer? = null
    private val binder = RadioBinder()
    private var currentStreamUrl: String? = null
    private var currentProxyHost: String? = null
    private var currentProxyPort: Int = 4444
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    private lateinit var audioManager: AudioManager

    // Recording
    private var isRecording = false
    private var recordingOutputStream: OutputStream? = null
    private var recordingFile: File? = null
    private var currentStationName: String = "Unknown Station"

    companion object {
        const val ACTION_PLAY = "com.opensource.i2pradio.PLAY"
        const val ACTION_PAUSE = "com.opensource.i2pradio.PAUSE"
        const val ACTION_STOP = "com.opensource.i2pradio.STOP"
        const val ACTION_START_RECORDING = "com.opensource.i2pradio.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.opensource.i2pradio.STOP_RECORDING"
        const val CHANNEL_ID = "I2PRadioChannel"
        const val NOTIFICATION_ID = 1
    }

    inner class RadioBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

                currentStreamUrl = streamUrl
                currentProxyHost = proxyHost
                currentProxyPort = proxyPort
                reconnectAttempts = 0

                playStream(streamUrl, proxyHost, proxyPort)
            }
            ACTION_PAUSE -> {
                player?.pause()
                startForeground(NOTIFICATION_ID, createNotification("Paused"))
            }
            ACTION_STOP -> {
                stopRecording()
                currentStreamUrl = null
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
        }
        return START_STICKY
    }

    private fun startRecording(stationName: String) {
        if (isRecording) return

        currentStationName = stationName
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${stationName.replace(Regex("[^a-zA-Z0-9]"), "_")}_$timestamp.mp3"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/I2P Radio")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    recordingOutputStream = contentResolver.openOutputStream(it)
                    isRecording = true
                    android.util.Log.d("RadioService", "Recording started: $fileName")
                    updateNotificationWithRecording()
                }
            } else {
                // Legacy storage for older Android versions
                val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "I2P Radio")
                if (!musicDir.exists()) musicDir.mkdirs()

                recordingFile = File(musicDir, fileName)
                recordingOutputStream = FileOutputStream(recordingFile)
                isRecording = true
                android.util.Log.d("RadioService", "Recording started: $fileName")
                updateNotificationWithRecording()
            }
        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Failed to start recording", e)
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        try {
            recordingOutputStream?.close()
            recordingOutputStream = null
            isRecording = false
            android.util.Log.d("RadioService", "Recording stopped")

            // Update notification
            if (player?.isPlaying == true) {
                startForeground(NOTIFICATION_ID, createNotification("Playing"))
            }
        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Failed to stop recording", e)
        }
    }

    private fun updateNotificationWithRecording() {
        if (player?.isPlaying == true) {
            startForeground(NOTIFICATION_ID, createNotification("Playing • Recording"))
        }
    }

    private fun playStream(streamUrl: String, proxyHost: String, proxyPort: Int) {
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

            val okHttpClient = if (proxyHost.isNotEmpty()) {
                OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            } else {
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            }

            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("I2PRadio/1.0")

            player = ExoPlayer.Builder(this).build().apply {
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamUrl))

                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e("RadioService", "Playback error: ${error.message}")
                        scheduleReconnect()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                reconnectAttempts = 0
                                val status = if (isRecording) "Playing • Recording" else "Playing"
                                startForeground(NOTIFICATION_ID, createNotification(status))
                                android.util.Log.d("RadioService", "Stream playing successfully")
                            }
                            Player.STATE_BUFFERING -> {
                                startForeground(NOTIFICATION_ID, createNotification("Buffering..."))
                                android.util.Log.d("RadioService", "Buffering stream...")
                            }
                            Player.STATE_ENDED -> {
                                android.util.Log.d("RadioService", "Stream ended, reconnecting...")
                                scheduleReconnect()
                            }
                            Player.STATE_IDLE -> {
                                android.util.Log.d("RadioService", "Player idle")
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
                playStream(url, currentProxyHost ?: "", currentProxyPort)
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("I2P Radio")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_pause, "Pause", playPausePendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setStyle(MediaNotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        stopStream()
    }
}

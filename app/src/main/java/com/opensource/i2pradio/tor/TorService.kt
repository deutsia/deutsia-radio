package com.opensource.i2pradio.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R

/**
 * Foreground service that manages Tor connectivity via Orbot.
 * This service keeps the Tor connection status updated and provides notifications.
 */
class TorService : Service() {

    companion object {
        private const val TAG = "TorService"
        const val CHANNEL_ID = "TorServiceChannel"
        const val NOTIFICATION_ID = 2

        const val ACTION_START = "com.opensource.i2pradio.tor.START"
        const val ACTION_STOP = "com.opensource.i2pradio.tor.STOP"

        /**
         * Start the Tor service.
         */
        fun start(context: Context) {
            val intent = Intent(context, TorService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the Tor service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, TorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val binder = TorBinder()

    inner class TorBinder : Binder() {
        fun getService(): TorService = this@TorService
    }

    private val stateListener: (TorManager.TorState) -> Unit = { state ->
        updateNotification(state)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        TorManager.addStateListener(stateListener)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Starting Tor service...")
                startForeground(NOTIFICATION_ID, createNotification(TorManager.TorState.STARTING))
                TorManager.start(this) { success ->
                    if (!success) {
                        Log.e(TAG, "Failed to start Tor: ${TorManager.errorMessage}")
                    }
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping Tor service...")
                TorManager.stop()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tor Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Tor connection status"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(state: TorManager.TorState): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val (title, text, icon) = when (state) {
            TorManager.TorState.STOPPED -> Triple("Tor Disconnected", "Tap to open app", R.drawable.ic_tor_off)
            TorManager.TorState.STARTING -> Triple("Connecting via Orbot...", "Please wait", R.drawable.ic_tor_connecting)
            TorManager.TorState.CONNECTED -> Triple("Tor Connected", "SOCKS port: ${TorManager.socksPort}", R.drawable.ic_tor_on)
            TorManager.TorState.ERROR -> Triple("Tor Error", TorManager.errorMessage ?: "Connection failed", R.drawable.ic_tor_off)
            TorManager.TorState.ORBOT_NOT_INSTALLED -> Triple("Orbot Required", "Please install Orbot for Tor support", R.drawable.ic_tor_off)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (state != TorManager.TorState.STARTING) {
                    addAction(R.drawable.ic_stop, "Stop Tor", stopPendingIntent)
                }
            }
            .build()
    }

    private fun updateNotification(state: TorManager.TorState) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(state))
    }

    override fun onDestroy() {
        super.onDestroy()
        TorManager.removeStateListener(stateListener)
        TorManager.cleanup(this)
        TorManager.stop()
    }
}

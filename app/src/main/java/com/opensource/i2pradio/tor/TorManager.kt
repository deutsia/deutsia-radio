package com.opensource.i2pradio.tor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manager for Tor connectivity using Orbot (the official Tor client for Android).
 *
 * This implementation provides seamless communication with Orbot, including:
 * - Real-time status updates via broadcast receiver
 * - Automatic connection state detection
 * - Periodic connection health checks
 * - Graceful error handling with user-friendly messages
 *
 * Orbot provides:
 * - A properly signed and sandboxed Tor implementation
 * - System-level proxy support
 * - Automatic circuit management
 * - Regular security updates
 */
object TorManager {
    private const val TAG = "TorManager"

    // Orbot package identifiers
    private const val ORBOT_PACKAGE_NAME = "org.torproject.android"
    private const val ORBOT_MARKET_URI = "market://details?id=$ORBOT_PACKAGE_NAME"
    private const val ORBOT_FDROID_URI = "https://f-droid.org/packages/$ORBOT_PACKAGE_NAME/"

    // Orbot Intent actions
    private const val ACTION_START_TOR = "org.torproject.android.intent.action.START"
    private const val ACTION_REQUEST_HS = "org.torproject.android.REQUEST_HS_PORT"
    private const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"

    // Orbot status broadcast
    private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
    private const val EXTRA_SOCKS_PROXY_HOST = "org.torproject.android.intent.extra.SOCKS_PROXY_HOST"
    private const val EXTRA_SOCKS_PROXY_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
    private const val EXTRA_HTTP_PROXY_HOST = "org.torproject.android.intent.extra.HTTP_PROXY_HOST"
    private const val EXTRA_HTTP_PROXY_PORT = "org.torproject.android.intent.extra.HTTP_PROXY_PORT"

    // Orbot status values
    private const val STATUS_ON = "ON"
    private const val STATUS_OFF = "OFF"
    private const val STATUS_STARTING = "STARTING"
    private const val STATUS_STOPPING = "STOPPING"

    // Default Orbot SOCKS port (when Orbot is running)
    private const val DEFAULT_ORBOT_SOCKS_PORT = 9050
    private const val DEFAULT_ORBOT_HTTP_PORT = 8118

    // Connection health check interval (30 seconds)
    private const val HEALTH_CHECK_INTERVAL = 30_000L

    // Connection state
    enum class TorState {
        STOPPED,
        STARTING,
        CONNECTED,
        ERROR,
        ORBOT_NOT_INSTALLED
    }

    private var _state: TorState = TorState.STOPPED
    val state: TorState get() = _state

    private var _socksPort: Int = -1
    val socksPort: Int get() = _socksPort

    private var _httpPort: Int = -1
    val httpPort: Int get() = _httpPort

    private var _socksHost: String = "127.0.0.1"
    val socksHost: String get() = _socksHost

    private var _errorMessage: String? = null
    val errorMessage: String? get() = _errorMessage

    // Connection start time for status display
    private var _connectionStartTime: Long = 0
    val connectionDuration: Long get() = if (_state == TorState.CONNECTED) System.currentTimeMillis() - _connectionStartTime else 0

    // Listeners for state changes
    private val stateListeners = mutableListOf<(TorState) -> Unit>()

    // Broadcast receiver for Orbot status updates
    private var orbotStatusReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    // Handler for periodic health checks
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private var healthCheckRunnable: Runnable? = null

    fun addStateListener(listener: (TorState) -> Unit) {
        synchronized(stateListeners) {
            stateListeners.add(listener)
        }
        // Immediately notify of current state
        listener(_state)
    }

    fun removeStateListener(listener: (TorState) -> Unit) {
        synchronized(stateListeners) {
            stateListeners.remove(listener)
        }
    }

    private fun notifyStateChange(newState: TorState) {
        val oldState = _state
        _state = newState

        // Track connection start time
        if (newState == TorState.CONNECTED && oldState != TorState.CONNECTED) {
            _connectionStartTime = System.currentTimeMillis()
            startHealthCheck()
        } else if (newState != TorState.CONNECTED) {
            stopHealthCheck()
        }

        synchronized(stateListeners) {
            stateListeners.toList().forEach { it(newState) }
        }

        Log.d(TAG, "Tor state changed: $oldState -> $newState")
    }

    private fun startHealthCheck() {
        stopHealthCheck()
        healthCheckRunnable = object : Runnable {
            override fun run() {
                if (_state == TorState.CONNECTED) {
                    checkConnectionHealth()
                    healthCheckHandler.postDelayed(this, HEALTH_CHECK_INTERVAL)
                }
            }
        }
        healthCheckHandler.postDelayed(healthCheckRunnable!!, HEALTH_CHECK_INTERVAL)
    }

    private fun stopHealthCheck() {
        healthCheckRunnable?.let { healthCheckHandler.removeCallbacks(it) }
        healthCheckRunnable = null
    }

    private fun checkConnectionHealth() {
        Thread {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(_socksHost, _socksPort), 2000)
                socket.close()
                // Connection is healthy, no state change needed
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (_state == TorState.CONNECTED) {
                        Log.w(TAG, "Connection health check failed: ${e.message}")
                        _errorMessage = "Tor connection lost. Please check Orbot."
                        notifyStateChange(TorState.ERROR)
                    }
                }
            }
        }.start()
    }

    /**
     * Check if Orbot is installed on the device.
     */
    fun isOrbotInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(ORBOT_PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Open the app store to install Orbot.
     */
    fun openOrbotInstallPage(context: Context) {
        try {
            // Try Google Play first
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ORBOT_MARKET_URI))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fall back to F-Droid web page
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ORBOT_FDROID_URI))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open Orbot install page", e2)
            }
        }
    }

    /**
     * Request Orbot to start the Tor service.
     *
     * This sends an intent to Orbot to start Tor. The actual connection status
     * will be received via broadcast.
     */
    fun start(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        if (_state == TorState.STARTING || _state == TorState.CONNECTED) {
            Log.d(TAG, "Tor is already ${_state.name}")
            onComplete?.invoke(_state == TorState.CONNECTED)
            return
        }

        // Check if Orbot is installed
        if (!isOrbotInstalled(context)) {
            Log.w(TAG, "Orbot is not installed")
            _errorMessage = "Orbot is not installed. Please install Orbot to use Tor."
            notifyStateChange(TorState.ORBOT_NOT_INSTALLED)
            onComplete?.invoke(false)
            return
        }

        notifyStateChange(TorState.STARTING)
        _errorMessage = null

        // Register receiver for Orbot status updates
        registerOrbotStatusReceiver(context, onComplete)

        // Send intent to start Orbot
        try {
            val intent = Intent(ACTION_START_TOR)
            intent.setPackage(ORBOT_PACKAGE_NAME)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent start request to Orbot")

            // Also try to launch Orbot activity if broadcast doesn't work
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE_NAME)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not launch Orbot activity (this is OK)", e)
            }

            // Set a timeout to check if Orbot responded
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (_state == TorState.STARTING) {
                    // Assume Orbot is running on default ports if we haven't received a status update
                    // This handles the case where Orbot is already running
                    checkOrbotConnection(context, onComplete)
                }
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Orbot", e)
            _errorMessage = e.message ?: "Failed to start Orbot"
            notifyStateChange(TorState.ERROR)
            onComplete?.invoke(false)
        }
    }

    /**
     * Check if Orbot's SOCKS proxy is accessible.
     */
    private fun checkOrbotConnection(context: Context, onComplete: ((Boolean) -> Unit)?) {
        Thread {
            try {
                // Try to connect to Orbot's SOCKS port
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", DEFAULT_ORBOT_SOCKS_PORT), 2000)
                socket.close()

                // Connection successful - Orbot is running
                _socksPort = DEFAULT_ORBOT_SOCKS_PORT
                _httpPort = DEFAULT_ORBOT_HTTP_PORT
                _socksHost = "127.0.0.1"

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Log.d(TAG, "Orbot is running on SOCKS port $_socksPort")
                    notifyStateChange(TorState.CONNECTED)
                    onComplete?.invoke(true)
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Log.w(TAG, "Orbot SOCKS port not accessible: ${e.message}")
                    _errorMessage = "Please open Orbot and start the Tor service"
                    notifyStateChange(TorState.ERROR)
                    onComplete?.invoke(false)
                }
            }
        }.start()
    }

    /**
     * Register a broadcast receiver for Orbot status updates.
     */
    private fun registerOrbotStatusReceiver(context: Context, onComplete: ((Boolean) -> Unit)?) {
        if (isReceiverRegistered) {
            return
        }

        orbotStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_STATUS) {
                    val status = intent.getStringExtra(EXTRA_STATUS)
                    Log.d(TAG, "Received Orbot status: $status")

                    when (status) {
                        STATUS_ON -> {
                            _socksHost = intent.getStringExtra(EXTRA_SOCKS_PROXY_HOST) ?: "127.0.0.1"
                            _socksPort = intent.getIntExtra(EXTRA_SOCKS_PROXY_PORT, DEFAULT_ORBOT_SOCKS_PORT)
                            _httpPort = intent.getIntExtra(EXTRA_HTTP_PROXY_PORT, DEFAULT_ORBOT_HTTP_PORT)

                            Log.d(TAG, "Orbot connected: SOCKS=$_socksHost:$_socksPort")
                            notifyStateChange(TorState.CONNECTED)
                            onComplete?.invoke(true)
                        }
                        STATUS_OFF -> {
                            _socksPort = -1
                            _httpPort = -1
                            Log.d(TAG, "Orbot stopped")
                            notifyStateChange(TorState.STOPPED)
                        }
                        STATUS_STARTING -> {
                            Log.d(TAG, "Orbot is starting...")
                            notifyStateChange(TorState.STARTING)
                        }
                        STATUS_STOPPING -> {
                            Log.d(TAG, "Orbot is stopping...")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Use RECEIVER_EXPORTED to receive broadcasts from Orbot (external app)
            context.registerReceiver(orbotStatusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(orbotStatusReceiver, filter)
        }
        isReceiverRegistered = true
    }

    /**
     * Stop listening for Orbot status updates.
     * Note: This doesn't stop Orbot itself - the user controls Orbot separately.
     */
    fun stop() {
        Log.d(TAG, "Stopping Tor manager...")
        _socksPort = -1
        _httpPort = -1
        _errorMessage = null
        notifyStateChange(TorState.STOPPED)
        Log.d(TAG, "Tor manager stopped")
    }

    /**
     * Unregister the Orbot status receiver.
     * Call this when the app is being destroyed.
     */
    fun cleanup(context: Context) {
        stopHealthCheck()
        if (isReceiverRegistered && orbotStatusReceiver != null) {
            try {
                context.unregisterReceiver(orbotStatusReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering Orbot receiver", e)
            }
            isReceiverRegistered = false
            orbotStatusReceiver = null
        }
    }

    /**
     * Initialize and check for Orbot status.
     * Call this when the app starts to detect if Orbot is already running.
     */
    fun initialize(context: Context) {
        if (!isOrbotInstalled(context)) {
            notifyStateChange(TorState.ORBOT_NOT_INSTALLED)
            return
        }

        // Register receiver first
        registerOrbotStatusReceiver(context, null)

        // Request current status from Orbot
        requestOrbotStatus(context)
    }

    /**
     * Request current status from Orbot.
     * Orbot will respond with a STATUS broadcast.
     */
    fun requestOrbotStatus(context: Context) {
        try {
            val intent = Intent(ACTION_STATUS)
            intent.setPackage(ORBOT_PACKAGE_NAME)
            context.sendBroadcast(intent)
            Log.d(TAG, "Requested Orbot status")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request Orbot status", e)
        }

        // Also check the socket as a fallback - this is the most reliable way to detect Tor status
        Thread {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", DEFAULT_ORBOT_SOCKS_PORT), 1000)
                socket.close()

                // Orbot is running
                Handler(Looper.getMainLooper()).post {
                    if (_state != TorState.CONNECTED) {
                        _socksPort = DEFAULT_ORBOT_SOCKS_PORT
                        _httpPort = DEFAULT_ORBOT_HTTP_PORT
                        _socksHost = "127.0.0.1"
                        notifyStateChange(TorState.CONNECTED)
                    }
                }
            } catch (e: Exception) {
                // Orbot not running or not accessible - update state regardless of current state
                Handler(Looper.getMainLooper()).post {
                    if (_state == TorState.CONNECTED || _state == TorState.STARTING) {
                        _socksPort = -1
                        _httpPort = -1
                        notifyStateChange(TorState.STOPPED)
                        Log.d(TAG, "Tor socket check failed - marking as stopped")
                    }
                }
            }
        }.start()
    }

    /**
     * Get a user-friendly status message for the current state.
     */
    fun getStatusMessage(): String {
        return when (_state) {
            TorState.STOPPED -> "Tor is not active"
            TorState.STARTING -> "Connecting to Tor network..."
            TorState.CONNECTED -> "Connected via Tor"
            TorState.ERROR -> _errorMessage ?: "Connection error"
            TorState.ORBOT_NOT_INSTALLED -> "Orbot app required"
        }
    }

    /**
     * Get a detailed status string including connection info.
     */
    fun getDetailedStatus(): String {
        return when (_state) {
            TorState.CONNECTED -> "SOCKS5 proxy at $_socksHost:$_socksPort"
            TorState.STARTING -> "Establishing connection..."
            TorState.ERROR -> _errorMessage ?: "Unknown error occurred"
            TorState.STOPPED -> "Tap to connect"
            TorState.ORBOT_NOT_INSTALLED -> "Install Orbot from Play Store or F-Droid"
        }
    }

    /**
     * Check if Tor is currently running and connected via Orbot.
     */
    fun isConnected(): Boolean {
        return _state == TorState.CONNECTED && _socksPort > 0
    }

    /**
     * Get the SOCKS proxy host.
     */
    fun getProxyHost(): String = _socksHost

    /**
     * Get the SOCKS proxy port, or -1 if not connected.
     */
    fun getProxyPort(): Int = if (isConnected()) _socksPort else -1

    /**
     * Restart Tor (request Orbot to restart).
     */
    fun restart(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        stop()
        start(context, onComplete)
    }
}

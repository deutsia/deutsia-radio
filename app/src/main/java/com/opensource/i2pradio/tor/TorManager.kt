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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manager for Tor connectivity using InviZible Pro (privacy app with Tor/DNSCrypt/I2P support).
 *
 * This implementation provides seamless communication with InviZible Pro, including:
 * - Real-time status updates via broadcast receiver
 * - Automatic connection state detection
 * - Periodic connection health checks
 * - Graceful error handling with user-friendly messages
 *
 * InviZible Pro provides:
 * - Easy proxy mode configuration (no VPN workaround needed)
 * - Tor, DNSCrypt, and I2P in one app
 * - System-level proxy support
 * - Automatic circuit management
 * - Regular security updates
 */
object TorManager {
    private const val TAG = "TorManager"

    // InviZible Pro package identifiers
    private const val INVIZIBLE_PACKAGE_NAME = "pan.alexander.tordnscrypt.gp"
    private const val INVIZIBLE_MARKET_URI = "https://play.google.com/store/apps/details?id=$INVIZIBLE_PACKAGE_NAME"
    private const val INVIZIBLE_FDROID_URI = "https://f-droid.org/packages/pan.alexander.tordnscrypt/"

    // Tor Intent actions (standard Tor control protocol, works with InviZible Pro)
    private const val ACTION_START_TOR = "org.torproject.android.intent.action.START"
    private const val ACTION_REQUEST_HS = "org.torproject.android.REQUEST_HS_PORT"
    private const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"

    // Tor status broadcast
    private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
    private const val EXTRA_SOCKS_PROXY_HOST = "org.torproject.android.intent.extra.SOCKS_PROXY_HOST"
    private const val EXTRA_SOCKS_PROXY_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
    private const val EXTRA_HTTP_PROXY_HOST = "org.torproject.android.intent.extra.HTTP_PROXY_HOST"
    private const val EXTRA_HTTP_PROXY_PORT = "org.torproject.android.intent.extra.HTTP_PROXY_PORT"

    // Tor status values
    private const val STATUS_ON = "ON"
    private const val STATUS_OFF = "OFF"
    private const val STATUS_STARTING = "STARTING"
    private const val STATUS_STOPPING = "STOPPING"

    // Default Tor SOCKS port (when InviZible Pro is running in proxy mode)
    private const val DEFAULT_TOR_SOCKS_PORT = 9050
    private const val DEFAULT_TOR_HTTP_PORT = 8118

    // Connection health check interval (30 seconds)
    private const val HEALTH_CHECK_INTERVAL = 30_000L

    // Connection state
    enum class TorState {
        STOPPED,
        STARTING,
        CONNECTED,
        ERROR,
        INVIZIBLE_NOT_INSTALLED
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

    // Listeners for state changes (thread-safe)
    private val stateListeners = CopyOnWriteArrayList<(TorState) -> Unit>()

    // Broadcast receiver for InviZible Pro status updates
    private var invizibleStatusReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    // Handler for periodic health checks
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private var healthCheckRunnable: Runnable? = null

    // Thread tracking for proper cleanup to prevent memory leaks
    @Volatile private var healthCheckThread: Thread? = null
    @Volatile private var socketCheckThread: Thread? = null
    @Volatile private var isShuttingDown = false

    fun addStateListener(listener: (TorState) -> Unit, notifyImmediately: Boolean = true) {
        stateListeners.add(listener)
        // Immediately notify of current state (unless suppressed during initialization)
        if (notifyImmediately) {
            listener(_state)
        }
    }

    fun removeStateListener(listener: (TorState) -> Unit) {
        stateListeners.remove(listener)
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

        stateListeners.forEach { it(newState) }

        // Enhanced logging for debugging Tor connectivity
        Log.d(TAG, "===== TOR STATE CHANGE =====")
        Log.d(TAG, "Tor state changed: $oldState -> $newState")
        Log.d(TAG, "SOCKS proxy: $_socksHost:$_socksPort")
        Log.d(TAG, "HTTP proxy port: $_httpPort")
        Log.d(TAG, "Is connected: ${isConnected()}")
        Log.d(TAG, "============================")
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

        // Interrupt any running health check thread to prevent memory leak
        healthCheckThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
            }
        }
        healthCheckThread = null
    }

    private fun checkConnectionHealth() {
        // Cancel any previous health check thread to prevent accumulation
        healthCheckThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
            }
        }

        healthCheckThread = Thread {
            // Check shutdown flag early to avoid unnecessary work
            if (isShuttingDown) return@Thread

            Log.d(TAG, "===== TOR HEALTH CHECK =====")
            Log.d(TAG, "Checking SOCKS proxy at $_socksHost:$_socksPort")
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(_socksHost, _socksPort), 2000)
                socket.close()
                // Connection is healthy, no state change needed
                Log.d(TAG, "Health check PASSED - Tor SOCKS proxy is responsive")
                Log.d(TAG, "============================")
            } catch (e: InterruptedException) {
                Log.d(TAG, "Health check interrupted (normal during cleanup)")
                Log.d(TAG, "============================")
            } catch (e: Exception) {
                // Don't process if we're shutting down
                if (isShuttingDown) return@Thread

                Log.w(TAG, "Health check FAILED: ${e.message}")
                Log.d(TAG, "============================")
                Handler(Looper.getMainLooper()).post {
                    if (_state == TorState.CONNECTED && !isShuttingDown) {
                        Log.w(TAG, "Connection health check failed: ${e.message}")
                        _errorMessage = "Tor connection lost. Please check InviZible Pro."
                        notifyStateChange(TorState.ERROR)
                    }
                }
            }
        }
        healthCheckThread?.start()
    }

    /**
     * Check if InviZible Pro is installed on the device.
     */
    fun isInviZibleInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(INVIZIBLE_PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Open the app store to install InviZible Pro.
     */
    fun openInviZibleInstallPage(context: Context) {
        try {
            // Try Google Play first
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(INVIZIBLE_MARKET_URI))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fall back to F-Droid web page
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(INVIZIBLE_FDROID_URI))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open InviZible Pro install page", e2)
            }
        }
    }

    /**
     * Request InviZible Pro to start the Tor service.
     *
     * This sends an intent to InviZible Pro to start Tor. The actual connection status
     * will be received via broadcast.
     */
    fun start(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        if (_state == TorState.STARTING || _state == TorState.CONNECTED) {
            Log.d(TAG, "Tor is already ${_state.name}")
            onComplete?.invoke(_state == TorState.CONNECTED)
            return
        }

        // Reset shutdown flag when starting
        isShuttingDown = false

        // Check if InviZible Pro is installed
        if (!isInviZibleInstalled(context)) {
            Log.w(TAG, "InviZible Pro is not installed")
            // CRITICAL: Clear port state when transitioning to INVIZIBLE_NOT_INSTALLED
            _socksPort = -1
            _httpPort = -1
            _errorMessage = "InviZible Pro is not installed. Please install InviZible Pro to use Tor."
            notifyStateChange(TorState.INVIZIBLE_NOT_INSTALLED)
            onComplete?.invoke(false)
            return
        }

        notifyStateChange(TorState.STARTING)
        _errorMessage = null

        // Register receiver for InviZible Pro status updates
        registerInviZibleStatusReceiver(context, onComplete)

        // Send intent to start InviZible Pro
        try {
            val intent = Intent(ACTION_START_TOR)
            intent.setPackage(INVIZIBLE_PACKAGE_NAME)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent start request to InviZible Pro")

            // Also try to launch InviZible Pro activity if broadcast doesn't work
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(INVIZIBLE_PACKAGE_NAME)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not launch InviZible Pro activity (this is OK)", e)
            }

            // Set a timeout to check if InviZible Pro responded
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (_state == TorState.STARTING) {
                    // Assume InviZible Pro is running on default ports if we haven't received a status update
                    // This handles the case where InviZible Pro is already running
                    checkTorConnection(context, onComplete)
                }
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start InviZible Pro", e)
            _errorMessage = e.message ?: "Failed to start InviZible Pro"
            notifyStateChange(TorState.ERROR)
            onComplete?.invoke(false)
        }
    }

    /**
     * Check if InviZible Pro's SOCKS proxy is accessible.
     */
    private fun checkTorConnection(context: Context, onComplete: ((Boolean) -> Unit)?) {
        // Cancel any previous socket check thread to prevent accumulation
        socketCheckThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
            }
        }

        socketCheckThread = Thread {
            // Check shutdown flag early
            if (isShuttingDown) {
                onComplete?.invoke(false)
                return@Thread
            }

            try {
                // Try to connect to InviZible Pro's SOCKS port
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", DEFAULT_TOR_SOCKS_PORT), 2000)
                socket.close()

                // Don't update state if shutting down
                if (isShuttingDown) return@Thread

                // Connection successful - InviZible Pro is running
                _socksPort = DEFAULT_TOR_SOCKS_PORT
                _httpPort = DEFAULT_TOR_HTTP_PORT
                _socksHost = "127.0.0.1"

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!isShuttingDown) {
                        Log.d(TAG, "InviZible Pro is running on SOCKS port $_socksPort")
                        notifyStateChange(TorState.CONNECTED)
                        onComplete?.invoke(true)
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "InviZible Pro connection check interrupted (normal during cleanup)")
                onComplete?.invoke(false)
            } catch (e: Exception) {
                if (isShuttingDown) return@Thread

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!isShuttingDown) {
                        Log.w(TAG, "InviZible Pro SOCKS port not accessible: ${e.message}")
                        _errorMessage = "Please open InviZible Pro and start Tor in proxy mode"
                        notifyStateChange(TorState.ERROR)
                        onComplete?.invoke(false)
                    }
                }
            }
        }
        socketCheckThread?.start()
    }

    /**
     * Register a broadcast receiver for InviZible Pro status updates.
     */
    private fun registerInviZibleStatusReceiver(context: Context, onComplete: ((Boolean) -> Unit)?) {
        if (isReceiverRegistered) {
            return
        }

        invizibleStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_STATUS) {
                    val status = intent.getStringExtra(EXTRA_STATUS)
                    Log.d(TAG, "Received InviZible Pro status: $status")

                    when (status) {
                        STATUS_ON -> {
                            _socksHost = intent.getStringExtra(EXTRA_SOCKS_PROXY_HOST) ?: "127.0.0.1"
                            _socksPort = intent.getIntExtra(EXTRA_SOCKS_PROXY_PORT, DEFAULT_TOR_SOCKS_PORT)
                            _httpPort = intent.getIntExtra(EXTRA_HTTP_PROXY_PORT, DEFAULT_TOR_HTTP_PORT)

                            Log.d(TAG, "InviZible Pro connected: SOCKS=$_socksHost:$_socksPort")
                            notifyStateChange(TorState.CONNECTED)
                            onComplete?.invoke(true)
                        }
                        STATUS_OFF -> {
                            _socksPort = -1
                            _httpPort = -1
                            Log.d(TAG, "InviZible Pro stopped")
                            notifyStateChange(TorState.STOPPED)
                        }
                        STATUS_STARTING -> {
                            Log.d(TAG, "InviZible Pro is starting...")
                            notifyStateChange(TorState.STARTING)
                        }
                        STATUS_STOPPING -> {
                            Log.d(TAG, "InviZible Pro is stopping...")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Use RECEIVER_EXPORTED to receive broadcasts from InviZible Pro (external app)
            context.registerReceiver(invizibleStatusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(invizibleStatusReceiver, filter)
        }
        isReceiverRegistered = true
    }

    /**
     * Stop listening for InviZible Pro status updates.
     * Note: This doesn't stop InviZible Pro itself - the user controls InviZible Pro separately.
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
     * Unregister the InviZible Pro status receiver.
     * Call this when the app is being destroyed.
     */
    fun cleanup(context: Context) {
        // Set shutdown flag to stop all background threads
        isShuttingDown = true

        stopHealthCheck()

        // Interrupt any running socket check thread
        socketCheckThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
            }
        }
        socketCheckThread = null

        if (isReceiverRegistered && invizibleStatusReceiver != null) {
            try {
                context.unregisterReceiver(invizibleStatusReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering InviZible Pro receiver", e)
            }
            isReceiverRegistered = false
            invizibleStatusReceiver = null
        }
    }

    /**
     * Initialize and check for InviZible Pro status.
     * Call this when the app starts to detect if InviZible Pro is already running.
     *
     * CRITICAL: This ALWAYS performs a socket check to ensure instantaneous leak detection.
     * UI components handle rapid state transitions via debouncing.
     */
    fun initialize(context: Context) {
        // Register receiver first (even if InviZible Pro check fails, the socket check will run)
        registerInviZibleStatusReceiver(context, null)

        // Request current status from InviZible Pro - this includes a socket check
        // which provides INSTANT leak detection (socket check completes in ~1-100ms)
        requestTorStatus(context)

        // Only mark as not installed if BOTH the package check fails AND
        // we're currently in a non-connected state. This prevents false positives
        // during activity recreation when PackageManager might be slow to respond
        // but Tor is actually running.
        if (!isInviZibleInstalled(context) && _state != TorState.CONNECTED) {
            // CRITICAL: Clear port state when transitioning to INVIZIBLE_NOT_INSTALLED
            // to prevent UI showing leak warnings while ports are still set
            _socksPort = -1
            _httpPort = -1
            notifyStateChange(TorState.INVIZIBLE_NOT_INSTALLED)
        }
    }

    /**
     * Request current status from InviZible Pro.
     * InviZible Pro will respond with a STATUS broadcast.
     */
    fun requestTorStatus(context: Context) {
        try {
            val intent = Intent(ACTION_STATUS)
            intent.setPackage(INVIZIBLE_PACKAGE_NAME)
            context.sendBroadcast(intent)
            Log.d(TAG, "Requested InviZible Pro status")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request InviZible Pro status", e)
        }

        // Also check the socket as a fallback - this is the most reliable way to detect Tor status
        // Cancel any previous socket check thread to prevent accumulation
        socketCheckThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
            }
        }

        socketCheckThread = Thread {
            // Check shutdown flag early
            if (isShuttingDown) return@Thread

            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", DEFAULT_TOR_SOCKS_PORT), 1000)
                socket.close()

                // Don't update state if shutting down
                if (isShuttingDown) return@Thread

                // InviZible Pro is running
                Handler(Looper.getMainLooper()).post {
                    if (_state != TorState.CONNECTED && !isShuttingDown) {
                        _socksPort = DEFAULT_TOR_SOCKS_PORT
                        _httpPort = DEFAULT_TOR_HTTP_PORT
                        _socksHost = "127.0.0.1"
                        notifyStateChange(TorState.CONNECTED)
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "InviZible Pro status check interrupted (normal during cleanup)")
            } catch (e: Exception) {
                // Don't process if shutting down
                if (isShuttingDown) return@Thread

                // InviZible Pro not running or not accessible - update state regardless of current state
                Handler(Looper.getMainLooper()).post {
                    if ((_state == TorState.CONNECTED || _state == TorState.STARTING) && !isShuttingDown) {
                        _socksPort = -1
                        _httpPort = -1
                        notifyStateChange(TorState.STOPPED)
                        Log.d(TAG, "Tor socket check failed - marking as stopped")
                    }
                }
            }
        }
        socketCheckThread?.start()
    }

    /**
     * Get a user-friendly status message for the current state.
     * NOTE: This returns raw English strings for logging/debugging.
     * For UI display, use the localized string resources in TorStatusView.
     */
    fun getStatusMessage(): String {
        return when (_state) {
            TorState.STOPPED -> "Tor is not active"
            TorState.STARTING -> "Connecting to Tor network..."
            TorState.CONNECTED -> "Connected via Tor"
            TorState.ERROR -> _errorMessage ?: "Connection error"
            TorState.INVIZIBLE_NOT_INSTALLED -> "InviZible Pro app required"
        }
    }

    /**
     * Get a detailed status string including connection info.
     * NOTE: This returns raw English strings for logging/debugging.
     * For UI display, use the localized string resources in TorStatusView.
     */
    fun getDetailedStatus(): String {
        return when (_state) {
            TorState.CONNECTED -> "SOCKS5 proxy at $_socksHost:$_socksPort"
            TorState.STARTING -> "Establishing connection..."
            TorState.ERROR -> _errorMessage ?: "Unknown error occurred"
            TorState.STOPPED -> "Tap to connect"
            TorState.INVIZIBLE_NOT_INSTALLED -> "Install InviZible Pro from Play Store or F-Droid"
        }
    }

    /**
     * Check if Tor is currently running and connected via InviZible Pro.
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
     * Restart Tor (request InviZible Pro to restart).
     */
    fun restart(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        stop()
        start(context, onComplete)
    }
}

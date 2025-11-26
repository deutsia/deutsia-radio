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

    // Atomic state holder to prevent inconsistencies during updates
    // All state changes happen atomically by replacing this entire object
    private data class TorConnectionState(
        val state: TorState = TorState.STOPPED,
        val socksHost: String = "127.0.0.1",
        val socksPort: Int = -1,
        val httpPort: Int = -1,
        val errorMessage: String? = null,
        val connectionStartTime: Long = 0
    )

    @Volatile
    private var currentState = TorConnectionState()

    // Public accessors (maintain API compatibility)
    val state: TorState get() = currentState.state
    val socksPort: Int get() = currentState.socksPort
    val httpPort: Int get() = currentState.httpPort
    val socksHost: String get() = currentState.socksHost
    val errorMessage: String? get() = currentState.errorMessage
    val connectionDuration: Long get() {
        val state = currentState
        return if (state.state == TorState.CONNECTED && state.connectionStartTime > 0) {
            System.currentTimeMillis() - state.connectionStartTime
        } else 0
    }

    // Listeners for state changes (using WeakReferences to prevent memory leaks)
    // Each listener is wrapped in WeakReference so if the owner is GC'd, listener is auto-removed
    private data class WeakListener(
        val ref: java.lang.ref.WeakReference<(TorState) -> Unit>,
        val id: Int = System.identityHashCode(ref.get())
    )

    private val stateListeners = CopyOnWriteArrayList<WeakListener>()

    // Broadcast receiver for Orbot status updates
    private var orbotStatusReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false
    // Store application context to prevent activity leaks
    @Volatile
    private var appContext: android.content.Context? = null

    // Handler for periodic health checks
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private var healthCheckRunnable: Runnable? = null

    fun addStateListener(listener: (TorState) -> Unit, notifyImmediately: Boolean = true) {
        // Clean up any dead references before adding
        cleanupDeadListeners()

        val weakListener = WeakListener(java.lang.ref.WeakReference(listener))
        stateListeners.add(weakListener)

        // Immediately notify of current state (unless suppressed during initialization)
        if (notifyImmediately) {
            listener(_state)
        }
    }

    fun removeStateListener(listener: (TorState) -> Unit) {
        val id = System.identityHashCode(listener)
        stateListeners.removeAll { it.id == id }
    }

    private fun cleanupDeadListeners() {
        stateListeners.removeAll { it.ref.get() == null }
    }

    /**
     * Update Tor state atomically - all state changes in one operation
     */
    private fun updateState(
        newState: TorState = currentState.state,
        socksHost: String = currentState.socksHost,
        socksPort: Int = currentState.socksPort,
        httpPort: Int = currentState.httpPort,
        errorMessage: String? = currentState.errorMessage
    ) {
        val oldState = currentState.state

        // Calculate connection start time
        val connectionStartTime = when {
            newState == TorState.CONNECTED && oldState != TorState.CONNECTED -> System.currentTimeMillis()
            newState == TorState.CONNECTED -> currentState.connectionStartTime
            else -> 0
        }

        // Atomic state update - all fields updated together
        currentState = TorConnectionState(
            state = newState,
            socksHost = socksHost,
            socksPort = socksPort,
            httpPort = httpPort,
            errorMessage = errorMessage,
            connectionStartTime = connectionStartTime
        )

        // Handle health checks
        if (newState == TorState.CONNECTED && oldState != TorState.CONNECTED) {
            startHealthCheck()
        } else if (newState != TorState.CONNECTED) {
            stopHealthCheck()
        }

        // Clean up dead references and notify alive listeners
        cleanupDeadListeners()
        stateListeners.forEach { weakListener ->
            weakListener.ref.get()?.invoke(newState)
        }
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
                Log.w(TAG, "Health check FAILED: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    if (currentState.state == TorState.CONNECTED) {
                        Log.w(TAG, "Connection health check failed: ${e.message}")
                        updateState(
                            newState = TorState.ERROR,
                            errorMessage = "Tor connection lost. Please check Orbot."
                        )
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
        val state = currentState.state
        if (state == TorState.STARTING || state == TorState.CONNECTED) {
            onComplete?.invoke(state == TorState.CONNECTED)
            return
        }

        // Check if Orbot is installed
        if (!isOrbotInstalled(context)) {
            Log.w(TAG, "Orbot is not installed")
            updateState(
                newState = TorState.ORBOT_NOT_INSTALLED,
                socksPort = -1,
                httpPort = -1,
                errorMessage = "Orbot is not installed. Please install Orbot to use Tor."
            )
            onComplete?.invoke(false)
            return
        }

        updateState(newState = TorState.STARTING, errorMessage = null)

        // Register receiver for Orbot status updates
        registerOrbotStatusReceiver(context, onComplete)

        // Send intent to start Orbot
        try {
            val intent = Intent(ACTION_START_TOR)
            intent.setPackage(ORBOT_PACKAGE_NAME)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)

            // Also try to launch Orbot activity if broadcast doesn't work
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE_NAME)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            } catch (e: Exception) {
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
            updateState(
                newState = TorState.ERROR,
                errorMessage = e.message ?: "Failed to start Orbot"
            )
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
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    updateState(
                        newState = TorState.CONNECTED,
                        socksHost = "127.0.0.1",
                        socksPort = DEFAULT_ORBOT_SOCKS_PORT,
                        httpPort = DEFAULT_ORBOT_HTTP_PORT
                    )
                    onComplete?.invoke(true)
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Log.w(TAG, "Orbot SOCKS port not accessible: ${e.message}")
                    updateState(
                        newState = TorState.ERROR,
                        errorMessage = "Please open Orbot and start the Tor service"
                    )
                    onComplete?.invoke(false)
                }
            }
        }.start()
    }

    /**
     * Register a broadcast receiver for Orbot status updates.
     * Uses application context to prevent activity leaks.
     */
    private fun registerOrbotStatusReceiver(context: Context, onComplete: ((Boolean) -> Unit)?) {
        if (isReceiverRegistered) {
            return
        }

        // Store application context to prevent leaks
        appContext = context.applicationContext

        orbotStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_STATUS) {
                    val status = intent.getStringExtra(EXTRA_STATUS)

                    when (status) {
                        STATUS_ON -> {
                            val host = intent.getStringExtra(EXTRA_SOCKS_PROXY_HOST) ?: "127.0.0.1"
                            val socksPort = intent.getIntExtra(EXTRA_SOCKS_PROXY_PORT, DEFAULT_ORBOT_SOCKS_PORT)
                            val httpPort = intent.getIntExtra(EXTRA_HTTP_PROXY_PORT, DEFAULT_ORBOT_HTTP_PORT)

                            updateState(
                                newState = TorState.CONNECTED,
                                socksHost = host,
                                socksPort = socksPort,
                                httpPort = httpPort
                            )
                            onComplete?.invoke(true)
                        }
                        STATUS_OFF -> {
                            updateState(
                                newState = TorState.STOPPED,
                                socksPort = -1,
                                httpPort = -1
                            )
                        }
                        STATUS_STARTING -> {
                            updateState(newState = TorState.STARTING)
                        }
                        STATUS_STOPPING -> {
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_STATUS)
        // Use application context to prevent activity leaks
        val appCtx = appContext ?: context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Use RECEIVER_EXPORTED to receive broadcasts from Orbot (external app)
            appCtx.registerReceiver(orbotStatusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appCtx.registerReceiver(orbotStatusReceiver, filter)
        }
        isReceiverRegistered = true
    }

    /**
     * Stop listening for Orbot status updates.
     * Note: This doesn't stop Orbot itself - the user controls Orbot separately.
     */
    fun stop() {
        updateState(
            newState = TorState.STOPPED,
            socksPort = -1,
            httpPort = -1,
            errorMessage = null
        )
    }

    /**
     * Unregister the Orbot status receiver.
     * Call this when the app is being destroyed.
     * Uses stored application context to prevent leaks.
     */
    fun cleanup(context: Context = appContext ?: throw IllegalStateException("No context available")) {
        stopHealthCheck()
        if (isReceiverRegistered && orbotStatusReceiver != null) {
            try {
                // Use application context for unregistering (same context used for registration)
                val ctx = appContext ?: context.applicationContext
                ctx.unregisterReceiver(orbotStatusReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was already unregistered - this is fine
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering Orbot receiver", e)
            } finally {
                // Always mark as unregistered and clear reference
                isReceiverRegistered = false
                orbotStatusReceiver = null
            }
        }
    }

    /**
     * Initialize and check for Orbot status.
     * Call this when the app starts to detect if Orbot is already running.
     *
     * CRITICAL: This ALWAYS performs a socket check to ensure instantaneous leak detection.
     * UI components handle rapid state transitions via debouncing.
     */
    fun initialize(context: Context) {
        // Register receiver first (even if Orbot check fails, the socket check will run)
        registerOrbotStatusReceiver(context, null)

        // Request current status from Orbot - this includes a socket check
        // which provides INSTANT leak detection (socket check completes in ~1-100ms)
        requestOrbotStatus(context)

        // Only mark as not installed if BOTH the package check fails AND
        // we're currently in a non-connected state. This prevents false positives
        // during activity recreation when PackageManager might be slow to respond
        // but Tor is actually running.
        if (!isOrbotInstalled(context) && currentState.state != TorState.CONNECTED) {
            updateState(
                newState = TorState.ORBOT_NOT_INSTALLED,
                socksPort = -1,
                httpPort = -1
            )
        }
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
                    if (currentState.state != TorState.CONNECTED) {
                        updateState(
                            newState = TorState.CONNECTED,
                            socksHost = "127.0.0.1",
                            socksPort = DEFAULT_ORBOT_SOCKS_PORT,
                            httpPort = DEFAULT_ORBOT_HTTP_PORT
                        )
                    }
                }
            } catch (e: Exception) {
                // Orbot not running or not accessible - update state regardless of current state
                Handler(Looper.getMainLooper()).post {
                    val state = currentState.state
                    if (state == TorState.CONNECTED || state == TorState.STARTING) {
                        updateState(
                            newState = TorState.STOPPED,
                            socksPort = -1,
                            httpPort = -1
                        )
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
        val state = currentState
        return state.state == TorState.CONNECTED && state.socksPort > 0
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

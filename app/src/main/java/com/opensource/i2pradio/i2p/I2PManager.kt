package com.opensource.i2pradio.i2p

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Manager for I2P HTTP proxy connectivity detection.
 *
 * This singleton maintains a cached connection state with periodic background
 * health checks, providing instant availability checks without blocking.
 *
 * Unlike TorManager which receives broadcast intents from InviZible Pro,
 * I2PManager proactively checks if the I2P HTTP proxy is reachable via
 * socket connection tests.
 */
object I2PManager {
    private const val TAG = "I2PManager"

    // Default I2P HTTP proxy settings
    private const val DEFAULT_I2P_HOST = "127.0.0.1"
    private const val DEFAULT_I2P_PORT = 4444

    // Health check interval (30 seconds)
    private const val HEALTH_CHECK_INTERVAL = 30_000L

    // Socket connection timeout for health checks (short to avoid blocking)
    private const val SOCKET_TIMEOUT_MS = 1500

    // Connection state
    enum class I2PState {
        UNKNOWN,      // Initial state, not yet checked
        AVAILABLE,    // I2P proxy is reachable
        UNAVAILABLE   // I2P proxy is not reachable
    }

    @Volatile
    private var _state: I2PState = I2PState.UNKNOWN
    val state: I2PState get() = _state

    @Volatile
    private var _host: String = DEFAULT_I2P_HOST

    @Volatile
    private var _port: Int = DEFAULT_I2P_PORT

    // Last check timestamp for debugging
    @Volatile
    private var lastCheckTime: Long = 0

    // Listeners for state changes (thread-safe)
    private val stateListeners = CopyOnWriteArrayList<(I2PState) -> Unit>()

    // Handler for periodic health checks on main thread
    private val healthCheckHandler = Handler(Looper.getMainLooper())

    // Single-thread executor for socket operations (non-blocking)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "I2PManager-HealthCheck").apply { isDaemon = true }
    }

    // Health check runnable
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            checkAvailabilityAsync()
            healthCheckHandler.postDelayed(this, HEALTH_CHECK_INTERVAL)
        }
    }

    private var isHealthCheckRunning = false

    /**
     * Initialize I2PManager and start periodic health checks.
     * Should be called on app startup (e.g., in MainActivity.onCreate).
     */
    fun initialize(host: String = DEFAULT_I2P_HOST, port: Int = DEFAULT_I2P_PORT) {
        _host = host
        _port = port
        Log.d(TAG, "Initializing I2PManager for $host:$port")
        startHealthChecks()
    }

    /**
     * Start periodic health checks.
     */
    fun startHealthChecks() {
        if (isHealthCheckRunning) {
            Log.d(TAG, "Health checks already running")
            return
        }
        isHealthCheckRunning = true
        Log.d(TAG, "Starting I2P health checks (interval: ${HEALTH_CHECK_INTERVAL}ms)")
        // Run first check immediately
        healthCheckHandler.post(healthCheckRunnable)
    }

    /**
     * Stop periodic health checks.
     */
    fun stopHealthChecks() {
        isHealthCheckRunning = false
        healthCheckHandler.removeCallbacks(healthCheckRunnable)
        Log.d(TAG, "Stopped I2P health checks")
    }

    /**
     * Check if I2P proxy is currently available.
     * This is an instant check using the cached state.
     */
    fun isAvailable(): Boolean {
        return _state == I2PState.AVAILABLE
    }

    /**
     * Get current host being monitored.
     */
    fun getHost(): String = _host

    /**
     * Get current port being monitored.
     */
    fun getPort(): Int = _port

    /**
     * Force an immediate availability check (async).
     * Results will be delivered via state listeners.
     */
    fun checkNow() {
        checkAvailabilityAsync()
    }

    /**
     * Add a listener for state changes.
     */
    fun addStateListener(listener: (I2PState) -> Unit) {
        stateListeners.add(listener)
    }

    /**
     * Remove a state listener.
     */
    fun removeStateListener(listener: (I2PState) -> Unit) {
        stateListeners.remove(listener)
    }

    /**
     * Perform async availability check on background thread.
     */
    private fun checkAvailabilityAsync() {
        executor.execute {
            val available = checkSocketConnection(_host, _port)
            val newState = if (available) I2PState.AVAILABLE else I2PState.UNAVAILABLE
            lastCheckTime = System.currentTimeMillis()

            if (newState != _state) {
                Log.d(TAG, "I2P state changed: $_state -> $newState")
                _state = newState
                // Notify listeners on main thread
                Handler(Looper.getMainLooper()).post {
                    notifyStateChange(newState)
                }
            } else {
                Log.v(TAG, "I2P state unchanged: $newState")
            }
        }
    }

    /**
     * Check if socket connection to host:port is possible.
     */
    private fun checkSocketConnection(host: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            socket.close()
            Log.d(TAG, "I2P proxy check PASSED - $host:$port is reachable")
            true
        } catch (e: Exception) {
            Log.d(TAG, "I2P proxy check FAILED - $host:$port not reachable: ${e.message}")
            false
        }
    }

    /**
     * Notify all listeners of state change.
     */
    private fun notifyStateChange(newState: I2PState) {
        stateListeners.forEach { listener ->
            try {
                listener(newState)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying state listener", e)
            }
        }
    }

    /**
     * Get a human-readable status message.
     */
    fun getStatusMessage(): String {
        return when (_state) {
            I2PState.UNKNOWN -> "Checking I2P availability..."
            I2PState.AVAILABLE -> "I2P proxy available at $_host:$_port"
            I2PState.UNAVAILABLE -> "I2P is not running"
        }
    }
}

package com.opensource.i2pradio.i2p

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manager for I2P connectivity detection.
 *
 * Unlike Tor which has Orbot as a standard Android client, I2P requires users
 * to run their own I2P router (typically via I2P Android app or a desktop router
 * with the HTTP proxy accessible).
 *
 * This manager provides:
 * - Detection of I2P HTTP proxy availability (default port 4444)
 * - Real-time status updates via listeners
 * - Periodic connection health checks
 *
 * The I2P HTTP proxy is typically at 127.0.0.1:4444
 */
object I2PManager {
    private const val TAG = "I2PManager"

    // Default I2P HTTP proxy settings
    const val DEFAULT_I2P_PROXY_HOST = "127.0.0.1"
    const val DEFAULT_I2P_PROXY_PORT = 4444

    // Connection health check interval (30 seconds)
    private const val HEALTH_CHECK_INTERVAL = 30_000L

    // Socket connection timeout (ms)
    private const val SOCKET_TIMEOUT = 2000

    // Connection state
    enum class I2PState {
        UNKNOWN,      // Haven't checked yet
        AVAILABLE,    // I2P proxy is responding
        UNAVAILABLE   // I2P proxy is not responding
    }

    private var _state: I2PState = I2PState.UNKNOWN
    val state: I2PState get() = _state

    private var _proxyHost: String = DEFAULT_I2P_PROXY_HOST
    val proxyHost: String get() = _proxyHost

    private var _proxyPort: Int = DEFAULT_I2P_PROXY_PORT
    val proxyPort: Int get() = _proxyPort

    // Last check timestamp
    private var _lastCheckTime: Long = 0
    val lastCheckTime: Long get() = _lastCheckTime

    // Listeners for state changes (thread-safe)
    private val stateListeners = CopyOnWriteArrayList<(I2PState) -> Unit>()

    // Handler for periodic health checks
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private var healthCheckRunnable: Runnable? = null

    // Thread tracking for proper cleanup
    @Volatile private var checkThread: Thread? = null
    @Volatile private var isShuttingDown = false

    fun addStateListener(listener: (I2PState) -> Unit, notifyImmediately: Boolean = true) {
        stateListeners.add(listener)
        if (notifyImmediately) {
            listener(_state)
        }
    }

    fun removeStateListener(listener: (I2PState) -> Unit) {
        stateListeners.remove(listener)
    }

    private fun notifyStateChange(newState: I2PState) {
        val oldState = _state
        _state = newState
        _lastCheckTime = System.currentTimeMillis()

        if (newState == I2PState.AVAILABLE && oldState != I2PState.AVAILABLE) {
            startHealthCheck()
        } else if (newState == I2PState.UNAVAILABLE) {
            stopHealthCheck()
        }

        stateListeners.forEach { it(newState) }

        Log.d(TAG, "===== I2P STATE CHANGE =====")
        Log.d(TAG, "I2P state changed: $oldState -> $newState")
        Log.d(TAG, "HTTP proxy: $_proxyHost:$_proxyPort")
        Log.d(TAG, "Is available: ${isAvailable()}")
        Log.d(TAG, "============================")
    }

    private fun startHealthCheck() {
        stopHealthCheck()
        healthCheckRunnable = object : Runnable {
            override fun run() {
                if (_state == I2PState.AVAILABLE) {
                    checkProxyAvailability()
                    healthCheckHandler.postDelayed(this, HEALTH_CHECK_INTERVAL)
                }
            }
        }
        healthCheckHandler.postDelayed(healthCheckRunnable!!, HEALTH_CHECK_INTERVAL)
    }

    private fun stopHealthCheck() {
        healthCheckRunnable?.let { healthCheckHandler.removeCallbacks(it) }
        healthCheckRunnable = null

        checkThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
            }
        }
        checkThread = null
    }

    /**
     * Check if I2P proxy is available by attempting a socket connection.
     * This is the primary method for detecting I2P availability.
     *
     * @param onComplete Optional callback with the result (true = available)
     */
    fun checkProxyAvailability(onComplete: ((Boolean) -> Unit)? = null) {
        // Cancel any previous check thread
        checkThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
            }
        }

        checkThread = Thread {
            if (isShuttingDown) {
                onComplete?.invoke(false)
                return@Thread
            }

            Log.d(TAG, "Checking I2P proxy at $_proxyHost:$_proxyPort")

            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(_proxyHost, _proxyPort), SOCKET_TIMEOUT)
                socket.close()

                if (isShuttingDown) return@Thread

                Handler(Looper.getMainLooper()).post {
                    if (!isShuttingDown) {
                        Log.d(TAG, "I2P proxy is AVAILABLE at $_proxyHost:$_proxyPort")
                        notifyStateChange(I2PState.AVAILABLE)
                        onComplete?.invoke(true)
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "I2P proxy check interrupted (normal during cleanup)")
                onComplete?.invoke(false)
            } catch (e: Exception) {
                if (isShuttingDown) return@Thread

                Handler(Looper.getMainLooper()).post {
                    if (!isShuttingDown) {
                        Log.d(TAG, "I2P proxy is UNAVAILABLE: ${e.message}")
                        notifyStateChange(I2PState.UNAVAILABLE)
                        onComplete?.invoke(false)
                    }
                }
            }
        }
        checkThread?.start()
    }

    /**
     * Synchronously check if I2P proxy is available.
     * WARNING: This blocks the calling thread. Do not call from main thread.
     *
     * @return true if I2P proxy is available
     */
    fun checkProxyAvailabilitySync(): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(_proxyHost, _proxyPort), SOCKET_TIMEOUT)
            socket.close()
            _state = I2PState.AVAILABLE
            _lastCheckTime = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            _state = I2PState.UNAVAILABLE
            _lastCheckTime = System.currentTimeMillis()
            false
        }
    }

    /**
     * Check if I2P proxy is currently available.
     * This returns the cached state from the last check.
     */
    fun isAvailable(): Boolean {
        return _state == I2PState.AVAILABLE
    }

    /**
     * Initialize and check for I2P proxy availability.
     * Call this when the app starts to detect if I2P router is running.
     */
    fun initialize() {
        isShuttingDown = false
        checkProxyAvailability()
    }

    /**
     * Cleanup resources.
     * Call this when the app is being destroyed.
     */
    fun cleanup() {
        isShuttingDown = true
        stopHealthCheck()
    }

    /**
     * Configure custom proxy host and port.
     * Use this if the user has a non-standard I2P proxy configuration.
     */
    fun configure(host: String, port: Int) {
        _proxyHost = host
        _proxyPort = port
        // Re-check with new configuration
        checkProxyAvailability()
    }

    /**
     * Reset to default I2P proxy configuration.
     */
    fun resetToDefaults() {
        _proxyHost = DEFAULT_I2P_PROXY_HOST
        _proxyPort = DEFAULT_I2P_PROXY_PORT
        checkProxyAvailability()
    }

    /**
     * Get a user-friendly status message.
     */
    fun getStatusMessage(): String {
        return when (_state) {
            I2PState.UNKNOWN -> "I2P status unknown"
            I2PState.AVAILABLE -> "I2P proxy available"
            I2PState.UNAVAILABLE -> "I2P proxy not available"
        }
    }
}

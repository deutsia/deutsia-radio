package com.opensource.i2pradio.tor

import android.content.Context
import android.util.Log
import com.msopentech.thali.android.toronionproxy.AndroidOnionProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Singleton manager for the embedded Tor daemon.
 * Provides plug-and-play Tor connectivity for radio streams.
 */
object TorManager {
    private const val TAG = "TorManager"
    private const val TOR_FILES_DIR = "torfiles"
    private const val TOR_BINARY_NAME = "tor"
    private const val TOR_STARTUP_TIMEOUT_SECONDS = 4 * 60  // 4 minutes
    private const val TOR_STARTUP_TRIES = 5

    private var onionProxyManager: AndroidOnionProxyManager? = null
    private var startupJob: Job? = null

    // Connection state
    enum class TorState {
        STOPPED,
        STARTING,
        CONNECTED,
        ERROR
    }

    private var _state: TorState = TorState.STOPPED
    val state: TorState get() = _state

    private var _socksPort: Int = -1
    val socksPort: Int get() = _socksPort

    private var _errorMessage: String? = null
    val errorMessage: String? get() = _errorMessage

    // Listeners for state changes
    private val stateListeners = mutableListOf<(TorState) -> Unit>()

    fun addStateListener(listener: (TorState) -> Unit) {
        stateListeners.add(listener)
        // Immediately notify of current state
        listener(_state)
    }

    fun removeStateListener(listener: (TorState) -> Unit) {
        stateListeners.remove(listener)
    }

    private fun notifyStateChange(newState: TorState) {
        _state = newState
        stateListeners.forEach { it(newState) }
    }

    /**
     * Start the embedded Tor daemon asynchronously.
     * Call this from a coroutine or use the callback.
     */
    fun start(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        if (_state == TorState.STARTING || _state == TorState.CONNECTED) {
            Log.d(TAG, "Tor is already ${_state.name}")
            onComplete?.invoke(_state == TorState.CONNECTED)
            return
        }

        notifyStateChange(TorState.STARTING)
        _errorMessage = null

        startupJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Initializing Tor...")

                // Create the onion proxy manager
                val manager = AndroidOnionProxyManager(
                    context.applicationContext,
                    TOR_FILES_DIR
                )
                onionProxyManager = manager

                // Fix permissions on the Tor binary (addresses "Permission denied" error)
                ensureTorBinaryPermissions(context)
                ensureAllTorFilesPermissions(context)

                // Start Tor with retry logic
                val started = manager.startWithRepeat(
                    TOR_STARTUP_TIMEOUT_SECONDS,
                    TOR_STARTUP_TRIES
                )

                if (started) {
                    // Wait for Tor to fully initialize
                    var waitCount = 0
                    while (!manager.isRunning && waitCount < 100) {
                        Thread.sleep(100)
                        waitCount++
                    }

                    if (manager.isRunning) {
                        _socksPort = manager.iPv4LocalHostSocksPort
                        Log.d(TAG, "Tor started successfully on SOCKS port: $_socksPort")

                        withContext(Dispatchers.Main) {
                            notifyStateChange(TorState.CONNECTED)
                            onComplete?.invoke(true)
                        }
                    } else {
                        throw IOException("Tor daemon failed to enter running state")
                    }
                } else {
                    throw IOException("Failed to start Tor after $TOR_STARTUP_TRIES attempts")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Tor", e)
                _errorMessage = e.message ?: "Unknown error"
                _socksPort = -1

                withContext(Dispatchers.Main) {
                    notifyStateChange(TorState.ERROR)
                    onComplete?.invoke(false)
                }
            }
        }
    }

    /**
     * Stop the embedded Tor daemon.
     */
    fun stop() {
        Log.d(TAG, "Stopping Tor...")

        startupJob?.cancel()
        startupJob = null

        try {
            onionProxyManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Tor", e)
        }

        onionProxyManager = null
        _socksPort = -1
        _errorMessage = null
        notifyStateChange(TorState.STOPPED)

        Log.d(TAG, "Tor stopped")
    }

    /**
     * Check if Tor is currently running and connected.
     */
    fun isConnected(): Boolean {
        return _state == TorState.CONNECTED &&
               onionProxyManager?.isRunning == true &&
               _socksPort > 0
    }

    /**
     * Get the SOCKS proxy host (always localhost for embedded Tor).
     */
    fun getProxyHost(): String = "127.0.0.1"

    /**
     * Get the SOCKS proxy port, or -1 if not connected.
     */
    fun getProxyPort(): Int = if (isConnected()) _socksPort else -1

    /**
     * Restart Tor (stop then start).
     */
    fun restart(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        stop()
        start(context, onComplete)
    }

    /**
     * Ensure the Tor binary has execute permissions.
     * This fixes "Permission denied" errors on Android when running the Tor binary.
     */
    private fun ensureTorBinaryPermissions(context: Context): Boolean {
        try {
            val torDir = File(context.applicationContext.getDir(TOR_FILES_DIR, Context.MODE_PRIVATE).absolutePath)
            val torBinary = File(torDir, TOR_BINARY_NAME)

            if (torBinary.exists()) {
                // Method 1: Use Java File API
                var result = torBinary.setExecutable(true, false)
                torBinary.setReadable(true, false)
                Log.d(TAG, "Set executable permission via File API on ${torBinary.absolutePath}: $result")

                // Method 2: Also try chmod via Runtime as a fallback (more reliable on some devices)
                try {
                    val process = Runtime.getRuntime().exec("chmod 755 ${torBinary.absolutePath}")
                    val exitCode = process.waitFor()
                    Log.d(TAG, "chmod 755 on Tor binary returned: $exitCode")
                    if (exitCode == 0) {
                        result = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "chmod fallback failed (this may be normal)", e)
                }

                return result
            } else {
                Log.d(TAG, "Tor binary not yet extracted at ${torBinary.absolutePath}")
                return true // Will be extracted by the library
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set Tor binary permissions", e)
            return false
        }
    }

    /**
     * Set permissions on all binaries in the Tor files directory.
     * Called after AndroidOnionProxyManager initialization to ensure all files are executable.
     */
    private fun ensureAllTorFilesPermissions(context: Context) {
        try {
            val torDir = context.applicationContext.getDir(TOR_FILES_DIR, Context.MODE_PRIVATE)

            // Find and set permissions on all executable files
            torDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.endsWith(".conf") && !file.name.endsWith(".pid")) {
                    try {
                        file.setExecutable(true, false)
                        file.setReadable(true, false)

                        // Also try chmod
                        try {
                            Runtime.getRuntime().exec("chmod 755 ${file.absolutePath}").waitFor()
                        } catch (_: Exception) {}

                        Log.d(TAG, "Set permissions on: ${file.name}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set permissions on ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set Tor files permissions", e)
        }
    }
}

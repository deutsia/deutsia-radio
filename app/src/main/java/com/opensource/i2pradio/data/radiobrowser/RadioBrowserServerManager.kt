package com.opensource.i2pradio.data.radiobrowser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Manages RadioBrowser API server discovery and selection.
 *
 * Implements dynamic server discovery similar to RadioDroid:
 * 1. DNS lookup of all.api.radio-browser.info to get available server IPs
 * 2. Reverse DNS lookup to get server hostnames
 * 3. Fallback to API endpoint if DNS fails
 * 4. Caching with periodic refresh
 *
 * This approach ensures the app always uses up-to-date servers rather than
 * relying on a hardcoded list that may become stale.
 */
object RadioBrowserServerManager {
    private const val TAG = "RadioBrowserServerMgr"

    // DNS discovery domain
    private const val DNS_LOOKUP_HOST = "all.api.radio-browser.info"

    // Fallback API endpoint to fetch server list (if DNS fails)
    private const val FALLBACK_API_URL = "https://all.api.radio-browser.info/json/servers"

    // Hardcoded fallback servers (last resort)
    private val FALLBACK_SERVERS = listOf(
        "de1.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "fi1.api.radio-browser.info"
    )

    // Cache settings
    private const val CACHE_DURATION_MS = 30 * 60 * 1000L // 30 minutes
    private const val DNS_TIMEOUT_MS = 5000L

    // Cached server list
    private var cachedServers: List<String> = emptyList()
    private var cacheTimestamp: Long = 0
    private var currentServerIndex = 0
    private val mutex = Mutex()

    /**
     * Get the current API server to use.
     * If no servers are cached, discovers them first.
     */
    suspend fun getCurrentServer(): String = mutex.withLock {
        if (cachedServers.isEmpty() || isCacheExpired()) {
            refreshServerList()
        }

        if (cachedServers.isEmpty()) {
            Log.w(TAG, "No servers available, using fallback")
            return@withLock FALLBACK_SERVERS.first()
        }

        return@withLock cachedServers[currentServerIndex % cachedServers.size]
    }

    /**
     * Cycle to the next available server.
     * Call this when the current server fails.
     */
    suspend fun cycleToNextServer(): String = mutex.withLock {
        if (cachedServers.isEmpty()) {
            Log.w(TAG, "No servers to cycle, using fallback")
            return@withLock FALLBACK_SERVERS.first()
        }

        currentServerIndex = (currentServerIndex + 1) % cachedServers.size
        val server = cachedServers[currentServerIndex]
        Log.d(TAG, "Cycled to server: $server (index $currentServerIndex of ${cachedServers.size})")
        return@withLock server
    }

    /**
     * Get the full API base URL for the current server.
     */
    suspend fun getApiBaseUrl(): String {
        return "https://${getCurrentServer()}/json"
    }

    /**
     * Force refresh the server list.
     */
    suspend fun forceRefresh() = mutex.withLock {
        refreshServerList()
    }

    /**
     * Get all available servers (for debugging/display).
     */
    suspend fun getAllServers(): List<String> = mutex.withLock {
        if (cachedServers.isEmpty() || isCacheExpired()) {
            refreshServerList()
        }
        return@withLock cachedServers.ifEmpty { FALLBACK_SERVERS }
    }

    private fun isCacheExpired(): Boolean {
        return System.currentTimeMillis() - cacheTimestamp > CACHE_DURATION_MS
    }

    /**
     * Refresh the server list using DNS discovery with API fallback.
     */
    private suspend fun refreshServerList() {
        Log.d(TAG, "Refreshing server list...")

        // Try DNS lookup first
        var servers = discoverServersViaDns()

        // If DNS fails, try the API endpoint
        if (servers.isEmpty()) {
            Log.d(TAG, "DNS discovery failed, trying API endpoint")
            servers = discoverServersViaApi()
        }

        // If both fail, use hardcoded fallbacks
        if (servers.isEmpty()) {
            Log.w(TAG, "All discovery methods failed, using hardcoded fallbacks")
            servers = FALLBACK_SERVERS
        }

        // Shuffle servers to distribute load
        cachedServers = servers.shuffled(Random)
        currentServerIndex = 0
        cacheTimestamp = System.currentTimeMillis()

        Log.d(TAG, "Server list refreshed: ${cachedServers.size} servers available")
        cachedServers.forEachIndexed { index, server ->
            Log.d(TAG, "  [$index] $server")
        }
    }

    /**
     * Discover servers via DNS lookup of all.api.radio-browser.info.
     * This is the recommended approach from radio-browser.info.
     */
    private suspend fun discoverServersViaDns(): List<String> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<String>()

        try {
            Log.d(TAG, "Performing DNS lookup for $DNS_LOOKUP_HOST")

            // Get all IP addresses for the DNS lookup host
            val addresses = InetAddress.getAllByName(DNS_LOOKUP_HOST)
            Log.d(TAG, "DNS lookup returned ${addresses.size} addresses")

            for (address in addresses) {
                try {
                    val ip = address.hostAddress ?: continue

                    // Reverse DNS lookup to get the server hostname
                    val reverseLookup = InetAddress.getByName(ip)
                    val hostname = reverseLookup.canonicalHostName

                    // Filter out entries that are just IPs or the lookup host itself
                    if (hostname != DNS_LOOKUP_HOST &&
                        hostname != ip &&
                        hostname.contains("api.radio-browser.info")) {
                        servers.add(hostname)
                        Log.d(TAG, "Discovered server: $hostname (from IP: $ip)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reverse DNS lookup failed for ${address.hostAddress}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS discovery failed: ${e.message}")
        }

        // Remove duplicates and return
        return@withContext servers.distinct()
    }

    /**
     * Discover servers via the API endpoint (fallback if DNS fails).
     */
    private suspend fun discoverServersViaApi(): List<String> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<String>()

        try {
            Log.d(TAG, "Fetching server list from API: $FALLBACK_API_URL")

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(FALLBACK_API_URL)
                .header("User-Agent", "DeutsiaRadio/1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string()
                response.close()

                if (!body.isNullOrEmpty()) {
                    val jsonArray = JSONArray(body)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val name = obj.optString("name", "")
                        if (name.isNotEmpty() && name.contains("api.radio-browser.info")) {
                            servers.add(name)
                            Log.d(TAG, "API discovered server: $name")
                        }
                    }
                }
            } else {
                response.close()
                Log.w(TAG, "API server list request failed: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "API discovery failed: ${e.message}")
        }

        return@withContext servers
    }

    /**
     * Reset the server manager (for testing or when network changes).
     */
    suspend fun reset() = mutex.withLock {
        cachedServers = emptyList()
        cacheTimestamp = 0
        currentServerIndex = 0
        Log.d(TAG, "Server manager reset")
    }
}

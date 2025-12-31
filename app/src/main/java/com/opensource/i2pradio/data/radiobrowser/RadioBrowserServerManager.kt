package com.opensource.i2pradio.data.radiobrowser

import android.content.Context
import android.util.Log
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
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

    // Timeouts for proxied connections (longer to account for Tor latency)
    private const val PROXY_CONNECT_TIMEOUT_SECONDS = 60L
    private const val PROXY_READ_TIMEOUT_SECONDS = 60L

    // Cached server list
    private var cachedServers: List<String> = emptyList()
    private var cacheTimestamp: Long = 0
    private var currentServerIndex = 0
    private val mutex = Mutex()

    /**
     * Custom DNS resolver that forces DNS resolution through SOCKS5 proxy.
     *
     * By default, OkHttp resolves DNS locally BEFORE connecting through SOCKS,
     * which leaks DNS queries to clearnet. This resolver returns a placeholder
     * address, forcing the SOCKS5 proxy (Tor) to handle DNS resolution.
     */
    private val SOCKS5_DNS = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            Log.d(TAG, "DNS lookup for '$hostname' - delegating to SOCKS5 proxy")
            return listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
        }
    }

    /**
     * Get the current API server to use.
     * If no servers are cached, discovers them first.
     *
     * @param context Optional context for proxy-aware server discovery.
     *                When provided and force proxy mode is enabled, server discovery
     *                will respect proxy settings to prevent DNS/HTTP leaks.
     */
    suspend fun getCurrentServer(context: Context? = null): String = mutex.withLock {
        if (cachedServers.isEmpty() || isCacheExpired()) {
            refreshServerList(context)
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
     *
     * @param context Optional context for proxy-aware server discovery.
     */
    suspend fun getApiBaseUrl(context: Context? = null): String {
        return "https://${getCurrentServer(context)}/json"
    }

    /**
     * Force refresh the server list.
     *
     * @param context Optional context for proxy-aware server discovery.
     */
    suspend fun forceRefresh(context: Context? = null) = mutex.withLock {
        refreshServerList(context)
    }

    /**
     * Get all available servers (for debugging/display).
     *
     * @param context Optional context for proxy-aware server discovery.
     */
    suspend fun getAllServers(context: Context? = null): List<String> = mutex.withLock {
        if (cachedServers.isEmpty() || isCacheExpired()) {
            refreshServerList(context)
        }
        return@withLock cachedServers.ifEmpty { FALLBACK_SERVERS }
    }

    private fun isCacheExpired(): Boolean {
        return System.currentTimeMillis() - cacheTimestamp > CACHE_DURATION_MS
    }

    /**
     * Refresh the server list using DNS discovery with API fallback.
     *
     * SECURITY: When force proxy mode is enabled, this method ensures NO clearnet
     * DNS or HTTP requests are made. Discovery either uses the configured proxy
     * or falls back to hardcoded servers (no network request).
     *
     * @param context Optional context for checking proxy settings.
     */
    private suspend fun refreshServerList(context: Context?) {
        Log.d(TAG, "Refreshing server list...")

        // Check if force proxy mode is enabled
        val torEnabled = context?.let { PreferencesHelper.isEmbeddedTorEnabled(it) } ?: false
        val forceTorAll = context?.let { PreferencesHelper.isForceTorAll(it) } ?: false
        val forceTorExceptI2P = context?.let { PreferencesHelper.isForceTorExceptI2P(it) } ?: false
        val forceCustomProxy = context?.let { PreferencesHelper.isForceCustomProxy(it) } ?: false
        val forceCustomProxyExceptTorI2P = context?.let { PreferencesHelper.isForceCustomProxyExceptTorI2P(it) } ?: false

        val forceTorEnabled = torEnabled && (forceTorAll || forceTorExceptI2P)
        val forceCustomProxyEnabled = forceCustomProxy || forceCustomProxyExceptTorI2P
        val forceProxyEnabled = forceTorEnabled || forceCustomProxyEnabled

        Log.d(TAG, "Force proxy mode: $forceProxyEnabled (ForceTor: $forceTorEnabled, ForceCustomProxy: $forceCustomProxyEnabled)")

        var servers: List<String> = emptyList()

        if (forceProxyEnabled && context != null) {
            // SECURITY: Skip DNS discovery entirely in force proxy mode
            // Java's InetAddress.getAllByName() bypasses proxies and would leak DNS queries
            Log.d(TAG, "Force proxy mode enabled - skipping DNS discovery to prevent leaks")

            // Try API discovery with proper proxy routing
            servers = discoverServersViaProxiedApi(context, forceTorEnabled, forceCustomProxyEnabled)

            if (servers.isEmpty()) {
                // Proxy unavailable or API failed - use hardcoded fallbacks
                // This is safe because no network request is made
                Log.w(TAG, "Proxied API discovery failed - using hardcoded fallbacks (no network request)")
                servers = FALLBACK_SERVERS
            }
        } else {
            // Normal flow - no force proxy mode
            // Try DNS lookup first
            servers = discoverServersViaDns()

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
     * Discover servers via the API endpoint using the configured proxy.
     *
     * SECURITY: This method routes the HTTP request through Tor or custom proxy
     * to prevent IP/DNS leaks when force proxy mode is enabled.
     *
     * @param context Context for reading proxy settings
     * @param forceTorEnabled True if Force Tor mode is active
     * @param forceCustomProxyEnabled True if Force Custom Proxy mode is active
     * @return List of discovered server hostnames, or empty if proxy unavailable/failed
     */
    private suspend fun discoverServersViaProxiedApi(
        context: Context,
        forceTorEnabled: Boolean,
        forceCustomProxyEnabled: Boolean
    ): List<String> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<String>()

        try {
            val builder = OkHttpClient.Builder()
                .connectTimeout(PROXY_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PROXY_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // Priority 1: Force Tor mode
            if (forceTorEnabled) {
                if (!TorManager.isConnected()) {
                    Log.e(TAG, "BLOCKING server discovery: Force Tor enabled but Tor is NOT connected")
                    return@withContext emptyList()
                }

                val socksHost = TorManager.getProxyHost()
                val socksPort = TorManager.getProxyPort()

                if (socksPort <= 0) {
                    Log.e(TAG, "BLOCKING server discovery: Force Tor enabled but Tor proxy port invalid ($socksPort)")
                    return@withContext emptyList()
                }

                Log.d(TAG, "Routing server discovery through Tor SOCKS5 at $socksHost:$socksPort")
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)))
                builder.dns(SOCKS5_DNS)  // Force DNS through SOCKS5 to prevent leaks
            }
            // Priority 2: Force Custom Proxy mode
            else if (forceCustomProxyEnabled) {
                val proxyHost = PreferencesHelper.getCustomProxyHost(context)
                val proxyPort = PreferencesHelper.getCustomProxyPort(context)
                val proxyProtocol = PreferencesHelper.getCustomProxyProtocol(context)

                if (proxyHost.isEmpty() || proxyPort <= 0) {
                    Log.e(TAG, "BLOCKING server discovery: Force Custom Proxy enabled but proxy not configured")
                    return@withContext emptyList()
                }

                val proxyType = when (proxyProtocol.uppercase()) {
                    "SOCKS4", "SOCKS5", "SOCKS" -> Proxy.Type.SOCKS
                    else -> Proxy.Type.HTTP
                }

                Log.d(TAG, "Routing server discovery through custom $proxyProtocol proxy at $proxyHost:$proxyPort")
                builder.proxy(Proxy(proxyType, InetSocketAddress(proxyHost, proxyPort)))

                // Force DNS through SOCKS5 proxy to prevent DNS leaks
                if (proxyType == Proxy.Type.SOCKS) {
                    builder.dns(SOCKS5_DNS)
                }

                // Add proxy authentication if configured
                val proxyUsername = PreferencesHelper.getCustomProxyUsername(context)
                val proxyPassword = PreferencesHelper.getCustomProxyPassword(context)
                if (proxyUsername.isNotEmpty() && proxyPassword.isNotEmpty()) {
                    builder.proxyAuthenticator { _, response ->
                        val previousAuth = response.request.header("Proxy-Authorization")
                        if (previousAuth != null) {
                            return@proxyAuthenticator null
                        }
                        val credential = okhttp3.Credentials.basic(proxyUsername, proxyPassword)
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                }
            }

            val client = builder.build()

            Log.d(TAG, "Fetching server list from API (proxied): $FALLBACK_API_URL")

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
                            Log.d(TAG, "Proxied API discovered server: $name")
                        }
                    }
                }
            } else {
                response.close()
                Log.w(TAG, "Proxied API server list request failed: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxied API discovery failed: ${e.message}")
        }

        return@withContext servers
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

package com.opensource.i2pradio.data.radioregistry

import android.content.Context
import android.util.Log
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Client for the Radio Registry API (api.deutsia.com)
 *
 * Provides access to privacy-focused Tor and I2P radio stations.
 * Supports clearnet access, with optional Tor/custom proxy routing.
 */
class RadioRegistryClient(private val context: Context) {

    companion object {
        private const val TAG = "RadioRegistryClient"

        // API Base URLs
        private const val CLEARNET_BASE_URL = "https://api.deutsia.com"
        private const val TOR_BASE_URL = "http://hujzvles33iouzuolttpxpfan4sjaqylc2unkz5tmcobztzfwgkl57yd.onion"

        // Timeouts
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 15L
        private const val CONNECT_TIMEOUT_PROXY_SECONDS = 30L
        private const val READ_TIMEOUT_PROXY_SECONDS = 30L

        // User agent
        private const val USER_AGENT = "DeutsiaRadio/1.0 (Android; +https://github.com/deutsia/i2pradio)"

        // Default pagination
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 200

        /**
         * Custom DNS resolver for SOCKS5 proxy to prevent DNS leaks.
         * Returns a placeholder address, forcing the SOCKS5 proxy to handle DNS resolution.
         */
        private val SOCKS5_DNS = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                Log.d(TAG, "DNS lookup for '$hostname' - delegating to SOCKS5 proxy")
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
            }
        }
    }

    /**
     * Build an OkHttpClient respecting Force Tor and Force Custom Proxy settings.
     *
     * @return OkHttpClient configured with appropriate proxy, or null if Force Tor is enabled
     *         but Tor is not available (to prevent IP leaks)
     */
    private fun buildHttpClient(): Pair<OkHttpClient?, String> {
        val builder = OkHttpClient.Builder()

        val torEnabled = PreferencesHelper.isEmbeddedTorEnabled(context)
        val forceTorAll = PreferencesHelper.isForceTorAll(context)
        val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(context)
        val forceCustomProxy = PreferencesHelper.isForceCustomProxy(context)
        val forceCustomProxyExceptTorI2P = PreferencesHelper.isForceCustomProxyExceptTorI2P(context)
        val torConnected = TorManager.isConnected()

        Log.d(TAG, "Building HTTP client - ForceTorAll: $forceTorAll, ForceTorExceptI2P: $forceTorExceptI2P, " +
                "ForceCustomProxy: $forceCustomProxy, TorEnabled: $torEnabled, TorConnected: $torConnected")

        // Priority 1: Force Tor mode
        if (torEnabled && (forceTorAll || forceTorExceptI2P)) {
            if (!torConnected) {
                Log.e(TAG, "BLOCKING Radio Registry API request: Force Tor enabled but Tor is NOT connected")
                return Pair(null, CLEARNET_BASE_URL)
            }

            val socksHost = TorManager.getProxyHost()
            val socksPort = TorManager.getProxyPort()

            if (socksPort > 0) {
                Log.d(TAG, "Routing Radio Registry API through Tor SOCKS5 proxy at $socksHost:$socksPort")
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)))
                builder.dns(SOCKS5_DNS)
                builder.connectTimeout(CONNECT_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
                builder.readTimeout(READ_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
                // Use the Tor onion service for maximum privacy
                return Pair(builder.build(), TOR_BASE_URL)
            } else {
                Log.e(TAG, "BLOCKING Radio Registry API request: Force Tor enabled but Tor proxy port invalid")
                return Pair(null, CLEARNET_BASE_URL)
            }
        }
        // Priority 2: Force Custom Proxy
        else if (forceCustomProxy || forceCustomProxyExceptTorI2P) {
            val proxyHost = PreferencesHelper.getCustomProxyHost(context)
            val proxyPort = PreferencesHelper.getCustomProxyPort(context)
            val proxyProtocol = PreferencesHelper.getCustomProxyProtocol(context)
            val proxyUsername = PreferencesHelper.getCustomProxyUsername(context)
            val proxyPassword = PreferencesHelper.getCustomProxyPassword(context)

            if (proxyHost.isNotEmpty() && proxyPort > 0) {
                val proxyType = when (proxyProtocol.uppercase()) {
                    "SOCKS4", "SOCKS5", "SOCKS" -> Proxy.Type.SOCKS
                    "HTTP", "HTTPS" -> Proxy.Type.HTTP
                    else -> Proxy.Type.HTTP
                }

                Log.d(TAG, "Routing Radio Registry API through CUSTOM proxy ($proxyProtocol) at $proxyHost:$proxyPort")
                builder.proxy(Proxy(proxyType, InetSocketAddress(proxyHost, proxyPort)))

                if (proxyType == Proxy.Type.SOCKS) {
                    builder.dns(SOCKS5_DNS)
                }

                // Add proxy authentication if configured
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

                builder.connectTimeout(CONNECT_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
                builder.readTimeout(READ_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
                return Pair(builder.build(), CLEARNET_BASE_URL)
            } else {
                Log.e(TAG, "BLOCKING Radio Registry API request: Force Custom Proxy enabled but not configured")
                return Pair(null, CLEARNET_BASE_URL)
            }
        }
        // Priority 3: Use Tor if available (opportunistic, for privacy)
        else if (torEnabled && torConnected) {
            val socksHost = TorManager.getProxyHost()
            val socksPort = TorManager.getProxyPort()

            if (socksPort > 0) {
                Log.d(TAG, "Opportunistically routing Radio Registry API through Tor")
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)))
                builder.dns(SOCKS5_DNS)
                builder.connectTimeout(CONNECT_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
                builder.readTimeout(READ_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
                return Pair(builder.build(), TOR_BASE_URL)
            }
        }

        // Default: Direct connection to clearnet
        Log.d(TAG, "Using direct connection for Radio Registry API")
        builder.connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        builder.readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return Pair(builder.build(), CLEARNET_BASE_URL)
    }

    /**
     * Execute an API request
     */
    private suspend fun <T> executeRequest(
        endpoint: String,
        parser: (String) -> T
    ): RadioRegistryResult<T> {
        return withContext(Dispatchers.IO) {
            try {
                val (client, baseUrl) = buildHttpClient()

                if (client == null) {
                    Log.e(TAG, "API request blocked: proxy required but not available")
                    return@withContext RadioRegistryResult.Error(
                        "Request blocked: Tor/proxy not connected. Enable Tor or disable Force Tor mode.",
                        null
                    )
                }

                val url = "$baseUrl/api$endpoint"
                Log.d(TAG, "API Request: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "API request failed with code: ${response.code}")
                    response.close()
                    return@withContext RadioRegistryResult.Error("HTTP ${response.code}")
                }

                val body = response.body?.string()
                response.close()

                if (body.isNullOrEmpty()) {
                    Log.w(TAG, "Empty response body")
                    return@withContext RadioRegistryResult.Error("Empty response")
                }

                val result = parser(body)
                RadioRegistryResult.Success(result)

            } catch (e: Exception) {
                Log.e(TAG, "API request failed: ${e.message}")
                RadioRegistryResult.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    /**
     * Get list of stations with optional filtering
     *
     * @param network Filter by network: "tor" or "i2p" (null for all)
     * @param genre Filter by genre
     * @param limit Maximum number of stations (max 200)
     * @param offset Pagination offset
     */
    suspend fun getStations(
        network: String? = null,
        genre: String? = null,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0
    ): RadioRegistryResult<StationListResponse> {
        val params = buildString {
            append("/stations?limit=${limit.coerceAtMost(MAX_LIMIT)}")
            append("&offset=$offset")
            network?.let { append("&network=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            genre?.let { append("&genre=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        }

        return executeRequest(params) { body ->
            StationListResponse.fromJson(JSONObject(body))
        }
    }

    /**
     * Get all Tor stations
     */
    suspend fun getTorStations(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0
    ): RadioRegistryResult<StationListResponse> {
        return getStations(network = "tor", limit = limit, offset = offset)
    }

    /**
     * Get all I2P stations
     */
    suspend fun getI2pStations(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0
    ): RadioRegistryResult<StationListResponse> {
        return getStations(network = "i2p", limit = limit, offset = offset)
    }

    /**
     * Get a single station by ID
     */
    suspend fun getStation(stationId: String): RadioRegistryResult<RadioRegistryStation> {
        return executeRequest("/stations/$stationId") { body ->
            RadioRegistryStation.fromJson(JSONObject(body))
        }
    }

    /**
     * Get API statistics
     */
    suspend fun getStats(): RadioRegistryResult<StatsResponse> {
        return executeRequest("/stats") { body ->
            StatsResponse.fromJson(JSONObject(body))
        }
    }

    /**
     * Get list of all genres
     */
    suspend fun getGenres(): RadioRegistryResult<List<String>> {
        return executeRequest("/genres") { body ->
            val jsonArray = JSONArray(body)
            val genres = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val genre = jsonArray.optString(i, "")
                if (genre.isNotEmpty()) {
                    genres.add(genre)
                }
            }
            genres
        }
    }

    /**
     * Check API health
     */
    suspend fun checkHealth(): RadioRegistryResult<Boolean> {
        return executeRequest("/health") { body ->
            val json = JSONObject(body)
            json.optString("status", "") == "ok" && json.optBoolean("database", false)
        }
    }

    /**
     * Check if Force Tor is enabled but Tor is not connected
     */
    fun isTorRequiredButNotConnected(): Boolean {
        val torIntegrationEnabled = PreferencesHelper.isEmbeddedTorEnabled(context)
        if (!torIntegrationEnabled) {
            return false
        }

        val forceTorAll = PreferencesHelper.isForceTorAll(context)
        val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(context)
        val torConnected = TorManager.isConnected()

        return (forceTorAll || forceTorExceptI2P) && !torConnected
    }
}

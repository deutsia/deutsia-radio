package com.opensource.i2pradio.data.radiobrowser

import android.content.Context
import android.util.Log
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client for the RadioBrowser API (radio-browser.info)
 *
 * Supports proxy routing through Tor when Force Tor settings are enabled,
 * ensuring metadata fetches don't leak user's real IP.
 *
 * IMPORTANT: In non-force Tor mode (Tor enabled but Force Tor disabled),
 * API calls go directly via clearnet for best performance. Only when
 * Force Tor All or Force Tor Except I2P is enabled will API calls route
 * through the Tor SOCKS proxy.
 */
class RadioBrowserClient(private val context: Context) {

    companion object {
        private const val TAG = "RadioBrowserClient"

        // RadioBrowser API servers (use multiple for redundancy)
        private val API_SERVERS = listOf(
            "de1.api.radio-browser.info",
            "nl1.api.radio-browser.info",
            "at1.api.radio-browser.info"
        )

        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val CONNECT_TIMEOUT_PROXY_SECONDS = 60L
        private const val READ_TIMEOUT_PROXY_SECONDS = 60L

        // User agent to identify our app to RadioBrowser
        private const val USER_AGENT = "DeutsiaRadio/1.0 (Android; +https://github.com/deutsia/i2pradio)"

        // DNS cache TTL in milliseconds (5 minutes)
        private const val DNS_CACHE_TTL_MS = 5 * 60 * 1000L

        // Cached HTTP client for connection reuse
        @Volatile
        private var cachedClient: OkHttpClient? = null
        @Volatile
        private var cachedClientConfig: ClientConfig? = null

        // DNS cache to avoid repeated lookups when system DNS is slow/flaky
        private val dnsCache = ConcurrentHashMap<String, DnsCacheEntry>()

        private data class DnsCacheEntry(
            val addresses: List<InetAddress>,
            val timestamp: Long
        )

        /**
         * Invalidate the cached HTTP client.
         * Call this when proxy settings change.
         */
        fun invalidateClient() {
            cachedClient = null
            cachedClientConfig = null
            Log.d(TAG, "HTTP client cache invalidated")
        }

        /**
         * Clear the DNS cache.
         */
        fun clearDnsCache() {
            dnsCache.clear()
            Log.d(TAG, "DNS cache cleared")
        }
    }

    /**
     * Custom DNS resolver with caching.
     * Caches DNS results to survive momentary DNS hiccups that can occur
     * when running alongside VPN apps like InviZible Pro.
     */
    private val cachingDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // Check cache first
            val cached = dnsCache[hostname]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < DNS_CACHE_TTL_MS) {
                Log.d(TAG, "DNS cache hit for $hostname")
                return cached.addresses
            }

            // Perform actual DNS lookup
            return try {
                val addresses = Dns.SYSTEM.lookup(hostname)
                // Cache the result
                dnsCache[hostname] = DnsCacheEntry(addresses, System.currentTimeMillis())
                Log.d(TAG, "DNS resolved $hostname -> ${addresses.size} addresses (cached)")
                addresses
            } catch (e: UnknownHostException) {
                // If we have a stale cache entry, use it as fallback
                if (cached != null) {
                    Log.w(TAG, "DNS lookup failed for $hostname, using stale cache")
                    return cached.addresses
                }
                throw e
            }
        }
    }

    /**
     * Configuration snapshot for caching decisions
     */
    private data class ClientConfig(
        val useTorProxy: Boolean,
        val torHost: String,
        val torPort: Int,
        val useCustomProxy: Boolean,
        val customHost: String,
        val customPort: Int,
        val customProtocol: String,
        val customUsername: String,
        val customPassword: String
    )

    private var currentServerIndex = 0

    /**
     * Get the current client configuration based on settings.
     */
    private fun getCurrentClientConfig(): ClientConfig {
        val torEnabled = PreferencesHelper.isEmbeddedTorEnabled(context)
        val forceTorAll = PreferencesHelper.isForceTorAll(context)
        val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(context)
        val forceCustomProxy = PreferencesHelper.isForceCustomProxy(context)
        val forceCustomProxyExceptTorI2P = PreferencesHelper.isForceCustomProxyExceptTorI2P(context)
        val torConnected = TorManager.isConnected()

        // Determine if we should use Tor proxy
        val useTorProxy = torEnabled && (forceTorAll || forceTorExceptI2P) && torConnected
        val torHost = if (useTorProxy) TorManager.getProxyHost() else ""
        val torPort = if (useTorProxy) TorManager.getProxyPort() else 0

        // Determine if we should use custom proxy (when Tor is not being used)
        val useCustomProxy = !useTorProxy && (forceCustomProxy || forceCustomProxyExceptTorI2P)

        return ClientConfig(
            useTorProxy = useTorProxy && torPort > 0,
            torHost = torHost,
            torPort = torPort,
            useCustomProxy = useCustomProxy,
            customHost = if (useCustomProxy) PreferencesHelper.getCustomProxyHost(context) else "",
            customPort = if (useCustomProxy) PreferencesHelper.getCustomProxyPort(context) else 0,
            customProtocol = if (useCustomProxy) PreferencesHelper.getCustomProxyProtocol(context) else "",
            customUsername = if (useCustomProxy) PreferencesHelper.getCustomProxyUsername(context) else "",
            customPassword = if (useCustomProxy) PreferencesHelper.getCustomProxyPassword(context) else ""
        )
    }

    /**
     * Build an OkHttpClient respecting both Force Tor and Force Custom Proxy settings.
     *
     * IMPORTANT: This ensures RadioBrowser API calls respect the same proxy settings
     * as media streams to prevent privacy leaks.
     *
     * NOTE: In non-force Tor mode (Tor integration enabled but Force Tor disabled),
     * API calls use DIRECT clearnet connection for best performance. The Tor integration
     * setting alone does not route API calls through Tor - only Force Tor settings do.
     *
     * The client is cached and reused for better connection pooling and performance.
     * Cache is invalidated when proxy settings change.
     */
    private fun buildHttpClient(): OkHttpClient {
        val currentConfig = getCurrentClientConfig()

        // Return cached client if configuration hasn't changed
        cachedClient?.let { client ->
            if (cachedClientConfig == currentConfig) {
                Log.d(TAG, "Reusing cached HTTP client")
                return client
            }
        }

        Log.d(TAG, "Building new HTTP client - useTorProxy: ${currentConfig.useTorProxy}, " +
                "useCustomProxy: ${currentConfig.useCustomProxy}")

        val builder = OkHttpClient.Builder()

        // Priority 1: Force Tor (if Tor integration is enabled and Force Tor is active)
        if (currentConfig.useTorProxy) {
            Log.d(TAG, "Routing RadioBrowser API through Tor SOCKS5 proxy at ${currentConfig.torHost}:${currentConfig.torPort}")
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(currentConfig.torHost, currentConfig.torPort)))
            builder.connectTimeout(CONNECT_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
            builder.readTimeout(READ_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
        }
        // Priority 2: Force Custom Proxy for clearnet (RadioBrowser API is clearnet)
        else if (currentConfig.useCustomProxy && currentConfig.customHost.isNotEmpty() && currentConfig.customPort > 0) {
            val proxyType = when (currentConfig.customProtocol.uppercase()) {
                "SOCKS4", "SOCKS5", "SOCKS" -> Proxy.Type.SOCKS
                "HTTP", "HTTPS" -> Proxy.Type.HTTP
                else -> Proxy.Type.HTTP
            }

            Log.d(TAG, "Routing RadioBrowser API through CUSTOM proxy (${currentConfig.customProtocol}) at ${currentConfig.customHost}:${currentConfig.customPort}")
            builder.proxy(Proxy(proxyType, InetSocketAddress(currentConfig.customHost, currentConfig.customPort)))

            // Add proxy authentication if credentials are configured
            if (currentConfig.customUsername.isNotEmpty() && currentConfig.customPassword.isNotEmpty()) {
                Log.d(TAG, "Adding proxy authentication for RadioBrowser API")
                builder.proxyAuthenticator { _, response ->
                    // Check if we've already tried authentication to avoid infinite loops
                    val previousAuth = response.request.header("Proxy-Authorization")
                    if (previousAuth != null) {
                        Log.w(TAG, "Proxy authentication already attempted - credentials may be incorrect")
                        return@proxyAuthenticator null
                    }

                    val credential = okhttp3.Credentials.basic(currentConfig.customUsername, currentConfig.customPassword)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }

            // Use longer timeouts for proxy connections
            builder.connectTimeout(CONNECT_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
            builder.readTimeout(READ_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
        } else {
            // No forced proxy - use DIRECT clearnet connection for best performance
            // This is the expected path for "Tor mode (non-force)" - API goes via clearnet
            Log.d(TAG, "Using DIRECT clearnet connection for RadioBrowser API (no Force proxy enabled)")
            // Use caching DNS resolver to survive momentary DNS hiccups
            // This is important when running alongside VPN apps like InviZible Pro
            builder.dns(cachingDns)
            builder.connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            builder.readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        val client = builder.build()

        // Cache the client and configuration
        cachedClient = client
        cachedClientConfig = currentConfig

        return client
    }

    /**
     * Get the current API base URL, cycling through servers on failure
     */
    private fun getApiBaseUrl(): String {
        return "https://${API_SERVERS[currentServerIndex]}/json"
    }

    /**
     * Cycle to the next API server
     */
    private fun cycleServer() {
        currentServerIndex = (currentServerIndex + 1) % API_SERVERS.size
        Log.d(TAG, "Cycled to API server: ${API_SERVERS[currentServerIndex]}")
    }

    /**
     * Execute an API request with retry logic and exponential backoff.
     *
     * Uses exponential backoff (500ms, 1s, 2s) between retries to allow
     * network/DNS to recover from momentary hiccups. This is critical when
     * running alongside apps like InviZible Pro that may affect network timing.
     */
    private suspend fun executeRequest(endpoint: String, retries: Int = 2): RadioBrowserResult<List<RadioBrowserStation>> {
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            val baseDelayMs = 500L  // Start with 500ms delay

            repeat(retries + 1) { attempt ->
                try {
                    val client = buildHttpClient()
                    val url = "${getApiBaseUrl()}$endpoint"

                    Log.d(TAG, "API Request (attempt ${attempt + 1}): $url")

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
                        if (attempt < retries) {
                            // Exponential backoff before retry
                            val delayMs = baseDelayMs * (1 shl attempt)  // 500ms, 1s, 2s...
                            Log.d(TAG, "Waiting ${delayMs}ms before retry...")
                            Thread.sleep(delayMs)
                            cycleServer()
                        }
                        lastException = Exception("HTTP ${response.code}")
                        return@repeat
                    }

                    val body = response.body?.string()
                    response.close()

                    if (body.isNullOrEmpty()) {
                        Log.w(TAG, "Empty response body")
                        lastException = Exception("Empty response")
                        if (attempt < retries) {
                            val delayMs = baseDelayMs * (1 shl attempt)
                            Log.d(TAG, "Waiting ${delayMs}ms before retry...")
                            Thread.sleep(delayMs)
                            cycleServer()
                        }
                        return@repeat
                    }

                    val stations = parseStationsJson(body)
                    Log.d(TAG, "Successfully parsed ${stations.size} stations")
                    return@withContext RadioBrowserResult.Success(stations)

                } catch (e: Exception) {
                    Log.e(TAG, "API request failed (attempt ${attempt + 1}): ${e.message}")
                    lastException = e
                    if (attempt < retries) {
                        // Exponential backoff: 500ms, 1s, 2s...
                        // This gives network/DNS time to recover from hiccups
                        val delayMs = baseDelayMs * (1 shl attempt)
                        Log.d(TAG, "Waiting ${delayMs}ms before retry...")
                        Thread.sleep(delayMs)
                        cycleServer()
                    }
                }
            }

            RadioBrowserResult.Error(
                lastException?.message ?: "Unknown error",
                lastException
            )
        }
    }

    /**
     * Parse JSON array of stations
     */
    private fun parseStationsJson(json: String): List<RadioBrowserStation> {
        val stations = mutableListOf<RadioBrowserStation>()
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                try {
                    val station = RadioBrowserStation.fromJson(jsonArray.getJSONObject(i))
                    // Filter out stations with empty names or URLs
                    if (station.name.isNotEmpty() && station.getStreamUrl().isNotEmpty()) {
                        stations.add(station)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse station at index $i: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stations JSON: ${e.message}")
        }
        return stations
    }

    /**
     * Search for stations by name
     */
    suspend fun searchByName(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        order: String = "votes",
        reverse: Boolean = true,
        hidebroken: Boolean = true
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val params = buildString {
            append("/stations/byname/${java.net.URLEncoder.encode(query, "UTF-8")}")
            append("?limit=$limit")
            append("&offset=$offset")
            append("&order=$order")
            append("&reverse=$reverse")
            append("&hidebroken=$hidebroken")
        }
        return executeRequest(params)
    }

    /**
     * Search stations with advanced filters
     */
    suspend fun searchStations(
        name: String? = null,
        tag: String? = null,
        country: String? = null,
        countrycode: String? = null,
        language: String? = null,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        order: String = "votes",
        reverse: Boolean = true,
        hidebroken: Boolean = true
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val params = buildString {
            append("/stations/search")
            append("?limit=$limit")
            append("&offset=$offset")
            append("&order=$order")
            append("&reverse=$reverse")
            append("&hidebroken=$hidebroken")
            name?.let { append("&name=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            tag?.let { append("&tag=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            country?.let { append("&country=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            countrycode?.let { append("&countrycode=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            language?.let { append("&language=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        }
        return executeRequest(params)
    }

    /**
     * Get top voted stations
     */
    suspend fun getTopVoted(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        hidebroken: Boolean = true
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val params = "/stations/topvote/$limit?offset=$offset&hidebroken=$hidebroken"
        return executeRequest(params)
    }

    /**
     * Get top clicked stations (popular)
     */
    suspend fun getTopClicked(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        hidebroken: Boolean = true
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val params = "/stations/topclick/$limit?offset=$offset&hidebroken=$hidebroken"
        return executeRequest(params)
    }

    /**
     * Get recently changed/updated stations
     */
    suspend fun getRecentlyChanged(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        hidebroken: Boolean = true
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val params = "/stations/lastchange/$limit?offset=$offset&hidebroken=$hidebroken"
        return executeRequest(params)
    }

    /**
     * Get stations by country code (ISO 3166-1 alpha-2)
     */
    suspend fun getByCountryCode(
        countryCode: String,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        order: String = "votes",
        reverse: Boolean = true,
        hidebroken: Boolean = true
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val params = buildString {
            append("/stations/bycountrycodeexact/${countryCode.uppercase()}")
            append("?limit=$limit")
            append("&offset=$offset")
            append("&order=$order")
            append("&reverse=$reverse")
            append("&hidebroken=$hidebroken")
        }
        return executeRequest(params)
    }

    /**
     * Get stations by tag/genre
     */
    suspend fun getByTag(
        tag: String,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        order: String = "votes",
        reverse: Boolean = true,
        hidebroken: Boolean = true
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val params = buildString {
            append("/stations/bytag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
            append("?limit=$limit")
            append("&offset=$offset")
            append("&order=$order")
            append("&reverse=$reverse")
            append("&hidebroken=$hidebroken")
        }
        return executeRequest(params)
    }

    /**
     * Get a station by its UUID
     */
    suspend fun getByUuid(uuid: String): RadioBrowserResult<List<RadioBrowserStation>> {
        val params = "/stations/byuuid/$uuid"
        return executeRequest(params)
    }

    /**
     * Get list of available countries with station counts
     */
    suspend fun getCountries(): RadioBrowserResult<List<CountryInfo>> {
        return withContext(Dispatchers.IO) {
            try {
                val client = buildHttpClient()
                val url = "${getApiBaseUrl()}/countries?order=stationcount&reverse=true&hidebroken=true"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    response.close()
                    return@withContext RadioBrowserResult.Error("HTTP ${response.code}")
                }

                val body = response.body?.string()
                response.close()

                if (body.isNullOrEmpty()) {
                    return@withContext RadioBrowserResult.Error("Empty response")
                }

                val countries = mutableListOf<CountryInfo>()
                val jsonArray = JSONArray(body)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    countries.add(
                        CountryInfo(
                            name = obj.optString("name", ""),
                            iso3166_1 = obj.optString("iso_3166_1", ""),
                            stationCount = obj.optInt("stationcount", 0)
                        )
                    )
                }

                RadioBrowserResult.Success(countries)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch countries: ${e.message}")
                RadioBrowserResult.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    /**
     * Get list of available tags with station counts
     */
    suspend fun getTags(limit: Int = 100): RadioBrowserResult<List<TagInfo>> {
        return withContext(Dispatchers.IO) {
            try {
                val client = buildHttpClient()
                val url = "${getApiBaseUrl()}/tags?order=stationcount&reverse=true&limit=$limit&hidebroken=true"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    response.close()
                    return@withContext RadioBrowserResult.Error("HTTP ${response.code}")
                }

                val body = response.body?.string()
                response.close()

                if (body.isNullOrEmpty()) {
                    return@withContext RadioBrowserResult.Error("Empty response")
                }

                val tags = mutableListOf<TagInfo>()
                val jsonArray = JSONArray(body)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "")
                    if (name.isNotEmpty()) {
                        tags.add(
                            TagInfo(
                                name = name,
                                stationCount = obj.optInt("stationcount", 0)
                            )
                        )
                    }
                }

                RadioBrowserResult.Success(tags)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch tags: ${e.message}")
                RadioBrowserResult.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    /**
     * Get list of available languages with station counts
     */
    suspend fun getLanguages(limit: Int = 100): RadioBrowserResult<List<LanguageInfo>> {
        return withContext(Dispatchers.IO) {
            try {
                val client = buildHttpClient()
                val url = "${getApiBaseUrl()}/languages?order=stationcount&reverse=true&limit=$limit&hidebroken=true"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    response.close()
                    return@withContext RadioBrowserResult.Error("HTTP ${response.code}")
                }

                val body = response.body?.string()
                response.close()

                if (body.isNullOrEmpty()) {
                    return@withContext RadioBrowserResult.Error("Empty response")
                }

                val languages = mutableListOf<LanguageInfo>()
                val jsonArray = JSONArray(body)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "")
                    if (name.isNotEmpty()) {
                        languages.add(
                            LanguageInfo(
                                name = name,
                                stationCount = obj.optInt("stationcount", 0)
                            )
                        )
                    }
                }

                RadioBrowserResult.Success(languages)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch languages: ${e.message}")
                RadioBrowserResult.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    /**
     * Get stations by language
     */
    suspend fun getByLanguage(
        language: String,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        order: String = "votes",
        reverse: Boolean = true,
        hidebroken: Boolean = true
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val params = buildString {
            append("/stations/bylanguage/${java.net.URLEncoder.encode(language, "UTF-8")}")
            append("?limit=$limit")
            append("&offset=$offset")
            append("&order=$order")
            append("&reverse=$reverse")
            append("&hidebroken=$hidebroken")
        }
        return executeRequest(params)
    }

    /**
     * Check if Force Tor is enabled but Tor is not connected
     * (used to warn users before making API calls)
     *
     * IMPORTANT: Force Tor settings only matter if Tor integration itself is enabled.
     * If Tor integration is disabled, Force Tor settings should be ignored.
     */
    fun isTorRequiredButNotConnected(): Boolean {
        // First check if Tor integration is even enabled
        val torIntegrationEnabled = PreferencesHelper.isEmbeddedTorEnabled(context)
        if (!torIntegrationEnabled) {
            // If Tor integration is disabled, Force Tor settings don't apply
            return false
        }

        val forceTorAll = PreferencesHelper.isForceTorAll(context)
        val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(context)
        val torConnected = TorManager.isConnected()

        return (forceTorAll || forceTorExceptI2P) && !torConnected
    }
}

/**
 * Country information from RadioBrowser
 */
data class CountryInfo(
    val name: String,
    val iso3166_1: String,
    val stationCount: Int
)

/**
 * Tag/genre information from RadioBrowser
 */
data class TagInfo(
    val name: String,
    val stationCount: Int
)

/**
 * Language information from RadioBrowser
 */
data class LanguageInfo(
    val name: String,
    val stationCount: Int
)

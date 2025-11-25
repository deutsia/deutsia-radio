package com.opensource.i2pradio.data.radiobrowser

import android.content.Context
import android.util.Log
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Client for the RadioBrowser API (radio-browser.info)
 *
 * Supports proxy routing through Tor when Force Tor settings are enabled,
 * ensuring metadata fetches don't leak user's real IP.
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
    }

    private var currentServerIndex = 0

    /**
     * Build an OkHttpClient respecting Force Tor settings
     */
    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()

        val torEnabled = PreferencesHelper.isEmbeddedTorEnabled(context)
        val forceTorAll = PreferencesHelper.isForceTorAll(context)
        val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(context)
        val torConnected = TorManager.isConnected()

        Log.d(TAG, "Building HTTP client - ForceTorAll: $forceTorAll, ForceTorExceptI2P: $forceTorExceptI2P, TorEnabled: $torEnabled, TorConnected: $torConnected")

        // Only apply Force Tor settings if Tor integration itself is enabled
        // Route through Tor if Force Tor is enabled, Tor integration is enabled, and Tor is connected
        if (torEnabled && (forceTorAll || forceTorExceptI2P) && torConnected) {
            val socksHost = TorManager.getProxyHost()
            val socksPort = TorManager.getProxyPort()

            if (socksPort > 0) {
                Log.d(TAG, "Routing RadioBrowser API through Tor SOCKS5 proxy at $socksHost:$socksPort")
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)))
                builder.connectTimeout(CONNECT_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
                builder.readTimeout(READ_TIMEOUT_PROXY_SECONDS, TimeUnit.SECONDS)
            } else {
                Log.w(TAG, "Force Tor enabled but Tor proxy port invalid, using direct connection")
                builder.connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                builder.readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } else {
            builder.connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            builder.readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        return builder.build()
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
     * Execute an API request with retry logic
     */
    private suspend fun executeRequest(endpoint: String, retries: Int = 2): RadioBrowserResult<List<RadioBrowserStation>> {
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null

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
                val url = "${getApiBaseUrl()}/countries?order=stationcount&reverse=true"

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
                val url = "${getApiBaseUrl()}/tags?order=stationcount&reverse=true&limit=$limit"

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

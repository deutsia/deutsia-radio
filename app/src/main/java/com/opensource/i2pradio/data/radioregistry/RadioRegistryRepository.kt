package com.opensource.i2pradio.data.radioregistry

import android.content.Context
import android.util.Log
import com.opensource.i2pradio.data.RadioDao
import com.opensource.i2pradio.data.RadioDatabase
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.StationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for Radio Registry API data with local caching.
 *
 * Manages privacy-focused Tor and I2P stations from the Radio Registry API,
 * with local caching to reduce network requests.
 */
class RadioRegistryRepository(private val context: Context) {

    companion object {
        private const val TAG = "RadioRegistryRepository"

        // Cache validity: 4 hours (matches API health check interval)
        private const val CACHE_VALIDITY_MS = 4 * 60 * 60 * 1000L
    }

    private val client = RadioRegistryClient(context)
    private val dao: RadioDao by lazy {
        RadioDatabase.getDatabase(context).radioDao()
    }

    /**
     * Get Tor stations from API or cache
     *
     * @param forceRefresh If true, always fetch from API
     * @param onlineOnly If true, filter to only online stations
     */
    suspend fun getTorStations(
        forceRefresh: Boolean = false,
        onlineOnly: Boolean = true,
        limit: Int = 50
    ): RadioRegistryResult<List<RadioRegistryStation>> = withContext(Dispatchers.IO) {
        try {
            // Try cache first if not forcing refresh
            if (!forceRefresh) {
                val cached = getCachedStations("tor")
                if (cached.isNotEmpty()) {
                    Log.d(TAG, "Returning ${cached.size} cached Tor stations")
                    val result = cached.map { it.toRegistryStation() }
                    return@withContext RadioRegistryResult.Success(
                        if (onlineOnly) result.filter { it.isOnline } else result
                    )
                }
            }

            // Fetch from API
            Log.d(TAG, "Fetching Tor stations from API")
            when (val result = client.getTorStations(onlineOnly = onlineOnly, limit = limit)) {
                is RadioRegistryResult.Success -> {
                    // Filter to ensure only Tor stations are included (client-side validation)
                    val stations = result.data.stations.filter { it.isTorStation }
                    Log.d(TAG, "Fetched ${stations.size} Tor stations from API")

                    // Cache the stations
                    cacheStations(stations)

                    RadioRegistryResult.Success(
                        if (onlineOnly) stations.filter { it.isOnline } else stations
                    )
                }
                is RadioRegistryResult.Error -> {
                    // Try to return cached data on error
                    val cached = getCachedStations("tor")
                    if (cached.isNotEmpty()) {
                        Log.d(TAG, "API error, returning ${cached.size} cached Tor stations")
                        val cachedResult = cached.map { it.toRegistryStation() }
                        RadioRegistryResult.Success(
                            if (onlineOnly) cachedResult.filter { it.isOnline } else cachedResult
                        )
                    } else {
                        result
                    }
                }
                is RadioRegistryResult.Loading -> RadioRegistryResult.Loading
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Tor stations: ${e.message}")
            RadioRegistryResult.Error(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Get I2P stations from API or cache
     */
    suspend fun getI2pStations(
        forceRefresh: Boolean = false,
        onlineOnly: Boolean = true,
        limit: Int = 50
    ): RadioRegistryResult<List<RadioRegistryStation>> = withContext(Dispatchers.IO) {
        try {
            // Try cache first if not forcing refresh
            if (!forceRefresh) {
                val cached = getCachedStations("i2p")
                if (cached.isNotEmpty()) {
                    Log.d(TAG, "Returning ${cached.size} cached I2P stations")
                    val result = cached.map { it.toRegistryStation() }
                    return@withContext RadioRegistryResult.Success(
                        if (onlineOnly) result.filter { it.isOnline } else result
                    )
                }
            }

            // Fetch from API
            Log.d(TAG, "Fetching I2P stations from API")
            when (val result = client.getI2pStations(onlineOnly = onlineOnly, limit = limit)) {
                is RadioRegistryResult.Success -> {
                    // Filter to ensure only I2P stations are included (client-side validation)
                    val stations = result.data.stations.filter { it.isI2pStation }
                    Log.d(TAG, "Fetched ${stations.size} I2P stations from API")

                    // Cache the stations
                    cacheStations(stations)

                    RadioRegistryResult.Success(
                        if (onlineOnly) stations.filter { it.isOnline } else stations
                    )
                }
                is RadioRegistryResult.Error -> {
                    val cached = getCachedStations("i2p")
                    if (cached.isNotEmpty()) {
                        Log.d(TAG, "API error, returning ${cached.size} cached I2P stations")
                        val cachedResult = cached.map { it.toRegistryStation() }
                        RadioRegistryResult.Success(
                            if (onlineOnly) cachedResult.filter { it.isOnline } else cachedResult
                        )
                    } else {
                        result
                    }
                }
                is RadioRegistryResult.Loading -> RadioRegistryResult.Loading
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting I2P stations: ${e.message}")
            RadioRegistryResult.Error(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Get all privacy radio stations (both Tor and I2P)
     */
    suspend fun getAllPrivacyStations(
        forceRefresh: Boolean = false,
        onlineOnly: Boolean = true
    ): RadioRegistryResult<List<RadioRegistryStation>> = withContext(Dispatchers.IO) {
        try {
            // Try cache first
            if (!forceRefresh) {
                val cachedTor = getCachedStations("tor")
                val cachedI2p = getCachedStations("i2p")
                if (cachedTor.isNotEmpty() || cachedI2p.isNotEmpty()) {
                    val all = (cachedTor + cachedI2p).map { it.toRegistryStation() }
                    return@withContext RadioRegistryResult.Success(
                        if (onlineOnly) all.filter { it.isOnline } else all
                    )
                }
            }

            // Fetch from API
            when (val result = client.getStations(limit = 200)) {
                is RadioRegistryResult.Success -> {
                    val stations = result.data.stations
                    cacheStations(stations)
                    RadioRegistryResult.Success(
                        if (onlineOnly) stations.filter { it.isOnline } else stations
                    )
                }
                is RadioRegistryResult.Error -> {
                    val cached = (getCachedStations("tor") + getCachedStations("i2p"))
                        .map { it.toRegistryStation() }
                    if (cached.isNotEmpty()) {
                        RadioRegistryResult.Success(
                            if (onlineOnly) cached.filter { it.isOnline } else cached
                        )
                    } else {
                        result
                    }
                }
                is RadioRegistryResult.Loading -> RadioRegistryResult.Loading
            }
        } catch (e: Exception) {
            RadioRegistryResult.Error(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Get stations by genre
     */
    suspend fun getStationsByGenre(
        genre: String,
        network: String? = null
    ): RadioRegistryResult<List<RadioRegistryStation>> = withContext(Dispatchers.IO) {
        when (val result = client.getStations(network = network, genre = genre, limit = 100)) {
            is RadioRegistryResult.Success -> {
                RadioRegistryResult.Success(result.data.stations.filter { it.isOnline })
            }
            is RadioRegistryResult.Error -> result
            is RadioRegistryResult.Loading -> RadioRegistryResult.Loading
        }
    }

    /**
     * Get API statistics
     */
    suspend fun getStats(): RadioRegistryResult<StatsResponse> {
        return client.getStats()
    }

    /**
     * Get available genres
     */
    suspend fun getGenres(): RadioRegistryResult<List<String>> {
        return client.getGenres()
    }

    /**
     * Check if Force Tor mode is blocking requests
     */
    fun isTorRequiredButNotConnected(): Boolean {
        return client.isTorRequiredButNotConnected()
    }

    /**
     * Convert a RadioRegistryStation to a playable RadioStation
     */
    fun toPlayableStation(station: RadioRegistryStation): RadioStation {
        return station.toRadioStation()
    }

    /**
     * Save a station to the library (as liked)
     */
    suspend fun saveStationToLibrary(station: RadioRegistryStation): Long = withContext(Dispatchers.IO) {
        val radioStation = station.toRadioStation().copy(
            isLiked = true,
            source = StationSource.RADIOBROWSER.name,
            cachedAt = System.currentTimeMillis()
        )
        dao.insertStation(radioStation)
    }

    /**
     * Check if a station is saved in the library
     */
    suspend fun isStationSaved(stationId: String): Boolean = withContext(Dispatchers.IO) {
        val uuid = "registry_$stationId"
        dao.countByRadioBrowserUuid(uuid) > 0
    }

    /**
     * Get station info from library by registry ID
     */
    suspend fun getStationFromLibrary(stationId: String): RadioStation? = withContext(Dispatchers.IO) {
        val uuid = "registry_$stationId"
        dao.getStationByRadioBrowserUuid(uuid)
    }

    // ==================== Private Helpers ====================

    /**
     * Cache stations in the local database
     */
    private suspend fun cacheStations(stations: List<RadioRegistryStation>) {
        try {
            val now = System.currentTimeMillis()
            val radioStations = stations.map { station ->
                station.toRadioStation().copy(
                    cachedAt = now,
                    isLiked = false,  // Cached stations are not liked by default
                    source = StationSource.RADIOBROWSER.name
                )
            }

            // Insert or update cached stations
            for (station in radioStations) {
                // Check if already exists
                val existing = dao.getStationByRadioBrowserUuid(station.radioBrowserUuid!!)
                if (existing != null) {
                    // Update cache timestamp but preserve liked status
                    dao.updateStation(existing.copy(
                        cachedAt = now,
                        // Update metadata from API
                        name = station.name,
                        streamUrl = station.streamUrl,
                        genre = station.genre,
                        bitrate = station.bitrate,
                        codec = station.codec,
                        coverArtUri = station.coverArtUri
                    ))
                } else {
                    dao.insertStation(station)
                }
            }

            Log.d(TAG, "Cached ${stations.size} stations")
        } catch (e: Exception) {
            Log.e(TAG, "Error caching stations: ${e.message}")
        }
    }

    /**
     * Get cached stations by network type
     */
    private suspend fun getCachedStations(network: String): List<RadioStation> {
        return try {
            val threshold = System.currentTimeMillis() - CACHE_VALIDITY_MS
            val allCached = dao.getStationsBySource(StationSource.RADIOBROWSER.name)

            // Filter by network type and cache validity
            allCached.filter { station ->
                station.cachedAt > threshold &&
                station.radioBrowserUuid?.startsWith("registry_") == true &&
                when (network.lowercase()) {
                    "tor" -> station.proxyType == "TOR"
                    "i2p" -> station.proxyType == "I2P"
                    else -> true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cached stations: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extension to convert RadioStation back to RadioRegistryStation for display
     */
    private fun RadioStation.toRegistryStation(): RadioRegistryStation {
        val registryId = radioBrowserUuid?.removePrefix("registry_") ?: ""
        return RadioRegistryStation(
            id = registryId,
            name = name,
            streamUrl = streamUrl,
            homepage = homepage.takeIf { it.isNotEmpty() },
            faviconUrl = coverArtUri,
            genre = genre,
            codec = codec.takeIf { it.isNotEmpty() },
            bitrate = bitrate.takeIf { it > 0 },
            language = null,
            network = when (proxyType) {
                "TOR" -> "tor"
                "I2P" -> "i2p"
                else -> ""
            },
            country = country.takeIf { it.isNotEmpty() },
            countryCode = countryCode.takeIf { it.isNotEmpty() },
            isOnline = true,  // Note: Online status not stored in cache, use fresh API data for accurate status
            lastCheckTime = null,
            status = "approved",
            checkCount = 0,
            checkOkCount = 0,
            submittedAt = null,
            createdAt = null,
            updatedAt = null
        )
    }
}

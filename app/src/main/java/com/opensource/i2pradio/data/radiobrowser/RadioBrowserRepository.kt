package com.opensource.i2pradio.data.radiobrowser

import android.content.Context
import android.util.Log
import com.opensource.i2pradio.data.BrowseHistory
import com.opensource.i2pradio.data.RadioDao
import com.opensource.i2pradio.data.RadioDatabase
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.StationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for RadioBrowser data with local caching support.
 *
 * Handles:
 * - Fetching stations from RadioBrowser API
 * - Caching results in local database
 * - Converting between RadioBrowser and app station formats
 * - Deduplication when saving discovered stations
 */
class RadioBrowserRepository(context: Context) {

    companion object {
        private const val TAG = "RadioBrowserRepository"

        // Cache TTL: 7 days for cached RadioBrowser stations
        private const val CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    }

    private val radioDao: RadioDao = RadioDatabase.getDatabase(context).radioDao()
    private val apiClient: RadioBrowserClient = RadioBrowserClient(context)

    /**
     * Search for stations by name
     */
    suspend fun searchByName(
        query: String,
        limit: Int = 50,
        offset: Int = 0
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        return apiClient.searchByName(query, limit, offset)
    }

    /**
     * Search stations with filters (supports combining multiple filters)
     */
    suspend fun searchStations(
        name: String? = null,
        tag: String? = null,
        country: String? = null,
        countrycode: String? = null,
        language: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        return apiClient.searchStations(
            name = name,
            tag = tag,
            country = country,
            countrycode = countrycode,
            language = language,
            limit = limit,
            offset = offset
        )
    }

    /**
     * Get top voted stations
     */
    suspend fun getTopVoted(limit: Int = 50, offset: Int = 0): RadioBrowserResult<List<RadioBrowserStation>> {
        return apiClient.getTopVoted(limit, offset)
    }

    /**
     * Get top clicked (popular) stations
     */
    suspend fun getTopClicked(limit: Int = 50, offset: Int = 0): RadioBrowserResult<List<RadioBrowserStation>> {
        return apiClient.getTopClicked(limit, offset)
    }

    /**
     * Get recently changed stations
     */
    suspend fun getRecentlyChanged(limit: Int = 50, offset: Int = 0): RadioBrowserResult<List<RadioBrowserStation>> {
        return apiClient.getRecentlyChanged(limit, offset)
    }

    /**
     * Get stations by country code
     */
    suspend fun getByCountryCode(
        countryCode: String,
        limit: Int = 50,
        offset: Int = 0
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        return apiClient.getByCountryCode(countryCode, limit, offset)
    }

    /**
     * Get stations by tag/genre
     */
    suspend fun getByTag(
        tag: String,
        limit: Int = 50,
        offset: Int = 0
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        return apiClient.getByTag(tag, limit, offset)
    }

    /**
     * Get list of countries
     */
    suspend fun getCountries(): RadioBrowserResult<List<CountryInfo>> {
        return apiClient.getCountries()
    }

    /**
     * Get list of tags
     */
    suspend fun getTags(limit: Int = 100): RadioBrowserResult<List<TagInfo>> {
        return apiClient.getTags(limit)
    }

    /**
     * Get list of languages
     */
    suspend fun getLanguages(limit: Int = 100): RadioBrowserResult<List<LanguageInfo>> {
        return apiClient.getLanguages(limit)
    }

    /**
     * Get stations by language
     */
    suspend fun getByLanguage(
        language: String,
        limit: Int = 50,
        offset: Int = 0
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        return apiClient.getByLanguage(language, limit, offset)
    }

    /**
     * Check if a RadioBrowser station is already saved to user's library
     */
    suspend fun isStationSaved(radioBrowserUuid: String): Boolean {
        return withContext(Dispatchers.IO) {
            radioDao.countByRadioBrowserUuid(radioBrowserUuid) > 0
        }
    }

    /**
     * Get station info by RadioBrowser UUID (including isLiked status)
     */
    suspend fun getStationInfoByUuid(radioBrowserUuid: String): RadioStation? {
        return withContext(Dispatchers.IO) {
            radioDao.getStationByRadioBrowserUuid(radioBrowserUuid)
        }
    }

    /**
     * Get station info for multiple RadioBrowser UUIDs at once (batch query)
     * Returns a map of UUID -> RadioStation for efficient lookup
     */
    suspend fun getStationInfoByUuids(radioBrowserUuids: List<String>): Map<String, RadioStation> {
        return withContext(Dispatchers.IO) {
            if (radioBrowserUuids.isEmpty()) {
                return@withContext emptyMap()
            }
            val stations = radioDao.getStationsByRadioBrowserUuids(radioBrowserUuids)
            // Filter out stations with null or empty UUIDs to avoid key collisions
            stations.filter { !it.radioBrowserUuid.isNullOrEmpty() }
                .associateBy { it.radioBrowserUuid!! }
        }
    }

    /**
     * Toggle like status for a station by its RadioBrowser UUID
     */
    suspend fun toggleLikeByUuid(radioBrowserUuid: String) {
        withContext(Dispatchers.IO) {
            val station = radioDao.getStationByRadioBrowserUuid(radioBrowserUuid)
            if (station != null) {
                radioDao.toggleLike(station.id)
            }
        }
    }

    /**
     * Save a RadioBrowser station as liked (saves and sets isLiked=true)
     */
    suspend fun saveStationAsLiked(station: RadioBrowserStation): Long? {
        return withContext(Dispatchers.IO) {
            // Check if already saved
            val existing = radioDao.getStationByRadioBrowserUuid(station.stationuuid)
            if (existing != null) {
                // If exists but not liked, toggle like
                if (!existing.isLiked) {
                    radioDao.toggleLike(existing.id)
                }
                Log.d(TAG, "Station already saved, toggled like: ${station.name}")
                return@withContext existing.id
            }

            // Save new station with isLiked = true
            val radioStation = convertToRadioStation(station, asUserStation = true).copy(isLiked = true)
            val id = radioDao.insertStation(radioStation)
            Log.d(TAG, "Saved and liked station: ${station.name} with ID: $id")
            id
        }
    }

    /**
     * Save a RadioBrowser station to user's library.
     * Converts it to the app's RadioStation format.
     *
     * @param station The RadioBrowser station to save
     * @param asUserStation If true, saves as USER source (user explicitly saved it)
     * @return The saved station's ID, or null if already exists
     */
    suspend fun saveStation(
        station: RadioBrowserStation,
        asUserStation: Boolean = true
    ): Long? {
        return withContext(Dispatchers.IO) {
            // Check if already saved
            val existing = radioDao.getStationByRadioBrowserUuid(station.stationuuid)
            if (existing != null) {
                Log.d(TAG, "Station already saved: ${station.name}")
                return@withContext existing.id
            }

            val radioStation = convertToRadioStation(station, asUserStation)
            val id = radioDao.insertStation(radioStation)
            Log.d(TAG, "Saved station: ${station.name} with ID: $id")
            id
        }
    }

    /**
     * Convert a RadioBrowserStation to the app's RadioStation format
     */
    fun convertToRadioStation(
        station: RadioBrowserStation,
        asUserStation: Boolean = false
    ): RadioStation {
        val now = System.currentTimeMillis()
        return RadioStation(
            name = station.name,
            streamUrl = station.getStreamUrl(),
            genre = station.getPrimaryGenre(),
            coverArtUri = station.favicon.takeIf { it.isNotEmpty() },
            source = if (asUserStation) StationSource.USER.name else StationSource.RADIOBROWSER.name,
            radioBrowserUuid = station.stationuuid,
            cachedAt = now,
            lastVerified = if (station.lastcheckok) now else 0L,
            bitrate = station.bitrate,
            codec = station.codec,
            country = station.country,
            countryCode = station.countrycode,
            homepage = station.homepage,
            addedTimestamp = now,
            // Use proxy settings from the station (for Tor/I2P curated stations)
            useProxy = station.useProxy,
            proxyType = station.proxyType,
            proxyHost = station.proxyHost,
            proxyPort = station.proxyPort
        )
    }

    /**
     * Cache a list of stations from RadioBrowser (for offline browsing)
     */
    suspend fun cacheStations(stations: List<RadioBrowserStation>) {
        withContext(Dispatchers.IO) {
            val radioStations = stations.map { convertToRadioStation(it, asUserStation = false) }
            radioDao.insertStations(radioStations)
            Log.d(TAG, "Cached ${stations.size} stations")
        }
    }

    /**
     * Clean up stale cached stations (older than CACHE_TTL)
     */
    suspend fun cleanupStaleCache() {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
            radioDao.deleteStaleCachedStations(cutoff)
            Log.d(TAG, "Cleaned up stale cached stations older than $cutoff")
        }
    }

    /**
     * Get cached stations by country (for offline browsing)
     */
    suspend fun getCachedStationsByCountry(countryCode: String): List<RadioStation> {
        return withContext(Dispatchers.IO) {
            radioDao.getCachedStationsByCountry(countryCode)
        }
    }

    /**
     * Check if Force Tor is required but not connected
     */
    fun isTorRequiredButNotConnected(): Boolean {
        return apiClient.isTorRequiredButNotConnected()
    }

    /**
     * Update last verified timestamp for a station
     */
    suspend fun markStationVerified(stationId: Long) {
        withContext(Dispatchers.IO) {
            radioDao.updateLastVerified(stationId, System.currentTimeMillis())
        }
    }

    /**
     * Delete a station by its RadioBrowser UUID
     * @return true if station was found and deleted, false otherwise
     */
    suspend fun deleteStationByUuid(radioBrowserUuid: String): Boolean {
        return withContext(Dispatchers.IO) {
            val station = radioDao.getStationByRadioBrowserUuid(radioBrowserUuid)
            if (station != null) {
                radioDao.deleteStation(station)
                Log.d(TAG, "Deleted station: ${station.name}")
                true
            } else {
                Log.d(TAG, "Station not found for deletion: $radioBrowserUuid")
                false
            }
        }
    }

    /**
     * Get browse history (last 75 visited stations from browse)
     * Returns the list as RadioBrowserStation objects for display
     */
    suspend fun getBrowseHistory(): RadioBrowserResult<List<RadioBrowserStation>> {
        return withContext(Dispatchers.IO) {
            try {
                val history = radioDao.getBrowseHistory()
                // Convert BrowseHistory entries to RadioBrowserStation for consistent display
                val stations = history.map { entry ->
                    RadioBrowserStation(
                        stationuuid = entry.radioBrowserUuid,
                        name = entry.stationName,
                        url = entry.streamUrl,
                        urlResolved = entry.streamUrl,
                        favicon = entry.coverArtUri ?: "",
                        tags = entry.genre,
                        country = entry.country,
                        countrycode = "",
                        state = "",
                        language = "",
                        languagecodes = "",
                        codec = "",
                        bitrate = 0,
                        homepage = "",
                        votes = 0,
                        clickcount = 0,
                        clicktrend = 0,
                        lastcheckok = true,
                        lastchangetime = "",
                        hls = false,
                        sslError = false,
                        geoLat = null,
                        geoLong = null
                    )
                }
                RadioBrowserResult.Success(stations)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching browse history", e)
                RadioBrowserResult.Error("Failed to load browse history: ${e.message}")
            }
        }
    }

    /**
     * Add a station to browse history
     * If the station already exists in history, updates its timestamp to make it most recent
     */
    suspend fun addToBrowseHistory(station: RadioBrowserStation) {
        withContext(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val existingCount = radioDao.countBrowseHistoryByUuid(station.stationuuid)

                if (existingCount > 0) {
                    // Update existing entry's timestamp
                    radioDao.updateBrowseHistoryTimestamp(station.stationuuid, timestamp)
                    Log.d(TAG, "Updated browse history timestamp for: ${station.name}")
                } else {
                    // Insert new entry
                    val browseHistory = BrowseHistory(
                        radioBrowserUuid = station.stationuuid,
                        visitedAt = timestamp,
                        stationName = station.name,
                        streamUrl = station.getStreamUrl(),
                        coverArtUri = station.favicon.takeIf { it.isNotEmpty() },
                        country = station.country,
                        genre = station.getPrimaryGenre()
                    )
                    radioDao.insertBrowseHistory(browseHistory)
                    Log.d(TAG, "Added to browse history: ${station.name}")

                    // Clean up old entries to keep only 75 most recent
                    radioDao.deleteOldBrowseHistory()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding to browse history", e)
            }
        }
    }

    /**
     * Clear all browse history
     */
    suspend fun clearBrowseHistory() {
        withContext(Dispatchers.IO) {
            radioDao.clearBrowseHistory()
            Log.d(TAG, "Cleared all browse history")
        }
    }
}

package com.opensource.i2pradio.data.radioregistry

import android.content.Context
import android.util.Log

/**
 * Repository for Radio Registry API.
 *
 * This is a simple wrapper around RadioRegistryClient that fetches
 * privacy-focused Tor and I2P stations directly from the API.
 *
 * No caching is performed - all requests go directly to the API.
 * Stations are only saved to the database when the user explicitly
 * imports them to their library.
 */
class RadioRegistryRepository(private val context: Context) {

    companion object {
        private const val TAG = "RadioRegistryRepository"
    }

    private val client = RadioRegistryClient(context)

    /**
     * Get Tor stations from API
     *
     * @param onlineOnly If true, filter to only online stations
     * @param limit Maximum number of stations to fetch
     */
    suspend fun getTorStations(
        onlineOnly: Boolean = true,
        limit: Int = 50
    ): RadioRegistryResult<List<RadioRegistryStation>> {
        Log.d(TAG, "Fetching Tor stations from API (onlineOnly=$onlineOnly, limit=$limit)")

        return when (val result = client.getTorStations(onlineOnly = onlineOnly, limit = limit)) {
            is RadioRegistryResult.Success -> {
                // Filter to ensure only Tor stations are included (client-side validation)
                val stations = result.data.stations.filter { it.isTorStation }
                Log.d(TAG, "Received ${result.data.stations.size} stations, ${stations.size} are Tor")
                RadioRegistryResult.Success(stations)
            }
            is RadioRegistryResult.Error -> {
                Log.e(TAG, "Failed to fetch Tor stations: ${result.message}")
                result
            }
            is RadioRegistryResult.Loading -> RadioRegistryResult.Loading
        }
    }

    /**
     * Get I2P stations from API
     *
     * @param onlineOnly If true, filter to only online stations
     * @param limit Maximum number of stations to fetch
     */
    suspend fun getI2pStations(
        onlineOnly: Boolean = true,
        limit: Int = 50
    ): RadioRegistryResult<List<RadioRegistryStation>> {
        Log.d(TAG, "Fetching I2P stations from API (onlineOnly=$onlineOnly, limit=$limit)")

        return when (val result = client.getI2pStations(onlineOnly = onlineOnly, limit = limit)) {
            is RadioRegistryResult.Success -> {
                // Filter to ensure only I2P stations are included (client-side validation)
                val stations = result.data.stations.filter { it.isI2pStation }
                Log.d(TAG, "Received ${result.data.stations.size} stations, ${stations.size} are I2P")
                RadioRegistryResult.Success(stations)
            }
            is RadioRegistryResult.Error -> {
                Log.e(TAG, "Failed to fetch I2P stations: ${result.message}")
                result
            }
            is RadioRegistryResult.Loading -> RadioRegistryResult.Loading
        }
    }

    /**
     * Get all privacy radio stations (both Tor and I2P)
     *
     * @param onlineOnly If true, filter to only online stations
     * @param limit Maximum number of stations to fetch
     */
    suspend fun getAllPrivacyStations(
        onlineOnly: Boolean = true,
        limit: Int = 200
    ): RadioRegistryResult<List<RadioRegistryStation>> {
        Log.d(TAG, "Fetching all privacy stations from API")

        return when (val result = client.getStations(onlineOnly = onlineOnly, limit = limit)) {
            is RadioRegistryResult.Success -> {
                val stations = result.data.stations
                Log.d(TAG, "Received ${stations.size} total privacy stations")
                RadioRegistryResult.Success(stations)
            }
            is RadioRegistryResult.Error -> {
                Log.e(TAG, "Failed to fetch privacy stations: ${result.message}")
                result
            }
            is RadioRegistryResult.Loading -> RadioRegistryResult.Loading
        }
    }

    /**
     * Get stations by genre
     *
     * @param genre Genre to filter by
     * @param network Optional network filter ("tor" or "i2p")
     */
    suspend fun getStationsByGenre(
        genre: String,
        network: String? = null
    ): RadioRegistryResult<List<RadioRegistryStation>> {
        return when (val result = client.getStations(network = network, genre = genre, limit = 100)) {
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
     * Search stations by name with optional network and genre filters
     *
     * @param query Search query to match against station names
     * @param network Filter by network: "tor" or "i2p" (null for all)
     * @param genre Filter by genre
     * @param limit Maximum number of results to return
     */
    suspend fun searchStations(
        query: String,
        network: String? = null,
        genre: String? = null,
        limit: Int = 50
    ): RadioRegistryResult<List<RadioRegistryStation>> {
        Log.d(TAG, "Searching stations: query='$query', network=$network, genre=$genre")
        return client.searchStations(query, network, genre, limit)
    }

    /**
     * Check if Force Tor mode is blocking requests
     */
    fun isTorRequiredButNotConnected(): Boolean {
        return client.isTorRequiredButNotConnected()
    }
}

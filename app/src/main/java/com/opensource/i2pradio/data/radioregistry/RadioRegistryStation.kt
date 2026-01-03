package com.opensource.i2pradio.data.radioregistry

import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.StationSource
import org.json.JSONObject

/**
 * Data class representing a radio station from the Radio Registry API.
 * Maps to the JSON response structure from api.deutsia.com
 *
 * The Radio Registry API provides privacy-focused Tor and I2P radio stations.
 */
data class RadioRegistryStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val homepage: String?,
    val faviconUrl: String?,
    val genre: String?,
    val codec: String?,
    val bitrate: Int?,
    val language: String?,
    val network: String,  // "tor" or "i2p"
    val country: String?,
    val countryCode: String?,
    val isOnline: Boolean,
    val lastCheckTime: String?,
    val status: String,  // "approved", "pending", "rejected"
    val checkCount: Int,
    val checkOkCount: Int,
    val submittedAt: String?,
    val createdAt: String?,
    val updatedAt: String?
) {
    companion object {
        /**
         * Parse a RadioRegistryStation from a JSONObject
         */
        fun fromJson(json: JSONObject): RadioRegistryStation {
            return RadioRegistryStation(
                id = json.optString("id", ""),
                name = json.optString("name", "").trim(),
                streamUrl = json.optString("streamUrl", ""),
                homepage = json.optString("homepage", "").takeIf { it.isNotEmpty() },
                faviconUrl = json.optString("faviconUrl", "").takeIf { it.isNotEmpty() },
                genre = json.optString("genre", "").takeIf { it.isNotEmpty() },
                codec = json.optString("codec", "").takeIf { it.isNotEmpty() },
                bitrate = json.optInt("bitrate", 0).takeIf { it > 0 },
                language = json.optString("language", "").takeIf { it.isNotEmpty() },
                network = json.optString("network", ""),
                country = json.optString("country", "").takeIf { it.isNotEmpty() },
                countryCode = json.optString("countryCode", "").takeIf { it.isNotEmpty() },
                isOnline = json.optBoolean("lastCheckOk", false),
                lastCheckTime = json.optString("lastCheckTime", "").takeIf { it.isNotEmpty() },
                status = json.optString("status", "approved"),
                checkCount = json.optInt("checkCount", 0),
                checkOkCount = json.optInt("checkOkCount", 0),
                submittedAt = json.optString("submittedAt", "").takeIf { it.isNotEmpty() },
                createdAt = json.optString("createdAt", "").takeIf { it.isNotEmpty() },
                updatedAt = json.optString("updatedAt", "").takeIf { it.isNotEmpty() }
            )
        }
    }

    /**
     * Calculate uptime percentage based on check history
     */
    val uptimePercent: Float
        get() = if (checkCount > 0) {
            (checkOkCount.toFloat() / checkCount.toFloat()) * 100f
        } else 0f

    /**
     * Check if this station uses Tor
     */
    val isTorStation: Boolean
        get() = network.equals("tor", ignoreCase = true) || streamUrl.contains(".onion")

    /**
     * Check if this station uses I2P
     */
    val isI2pStation: Boolean
        get() = network.equals("i2p", ignoreCase = true) || streamUrl.contains(".i2p")

    /**
     * Get the appropriate proxy type for this station
     */
    fun getProxyType(): ProxyType {
        return when {
            isTorStation -> ProxyType.TOR
            isI2pStation -> ProxyType.I2P
            else -> ProxyType.NONE
        }
    }

    /**
     * Get a display string for quality info (bitrate + codec)
     */
    fun getQualityInfo(): String {
        val parts = mutableListOf<String>()
        bitrate?.let { if (it > 0) parts.add("${it}kbps") }
        codec?.let { if (it.isNotEmpty()) parts.add(it.uppercase()) }
        return parts.joinToString(" • ")
    }

    /**
     * Get the genre string with network indicator suffix
     */
    fun getGenreWithNetwork(): String {
        val genreText = genre ?: "Other"
        val networkIndicator = if (isTorStation) "Tor" else if (isI2pStation) "I2P" else null
        return if (networkIndicator != null && genreText.isNotEmpty() && genreText != "Other") {
            "$genreText · $networkIndicator"
        } else if (networkIndicator != null) {
            networkIndicator
        } else {
            genreText
        }
    }

    /**
     * Get the primary genre (without network indicator)
     */
    fun getPrimaryGenre(): String = genre ?: "Other"

    /**
     * Get network indicator (Tor or I2P)
     */
    fun getNetworkIndicator(): String? {
        return when {
            isTorStation -> "Tor"
            isI2pStation -> "I2P"
            else -> null
        }
    }

    /**
     * Convert to the app's RadioStation model for playback
     */
    fun toRadioStation(): RadioStation {
        val proxyType = getProxyType()
        val defaultPort = proxyType.getDefaultPort()
        val defaultHost = proxyType.getDefaultHost()

        return RadioStation(
            id = 0,  // Will be assigned by Room if saved
            name = name,
            streamUrl = streamUrl,
            proxyHost = defaultHost,
            proxyPort = defaultPort,
            useProxy = proxyType != ProxyType.NONE,
            proxyType = proxyType.name,
            genre = getPrimaryGenre(),
            coverArtUri = faviconUrl,
            isPreset = false,
            isLiked = false,
            source = StationSource.RADIOBROWSER.name,  // Reusing this source type
            radioBrowserUuid = "registry_$id",  // Prefix to distinguish from RadioBrowser UUIDs
            bitrate = bitrate ?: 0,
            codec = codec ?: "",
            country = country ?: "",
            countryCode = countryCode ?: "",
            homepage = homepage ?: ""
        )
    }
}

/**
 * Response wrapper for station list endpoint
 */
data class StationListResponse(
    val stations: List<RadioRegistryStation>,
    val total: Int,
    val online: Int
) {
    companion object {
        fun fromJson(json: JSONObject): StationListResponse {
            val stationsArray = json.optJSONArray("stations") ?: org.json.JSONArray()
            val stations = mutableListOf<RadioRegistryStation>()

            for (i in 0 until stationsArray.length()) {
                try {
                    val station = RadioRegistryStation.fromJson(stationsArray.getJSONObject(i))
                    if (station.name.isNotEmpty() && station.streamUrl.isNotEmpty()) {
                        stations.add(station)
                    }
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }

            return StationListResponse(
                stations = stations,
                total = json.optInt("total", stations.size),
                online = json.optInt("online", stations.count { it.isOnline })
            )
        }
    }
}

/**
 * Response wrapper for stats endpoint
 */
data class StatsResponse(
    val totalStations: Int,
    val onlineStations: Int,
    val torStations: Int,
    val i2pStations: Int,
    val pendingSubmissions: Int,
    val lastHealthCheck: String?
) {
    companion object {
        fun fromJson(json: JSONObject): StatsResponse {
            return StatsResponse(
                totalStations = json.optInt("total_stations", 0),
                onlineStations = json.optInt("online_stations", 0),
                torStations = json.optInt("tor_stations", 0),
                i2pStations = json.optInt("i2p_stations", 0),
                pendingSubmissions = json.optInt("pending_submissions", 0),
                lastHealthCheck = json.optString("last_health_check", "").takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * Result wrapper for Radio Registry API calls
 */
sealed class RadioRegistryResult<out T> {
    data class Success<T>(val data: T) : RadioRegistryResult<T>()
    data class Error(val message: String, val exception: Exception? = null) : RadioRegistryResult<Nothing>()
    data object Loading : RadioRegistryResult<Nothing>()
}

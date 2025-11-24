package com.opensource.i2pradio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Proxy type for radio streams
 * - NONE: Direct connection without proxy
 * - I2P: HTTP proxy (default port 4444)
 * - TOR: SOCKS5 proxy (default port 9050)
 */
enum class ProxyType {
    NONE,
    I2P,
    TOR;

    companion object {
        fun fromString(value: String?): ProxyType {
            return when (value?.uppercase()) {
                "I2P" -> I2P
                "TOR" -> TOR
                else -> NONE
            }
        }
    }

    fun getDefaultPort(): Int {
        return when (this) {
            NONE -> 0
            I2P -> 4444
            TOR -> 9050
        }
    }

    fun getDefaultHost(): String {
        return when (this) {
            NONE -> ""
            I2P, TOR -> "127.0.0.1"
        }
    }
}

/**
 * Source of a radio station
 * - USER: Manually added by the user
 * - RADIOBROWSER: Discovered from RadioBrowser API
 * - BUNDLED: Came bundled with the app
 */
enum class StationSource {
    USER,
    RADIOBROWSER,
    BUNDLED
}

@Entity(tableName = "radio_stations")
data class RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val proxyHost: String = "",
    val proxyPort: Int = 4444,
    val useProxy: Boolean = false,
    val proxyType: String = ProxyType.NONE.name, // Store as String for Room compatibility
    val genre: String = "Other",
    val coverArtUri: String? = null,
    val isPreset: Boolean = false,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val isLiked: Boolean = false,
    val lastPlayedAt: Long = 0L,
    // New fields for RadioBrowser integration
    val source: String = StationSource.USER.name, // Source of this station
    val radioBrowserUuid: String? = null, // UUID from RadioBrowser for dedup
    val lastVerified: Long = 0L, // When stream was last verified working
    val cachedAt: Long = 0L, // When fetched from RadioBrowser
    val bitrate: Int = 0, // Bitrate in kbps (from RadioBrowser)
    val codec: String = "", // Audio codec (from RadioBrowser)
    val country: String = "", // Country name (from RadioBrowser)
    val countryCode: String = "", // ISO country code (from RadioBrowser)
    val homepage: String = "" // Station homepage URL
) {
    /**
     * Get the ProxyType enum from the stored string
     */
    fun getProxyTypeEnum(): ProxyType = ProxyType.fromString(proxyType)

    /**
     * Check if this station uses any proxy (I2P or Tor)
     */
    fun usesProxy(): Boolean = useProxy && getProxyTypeEnum() != ProxyType.NONE

    /**
     * Get the StationSource enum from the stored string
     */
    fun getSourceEnum(): StationSource {
        return try {
            StationSource.valueOf(source)
        } catch (e: Exception) {
            StationSource.USER
        }
    }

    /**
     * Check if this station was discovered from RadioBrowser
     */
    fun isFromRadioBrowser(): Boolean = getSourceEnum() == StationSource.RADIOBROWSER

    /**
     * Get quality info string (bitrate + codec)
     */
    fun getQualityInfo(): String {
        val parts = mutableListOf<String>()
        if (bitrate > 0) parts.add("${bitrate}kbps")
        if (codec.isNotEmpty()) parts.add(codec.uppercase())
        return parts.joinToString(" ")
    }
}
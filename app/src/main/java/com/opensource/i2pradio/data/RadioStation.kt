package com.opensource.i2pradio.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Proxy type for radio streams
 * - NONE: Direct connection without proxy
 * - I2P: HTTP proxy (default port 4444)
 * - TOR: SOCKS5 proxy (default port 9050)
 * - CUSTOM: User-defined custom proxy with full configuration
 */
enum class ProxyType {
    NONE,
    I2P,
    TOR,
    CUSTOM;

    companion object {
        fun fromString(value: String?): ProxyType {
            return when (value?.uppercase()) {
                "I2P" -> I2P
                "TOR" -> TOR
                "CUSTOM" -> CUSTOM
                else -> NONE
            }
        }
    }

    fun getDefaultPort(): Int {
        return when (this) {
            NONE -> 0
            I2P -> 4444
            TOR -> 9050
            CUSTOM -> 8080
        }
    }

    fun getDefaultHost(): String {
        return when (this) {
            NONE -> ""
            I2P, TOR -> "127.0.0.1"
            CUSTOM -> ""
        }
    }
}

/**
 * Custom proxy protocol types
 * - HTTP: Standard HTTP proxy
 * - HTTPS: HTTP proxy over TLS
 * - SOCKS4: SOCKS version 4 proxy
 * - SOCKS5: SOCKS version 5 proxy (supports authentication)
 */
enum class ProxyProtocol {
    HTTP,
    HTTPS,
    SOCKS4,
    SOCKS5;

    companion object {
        fun fromString(value: String?): ProxyProtocol {
            return when (value?.uppercase()) {
                "HTTP" -> HTTP
                "HTTPS" -> HTTPS
                "SOCKS4" -> SOCKS4
                "SOCKS5" -> SOCKS5
                else -> HTTP
            }
        }
    }

    fun toJavaProxyType(): java.net.Proxy.Type {
        return when (this) {
            HTTP, HTTPS -> java.net.Proxy.Type.HTTP
            SOCKS4, SOCKS5 -> java.net.Proxy.Type.SOCKS
        }
    }
}

/**
 * Proxy authentication method
 * - NONE: No authentication required
 * - BASIC: HTTP Basic authentication (username:password in Base64)
 * - DIGEST: HTTP Digest authentication (more secure than Basic)
 */
enum class ProxyAuthType {
    NONE,
    BASIC,
    DIGEST;

    companion object {
        fun fromString(value: String?): ProxyAuthType {
            return when (value?.uppercase()) {
                "BASIC" -> BASIC
                "DIGEST" -> DIGEST
                else -> NONE
            }
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

@Entity(
    tableName = "radio_stations",
    indices = [Index(value = ["radioBrowserUuid"])]
)
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
    val homepage: String = "", // Station homepage URL
    // Custom proxy configuration fields
    val customProxyProtocol: String = ProxyProtocol.HTTP.name, // HTTP, HTTPS, SOCKS4, SOCKS5
    val proxyUsername: String = "", // Optional proxy authentication username
    val proxyPassword: String = "", // Optional proxy authentication password
    val proxyAuthType: String = ProxyAuthType.NONE.name, // NONE, BASIC, DIGEST
    val proxyDnsResolution: Boolean = true, // Resolve DNS through proxy
    val proxyConnectionTimeout: Int = 30, // Connection timeout in seconds (0 = use default)
    val proxyBypassLocalAddresses: Boolean = false // Bypass proxy for local/private addresses
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

    /**
     * Get the ProxyProtocol enum from the stored string (for CUSTOM proxy type)
     */
    fun getCustomProxyProtocolEnum(): ProxyProtocol = ProxyProtocol.fromString(customProxyProtocol)

    /**
     * Get the ProxyAuthType enum from the stored string
     */
    fun getProxyAuthTypeEnum(): ProxyAuthType = ProxyAuthType.fromString(proxyAuthType)

    /**
     * Check if proxy authentication is configured
     */
    fun hasProxyAuthentication(): Boolean = proxyUsername.isNotEmpty() && proxyPassword.isNotEmpty()

    /**
     * Check if this is a custom proxy with valid configuration
     */
    fun isCustomProxy(): Boolean = getProxyTypeEnum() == ProxyType.CUSTOM && proxyHost.isNotEmpty()

    /**
     * Get effective connection timeout (returns 30 if set to 0 or negative)
     */
    fun getEffectiveConnectionTimeout(): Int = if (proxyConnectionTimeout > 0) proxyConnectionTimeout else 30

    /**
     * Get a display string for the proxy configuration
     */
    fun getProxyDisplayString(): String {
        return when (val type = getProxyTypeEnum()) {
            ProxyType.NONE -> "Direct Connection"
            ProxyType.I2P -> "I2P ($proxyHost:$proxyPort)"
            ProxyType.TOR -> "Tor ($proxyHost:$proxyPort)"
            ProxyType.CUSTOM -> {
                val protocol = getCustomProxyProtocolEnum().name
                val auth = if (hasProxyAuthentication()) " [Auth]" else ""
                "$protocol Proxy ($proxyHost:$proxyPort)$auth"
            }
        }
    }
}
package com.opensource.i2pradio.data.radiobrowser

import org.json.JSONObject

/**
 * Data class representing a radio station from the RadioBrowser API.
 * Maps to the JSON response structure from radio-browser.info
 */
data class RadioBrowserStation(
    val stationuuid: String,
    val name: String,
    val url: String,
    val urlResolved: String,
    val homepage: String,
    val favicon: String,
    val tags: String,
    val country: String,
    val countrycode: String,
    val state: String,
    val language: String,
    val languagecodes: String,
    val votes: Int,
    val lastchangetime: String,
    val codec: String,
    val bitrate: Int,
    val hls: Boolean,
    val lastcheckok: Boolean,
    val clickcount: Int,
    val clicktrend: Int,
    val sslError: Boolean,
    val geoLat: Double?,
    val geoLong: Double?
) {
    companion object {
        /**
         * Parse a RadioBrowserStation from a JSONObject
         */
        fun fromJson(json: JSONObject): RadioBrowserStation {
            return RadioBrowserStation(
                stationuuid = json.optString("stationuuid", ""),
                name = json.optString("name", "").trim(),
                url = json.optString("url", ""),
                urlResolved = json.optString("url_resolved", ""),
                homepage = json.optString("homepage", ""),
                favicon = json.optString("favicon", ""),
                tags = json.optString("tags", ""),
                country = json.optString("country", ""),
                countrycode = json.optString("countrycode", ""),
                state = json.optString("state", ""),
                language = json.optString("language", ""),
                languagecodes = json.optString("languagecodes", ""),
                votes = json.optInt("votes", 0),
                lastchangetime = json.optString("lastchangetime", ""),
                codec = json.optString("codec", ""),
                bitrate = json.optInt("bitrate", 0),
                hls = json.optInt("hls", 0) == 1,
                lastcheckok = json.optInt("lastcheckok", 0) == 1,
                clickcount = json.optInt("clickcount", 0),
                clicktrend = json.optInt("clicktrend", 0),
                sslError = json.optInt("ssl_error", 0) == 1,
                geoLat = json.optDouble("geo_lat").takeIf { !it.isNaN() },
                geoLong = json.optDouble("geo_long").takeIf { !it.isNaN() }
            )
        }
    }

    /**
     * Get the best available stream URL (resolved URL preferred)
     */
    fun getStreamUrl(): String {
        return urlResolved.takeIf { it.isNotEmpty() } ?: url
    }

    /**
     * Get the primary genre/tag from the tags list
     */
    fun getPrimaryGenre(): String {
        val tagList = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return mapToAppGenre(tagList.firstOrNull() ?: "Other")
    }

    /**
     * Map RadioBrowser tags to app's genre categories
     */
    private fun mapToAppGenre(tag: String): String {
        val lowerTag = tag.lowercase()
        return when {
            lowerTag.contains("news") -> "News"
            lowerTag.contains("talk") -> "Talk"
            lowerTag.contains("sport") -> "Sports"
            lowerTag.contains("classical") -> "Classical"
            lowerTag.contains("jazz") -> "Jazz"
            lowerTag.contains("rock") -> "Rock"
            lowerTag.contains("pop") -> "Pop"
            lowerTag.contains("electronic") || lowerTag.contains("techno") ||
                lowerTag.contains("house") || lowerTag.contains("trance") -> "Electronic"
            lowerTag.contains("dance") || lowerTag.contains("edm") -> "Dance"
            lowerTag.contains("hip hop") || lowerTag.contains("hip-hop") ||
                lowerTag.contains("hiphop") || lowerTag.contains("rap") -> "Hip Hop"
            lowerTag.contains("country") -> "Country"
            lowerTag.contains("blues") -> "Blues"
            lowerTag.contains("metal") -> "Metal"
            lowerTag.contains("punk") -> "Punk"
            lowerTag.contains("indie") -> "Indie"
            lowerTag.contains("alternative") -> "Alternative"
            lowerTag.contains("folk") -> "Folk"
            lowerTag.contains("reggae") -> "Reggae"
            lowerTag.contains("latin") || lowerTag.contains("salsa") ||
                lowerTag.contains("merengue") -> "Latin"
            lowerTag.contains("r&b") || lowerTag.contains("rnb") ||
                lowerTag.contains("soul") -> "R&B"
            lowerTag.contains("gospel") || lowerTag.contains("christian") ||
                lowerTag.contains("religious") -> "Christian"
            lowerTag.contains("ambient") || lowerTag.contains("chillout") ||
                lowerTag.contains("lounge") -> "Ambient"
            lowerTag.contains("world") -> "World"
            lowerTag.contains("oldies") || lowerTag.contains("80s") ||
                lowerTag.contains("70s") || lowerTag.contains("60s") -> "Oldies"
            lowerTag.contains("kpop") || lowerTag.contains("k-pop") -> "K-Pop"
            lowerTag.contains("lofi") || lowerTag.contains("lo-fi") -> "Lo-Fi"
            lowerTag.contains("funk") -> "Funk"
            lowerTag.contains("comedy") -> "Comedy"
            else -> tag.replaceFirstChar { it.uppercase() }.take(20)
        }
    }

    /**
     * Check if this station appears to be working
     */
    fun isLikelyWorking(): Boolean {
        return lastcheckok && !sslError && getStreamUrl().isNotEmpty()
    }

    /**
     * Get a display string for bitrate/codec info
     */
    fun getQualityInfo(): String {
        val parts = mutableListOf<String>()
        if (bitrate > 0) parts.add("${bitrate}kbps")
        if (codec.isNotEmpty()) parts.add(codec.uppercase())
        return parts.joinToString(" • ")
    }
}

/**
 * Enum for different search/browse categories
 */
enum class BrowseCategory {
    TOP_VOTED,
    TOP_CLICKED,
    HISTORY,  // Recently changed stations (placeholder for future user browse history)
    BY_COUNTRY,
    BY_TAG,
    SEARCH
}

/**
 * Result wrapper for RadioBrowser API calls
 */
sealed class RadioBrowserResult<out T> {
    data class Success<T>(val data: T) : RadioBrowserResult<T>()
    data class Error(val message: String, val exception: Exception? = null) : RadioBrowserResult<Nothing>()
    data object Loading : RadioBrowserResult<Nothing>()
}

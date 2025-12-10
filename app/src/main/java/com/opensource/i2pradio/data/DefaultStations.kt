package com.opensource.i2pradio.data

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Provides default/bundled radio stations.
 *
 * Loads stations from bundled_stations.json asset file for a rich first-launch experience.
 * Falls back to hardcoded stations if asset loading fails.
 */
object DefaultStations {
    private const val TAG = "DefaultStations"
    private const val BUNDLED_STATIONS_FILE = "bundled_stations.json"
    private const val I2P_STATIONS_FILE = "i2p_stations.json"
    private const val TOR_STATIONS_FILE = "tor_stations.json"

    /**
     * Get preset stations from bundled JSON asset.
     * Falls back to hardcoded stations if loading fails.
     */
    fun getPresetStations(context: Context): List<RadioStation> {
        return try {
            loadStationsFromFile(context, BUNDLED_STATIONS_FILE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled stations, using fallback", e)
            getFallbackStations()
        }
    }

    /**
     * Get I2P curated stations from JSON asset.
     */
    fun getI2pStations(context: Context): List<RadioStation> {
        return try {
            loadStationsFromFile(context, I2P_STATIONS_FILE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load I2P stations", e)
            emptyList()
        }
    }

    /**
     * Get Tor curated stations from JSON asset.
     */
    fun getTorStations(context: Context): List<RadioStation> {
        return try {
            loadStationsFromFile(context, TOR_STATIONS_FILE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Tor stations", e)
            emptyList()
        }
    }

    /**
     * Load stations from a JSON asset file.
     */
    private fun loadStationsFromFile(context: Context, fileName: String): List<RadioStation> {
        val stations = mutableListOf<RadioStation>()
        val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)
        val stationsArray = json.getJSONArray("stations")

        for (i in 0 until stationsArray.length()) {
            val stationJson = stationsArray.getJSONObject(i)

            // Parse proxy settings
            val useProxy = stationJson.optBoolean("useProxy", false)
            val proxyTypeStr = stationJson.optString("proxyType", ProxyType.NONE.name)
            val proxyType = ProxyType.fromString(proxyTypeStr)

            val station = RadioStation(
                name = stationJson.getString("name"),
                streamUrl = stationJson.getString("streamUrl"),
                genre = stationJson.optString("genre", "Other"),
                country = stationJson.optString("country", ""),
                countryCode = stationJson.optString("countryCode", ""),
                bitrate = stationJson.optInt("bitrate", 0),
                codec = stationJson.optString("codec", ""),
                coverArtUri = stationJson.optString("coverArtUri", null),
                useProxy = useProxy,
                proxyType = proxyType.name,
                proxyHost = stationJson.optString("proxyHost", proxyType.getDefaultHost()),
                proxyPort = stationJson.optInt("proxyPort", proxyType.getDefaultPort()),
                source = StationSource.BUNDLED.name,
                isPreset = false
            )
            stations.add(station)
        }

        Log.d(TAG, "Loaded ${stations.size} bundled stations")
        return stations
    }

    /**
     * Fallback hardcoded stations in case asset loading fails.
     */
    private fun getFallbackStations(): List<RadioStation> {
        return listOf(
            RadioStation(
                name = "BBC World Service",
                streamUrl = "http://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
                genre = "News",
                country = "United Kingdom",
                countryCode = "GB",
                coverArtUri = "http://cdn-profiles.tunein.com/s24948/images/logoq.jpg",
                bitrate = 96,
                codec = "MP3",
                useProxy = false,
                proxyType = ProxyType.NONE.name,
                proxyHost = "",
                proxyPort = 0,
                source = StationSource.BUNDLED.name,
                isPreset = false
            ),
            RadioStation(
                name = "Aktuelle HITS 24/7",
                streamUrl = "https://rautemusik.stream43.radiohost.de/breakz",
                genre = "Dance",
                coverArtUri = "https://i.ibb.co/3mtCKLC/aktHits.jpg",
                useProxy = false,
                proxyType = ProxyType.NONE.name,
                proxyHost = "",
                proxyPort = 0,
                source = StationSource.BUNDLED.name,
                isPreset = false
            ),
            RadioStation(
                name = "MANGORADIO",
                streamUrl = "https://mangoradio.stream.laut.fm/mangoradio",
                genre = "Music",
                coverArtUri = "https://mangoradio.de/wp-content/uploads/cropped-Logo-192x192.webp",
                useProxy = false,
                proxyType = ProxyType.NONE.name,
                proxyHost = "",
                proxyPort = 0,
                source = StationSource.BUNDLED.name,
                isPreset = false
            )
        )
    }

    /**
     * Legacy method for backward compatibility.
     * @deprecated Use getPresetStations(context) instead
     */
    @Deprecated("Use getPresetStations(context) instead", ReplaceWith("getPresetStations(context)"))
    fun getPresetStations(): List<RadioStation> {
        return getFallbackStations()
    }
}

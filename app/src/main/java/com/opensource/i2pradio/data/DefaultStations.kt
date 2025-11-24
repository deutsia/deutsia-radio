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

    /**
     * Get preset stations from bundled JSON asset.
     * Falls back to hardcoded stations if loading fails.
     */
    fun getPresetStations(context: Context): List<RadioStation> {
        return try {
            loadBundledStations(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled stations, using fallback", e)
            getFallbackStations()
        }
    }

    /**
     * Load stations from the bundled JSON asset file.
     */
    private fun loadBundledStations(context: Context): List<RadioStation> {
        val stations = mutableListOf<RadioStation>()
        val jsonString = context.assets.open(BUNDLED_STATIONS_FILE).bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)
        val stationsArray = json.getJSONArray("stations")

        for (i in 0 until stationsArray.length()) {
            val stationJson = stationsArray.getJSONObject(i)
            val station = RadioStation(
                name = stationJson.getString("name"),
                streamUrl = stationJson.getString("streamUrl"),
                genre = stationJson.optString("genre", "Other"),
                country = stationJson.optString("country", ""),
                countryCode = stationJson.optString("countryCode", ""),
                bitrate = stationJson.optInt("bitrate", 0),
                codec = stationJson.optString("codec", ""),
                source = StationSource.BUNDLED.name,
                isPreset = false,
                useProxy = false
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
                source = StationSource.BUNDLED.name,
                isPreset = false,
                useProxy = false
            ),
            RadioStation(
                name = "NPR News",
                streamUrl = "https://npr-ice.streamguys1.com/live.mp3",
                genre = "News",
                source = StationSource.BUNDLED.name,
                isPreset = false,
                useProxy = false
            ),
            RadioStation(
                name = "Classical KING FM",
                streamUrl = "https://classicalking.streamguys1.com/king-aac-64k",
                genre = "Classical",
                source = StationSource.BUNDLED.name,
                isPreset = false,
                useProxy = false
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

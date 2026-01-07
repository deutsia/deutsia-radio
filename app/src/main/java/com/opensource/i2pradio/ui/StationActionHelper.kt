package com.opensource.i2pradio.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserRepository
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserStation

/**
 * Result of a like toggle action.
 * Contains all information needed to update UI and show appropriate toast.
 */
data class LikeActionResult(
    val isLiked: Boolean,
    val stationId: Long,
    val radioBrowserUuid: String,
    val wasAlreadySaved: Boolean,
    val stationAgeMillis: Long
)

/**
 * Result of a save/remove action.
 */
data class SaveActionResult(
    val isSaved: Boolean,
    val radioBrowserUuid: String
)

/**
 * Helper class that provides shared logic for station actions (like, save to library).
 * Used by both NowPlayingFragment and BrowseStationsFragment to ensure consistent behavior.
 */
object StationActionHelper {

    /**
     * Toggle like status for a global radio station.
     *
     * Logic matches BrowseStationsFragment/BrowseViewModel:
     * - If not liked: Save station as liked
     * - If liked: Delete the station entirely from the library
     *
     * @param repository The RadioBrowserRepository to use
     * @param station The RadioBrowserStation to toggle
     * @param savedUuids Set of currently saved station UUIDs (for determining if already saved)
     * @param likedUuids Set of currently liked station UUIDs
     * @return LikeActionResult with all information needed for UI updates
     */
    suspend fun toggleLikeForGlobalRadio(
        repository: RadioBrowserRepository,
        station: RadioBrowserStation,
        savedUuids: Set<String>,
        likedUuids: Set<String>
    ): LikeActionResult {
        val uuid = station.stationuuid
        val wasLiked = likedUuids.contains(uuid)
        val wasSaved = savedUuids.contains(uuid)

        // Get station info before the operation to calculate age
        val existingStation = repository.getStationInfoByUuid(uuid)
        val stationAge = if (existingStation != null) {
            System.currentTimeMillis() - existingStation.addedTimestamp
        } else {
            0L
        }

        val resultStationId: Long
        val resultIsLiked: Boolean

        if (wasLiked) {
            // Unlike: Delete the station entirely from the library (matches BrowseViewModel behavior)
            repository.deleteStationByUuid(uuid)
            resultStationId = existingStation?.id ?: -1L
            resultIsLiked = false
        } else {
            // Like: Save the station as liked
            val id = repository.saveStationAsLiked(station)
            resultStationId = id ?: -1L
            resultIsLiked = true
        }

        return LikeActionResult(
            isLiked = resultIsLiked,
            stationId = resultStationId,
            radioBrowserUuid = uuid,
            wasAlreadySaved = wasSaved,
            stationAgeMillis = stationAge
        )
    }

    /**
     * Toggle like status for a global radio using RadioStation (from ViewModel).
     * Converts to RadioBrowserStation format internally.
     */
    suspend fun toggleLikeForGlobalRadio(
        repository: RadioBrowserRepository,
        station: RadioStation,
        savedUuids: Set<String>,
        likedUuids: Set<String>
    ): LikeActionResult {
        val radioBrowserStation = convertToRadioBrowserStation(station)
        return toggleLikeForGlobalRadio(repository, radioBrowserStation, savedUuids, likedUuids)
    }

    /**
     * Toggle like status for a global radio station, querying the database for current state.
     * This is a convenience method for use in NowPlayingFragment where we don't have access
     * to the BrowseViewModel's UUID sets.
     *
     * Logic matches BrowseStationsFragment/BrowseViewModel:
     * - If not liked: Save station as liked
     * - If liked: Delete the station entirely from the library
     *
     * @param repository The RadioBrowserRepository to use
     * @param station The RadioStation to toggle
     * @return LikeActionResult with all information needed for UI updates
     */
    suspend fun toggleLikeForGlobalRadio(
        repository: RadioBrowserRepository,
        station: RadioStation
    ): LikeActionResult {
        val uuid = station.radioBrowserUuid!!

        // Query database for current state
        val existingStation = repository.getStationInfoByUuid(uuid)
        val wasLiked = existingStation?.isLiked ?: false
        val wasSaved = existingStation != null
        val stationAge = if (existingStation != null) {
            System.currentTimeMillis() - existingStation.addedTimestamp
        } else {
            0L
        }

        val resultStationId: Long
        val resultIsLiked: Boolean

        if (wasLiked) {
            // Unlike: Delete the station entirely from the library (matches BrowseViewModel behavior)
            repository.deleteStationByUuid(uuid)
            resultStationId = existingStation?.id ?: -1L
            resultIsLiked = false
        } else {
            // Like: Save the station as liked
            val radioBrowserStation = convertToRadioBrowserStation(station)
            val id = repository.saveStationAsLiked(radioBrowserStation)
            resultStationId = id ?: -1L
            resultIsLiked = true
        }

        return LikeActionResult(
            isLiked = resultIsLiked,
            stationId = resultStationId,
            radioBrowserUuid = uuid,
            wasAlreadySaved = wasSaved,
            stationAgeMillis = stationAge
        )
    }

    /**
     * Toggle save status for a global radio station.
     *
     * @param repository The RadioBrowserRepository to use
     * @param station The RadioBrowserStation to toggle
     * @param savedUuids Set of currently saved station UUIDs
     * @return SaveActionResult with all information needed for UI updates
     */
    suspend fun toggleSaveForGlobalRadio(
        repository: RadioBrowserRepository,
        station: RadioBrowserStation,
        savedUuids: Set<String>
    ): SaveActionResult {
        val uuid = station.stationuuid
        val wasSaved = savedUuids.contains(uuid)

        if (wasSaved) {
            // Remove from library
            repository.deleteStationByUuid(uuid)
        } else {
            // Add to library
            repository.saveStation(station, asUserStation = true)
        }

        return SaveActionResult(
            isSaved = !wasSaved,
            radioBrowserUuid = uuid
        )
    }

    /**
     * Toggle save status using RadioStation (from ViewModel).
     */
    suspend fun toggleSaveForGlobalRadio(
        repository: RadioBrowserRepository,
        station: RadioStation,
        savedUuids: Set<String>
    ): SaveActionResult {
        val radioBrowserStation = convertToRadioBrowserStation(station)
        return toggleSaveForGlobalRadio(repository, radioBrowserStation, savedUuids)
    }

    /**
     * Toggle save status for a global radio station, querying the database for current state.
     * This is a convenience method for use in NowPlayingFragment.
     *
     * @param repository The RadioBrowserRepository to use
     * @param station The RadioStation to toggle
     * @return SaveActionResult with all information needed for UI updates
     */
    suspend fun toggleSaveForGlobalRadio(
        repository: RadioBrowserRepository,
        station: RadioStation
    ): SaveActionResult {
        val uuid = station.radioBrowserUuid!!

        // Query database to check if saved
        val existingStation = repository.getStationInfoByUuid(uuid)
        val wasSaved = existingStation != null

        if (wasSaved) {
            // Remove from library
            repository.deleteStationByUuid(uuid)
        } else {
            // Add to library
            val radioBrowserStation = convertToRadioBrowserStation(station)
            repository.saveStation(radioBrowserStation, asUserStation = true)
        }

        return SaveActionResult(
            isSaved = !wasSaved,
            radioBrowserUuid = uuid
        )
    }

    /**
     * Convert a RadioStation to RadioBrowserStation format.
     */
    private fun convertToRadioBrowserStation(station: RadioStation): RadioBrowserStation {
        return RadioBrowserStation(
            stationuuid = station.radioBrowserUuid!!,
            name = station.name,
            url = station.streamUrl,
            urlResolved = station.streamUrl,
            homepage = station.homepage ?: "",
            favicon = station.coverArtUri ?: "",
            tags = station.genre,
            country = station.country ?: "",
            countrycode = station.countryCode ?: "",
            state = "",
            language = "",
            languagecodes = "",
            votes = 0,
            lastchangetime = "",
            codec = station.codec ?: "",
            bitrate = station.bitrate,
            hls = false,
            lastcheckok = true,
            clickcount = 0,
            clicktrend = 0,
            sslError = false,
            geoLat = null,
            geoLong = null
        )
    }

    /**
     * Show the appropriate toast message for a like action.
     * Matches the behavior in BrowseStationsFragment.likeStation().
     */
    fun showLikeToast(context: Context, stationName: String, result: LikeActionResult) {
        if (PreferencesHelper.isToastMessagesDisabled(context)) return

        val message = if (result.isLiked) {
            if (!result.wasAlreadySaved) {
                // Station was not saved before - show "added to library"
                context.getString(R.string.station_saved, stationName)
            } else {
                // Station was already saved - show "added to favorites"
                context.getString(R.string.station_added_to_favorites, stationName)
            }
        } else {
            // Unliking
            val fiveMinutesInMillis = 5 * 60 * 1000L
            if (result.stationAgeMillis > fiveMinutesInMillis) {
                // Station has been saved for more than 5 minutes - show "removed from favorites"
                context.getString(R.string.station_removed_from_favorites, stationName)
            } else {
                // Station was recently saved - show "removed from library"
                context.getString(R.string.station_removed, stationName)
            }
        }

        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Show toast for save/remove action.
     */
    fun showSaveToast(context: Context, stationName: String, isSaved: Boolean) {
        if (PreferencesHelper.isToastMessagesDisabled(context)) return

        val message = if (isSaved) {
            context.getString(R.string.station_saved, stationName)
        } else {
            context.getString(R.string.station_removed, stationName)
        }

        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Broadcast like state change to all views.
     */
    fun broadcastLikeStateChange(context: Context, result: LikeActionResult) {
        val broadcastIntent = Intent(MainActivity.BROADCAST_LIKE_STATE_CHANGED).apply {
            putExtra(MainActivity.EXTRA_IS_LIKED, result.isLiked)
            putExtra(MainActivity.EXTRA_STATION_ID, result.stationId)
            putExtra(MainActivity.EXTRA_RADIO_BROWSER_UUID, result.radioBrowserUuid)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent)
    }

    /**
     * Broadcast saved state change to all views.
     */
    fun broadcastSaveStateChange(context: Context, result: SaveActionResult) {
        val broadcastIntent = Intent(MainActivity.BROADCAST_SAVED_STATE_CHANGED).apply {
            putExtra(MainActivity.EXTRA_IS_SAVED, result.isSaved)
            putExtra(MainActivity.EXTRA_RADIO_BROWSER_UUID, result.radioBrowserUuid)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent)
    }
}

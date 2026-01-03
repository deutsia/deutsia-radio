package com.opensource.i2pradio.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.opensource.i2pradio.ui.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SortOrder {
    DEFAULT,         // Liked first, then presets, then by added time
    NAME,            // Alphabetical by name
    RECENTLY_PLAYED, // Most recently played first
    LIKED,           // Only liked stations
    GENRE            // Sorted by genre alphabetically, then by name
}

class RadioRepository(context: Context) {
    private val radioDao = RadioDatabase.getDatabase(context).radioDao()

    val allStations: LiveData<List<RadioStation>> = radioDao.getAllStations()

    fun getStationsSorted(sortOrder: SortOrder): LiveData<List<RadioStation>> {
        return when (sortOrder) {
            SortOrder.DEFAULT -> radioDao.getAllStations()
            SortOrder.NAME -> radioDao.getAllStationsSortedByName()
            SortOrder.RECENTLY_PLAYED -> radioDao.getAllStationsSortedByRecentlyPlayed()
            SortOrder.LIKED -> radioDao.getLikedStations()
            SortOrder.GENRE -> radioDao.getAllStationsSortedByGenre()
        }
    }

    fun getStationsByGenreSorted(genre: String, sortOrder: SortOrder): LiveData<List<RadioStation>> {
        return when (sortOrder) {
            SortOrder.DEFAULT -> radioDao.getStationsByGenreDefault(genre)
            SortOrder.NAME -> radioDao.getStationsByGenreSortedByName(genre)
            SortOrder.RECENTLY_PLAYED -> radioDao.getStationsByGenreSortedByRecentlyPlayed(genre)
            SortOrder.LIKED -> radioDao.getLikedStationsByGenre(genre)
            SortOrder.GENRE -> radioDao.getStationsByGenreDefault(genre) // Same as default when filtered by genre
        }
    }

    fun getAllGenres(): LiveData<List<String>> = radioDao.getAllGenres()

    suspend fun getAllGenresSync(): List<String> {
        return withContext(Dispatchers.IO) {
            radioDao.getAllGenresSync()
        }
    }

    suspend fun getAllStationsSync(): List<RadioStation> {
        return withContext(Dispatchers.IO) {
            radioDao.getAllStationsSync()
        }
    }

    fun getLikedStations(): LiveData<List<RadioStation>> = radioDao.getLikedStations()

    suspend fun insertStation(station: RadioStation): Long {
        return withContext(Dispatchers.IO) {
            radioDao.insertStation(station)
        }
    }

    suspend fun updateStation(station: RadioStation) {
        withContext(Dispatchers.IO) {
            radioDao.updateStation(station)
        }
    }

    suspend fun deleteStation(station: RadioStation) {
        withContext(Dispatchers.IO) {
            radioDao.deleteStation(station)
        }
    }

    suspend fun deleteStationsByIds(stationIds: List<Long>) {
        withContext(Dispatchers.IO) {
            radioDao.deleteStationsByIds(stationIds)
        }
    }

    suspend fun getStationById(id: Long): RadioStation? {
        return withContext(Dispatchers.IO) {
            radioDao.getStationById(id)
        }
    }

    suspend fun toggleLike(stationId: Long) {
        withContext(Dispatchers.IO) {
            radioDao.toggleLike(stationId)
        }
    }

    suspend fun updateLastPlayedAt(stationId: Long) {
        withContext(Dispatchers.IO) {
            radioDao.updateLastPlayedAt(stationId, System.currentTimeMillis())
        }
    }

    suspend fun initializePresetStations(context: Context) {
        withContext(Dispatchers.IO) {
            // Check if we've already initialized presets
            if (PreferencesHelper.arePresetsInitialized(context)) {
                return@withContext
            }

            // Add preset stations from bundled JSON
            DefaultStations.getPresetStations(context).forEach { station ->
                radioDao.insertStation(station)
            }

            // Mark as initialized
            PreferencesHelper.setPresetsInitialized(context, true)
        }
    }

    /**
     * Clear all cached API stations that are not liked by the user.
     * This removes leftover cache entries from the old caching system.
     */
    suspend fun clearUnlikedCachedStations() {
        withContext(Dispatchers.IO) {
            // Delete all cached stations older than now (i.e., all of them)
            radioDao.deleteStaleCachedStations(System.currentTimeMillis() + 1)
        }
    }
}

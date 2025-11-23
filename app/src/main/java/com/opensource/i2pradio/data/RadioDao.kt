package com.opensource.i2pradio.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface RadioDao {
    // Default sorting: liked first, then presets, then by added time
    @Query("SELECT * FROM radio_stations ORDER BY isLiked DESC, isPreset DESC, addedTimestamp DESC")
    fun getAllStations(): LiveData<List<RadioStation>>

    // Sort by name alphabetically
    @Query("SELECT * FROM radio_stations ORDER BY isLiked DESC, name ASC")
    fun getAllStationsSortedByName(): LiveData<List<RadioStation>>

    // Sort by recently played
    @Query("SELECT * FROM radio_stations ORDER BY isLiked DESC, lastPlayedAt DESC")
    fun getAllStationsSortedByRecentlyPlayed(): LiveData<List<RadioStation>>

    // Get only liked stations
    @Query("SELECT * FROM radio_stations WHERE isLiked = 1 ORDER BY name ASC")
    fun getLikedStations(): LiveData<List<RadioStation>>

    @Query("SELECT * FROM radio_stations WHERE genre = :genre ORDER BY addedTimestamp DESC")
    fun getStationsByGenre(genre: String): LiveData<List<RadioStation>>

    @Query("SELECT * FROM radio_stations WHERE id = :id")
    suspend fun getStationById(id: Long): RadioStation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStation(station: RadioStation): Long

    @Update
    suspend fun updateStation(station: RadioStation)

    @Delete
    suspend fun deleteStation(station: RadioStation)

    @Query("DELETE FROM radio_stations WHERE id = :id")
    suspend fun deleteStationById(id: Long)

    // Toggle like status
    @Query("UPDATE radio_stations SET isLiked = NOT isLiked WHERE id = :id")
    suspend fun toggleLike(id: Long)

    // Update last played timestamp
    @Query("UPDATE radio_stations SET lastPlayedAt = :timestamp WHERE id = :id")
    suspend fun updateLastPlayedAt(id: Long, timestamp: Long)
}

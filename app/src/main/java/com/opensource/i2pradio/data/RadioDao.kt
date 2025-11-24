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

    // Sort by genre (alphabetically by genre, then by station name)
    @Query("SELECT * FROM radio_stations ORDER BY genre ASC, name ASC")
    fun getAllStationsSortedByGenre(): LiveData<List<RadioStation>>

    @Query("SELECT * FROM radio_stations WHERE genre = :genre ORDER BY addedTimestamp DESC")
    fun getStationsByGenre(genre: String): LiveData<List<RadioStation>>

    // Get stations by genre with different sort orders
    @Query("SELECT * FROM radio_stations WHERE genre = :genre ORDER BY isLiked DESC, isPreset DESC, addedTimestamp DESC")
    fun getStationsByGenreDefault(genre: String): LiveData<List<RadioStation>>

    @Query("SELECT * FROM radio_stations WHERE genre = :genre ORDER BY isLiked DESC, name ASC")
    fun getStationsByGenreSortedByName(genre: String): LiveData<List<RadioStation>>

    @Query("SELECT * FROM radio_stations WHERE genre = :genre ORDER BY isLiked DESC, lastPlayedAt DESC")
    fun getStationsByGenreSortedByRecentlyPlayed(genre: String): LiveData<List<RadioStation>>

    @Query("SELECT * FROM radio_stations WHERE genre = :genre AND isLiked = 1 ORDER BY name ASC")
    fun getLikedStationsByGenre(genre: String): LiveData<List<RadioStation>>

    // Get all unique genres
    @Query("SELECT DISTINCT genre FROM radio_stations ORDER BY genre ASC")
    fun getAllGenres(): LiveData<List<String>>

    // Synchronous version for immediate access
    @Query("SELECT DISTINCT genre FROM radio_stations ORDER BY genre ASC")
    suspend fun getAllGenresSync(): List<String>

    // Get all stations synchronously for search filtering
    @Query("SELECT * FROM radio_stations ORDER BY isLiked DESC, isPreset DESC, addedTimestamp DESC")
    suspend fun getAllStationsSync(): List<RadioStation>

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

    // ==================== RadioBrowser Integration ====================

    // Get station by RadioBrowser UUID (for deduplication)
    @Query("SELECT * FROM radio_stations WHERE radioBrowserUuid = :uuid LIMIT 1")
    suspend fun getStationByRadioBrowserUuid(uuid: String): RadioStation?

    // Check if a RadioBrowser station is already saved
    @Query("SELECT COUNT(*) FROM radio_stations WHERE radioBrowserUuid = :uuid")
    suspend fun countByRadioBrowserUuid(uuid: String): Int

    // Get all stations from RadioBrowser source
    @Query("SELECT * FROM radio_stations WHERE source = 'RADIOBROWSER' ORDER BY cachedAt DESC")
    fun getRadioBrowserStations(): LiveData<List<RadioStation>>

    // Get cached stations by country
    @Query("SELECT * FROM radio_stations WHERE source = 'RADIOBROWSER' AND countryCode = :countryCode ORDER BY name ASC")
    suspend fun getCachedStationsByCountry(countryCode: String): List<RadioStation>

    // Delete stale cached stations (older than given timestamp)
    @Query("DELETE FROM radio_stations WHERE source = 'RADIOBROWSER' AND isLiked = 0 AND cachedAt < :olderThan")
    suspend fun deleteStaleCachedStations(olderThan: Long)

    // Get stations by source
    @Query("SELECT * FROM radio_stations WHERE source = :source ORDER BY name ASC")
    suspend fun getStationsBySource(source: String): List<RadioStation>

    // Insert multiple stations at once
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<RadioStation>)

    // Update cached station's verification timestamp
    @Query("UPDATE radio_stations SET lastVerified = :timestamp WHERE id = :id")
    suspend fun updateLastVerified(id: Long, timestamp: Long)
}

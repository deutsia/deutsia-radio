package com.opensource.i2pradio.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface RadioDao {
    @Query("SELECT * FROM radio_stations ORDER BY isPreset DESC, addedTimestamp DESC")
    fun getAllStations(): LiveData<List<RadioStation>>

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
}
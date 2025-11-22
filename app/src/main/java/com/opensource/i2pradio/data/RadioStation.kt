package com.opensource.i2pradio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "radio_stations")
data class RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val proxyHost: String = "",
    val proxyPort: Int = 4444,
    val useProxy: Boolean = false,
    val genre: String = "Other",
    val coverArtUri: String? = null,
    val isPreset: Boolean = false,
    val addedTimestamp: Long = System.currentTimeMillis()
)
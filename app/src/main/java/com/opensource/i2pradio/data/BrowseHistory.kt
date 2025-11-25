package com.opensource.i2pradio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity to track stations visited/played from the Browse section.
 * Keeps a history of the last 75 stations the user interacted with in browse.
 */
@Entity(tableName = "browse_history")
data class BrowseHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * RadioBrowser UUID of the station
     */
    val radioBrowserUuid: String,

    /**
     * Timestamp when the station was visited/played from browse
     */
    val visitedAt: Long = System.currentTimeMillis(),

    /**
     * Station name (cached for display without needing to join with radio_stations table)
     */
    val stationName: String,

    /**
     * Station stream URL (cached)
     */
    val streamUrl: String,

    /**
     * Station cover art URI (cached)
     */
    val coverArtUri: String? = null,

    /**
     * Station country (cached)
     */
    val country: String = "",

    /**
     * Station genre/tags (cached)
     */
    val genre: String = ""
)

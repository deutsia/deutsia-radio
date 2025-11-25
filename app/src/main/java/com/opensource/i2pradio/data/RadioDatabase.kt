package com.opensource.i2pradio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RadioStation::class, BrowseHistory::class], version = 5, exportSchema = false)
abstract class RadioDatabase : RoomDatabase() {
    abstract fun radioDao(): RadioDao

    companion object {
        @Volatile
        private var INSTANCE: RadioDatabase? = null

        // Migration from version 1 to 2: Add proxyType column
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add proxyType column with default value "NONE"
                // For existing stations with useProxy=true, set proxyType to "I2P"
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN proxyType TEXT NOT NULL DEFAULT 'NONE'"
                )
                // Update existing proxy stations to use I2P type
                database.execSQL(
                    "UPDATE radio_stations SET proxyType = 'I2P' WHERE useProxy = 1"
                )
            }
        }

        // Migration from version 2 to 3: Add isLiked and lastPlayedAt columns
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN isLiked INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration from version 3 to 4: Add RadioBrowser integration fields
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add source column - default to USER for existing stations
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN source TEXT NOT NULL DEFAULT 'USER'"
                )
                // Add RadioBrowser UUID for deduplication
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN radioBrowserUuid TEXT DEFAULT NULL"
                )
                // Add lastVerified timestamp
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN lastVerified INTEGER NOT NULL DEFAULT 0"
                )
                // Add cachedAt timestamp
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN cachedAt INTEGER NOT NULL DEFAULT 0"
                )
                // Add bitrate
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN bitrate INTEGER NOT NULL DEFAULT 0"
                )
                // Add codec
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN codec TEXT NOT NULL DEFAULT ''"
                )
                // Add country
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN country TEXT NOT NULL DEFAULT ''"
                )
                // Add countryCode
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN countryCode TEXT NOT NULL DEFAULT ''"
                )
                // Add homepage
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN homepage TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        // Migration from version 4 to 5: Add browse_history table
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create browse_history table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS browse_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        radioBrowserUuid TEXT NOT NULL,
                        visitedAt INTEGER NOT NULL,
                        stationName TEXT NOT NULL,
                        streamUrl TEXT NOT NULL,
                        coverArtUri TEXT,
                        country TEXT NOT NULL DEFAULT '',
                        genre TEXT NOT NULL DEFAULT ''
                    )
                """)
                // Create index on radioBrowserUuid for faster lookups
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_browse_history_uuid ON browse_history(radioBrowserUuid)"
                )
                // Create index on visitedAt for faster ordering
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_browse_history_visitedAt ON browse_history(visitedAt DESC)"
                )
            }
        }

        fun getDatabase(context: Context): RadioDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RadioDatabase::class.java,
                    "radio_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()  // Handles both upgrades and downgrades if migration not found
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
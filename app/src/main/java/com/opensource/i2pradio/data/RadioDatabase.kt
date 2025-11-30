package com.opensource.i2pradio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opensource.i2pradio.utils.DatabaseEncryptionManager
import net.sqlcipher.database.SupportFactory

@Database(entities = [RadioStation::class, BrowseHistory::class], version = 8, exportSchema = false)
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

        // Migration from version 5 to 6: Add custom proxy configuration fields
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add customProxyProtocol column (HTTP, HTTPS, SOCKS4, SOCKS5)
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN customProxyProtocol TEXT NOT NULL DEFAULT 'HTTP'"
                )
                // Add proxyUsername for optional authentication
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN proxyUsername TEXT NOT NULL DEFAULT ''"
                )
                // Add proxyPassword for optional authentication
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN proxyPassword TEXT NOT NULL DEFAULT ''"
                )
                // Add proxyAuthType (NONE, BASIC, DIGEST)
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN proxyAuthType TEXT NOT NULL DEFAULT 'NONE'"
                )
                // Add proxyDnsResolution flag
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN proxyDnsResolution INTEGER NOT NULL DEFAULT 1"
                )
                // Add proxyConnectionTimeout in seconds
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN proxyConnectionTimeout INTEGER NOT NULL DEFAULT 30"
                )
                // Add proxyBypassLocalAddresses flag
                database.execSQL(
                    "ALTER TABLE radio_stations ADD COLUMN proxyBypassLocalAddresses INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration from version 6 to 7: Add index on radioBrowserUuid for faster batch lookups
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create index on radioBrowserUuid for faster queries when checking if stations are saved
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_radio_stations_radioBrowserUuid ON radio_stations(radioBrowserUuid)"
                )
            }
        }

        // Migration from version 7 to 8: Encrypt all proxy passwords
        // Note: Actual encryption happens lazily when passwords are accessed
        // This migration just marks the schema change for password encryption
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // No schema changes needed - passwords will be encrypted transparently
                // when accessed through helper methods. Existing plain-text passwords
                // will be automatically migrated to encrypted format on first access.
            }
        }

        fun getDatabase(context: Context): RadioDatabase {
            return INSTANCE ?: synchronized(this) {
                // Initialize SQLCipher
                DatabaseEncryptionManager.initializeSQLCipher(context)

                // Get SupportFactory for encryption (null if encryption disabled)
                val supportFactory = DatabaseEncryptionManager.getSupportFactory(context)

                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    RadioDatabase::class.java,
                    "radio_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()  // Handles both upgrades and downgrades if migration not found

                // Apply SQLCipher encryption if enabled
                if (supportFactory != null) {
                    builder.openHelperFactory(supportFactory)
                }

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Close and clear the database instance
         * Call this before enabling/disabling encryption
         */
        fun closeDatabase() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
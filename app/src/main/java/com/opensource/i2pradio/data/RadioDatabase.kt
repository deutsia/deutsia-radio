package com.opensource.i2pradio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RadioStation::class], version = 2, exportSchema = false)
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

        fun getDatabase(context: Context): RadioDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RadioDatabase::class.java,
                    "radio_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
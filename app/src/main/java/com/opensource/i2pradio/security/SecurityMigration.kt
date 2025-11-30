package com.opensource.i2pradio.security

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Security Migration Utility
 *
 * Handles migration of existing plaintext passwords to encrypted storage.
 * This runs on app startup to ensure all sensitive data is properly encrypted.
 *
 * MIGRATION STRATEGY:
 * 1. Check if migration has already run
 * 2. Read plaintext passwords from legacy storage
 * 3. Write to encrypted storage
 * 4. Verify migration succeeded
 * 5. Delete plaintext data
 * 6. Mark migration complete
 */
object SecurityMigration {

    private const val LEGACY_PREFS_NAME = "DeutsiaRadioPrefs"
    private const val KEY_CUSTOM_PROXY_USERNAME = "custom_proxy_username"
    private const val KEY_CUSTOM_PROXY_PASSWORD = "custom_proxy_password"

    /**
     * Perform security migration from plaintext to encrypted storage
     * Safe to call multiple times - will only migrate once
     */
    suspend fun migrateToEncryptedStorage(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // Initialize secure preferences
            if (!SecurePreferencesManager.initialize(context)) {
                android.util.Log.e("SecurityMigration", "Failed to initialize secure storage")
                return@withContext false
            }

            // Check if migration already completed
            if (SecurePreferencesManager.isMigrationCompleted()) {
                SecurityAuditLogger.logSecurityCheck(
                    "Security Migration",
                    true,
                    "Already completed - skipping"
                )
                return@withContext true
            }

            android.util.Log.i("SecurityMigration", "Starting security migration...")

            // Get legacy preferences
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

            var migratedCount = 0

            // Migrate username
            val username = legacyPrefs.getString(KEY_CUSTOM_PROXY_USERNAME, null)
            if (username != null && username.isNotEmpty()) {
                SecurePreferencesManager.putString(KEY_CUSTOM_PROXY_USERNAME, username)
                migratedCount++
                SecurityAuditLogger.logSecureStorage(
                    KEY_CUSTOM_PROXY_USERNAME,
                    "[MIGRATED: ${username.length} chars]",
                    true
                )
            }

            // Migrate password
            val password = legacyPrefs.getString(KEY_CUSTOM_PROXY_PASSWORD, null)
            if (password != null && password.isNotEmpty()) {
                SecurePreferencesManager.putString(KEY_CUSTOM_PROXY_PASSWORD, password)
                migratedCount++
                SecurityAuditLogger.logPasswordUsage(
                    "Migration: Plaintext -> Encrypted",
                    password.length,
                    true
                )
            }

            // Verify migration
            val verifyUsername = SecurePreferencesManager.getString(KEY_CUSTOM_PROXY_USERNAME, "")
            val verifyPassword = SecurePreferencesManager.getString(KEY_CUSTOM_PROXY_PASSWORD, "")

            val migrationSuccessful = (username == null || verifyUsername == username) &&
                                     (password == null || verifyPassword == password)

            if (migrationSuccessful) {
                // Delete plaintext data from legacy storage
                legacyPrefs.edit()
                    .remove(KEY_CUSTOM_PROXY_USERNAME)
                    .remove(KEY_CUSTOM_PROXY_PASSWORD)
                    .apply()

                // Mark migration complete
                SecurePreferencesManager.setMigrationCompleted()

                android.util.Log.i("SecurityMigration", "Migration completed: $migratedCount items migrated")

                SecurityAuditLogger.logSecurityCheck(
                    "Security Migration",
                    true,
                    "Migrated $migratedCount credentials from plaintext to encrypted storage"
                )

                return@withContext true
            } else {
                android.util.Log.e("SecurityMigration", "Migration verification failed!")

                SecurityAuditLogger.logSecurityCheck(
                    "Security Migration",
                    false,
                    "Migration verification failed - data mismatch"
                )

                return@withContext false
            }

        } catch (e: Exception) {
            android.util.Log.e("SecurityMigration", "Migration failed", e)

            SecurityAuditLogger.logSecurityCheck(
                "Security Migration",
                false,
                "Exception: ${e.message}"
            )

            return@withContext false
        }
    }

    /**
     * Check if there are any plaintext passwords still in legacy storage
     * Returns true if plaintext passwords found (security issue!)
     */
    fun hasPlaintextPasswords(context: Context): Boolean {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val hasPassword = legacyPrefs.contains(KEY_CUSTOM_PROXY_PASSWORD)

        if (hasPassword) {
            android.util.Log.w("SecurityMigration", "⚠️  SECURITY WARNING: Plaintext password found in legacy storage!")
        }

        return hasPassword
    }

    /**
     * Force cleanup of any plaintext passwords
     * Use only after confirming data is migrated
     */
    fun cleanupPlaintextPasswords(context: Context) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

        legacyPrefs.edit()
            .remove(KEY_CUSTOM_PROXY_USERNAME)
            .remove(KEY_CUSTOM_PROXY_PASSWORD)
            .apply()

        android.util.Log.i("SecurityMigration", "Cleaned up plaintext credentials from legacy storage")

        SecurityAuditLogger.logSecurityCheck(
            "Plaintext Cleanup",
            true,
            "Removed plaintext credentials from legacy storage"
        )
    }
}

package com.opensource.i2pradio.data

import android.content.Context
import com.opensource.i2pradio.utils.PasswordEncryptionUtil

/**
 * Helper class for managing encrypted passwords in RadioStation entities.
 * Provides methods to safely get/set passwords with automatic encryption.
 */
object RadioStationPasswordHelper {

    /**
     * Get the decrypted password from a RadioStation
     * Handles both plain-text (legacy) and encrypted passwords
     */
    fun getDecryptedPassword(context: Context, station: RadioStation): String {
        if (station.proxyPassword.isEmpty()) return ""

        // Check if password is already encrypted
        return if (PasswordEncryptionUtil.isEncrypted(context, station.proxyPassword)) {
            // Decrypt it (use safe version to handle errors gracefully)
            PasswordEncryptionUtil.decryptPasswordSafe(context, station.proxyPassword)
        } else {
            // Plain-text password (legacy), return as-is
            station.proxyPassword
        }
    }

    /**
     * Create a copy of RadioStation with encrypted password
     * If password is already encrypted, returns station as-is
     * If password is plain-text, encrypts it and returns updated station
     */
    fun withEncryptedPassword(context: Context, station: RadioStation): RadioStation {
        if (station.proxyPassword.isEmpty()) return station

        // Check if password is already encrypted
        return if (PasswordEncryptionUtil.isEncrypted(context, station.proxyPassword)) {
            // Already encrypted
            station
        } else {
            // Plain-text password, encrypt it
            val encryptedPassword = PasswordEncryptionUtil.encryptPassword(context, station.proxyPassword)
            station.copy(proxyPassword = encryptedPassword)
        }
    }

    /**
     * Create a copy of RadioStation with a new password (will be encrypted)
     * Use this when setting a password from user input
     */
    fun withNewPassword(context: Context, station: RadioStation, plainPassword: String): RadioStation {
        if (plainPassword.isEmpty()) {
            return station.copy(proxyPassword = "")
        }

        val encryptedPassword = PasswordEncryptionUtil.encryptPassword(context, plainPassword)
        return station.copy(proxyPassword = encryptedPassword)
    }

    /**
     * Migrate a station's password to encrypted format if needed
     * Returns updated station if migration occurred, otherwise returns original
     */
    fun migratePasswordIfNeeded(context: Context, station: RadioStation): RadioStation {
        return withEncryptedPassword(context, station)
    }

    /**
     * Check if a station has an encrypted password
     */
    fun hasEncryptedPassword(context: Context, station: RadioStation): Boolean {
        return station.proxyPassword.isNotEmpty() &&
               PasswordEncryptionUtil.isEncrypted(context, station.proxyPassword)
    }

    /**
     * Check if a station has a plain-text password that needs migration
     */
    fun needsPasswordMigration(context: Context, station: RadioStation): Boolean {
        return station.proxyPassword.isNotEmpty() &&
               !PasswordEncryptionUtil.isEncrypted(context, station.proxyPassword)
    }
}

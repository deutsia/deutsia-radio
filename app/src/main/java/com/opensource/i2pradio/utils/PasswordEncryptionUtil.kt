package com.opensource.i2pradio.utils

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Utility class for encrypting and decrypting passwords.
 * Uses Android Jetpack Security library with AES-256-GCM encryption.
 *
 * This provides two encryption methods:
 * 1. EncryptedSharedPreferences for global settings
 * 2. Direct encryption/decryption for database fields
 */
object PasswordEncryptionUtil {
    private const val ENCRYPTED_PREFS_NAME = "encrypted_passwords"
    private const val KEY_CUSTOM_PROXY_PASSWORD = "custom_proxy_password"

    // For database field encryption
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    /**
     * Get or create the master key for encryption
     */
    private fun getMasterKey(context: Context): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Get EncryptedSharedPreferences instance for storing passwords
     */
    private fun getEncryptedPreferences(context: Context): android.content.SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                getMasterKey(context),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Failed to create EncryptedSharedPreferences", e)
        }
    }

    // ===== Global Proxy Password (EncryptedSharedPreferences) =====

    /**
     * Save the global custom proxy password securely
     */
    fun saveCustomProxyPassword(context: Context, password: String) {
        getEncryptedPreferences(context)
            .edit()
            .putString(KEY_CUSTOM_PROXY_PASSWORD, password)
            .apply()
    }

    /**
     * Retrieve the global custom proxy password
     */
    fun getCustomProxyPassword(context: Context): String {
        return getEncryptedPreferences(context)
            .getString(KEY_CUSTOM_PROXY_PASSWORD, "") ?: ""
    }

    /**
     * Clear the global custom proxy password
     */
    fun clearCustomProxyPassword(context: Context) {
        getEncryptedPreferences(context)
            .edit()
            .remove(KEY_CUSTOM_PROXY_PASSWORD)
            .apply()
    }

    // ===== Database Field Encryption (for per-station passwords) =====

    /**
     * Get the encryption key for database fields
     * This is stored in EncryptedSharedPreferences for security
     */
    private fun getDatabaseEncryptionKey(context: Context): SecretKey {
        val prefs = getEncryptedPreferences(context)
        val keyString = prefs.getString("db_encryption_key", null)

        return if (keyString != null) {
            // Decode existing key
            val keyBytes = Base64.decode(keyString, Base64.DEFAULT)
            object : SecretKey {
                override fun getAlgorithm() = "AES"
                override fun getFormat() = "RAW"
                override fun getEncoded() = keyBytes
            }
        } else {
            // Generate new key
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(KEY_SIZE)
            val key = keyGenerator.generateKey()

            // Save key for future use
            val keyBytes = key.encoded
            val keyString = Base64.encodeToString(keyBytes, Base64.DEFAULT)
            prefs.edit().putString("db_encryption_key", keyString).apply()

            key
        }
    }

    /**
     * Encrypt a password for storage in the database
     * Returns Base64-encoded encrypted data with IV prepended
     * Format: [IV(12 bytes)][Encrypted Data][Auth Tag(16 bytes)]
     */
    fun encryptPassword(context: Context, plaintext: String): String {
        if (plaintext.isEmpty()) return ""

        try {
            val key = getDatabaseEncryptionKey(context)
            val cipher = Cipher.getInstance(TRANSFORMATION)

            // Generate random IV
            val iv = ByteArray(GCM_IV_LENGTH)
            java.security.SecureRandom().nextBytes(iv)

            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // Combine IV + encrypted data
            val combined = iv + encryptedBytes

            return Base64.encodeToString(combined, Base64.DEFAULT)
        } catch (e: Exception) {
            throw RuntimeException("Failed to encrypt password", e)
        }
    }

    /**
     * Decrypt a password from the database
     * Expects Base64-encoded data with IV prepended
     */
    fun decryptPassword(context: Context, encrypted: String): String {
        if (encrypted.isEmpty()) return ""

        try {
            val combined = Base64.decode(encrypted, Base64.DEFAULT)

            // Extract IV and encrypted data
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val key = getDatabaseEncryptionKey(context)
            val cipher = Cipher.getInstance(TRANSFORMATION)

            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)

            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // Return empty string on decryption failure (corrupted data)
            android.util.Log.e("PasswordEncryption", "Failed to decrypt password", e)
            return ""
        }
    }

    /**
     * Migrate a plain-text password to encrypted format
     * This is used during database migration
     */
    fun migratePasswordToEncrypted(context: Context, plainPassword: String?): String {
        if (plainPassword.isNullOrEmpty()) return ""

        // Check if already encrypted (starts with valid Base64 and has minimum length)
        return try {
            // Attempt to decrypt - if successful, it's already encrypted
            val decoded = Base64.decode(plainPassword, Base64.DEFAULT)
            if (decoded.size > GCM_IV_LENGTH) {
                // Looks encrypted, return as-is
                plainPassword
            } else {
                // Not encrypted, encrypt it
                encryptPassword(context, plainPassword)
            }
        } catch (e: Exception) {
            // Not valid Base64 or decryption failed, treat as plain text
            encryptPassword(context, plainPassword)
        }
    }

    /**
     * Check if a password string is encrypted
     */
    fun isEncrypted(password: String): Boolean {
        if (password.isEmpty()) return false

        return try {
            val decoded = Base64.decode(password, Base64.DEFAULT)
            // Valid encrypted password should have at least IV + some data + auth tag
            decoded.size > (GCM_IV_LENGTH + 16)
        } catch (e: Exception) {
            false
        }
    }
}

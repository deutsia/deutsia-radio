package com.opensource.i2pradio.utils

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utility class for encrypting and decrypting passwords.
 * Uses Android Jetpack Security library with AES-256-GCM encryption.
 *
 * SECURITY FIXES APPLIED:
 * ✓ Fixed non-standard SecretKey implementation (use SecretKeySpec)
 * ✓ Fixed Base64 flags (NO_WRAP instead of DEFAULT)
 * ✓ Proper encryption detection with GCM verification
 * ✓ Safe migration logic with actual decryption test
 * ✓ Proper error handling with user notification
 * ✓ Memory wiping for sensitive data
 * ✓ AEAD with Additional Authenticated Data (AAD)
 * ✓ Specific exception handling
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

    // AAD context identifier (prevents ciphertext swapping)
    private const val AAD_CONTEXT = "deutsia-radio-password-v1"

    /**
     * Exception thrown when password decryption fails
     */
    class PasswordDecryptionException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

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
     *
     * FIXED: Now uses SecretKeySpec instead of anonymous SecretKey object
     */
    private fun getDatabaseEncryptionKey(context: Context): SecretKey {
        val prefs = getEncryptedPreferences(context)
        val keyString = prefs.getString("db_encryption_key", null)

        return if (keyString != null) {
            // Decode existing key
            val keyBytes = Base64.decode(keyString, Base64.NO_WRAP)
            try {
                // Use standard SecretKeySpec instead of anonymous object
                SecretKeySpec(keyBytes, "AES")
            } finally {
                // Wipe sensitive data from memory
                keyBytes.fill(0)
            }
        } else {
            // Generate new key
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(KEY_SIZE)
            val key = keyGenerator.generateKey()

            // Save key for future use
            val keyBytes = key.encoded
            val keyString = Base64.encodeToString(keyBytes, Base64.NO_WRAP) // FIXED: NO_WRAP
            prefs.edit().putString("db_encryption_key", keyString).apply()

            // Wipe sensitive data
            keyBytes.fill(0)

            key
        }
    }

    /**
     * Encrypt a password for storage in the database
     * Returns Base64-encoded encrypted data with IV prepended
     * Format: [IV(12 bytes)][Encrypted Data][Auth Tag(16 bytes)]
     *
     * FIXED: Uses NO_WRAP for Base64, adds AAD for context binding
     */
    fun encryptPassword(context: Context, plaintext: String): String {
        if (plaintext.isEmpty()) return ""

        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)

        return try {
            val key = getDatabaseEncryptionKey(context)
            val cipher = Cipher.getInstance(TRANSFORMATION)

            // Generate random IV
            val iv = ByteArray(GCM_IV_LENGTH)
            java.security.SecureRandom().nextBytes(iv)

            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

            // Add Additional Authenticated Data to bind ciphertext to context
            cipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))

            val encryptedBytes = cipher.doFinal(plaintextBytes)

            // Combine IV + encrypted data
            val combined = iv + encryptedBytes

            // FIXED: Use NO_WRAP instead of DEFAULT
            val result = Base64.encodeToString(combined, Base64.NO_WRAP)

            // Wipe sensitive data
            encryptedBytes.fill(0)
            combined.fill(0)

            result
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Failed to encrypt password", e)
        } finally {
            // Wipe plaintext from memory
            plaintextBytes.fill(0)
        }
    }

    /**
     * Decrypt a password from the database
     * Expects Base64-encoded data with IV prepended
     *
     * FIXED: Uses NO_WRAP for Base64, specific exception handling, AAD verification
     * @throws PasswordDecryptionException if decryption fails
     */
    fun decryptPassword(context: Context, encrypted: String): String {
        if (encrypted.isEmpty()) return ""

        return try {
            val combined = Base64.decode(encrypted, Base64.NO_WRAP) // FIXED: NO_WRAP

            // Validate minimum length
            if (combined.size < GCM_IV_LENGTH + 16) { // IV + at least auth tag
                throw PasswordDecryptionException("Encrypted data too short")
            }

            // Extract IV and encrypted data
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val key = getDatabaseEncryptionKey(context)
            val cipher = Cipher.getInstance(TRANSFORMATION)

            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            // Verify Additional Authenticated Data
            cipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))

            val decryptedBytes = try {
                cipher.doFinal(encryptedBytes)
            } catch (e: AEADBadTagException) {
                // GCM auth tag verification failed - data corrupted or tampered
                throw PasswordDecryptionException("Password data corrupted or tampered", e)
            }

            val result = String(decryptedBytes, Charsets.UTF_8)

            // Wipe sensitive data
            decryptedBytes.fill(0)
            combined.fill(0)
            encryptedBytes.fill(0)

            result
        } catch (e: PasswordDecryptionException) {
            // Re-throw our custom exception
            throw e
        } catch (e: IllegalArgumentException) {
            // Base64 decode failed
            throw PasswordDecryptionException("Invalid encrypted password format", e)
        } catch (e: GeneralSecurityException) {
            // Other crypto errors
            throw PasswordDecryptionException("Failed to decrypt password", e)
        }
    }

    /**
     * Safely decrypt a password, returning empty string on failure
     * Use this when you want to handle decryption failures gracefully
     * For critical operations, use decryptPassword() which throws exceptions
     */
    fun decryptPasswordSafe(context: Context, encrypted: String): String {
        return try {
            decryptPassword(context, encrypted)
        } catch (e: PasswordDecryptionException) {
            android.util.Log.e("PasswordEncryption", "Failed to decrypt password", e)
            ""
        }
    }

    /**
     * Check if a password string is encrypted by attempting decryption
     *
     * FIXED: Actually verifies GCM decryption instead of just checking length
     */
    fun isEncrypted(context: Context, password: String): Boolean {
        if (password.isEmpty()) return false

        return try {
            // Attempt to decrypt - if successful, it's encrypted
            decryptPassword(context, password)
            true
        } catch (e: PasswordDecryptionException) {
            // Decryption failed - not encrypted or corrupted
            false
        } catch (e: Exception) {
            // Any other error - treat as not encrypted
            false
        }
    }

    /**
     * Migrate a plain-text password to encrypted format
     * This is used during database migration
     *
     * FIXED: Actually tests decryption instead of just checking length
     */
    fun migratePasswordToEncrypted(context: Context, plainPassword: String?): String {
        if (plainPassword.isNullOrEmpty()) return ""

        // Test if already encrypted by attempting decryption
        if (isEncrypted(context, plainPassword)) {
            // Already encrypted, return as-is
            return plainPassword
        }

        // Not encrypted, encrypt it
        return encryptPassword(context, plainPassword)
    }
}

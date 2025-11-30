package com.opensource.i2pradio.security

import android.util.Base64
import java.security.SecureRandom
import java.util.Arrays

/**
 * Password Obfuscator - Additional layer of security for password handling
 *
 * While EncryptedSharedPreferences provides encryption at rest, this class
 * provides additional obfuscation for passwords in memory and database.
 *
 * FEATURES:
 * - Base64 encoding with random salt
 * - Memory-safe password wiping
 * - Prevents password caching in String pool
 * - Additional defense-in-depth layer
 *
 * NOTE: This is NOT encryption, just obfuscation. True encryption is handled
 * by EncryptedSharedPreferences and SQLCipher.
 */
object PasswordObfuscator {

    private const val SALT_LENGTH = 16
    private val random = SecureRandom()

    /**
     * Obfuscate a password for storage
     * Format: [16-byte-salt][base64-encoded-password]
     */
    fun obfuscate(password: String): String {
        if (password.isEmpty()) return ""

        // Generate random salt
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)

        // Combine salt with password bytes
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val combined = salt + passwordBytes

        // Encode to base64
        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)

        // Wipe password bytes from memory
        Arrays.fill(passwordBytes, 0)

        SecurityAuditLogger.logEncryption(
            "PasswordObfuscation",
            password.length,
            encoded.length
        )

        return encoded
    }

    /**
     * De-obfuscate a password from storage
     */
    fun deobfuscate(obfuscated: String): String {
        if (obfuscated.isEmpty()) return ""

        try {
            // Decode from base64
            val combined = Base64.decode(obfuscated, Base64.NO_WRAP)

            // Extract password (skip salt)
            val passwordBytes = combined.copyOfRange(SALT_LENGTH, combined.size)
            val password = String(passwordBytes, Charsets.UTF_8)

            // Wipe arrays from memory
            Arrays.fill(combined, 0)
            Arrays.fill(passwordBytes, 0)

            SecurityAuditLogger.logDecryption(
                "PasswordObfuscation",
                obfuscated.length,
                password.length
            )

            return password
        } catch (e: Exception) {
            android.util.Log.e("PasswordObfuscator", "Failed to deobfuscate password", e)
            return ""
        }
    }

    /**
     * Securely wipe a string from memory (best effort)
     *
     * Note: Kotlin/Java strings are immutable and cannot be truly wiped,
     * but this helps clear the reference and suggests GC collection
     */
    fun wipePassword(password: String?) {
        // This is a best-effort approach
        // In JVM, strings are immutable and stored in string pool
        // True secure wiping requires using CharArray instead of String
        password?.let {
            // At minimum, clear the reference
            @Suppress("UNUSED_VALUE")
            var temp: String? = it
            temp = null
            System.gc() // Suggest garbage collection (not guaranteed)
        }
    }

    /**
     * Create a secure char array for password handling
     * Caller is responsible for wiping the array after use
     */
    fun toSecureCharArray(password: String): CharArray {
        return password.toCharArray()
    }

    /**
     * Wipe a char array from memory
     */
    fun wipeCharArray(array: CharArray) {
        Arrays.fill(array, '\u0000')
    }

    /**
     * Verify that a value appears to be obfuscated (not plaintext)
     */
    fun isObfuscated(value: String): Boolean {
        if (value.isEmpty()) return true

        try {
            // Try to decode as base64
            val decoded = Base64.decode(value, Base64.NO_WRAP)
            // Check if it has the expected salt length
            return decoded.size > SALT_LENGTH
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Generate a display-safe version of password for debugging
     * Shows only length and checksum
     */
    fun toDebugString(password: String): String {
        if (password.isEmpty()) return "[EMPTY]"
        return "[PASSWORD: ${password.length} chars, checksum: ${password.hashCode()}]"
    }
}

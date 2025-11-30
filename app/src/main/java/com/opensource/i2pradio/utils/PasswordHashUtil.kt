package com.opensource.i2pradio.utils

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Secure password hashing utility using PBKDF2 with SHA-256.
 *
 * This implementation follows OWASP 2023 recommendations:
 * - PBKDF2-HMAC-SHA256 with 600,000 iterations
 * - 32-byte (256-bit) random salt
 * - 32-byte (256-bit) derived key
 * - Constant-time comparison to prevent timing attacks
 */
object PasswordHashUtil {
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 600_000 // OWASP 2023 recommendation
    private const val SALT_LENGTH = 32 // 256 bits
    private const val HASH_LENGTH = 32 // 256 bits

    /**
     * Generate a cryptographically secure random salt
     * Used for both password hashing and database encryption
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /**
     * Hash a password using PBKDF2-HMAC-SHA256
     *
     * @param password The password to hash
     * @param salt The salt to use
     * @return The derived hash
     */
    private fun hashPassword(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, HASH_LENGTH * 8)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)

        return try {
            factory.generateSecret(spec).encoded
        } finally {
            // Clear sensitive data from memory
            spec.clearPassword()
        }
    }

    /**
     * Hash a password and return the result with salt for storage
     *
     * @param password The password to hash
     * @return Base64-encoded string in format: salt$hash
     */
    fun hashPassword(password: String): String {
        val passwordChars = password.toCharArray()
        return try {
            val salt = generateSalt()
            val hash = hashPassword(passwordChars, salt)

            // Format: salt$hash (both Base64-encoded)
            val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
            val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)

            // Wipe sensitive data
            hash.fill(0)

            "$saltB64\$$hashB64"
        } finally {
            // Clear password from memory
            passwordChars.fill('\u0000')
        }
    }

    /**
     * Derive a cryptographic key from a password and salt
     * This is used for database encryption key derivation
     *
     * @param password The password to derive from
     * @param salt The salt (must be 32 bytes)
     * @return 32-byte derived key suitable for AES-256 encryption
     */
    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        require(salt.size == SALT_LENGTH) { "Salt must be $SALT_LENGTH bytes" }

        val passwordChars = password.toCharArray()
        return try {
            hashPassword(passwordChars, salt)
        } finally {
            // Clear password from memory
            passwordChars.fill('\u0000')
        }
    }

    /**
     * Verify a password against a stored hash using constant-time comparison
     *
     * @param password The password to verify
     * @param storedHash The stored hash in format: salt$hash
     * @return True if password matches, false otherwise
     */
    fun verifyPassword(password: String, storedHash: String): Boolean {
        val passwordChars = password.toCharArray()

        return try {
            // Parse stored hash
            val parts = storedHash.split("$")
            if (parts.size != 2) {
                return false
            }

            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val expectedHash = Base64.decode(parts[1], Base64.NO_WRAP)

            // Hash the provided password with the same salt
            val actualHash = hashPassword(passwordChars, salt)

            // Constant-time comparison to prevent timing attacks
            val result = MessageDigest.isEqual(expectedHash, actualHash)

            // Wipe sensitive data
            actualHash.fill(0)

            result
        } catch (e: Exception) {
            android.util.Log.e("PasswordHashUtil", "Error verifying password", e)
            false
        } finally {
            // Clear password from memory
            passwordChars.fill('\u0000')
        }
    }
}

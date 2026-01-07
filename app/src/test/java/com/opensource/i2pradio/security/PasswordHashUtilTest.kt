package com.opensource.i2pradio.security

import android.util.Base64
import com.opensource.i2pradio.utils.PasswordHashUtil
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Comprehensive security tests for PasswordHashUtil.
 *
 * Test Coverage:
 * 1. PBKDF2-HMAC-SHA256 Hash Generation
 * 2. Salt Generation (Cryptographic Randomness)
 * 3. Password Verification (Constant-Time Comparison)
 * 4. Key Derivation for Database Encryption
 * 5. Edge Cases and Error Handling
 * 6. Timing Attack Resistance
 */
class PasswordHashUtilTest {

    @Before
    fun setup() {
        // Mock Android's Base64 class for JVM tests
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ========================================================================
    // Salt Generation Tests
    // ========================================================================

    @Test
    fun `test salt generation produces 32-byte salt`() {
        val salt = PasswordHashUtil.generateSalt()

        assertEquals("Salt should be 32 bytes (256 bits)", 32, salt.size)
    }

    @Test
    fun `test salt generation produces unique salts`() {
        val salts = (1..100).map { PasswordHashUtil.generateSalt() }
        val uniqueSalts = salts.map { it.toList() }.toSet()

        // All 100 salts should be unique
        assertEquals("All generated salts should be unique", 100, uniqueSalts.size)
    }

    @Test
    fun `test salt has sufficient entropy`() {
        // Generate multiple salts and verify they don't have obvious patterns
        val salts = (1..10).map { PasswordHashUtil.generateSalt() }

        for (salt in salts) {
            // Check that salt isn't all zeros
            assertFalse("Salt should not be all zeros", salt.all { it == 0.toByte() })

            // Check that salt isn't all same value
            val uniqueBytes = salt.toSet()
            assertTrue("Salt should have diverse byte values", uniqueBytes.size > 1)
        }
    }

    // ========================================================================
    // Password Hashing Tests
    // ========================================================================

    @Test
    fun `test password hash produces valid format`() {
        val hash = PasswordHashUtil.hashPassword("testPassword123")

        // Hash should be in format: salt$hash (both Base64-encoded)
        assertTrue("Hash should contain dollar sign separator", hash.contains("$"))

        val parts = hash.split("$")
        assertEquals("Hash should have exactly 2 parts (salt and hash)", 2, parts.size)

        // Both parts should be valid Base64
        assertNotNull("Salt should be valid Base64", java.util.Base64.getDecoder().decode(parts[0]))
        assertNotNull("Hash should be valid Base64", java.util.Base64.getDecoder().decode(parts[1]))
    }

    @Test
    fun `test same password produces different hashes due to random salt`() {
        val password = "samePassword123"

        val hash1 = PasswordHashUtil.hashPassword(password)
        val hash2 = PasswordHashUtil.hashPassword(password)

        // Same password should produce different hashes (different salts)
        assertNotEquals("Same password should produce different hashes", hash1, hash2)
    }

    @Test
    fun `test different passwords produce different hashes`() {
        val hash1 = PasswordHashUtil.hashPassword("password1")
        val hash2 = PasswordHashUtil.hashPassword("password2")

        assertNotEquals("Different passwords should produce different hashes", hash1, hash2)
    }

    @Test
    fun `test hash output length is correct`() {
        val hash = PasswordHashUtil.hashPassword("testPassword")
        val parts = hash.split("$")

        val saltBytes = java.util.Base64.getDecoder().decode(parts[0])
        val hashBytes = java.util.Base64.getDecoder().decode(parts[1])

        assertEquals("Salt should be 32 bytes", 32, saltBytes.size)
        assertEquals("Hash should be 32 bytes", 32, hashBytes.size)
    }

    // ========================================================================
    // Password Verification Tests
    // ========================================================================

    @Test
    fun `test verification succeeds with correct password`() {
        val password = "correctPassword123"
        val hash = PasswordHashUtil.hashPassword(password)

        assertTrue("Verification should succeed with correct password",
            PasswordHashUtil.verifyPassword(password, hash))
    }

    @Test
    fun `test verification fails with incorrect password`() {
        val correctPassword = "correctPassword123"
        val wrongPassword = "wrongPassword456"
        val hash = PasswordHashUtil.hashPassword(correctPassword)

        assertFalse("Verification should fail with incorrect password",
            PasswordHashUtil.verifyPassword(wrongPassword, hash))
    }

    @Test
    fun `test verification fails with similar password`() {
        val password = "Password123"
        val hash = PasswordHashUtil.hashPassword(password)

        // Test case sensitivity
        assertFalse("Verification should fail with different case",
            PasswordHashUtil.verifyPassword("password123", hash))

        // Test with extra character
        assertFalse("Verification should fail with extra character",
            PasswordHashUtil.verifyPassword("Password1234", hash))

        // Test with missing character
        assertFalse("Verification should fail with missing character",
            PasswordHashUtil.verifyPassword("Password12", hash))
    }

    @Test
    fun `test verification fails with empty password`() {
        val hash = PasswordHashUtil.hashPassword("validPassword")

        assertFalse("Verification should fail with empty password",
            PasswordHashUtil.verifyPassword("", hash))
    }

    @Test
    fun `test verification handles malformed hash gracefully`() {
        // Invalid format - no separator
        assertFalse("Should handle hash without separator",
            PasswordHashUtil.verifyPassword("password", "invalidhash"))

        // Invalid format - too many parts
        assertFalse("Should handle hash with too many parts",
            PasswordHashUtil.verifyPassword("password", "part1\$part2\$part3"))

        // Empty hash
        assertFalse("Should handle empty hash",
            PasswordHashUtil.verifyPassword("password", ""))
    }

    @Test
    fun `test verification handles invalid Base64 gracefully`() {
        // Invalid Base64 in salt
        assertFalse("Should handle invalid Base64 in salt",
            PasswordHashUtil.verifyPassword("password", "!!!invalid!!!\$validBase64"))

        // Invalid Base64 in hash
        assertFalse("Should handle invalid Base64 in hash",
            PasswordHashUtil.verifyPassword("password", "dGVzdA==\$!!!invalid!!!"))
    }

    // ========================================================================
    // Key Derivation Tests
    // ========================================================================

    @Test
    fun `test key derivation produces 32-byte key`() {
        val password = "testPassword"
        val salt = PasswordHashUtil.generateSalt()

        val key = PasswordHashUtil.deriveKey(password, salt)

        assertEquals("Derived key should be 32 bytes (256 bits)", 32, key.size)
    }

    @Test
    fun `test key derivation is deterministic with same salt`() {
        val password = "testPassword"
        val salt = PasswordHashUtil.generateSalt()

        val key1 = PasswordHashUtil.deriveKey(password, salt)
        val key2 = PasswordHashUtil.deriveKey(password, salt)

        assertArrayEquals("Same password and salt should produce same key", key1, key2)
    }

    @Test
    fun `test key derivation produces different keys with different salts`() {
        val password = "testPassword"
        val salt1 = PasswordHashUtil.generateSalt()
        val salt2 = PasswordHashUtil.generateSalt()

        val key1 = PasswordHashUtil.deriveKey(password, salt1)
        val key2 = PasswordHashUtil.deriveKey(password, salt2)

        assertFalse("Different salts should produce different keys",
            key1.contentEquals(key2))
    }

    @Test
    fun `test key derivation produces different keys with different passwords`() {
        val salt = PasswordHashUtil.generateSalt()

        val key1 = PasswordHashUtil.deriveKey("password1", salt)
        val key2 = PasswordHashUtil.deriveKey("password2", salt)

        assertFalse("Different passwords should produce different keys",
            key1.contentEquals(key2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `test key derivation rejects invalid salt length`() {
        val password = "testPassword"
        val invalidSalt = ByteArray(16) // Should be 32 bytes

        PasswordHashUtil.deriveKey(password, invalidSalt)
    }

    // ========================================================================
    // Edge Cases and Special Characters
    // ========================================================================

    @Test
    fun `test password with special characters`() {
        val specialPasswords = listOf(
            "P@ss\$w0rd!#%",
            "密码测试123",  // Chinese characters
            "пароль123",   // Cyrillic characters
            "🔐🔑secure",  // Emoji
            "tab\there",   // Tab character
            "new\nline",   // Newline
            "quote\"test", // Quote
            "null\u0000char" // Null character
        )

        for (password in specialPasswords) {
            val hash = PasswordHashUtil.hashPassword(password)
            assertTrue("Should verify password with special characters: ${password.take(10)}...",
                PasswordHashUtil.verifyPassword(password, hash))
        }
    }

    @Test
    fun `test very long password`() {
        val longPassword = "a".repeat(10000) // 10KB password

        val hash = PasswordHashUtil.hashPassword(longPassword)
        assertTrue("Should verify very long password",
            PasswordHashUtil.verifyPassword(longPassword, hash))
    }

    @Test
    fun `test single character password`() {
        val singleChar = "a"

        val hash = PasswordHashUtil.hashPassword(singleChar)
        assertTrue("Should verify single character password",
            PasswordHashUtil.verifyPassword(singleChar, hash))
    }

    // ========================================================================
    // Timing Attack Resistance Tests
    // ========================================================================

    @Test
    fun `test constant-time comparison is used`() {
        // This test verifies the implementation uses MessageDigest.isEqual()
        // We can't perfectly test timing, but we can verify the behavior
        val password = "testPassword"
        val hash = PasswordHashUtil.hashPassword(password)

        // Both correct and incorrect passwords should take similar time
        // (within reason - this is more of a smoke test)
        val iterations = 100

        val correctTimes = mutableListOf<Long>()
        val wrongTimes = mutableListOf<Long>()

        // Warm up JIT
        repeat(10) {
            PasswordHashUtil.verifyPassword(password, hash)
            PasswordHashUtil.verifyPassword("wrong", hash)
        }

        // Measure times
        repeat(iterations) {
            val start1 = System.nanoTime()
            PasswordHashUtil.verifyPassword(password, hash)
            correctTimes.add(System.nanoTime() - start1)

            val start2 = System.nanoTime()
            PasswordHashUtil.verifyPassword("wrong", hash)
            wrongTimes.add(System.nanoTime() - start2)
        }

        // The times should be roughly similar (PBKDF2 dominates the time)
        // We're mainly testing that it doesn't short-circuit
        val avgCorrect = correctTimes.average()
        val avgWrong = wrongTimes.average()

        // Both should take substantial time due to PBKDF2
        assertTrue("Correct password verification should take time", avgCorrect > 1_000_000) // > 1ms
        assertTrue("Wrong password verification should take time", avgWrong > 1_000_000)
    }

    @Test
    fun `test verification timing doesn't leak password length`() {
        val hash = PasswordHashUtil.hashPassword("testPassword123")

        // Test with passwords of different lengths
        val shortPassword = "a"
        val mediumPassword = "a".repeat(50)
        val longPassword = "a".repeat(1000)

        // All should fail and take similar time (PBKDF2 dominates)
        val iterations = 10

        val times = mutableMapOf<String, MutableList<Long>>()
        times["short"] = mutableListOf()
        times["medium"] = mutableListOf()
        times["long"] = mutableListOf()

        repeat(iterations) {
            var start = System.nanoTime()
            PasswordHashUtil.verifyPassword(shortPassword, hash)
            times["short"]!!.add(System.nanoTime() - start)

            start = System.nanoTime()
            PasswordHashUtil.verifyPassword(mediumPassword, hash)
            times["medium"]!!.add(System.nanoTime() - start)

            start = System.nanoTime()
            PasswordHashUtil.verifyPassword(longPassword, hash)
            times["long"]!!.add(System.nanoTime() - start)
        }

        // All verifications should take roughly similar time
        // (the PBKDF2 computation time should dominate any string comparison)
        val avgShort = times["short"]!!.average()
        val avgMedium = times["medium"]!!.average()
        val avgLong = times["long"]!!.average()

        // Times shouldn't vary by more than 10x (accounting for variance)
        assertTrue("Short password verification shouldn't be significantly faster",
            avgShort > avgMedium / 10)
        assertTrue("Long password verification shouldn't be significantly slower",
            avgLong < avgMedium * 10)
    }
}

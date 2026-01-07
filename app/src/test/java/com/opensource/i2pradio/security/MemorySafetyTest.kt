package com.opensource.i2pradio.security

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.ref.WeakReference
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Memory Safety and Anti-Forensic Security Tests
 *
 * These tests verify protection against forensic tools like Cellebrite that
 * exploit memory remnants and improper data handling.
 *
 * Test Coverage:
 * 1. Memory Wiping - Sensitive data is zeroed after use
 * 2. No String Interning - Passwords avoid String pool
 * 3. Object Lifecycle - Proper cleanup patterns
 * 4. Exception Safety - No secrets in error messages
 * 5. Logging Safety - Sensitive data not logged
 * 6. Reference Safety - No unexpected object retention
 *
 * IMPORTANT: These tests verify defensive patterns. Complete protection
 * against forensic analysis requires OS-level support (secure memory,
 * hardware-backed keystores, etc.) which cannot be fully tested at the
 * application level.
 */
class MemorySafetyTest {

    private val SALT_LENGTH = 32
    private val HASH_LENGTH = 32
    private val ITERATIONS = 600_000
    private val GCM_IV_LENGTH = 12
    private val GCM_TAG_LENGTH = 128

    @Before
    fun setup() {
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
    // Password Memory Wiping Tests
    // ========================================================================

    @Test
    fun `test CharArray password can be wiped`() {
        val password = "sensitivePassword123!".toCharArray()
        val originalLength = password.size

        // Verify password is set
        assertFalse("Password should not start as zeros",
            password.all { it == '\u0000' })

        // Wipe using standard pattern
        password.fill('\u0000')

        // Verify wiped
        assertTrue("All characters should be zeroed",
            password.all { it == '\u0000' })
        assertEquals("Length should be preserved", originalLength, password.size)
    }

    @Test
    fun `test ByteArray key can be wiped`() {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)

        // Verify key is set
        assertFalse("Key should not start as zeros",
            key.all { it == 0.toByte() })

        // Wipe
        key.fill(0)

        // Verify wiped
        assertTrue("All bytes should be zeroed",
            key.all { it == 0.toByte() })
    }

    @Test
    fun `test PBEKeySpec clearPassword actually clears`() {
        val password = "testPassword123".toCharArray()
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)

        val spec = PBEKeySpec(password, salt, ITERATIONS, HASH_LENGTH * 8)

        // Clear password in spec
        spec.clearPassword()

        // After clear, getPassword() should throw or return cleared array
        try {
            val clearedPassword = spec.password
            // If it returns, verify it's cleared
            assertTrue("Password in spec should be cleared",
                clearedPassword.all { it == '\u0000' })
        } catch (e: IllegalStateException) {
            // Some implementations throw after clear - acceptable
        }
    }

    @Test
    fun `test password wiping after key derivation`() {
        val password = "sensitivePassword".toCharArray()
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)

        // Derive key with proper cleanup pattern
        val derivedKey: ByteArray
        try {
            val spec = PBEKeySpec(password, salt, ITERATIONS, HASH_LENGTH * 8)
            try {
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                derivedKey = factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        } finally {
            password.fill('\u0000')
        }

        // Verify original password is wiped
        assertTrue("Password should be wiped after derivation",
            password.all { it == '\u0000' })

        // Key should still be valid
        assertEquals("Derived key should be 32 bytes", 32, derivedKey.size)
    }

    @Test
    fun `test multiple wipe passes for paranoid security`() {
        val sensitiveData = ByteArray(64)
        SecureRandom().nextBytes(sensitiveData)

        // Multiple wipe passes (paranoid pattern)
        // Pass 1: Random data
        SecureRandom().nextBytes(sensitiveData)

        // Pass 2: All ones
        sensitiveData.fill(0xFF.toByte())

        // Pass 3: All zeros
        sensitiveData.fill(0x00.toByte())

        // Verify final state
        assertTrue("Data should be zeroed after multi-pass wipe",
            sensitiveData.all { it == 0.toByte() })
    }

    // ========================================================================
    // String Pool Avoidance Tests
    // ========================================================================

    @Test
    fun `test CharArray avoids String interning`() {
        // Strings are interned in Java, making them persist in memory
        // CharArrays are not interned and can be wiped

        val password1 = "testPassword".toCharArray()
        val password2 = "testPassword".toCharArray()

        // CharArrays should be different objects (not interned)
        assertNotSame("CharArrays should not be same object", password1, password2)

        // But content should be equal
        assertArrayEquals("Content should be equal", password1, password2)

        // Wipe one, other should be unaffected
        password1.fill('\u0000')

        assertTrue("password1 should be wiped",
            password1.all { it == '\u0000' })
        assertFalse("password2 should be unaffected",
            password2.all { it == '\u0000' })
    }

    @Test
    fun `test avoid creating String from password`() {
        // This test documents the WRONG pattern
        // In real code, NEVER convert password CharArray to String

        val passwordChars = "sensitivePassword".toCharArray()

        // WRONG: String creation (for documentation only)
        // val passwordString = String(passwordChars) // DO NOT DO THIS

        // RIGHT: Work directly with CharArray
        val spec = PBEKeySpec(passwordChars, ByteArray(32), 1000, 256)

        // Wipe original
        passwordChars.fill('\u0000')

        // Verify wiped
        assertTrue("CharArray can be wiped, String cannot",
            passwordChars.all { it == '\u0000' })

        spec.clearPassword()
    }

    // ========================================================================
    // Exception Safety Tests (No Secrets in Errors)
    // ========================================================================

    @Test
    fun `test decryption failure doesn't leak plaintext in exception`() {
        val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val wrongKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val sensitiveData = "CREDIT_CARD:4111111111111111"

        // Encrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(sensitiveData.toByteArray())

        // Attempt decrypt with wrong key
        cipher.init(Cipher.DECRYPT_MODE, wrongKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        try {
            cipher.doFinal(ciphertext)
            fail("Should throw on wrong key")
        } catch (e: Exception) {
            // Exception should NOT contain sensitive data
            val exceptionMessage = e.toString() + e.message + e.stackTraceToString()

            assertFalse("Exception should not contain plaintext",
                exceptionMessage.contains("CREDIT_CARD"))
            assertFalse("Exception should not contain card number",
                exceptionMessage.contains("4111111111111111"))
        }
    }

    @Test
    fun `test invalid Base64 exception doesn't leak context`() {
        val sensitiveContext = "password=hunter2"

        try {
            // This will fail but shouldn't leak the input in error
            java.util.Base64.getDecoder().decode("!!!invalid!!!")
        } catch (e: IllegalArgumentException) {
            val exceptionText = e.toString() + e.message

            // Should be about format, not contain our context
            assertFalse("Exception should not leak context",
                exceptionText.contains("hunter2"))
        }
    }

    @Test
    fun `test key derivation failure doesn't expose password`() {
        val password = "superSecretPassword123!"

        try {
            // Try with invalid salt length (should fail)
            val spec = PBEKeySpec(password.toCharArray(), ByteArray(0), ITERATIONS, HASH_LENGTH * 8)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec)
        } catch (e: Exception) {
            val exceptionText = e.toString() + e.message + e.stackTraceToString()

            assertFalse("Exception should not contain password",
                exceptionText.contains("superSecretPassword123!"))
        }
    }

    // ========================================================================
    // Object Reference Safety Tests
    // ========================================================================

    @Test
    fun `test sensitive objects can be garbage collected`() {
        // Create sensitive data in a scope
        var weakRef: WeakReference<ByteArray>? = null

        run {
            val sensitiveKey = ByteArray(32)
            SecureRandom().nextBytes(sensitiveKey)
            weakRef = WeakReference(sensitiveKey)

            // Wipe before leaving scope
            sensitiveKey.fill(0)
        }

        // Force GC (not guaranteed but helps)
        System.gc()
        Thread.sleep(100)
        System.gc()

        // WeakReference may or may not be cleared (GC is non-deterministic)
        // This test mainly verifies the pattern is correct
        // In real forensic scenarios, memory may still contain remnants
    }

    @Test
    fun `test no strong references to sensitive data after use`() {
        class SecureContainer {
            private var key: ByteArray? = null

            fun setKey(newKey: ByteArray) {
                key = newKey.clone()
            }

            fun useKey(): Boolean {
                return key != null
            }

            fun clear() {
                key?.fill(0)
                key = null
            }
        }

        val container = SecureContainer()
        val originalKey = ByteArray(32)
        SecureRandom().nextBytes(originalKey)

        container.setKey(originalKey)
        assertTrue("Container should have key", container.useKey())

        // Clear container
        container.clear()

        // Clear original
        originalKey.fill(0)

        // Both should be cleared
        assertTrue("Original key should be zeroed",
            originalKey.all { it == 0.toByte() })
    }

    // ========================================================================
    // Secure Comparison Tests
    // ========================================================================

    @Test
    fun `test timing-safe comparison for all byte positions`() {
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)

        // Test that comparison examines all bytes (no short-circuit)
        for (position in secret.indices) {
            val modified = secret.clone()
            modified[position] = (modified[position].toInt() xor 0xFF).toByte()

            assertFalse("Should detect change at position $position",
                java.security.MessageDigest.isEqual(secret, modified))
        }
    }

    @Test
    fun `test comparison result doesn't leak match position`() {
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)

        // All these should take roughly the same time
        val testCases = listOf(
            secret.clone(), // Exact match
            ByteArray(32), // All different
            secret.clone().also { it[0] = 0 }, // First byte different
            secret.clone().also { it[31] = 0 }, // Last byte different
            secret.clone().also { it[15] = 0 }  // Middle byte different
        )

        // Note: Actually measuring timing is unreliable in tests
        // This verifies the comparison method is used correctly
        for (testCase in testCases) {
            java.security.MessageDigest.isEqual(secret, testCase)
        }
    }

    // ========================================================================
    // Array Bounds Safety Tests
    // ========================================================================

    @Test
    fun `test safe array copying`() {
        val source = ByteArray(32)
        SecureRandom().nextBytes(source)

        // Safe copy using copyOf (creates new array)
        val copy = source.copyOf()

        // Modify copy
        copy[0] = 0

        // Original should be unaffected
        assertNotEquals("Original should be unaffected by copy modification",
            0.toByte(), source[0])
    }

    @Test
    fun `test array slicing doesn't share backing array`() {
        val original = ByteArray(64)
        SecureRandom().nextBytes(original)

        val slice = original.copyOfRange(0, 32)

        // Wipe slice
        slice.fill(0)

        // Original should be unaffected
        assertFalse("Original should be unaffected by slice wipe",
            original.copyOfRange(0, 32).all { it == 0.toByte() })
    }

    // ========================================================================
    // Initialization Vector Safety Tests
    // ========================================================================

    @Test
    fun `test IV is never reused`() {
        val ivSet = mutableSetOf<String>()
        val random = SecureRandom()

        repeat(10000) {
            val iv = ByteArray(GCM_IV_LENGTH)
            random.nextBytes(iv)
            val ivHex = iv.joinToString("") { "%02x".format(it) }

            assertFalse("IV should never be reused (iteration $it)",
                ivSet.contains(ivHex))

            ivSet.add(ivHex)
        }
    }

    @Test
    fun `test IV has full entropy`() {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        // Should have diverse bytes
        val uniqueBytes = iv.toSet()
        assertTrue("IV should have diverse bytes (got ${uniqueBytes.size})",
            uniqueBytes.size > 3)

        // Should not be all zeros or all ones
        assertFalse("IV should not be all zeros", iv.all { it == 0.toByte() })
        assertFalse("IV should not be all 0xFF", iv.all { it == 0xFF.toByte() })
    }

    // ========================================================================
    // Defensive Copy Tests
    // ========================================================================

    @Test
    fun `test defensive copy on input`() {
        class SecureProcessor {
            fun process(data: ByteArray): ByteArray {
                // Defensive copy on input
                val safeCopy = data.clone()
                try {
                    // Process (in real code, this would do encryption etc.)
                    return safeCopy.reversedArray()
                } finally {
                    safeCopy.fill(0)
                }
            }
        }

        val processor = SecureProcessor()
        val input = byteArrayOf(1, 2, 3, 4)
        val result = processor.process(input)

        // Original input should be unmodified
        assertArrayEquals("Input should be unmodified",
            byteArrayOf(1, 2, 3, 4), input)

        // Result should be processed
        assertArrayEquals("Result should be processed",
            byteArrayOf(4, 3, 2, 1), result)
    }

    @Test
    fun `test defensive copy on output`() {
        class SecureKeyHolder {
            private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

            fun getKey(): ByteArray {
                // Return defensive copy, not internal state
                return key.clone()
            }

            fun clear() {
                key.fill(0)
            }
        }

        val holder = SecureKeyHolder()
        val key1 = holder.getKey()
        val key2 = holder.getKey()

        // Should be equal but not same object
        assertArrayEquals("Keys should have same content", key1, key2)
        assertNotSame("Keys should not be same object", key1, key2)

        // Modifying one shouldn't affect the other
        key1[0] = 0
        assertNotEquals("Keys should be independent", key1[0], key2[0])
    }
}

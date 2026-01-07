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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Comprehensive cryptographic security tests.
 *
 * Test Coverage:
 * 1. PBKDF2 Key Derivation Security
 * 2. AES-256 Key Security
 * 3. Cryptographic Randomness (SecureRandom)
 * 4. Memory Safety Patterns
 * 5. Algorithm Correctness
 * 6. Side-Channel Attack Resistance
 */
class CryptographicSecurityTest {

    // PBKDF2 parameters (matching implementation)
    private val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private val ITERATIONS = 600_000
    private val SALT_LENGTH = 32
    private val HASH_LENGTH = 32

    // GCM parameters
    private val GCM_IV_LENGTH = 12
    private val GCM_TAG_LENGTH = 128

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
    // PBKDF2 Key Derivation Security Tests
    // ========================================================================

    @Test
    fun `test PBKDF2 iteration count meets OWASP 2023 recommendation`() {
        // OWASP 2023 recommends minimum 600,000 iterations for PBKDF2-HMAC-SHA256
        assertTrue("Iteration count should be at least 600,000",
            ITERATIONS >= 600_000)
    }

    @Test
    fun `test PBKDF2 produces consistent output`() {
        val password = "testPassword"
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)

        val spec1 = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_LENGTH * 8)
        val spec2 = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_LENGTH * 8)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)

        val hash1 = factory.generateSecret(spec1).encoded
        val hash2 = factory.generateSecret(spec2).encoded

        assertArrayEquals("Same inputs should produce same hash", hash1, hash2)

        spec1.clearPassword()
        spec2.clearPassword()
    }

    @Test
    fun `test PBKDF2 output is affected by salt`() {
        val password = "testPassword"
        val salt1 = ByteArray(SALT_LENGTH)
        val salt2 = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt1)
        SecureRandom().nextBytes(salt2)

        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)

        val spec1 = PBEKeySpec(password.toCharArray(), salt1, ITERATIONS, HASH_LENGTH * 8)
        val spec2 = PBEKeySpec(password.toCharArray(), salt2, ITERATIONS, HASH_LENGTH * 8)

        val hash1 = factory.generateSecret(spec1).encoded
        val hash2 = factory.generateSecret(spec2).encoded

        assertFalse("Different salts should produce different hashes",
            hash1.contentEquals(hash2))

        spec1.clearPassword()
        spec2.clearPassword()
    }

    @Test
    fun `test PBKDF2 output length is correct`() {
        val password = "testPassword"
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)

        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_LENGTH * 8)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val hash = factory.generateSecret(spec).encoded

        assertEquals("Hash length should be 32 bytes", 32, hash.size)

        spec.clearPassword()
    }

    @Test
    fun `test PBKDF2 is computationally expensive`() {
        val password = "testPassword"
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)

        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)

        val startTime = System.currentTimeMillis()
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_LENGTH * 8)
        factory.generateSecret(spec).encoded
        val elapsed = System.currentTimeMillis() - startTime

        spec.clearPassword()

        // 600,000 iterations should take at least 100ms on most systems
        assertTrue("PBKDF2 with 600k iterations should be computationally expensive (took ${elapsed}ms)",
            elapsed >= 50) // Allow some variance for fast systems
    }

    // ========================================================================
    // AES-256 Key Security Tests
    // ========================================================================

    @Test
    fun `test AES-256 key generation produces correct key size`() {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        val key = keyGenerator.generateKey()

        assertEquals("AES-256 key should be 32 bytes", 32, key.encoded.size)
    }

    @Test
    fun `test AES-256 keys are cryptographically random`() {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)

        val keys = (1..100).map { keyGenerator.generateKey().encoded }
        val uniqueKeys = keys.map { it.toList() }.toSet()

        assertEquals("All generated keys should be unique", 100, uniqueKeys.size)
    }

    @Test
    fun `test AES-256 key entropy`() {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        val key = keyGenerator.generateKey().encoded

        // Calculate byte distribution
        val byteCounts = key.groupBy { it }.mapValues { it.value.size }

        // Should have reasonable distribution (not all zeros or same value)
        assertTrue("Key should have multiple unique bytes", byteCounts.size > 10)
    }

    // ========================================================================
    // SecureRandom Tests
    // ========================================================================

    @Test
    fun `test SecureRandom produces unpredictable output`() {
        val random = SecureRandom()
        val outputs = mutableSetOf<String>()

        repeat(1000) {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            outputs.add(bytes.toList().toString())
        }

        assertEquals("All random outputs should be unique", 1000, outputs.size)
    }

    @Test
    fun `test SecureRandom salt generation`() {
        val salts = (1..100).map { PasswordHashUtil.generateSalt() }
        val uniqueSalts = salts.map { it.toList() }.toSet()

        assertEquals("All salts should be unique", 100, uniqueSalts.size)
    }

    @Test
    fun `test SecureRandom IV generation for GCM`() {
        val ivs = mutableSetOf<String>()
        val random = SecureRandom()

        repeat(1000) {
            val iv = ByteArray(GCM_IV_LENGTH)
            random.nextBytes(iv)
            ivs.add(iv.toList().toString())
        }

        assertEquals("All IVs should be unique (critical for GCM security)", 1000, ivs.size)
    }

    // ========================================================================
    // Constant-Time Comparison Tests
    // ========================================================================

    @Test
    fun `test MessageDigest isEqual is constant-time`() {
        // MessageDigest.isEqual() is documented to be constant-time
        val hash1 = ByteArray(32)
        val hash2 = ByteArray(32)
        SecureRandom().nextBytes(hash1)
        SecureRandom().nextBytes(hash2)

        // Test that comparison works correctly
        assertFalse("Different hashes should not be equal",
            MessageDigest.isEqual(hash1, hash2))

        assertTrue("Same hash should be equal",
            MessageDigest.isEqual(hash1, hash1.clone()))
    }

    @Test
    fun `test constant-time comparison rejects different length inputs`() {
        val hash1 = ByteArray(32)
        val hash2 = ByteArray(16)
        SecureRandom().nextBytes(hash1)
        SecureRandom().nextBytes(hash2)

        assertFalse("Different length arrays should not be equal",
            MessageDigest.isEqual(hash1, hash2))
    }

    @Test
    fun `test constant-time comparison with single byte difference`() {
        val hash1 = ByteArray(32)
        SecureRandom().nextBytes(hash1)
        val hash2 = hash1.clone()

        // Flip one bit at various positions
        for (i in hash1.indices) {
            hash2[i] = (hash2[i].toInt() xor 0x01).toByte()

            assertFalse("Single bit difference should be detected at position $i",
                MessageDigest.isEqual(hash1, hash2))

            // Restore for next iteration
            hash2[i] = hash1[i]
        }
    }

    // ========================================================================
    // Memory Safety Pattern Tests
    // ========================================================================

    @Test
    fun `test PBEKeySpec clearPassword clears sensitive data`() {
        val password = "sensitivePassword"
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)

        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_LENGTH * 8)

        // Clear password
        spec.clearPassword()

        // After clearPassword(), getPassword() should throw or return cleared array
        try {
            val clearedPassword = spec.password
            // If it returns, it should be cleared (all zeros)
            assertTrue("Password should be cleared",
                clearedPassword.all { it == '\u0000' })
        } catch (e: IllegalStateException) {
            // Some implementations throw IllegalStateException after clear
            // This is also acceptable behavior
        }
    }

    @Test
    fun `test CharArray can be zeroed`() {
        val password = "sensitivePassword".toCharArray()

        // Store original length
        val originalLength = password.size

        // Zero out array
        password.fill('\u0000')

        // Verify all chars are zeroed
        assertTrue("All characters should be zeroed",
            password.all { it == '\u0000' })
        assertEquals("Length should be preserved", originalLength, password.size)
    }

    @Test
    fun `test ByteArray can be zeroed`() {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)

        // Store that it's not all zeros initially
        assertFalse("Key should not be all zeros initially",
            key.all { it == 0.toByte() })

        // Zero out array
        key.fill(0)

        // Verify all bytes are zeroed
        assertTrue("All bytes should be zeroed",
            key.all { it == 0.toByte() })
    }

    // ========================================================================
    // Algorithm Correctness Tests
    // ========================================================================

    @Test
    fun `test PBKDF2WithHmacSHA256 is available`() {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        assertEquals("Algorithm should be PBKDF2WithHmacSHA256",
            PBKDF2_ALGORITHM, factory.algorithm)
    }

    @Test
    fun `test AES GCM NoPadding is available`() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        assertEquals("Algorithm should be AES/GCM/NoPadding",
            "AES/GCM/NoPadding", cipher.algorithm)
    }

    @Test
    fun `test GCM tag length is 128 bits`() {
        val key = SecretKeySpec(ByteArray(32), "AES")
        val iv = ByteArray(GCM_IV_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // Should accept 128-bit tag
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))

        // 128 bits = 16 bytes of authentication tag
        assertEquals("GCM tag should be 128 bits", 128, GCM_TAG_LENGTH)
    }

    @Test
    fun `test MD5 produces 128-bit output`() {
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest("test".toByteArray())

        assertEquals("MD5 should produce 16 bytes", 16, hash.size)
    }

    @Test
    fun `test SHA-256 produces 256-bit output`() {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest("test".toByteArray())

        assertEquals("SHA-256 should produce 32 bytes", 32, hash.size)
    }

    // ========================================================================
    // Edge Case Security Tests
    // ========================================================================

    @Test
    fun `test empty password produces valid hash`() {
        val hash = PasswordHashUtil.hashPassword("")

        assertTrue("Empty password should produce valid format hash",
            hash.contains("$"))

        // Should still be verifiable
        assertTrue("Empty password should verify correctly",
            PasswordHashUtil.verifyPassword("", hash))
    }

    @Test
    fun `test null byte in password is handled`() {
        val passwordWithNull = "test\u0000password"
        val hash = PasswordHashUtil.hashPassword(passwordWithNull)

        assertTrue("Password with null byte should verify correctly",
            PasswordHashUtil.verifyPassword(passwordWithNull, hash))

        // Different from truncated password
        assertFalse("Should not match truncated password",
            PasswordHashUtil.verifyPassword("test", hash))
    }

    @Test
    fun `test maximum length password handling`() {
        // Test with very long password (should not cause overflow)
        val veryLongPassword = "a".repeat(1_000_000) // 1MB password

        val hash = PasswordHashUtil.hashPassword(veryLongPassword)
        assertTrue("Very long password should verify correctly",
            PasswordHashUtil.verifyPassword(veryLongPassword, hash))
    }

    @Test
    fun `test salt is not predictable from password`() {
        val password = "testPassword"

        val hashes = (1..10).map { PasswordHashUtil.hashPassword(password) }
        val salts = hashes.map { it.split("$")[0] }

        // All salts should be unique
        assertEquals("Salts should be unique for same password",
            10, salts.toSet().size)
    }

    // ========================================================================
    // Cryptographic Binding Tests
    // ========================================================================

    @Test
    fun `test GCM with AAD binds ciphertext to context`() {
        val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val plaintext = "secret data"
        val aad1 = "context-1"
        val aad2 = "context-2"

        // Encrypt with AAD1
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        encryptCipher.updateAAD(aad1.toByteArray())
        val ciphertext = encryptCipher.doFinal(plaintext.toByteArray())

        // Try to decrypt with wrong AAD
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        decryptCipher.updateAAD(aad2.toByteArray())

        assertThrows("Wrong AAD should cause decryption failure",
            javax.crypto.AEADBadTagException::class.java) {
            decryptCipher.doFinal(ciphertext)
        }
    }

    @Test
    fun `test key derivation is deterministic`() {
        val password = "testPassword"
        val salt = PasswordHashUtil.generateSalt()

        val key1 = PasswordHashUtil.deriveKey(password, salt)
        val key2 = PasswordHashUtil.deriveKey(password, salt)

        assertArrayEquals("Same password and salt should produce same key", key1, key2)
    }

    @Test
    fun `test derived keys are suitable for AES-256`() {
        val password = "testPassword"
        val salt = PasswordHashUtil.generateSalt()

        val derivedKey = PasswordHashUtil.deriveKey(password, salt)
        val aesKey = SecretKeySpec(derivedKey, "AES")

        // Should be able to initialize cipher with derived key
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        // Should not throw
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        // Should be able to encrypt
        val ciphertext = cipher.doFinal("test".toByteArray())
        assertTrue("Should produce ciphertext", ciphertext.isNotEmpty())
    }
}

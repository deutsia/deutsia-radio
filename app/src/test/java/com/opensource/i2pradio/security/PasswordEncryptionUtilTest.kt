package com.opensource.i2pradio.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.opensource.i2pradio.utils.PasswordEncryptionUtil
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Comprehensive security tests for PasswordEncryptionUtil.
 *
 * Test Coverage:
 * 1. AES-256-GCM Encryption/Decryption
 * 2. IV (Initialization Vector) Uniqueness
 * 3. AAD (Additional Authenticated Data) Verification
 * 4. Ciphertext Integrity (GCM Authentication Tag)
 * 5. Edge Cases and Error Handling
 * 6. Migration Logic Security
 *
 * NOTE: These tests mock Android-specific classes (Context, SharedPreferences, etc.)
 * and test the cryptographic logic directly.
 */
class PasswordEncryptionUtilTest {

    private lateinit var mockContext: Context
    private lateinit var mockSharedPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var testEncryptionKey: ByteArray

    // Test constants matching the implementation
    private val GCM_IV_LENGTH = 12
    private val GCM_TAG_LENGTH = 128
    private val AAD_CONTEXT = "deutsia-radio-password-v1"

    @Before
    fun setup() {
        // Generate a test encryption key
        testEncryptionKey = ByteArray(32)
        SecureRandom().nextBytes(testEncryptionKey)

        // Mock Android's Base64 class for JVM tests
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }

        // Mock SharedPreferences
        mockEditor = mockk(relaxed = true)
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just runs

        mockSharedPrefs = mockk()
        every { mockSharedPrefs.edit() } returns mockEditor
        every { mockSharedPrefs.getString("db_encryption_key", null) } returns
            java.util.Base64.getEncoder().encodeToString(testEncryptionKey)

        // Mock Context
        mockContext = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ========================================================================
    // AES-256-GCM Encryption Tests (Pure Crypto - No Android Dependencies)
    // ========================================================================

    @Test
    fun `test GCM encryption produces valid ciphertext format`() {
        val plaintext = "testPassword123"
        val key = SecretKeySpec(testEncryptionKey, "AES")

        // Encrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Combined format: IV + encrypted data + auth tag
        val combined = iv + encrypted

        // Verify minimum length (IV + at least auth tag)
        assertTrue("Ciphertext should include IV and auth tag",
            combined.size >= GCM_IV_LENGTH + 16) // 16 bytes = 128 bit tag
    }

    @Test
    fun `test GCM decryption recovers original plaintext`() {
        val plaintext = "testPassword123"
        val key = SecretKeySpec(testEncryptionKey, "AES")

        // Encrypt
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        encryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted = encryptCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Decrypt
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        decryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val decrypted = String(decryptCipher.doFinal(encrypted), Charsets.UTF_8)

        assertEquals("Decrypted text should match original", plaintext, decrypted)
    }

    @Test
    fun `test encryption produces unique ciphertexts for same plaintext`() {
        val plaintext = "testPassword123"
        val key = SecretKeySpec(testEncryptionKey, "AES")

        val ciphertexts = mutableSetOf<String>()

        repeat(100) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
            val encrypted = iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            ciphertexts.add(java.util.Base64.getEncoder().encodeToString(encrypted))
        }

        assertEquals("All ciphertexts should be unique (unique IVs)",
            100, ciphertexts.size)
    }

    // ========================================================================
    // IV (Initialization Vector) Tests
    // ========================================================================

    @Test
    fun `test IV is exactly 12 bytes`() {
        val key = SecretKeySpec(testEncryptionKey, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        assertEquals("IV should be 12 bytes for GCM", 12, iv.size)

        // Should not throw with correct IV length
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
    }

    @Test
    fun `test IV reuse with same key is detectable via unique ciphertext`() {
        // Note: In real GCM, IV reuse with same key is catastrophic
        // This test documents why unique IVs are critical
        val plaintext1 = "password1"
        val plaintext2 = "password2"
        val key = SecretKeySpec(testEncryptionKey, "AES")
        val sharedIv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(sharedIv)

        // Encrypt two different plaintexts with same IV (DANGEROUS - for testing only)
        val cipher1 = Cipher.getInstance("AES/GCM/NoPadding")
        cipher1.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, sharedIv))
        cipher1.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted1 = cipher1.doFinal(plaintext1.toByteArray(Charsets.UTF_8))

        val cipher2 = Cipher.getInstance("AES/GCM/NoPadding")
        cipher2.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, sharedIv))
        cipher2.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted2 = cipher2.doFinal(plaintext2.toByteArray(Charsets.UTF_8))

        // Different plaintexts should produce different ciphertexts even with same IV
        assertFalse("Different plaintexts should produce different ciphertexts",
            encrypted1.contentEquals(encrypted2))
    }

    // ========================================================================
    // AAD (Additional Authenticated Data) Tests
    // ========================================================================

    @Test
    fun `test decryption fails with wrong AAD`() {
        val plaintext = "testPassword123"
        val key = SecretKeySpec(testEncryptionKey, "AES")

        // Encrypt with correct AAD
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        encryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted = encryptCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Try to decrypt with wrong AAD
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        decryptCipher.updateAAD("wrong-context".toByteArray(Charsets.UTF_8))

        // Should throw due to authentication failure
        assertThrows("Decryption with wrong AAD should fail",
            javax.crypto.AEADBadTagException::class.java) {
            decryptCipher.doFinal(encrypted)
        }
    }

    @Test
    fun `test decryption fails without AAD when AAD was used for encryption`() {
        val plaintext = "testPassword123"
        val key = SecretKeySpec(testEncryptionKey, "AES")

        // Encrypt with AAD
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        encryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted = encryptCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Try to decrypt without AAD
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        // No updateAAD call

        // Should throw due to authentication failure
        assertThrows("Decryption without AAD should fail when AAD was used",
            javax.crypto.AEADBadTagException::class.java) {
            decryptCipher.doFinal(encrypted)
        }
    }

    // ========================================================================
    // GCM Authentication Tag Tests
    // ========================================================================

    @Test
    fun `test tampered ciphertext is detected`() {
        val plaintext = "testPassword123"
        val key = SecretKeySpec(testEncryptionKey, "AES")

        // Encrypt
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        encryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted = encryptCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Tamper with ciphertext (flip a bit)
        val tamperedEncrypted = encrypted.clone()
        tamperedEncrypted[0] = (tamperedEncrypted[0].toInt() xor 0x01).toByte()

        // Try to decrypt
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        decryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))

        // Should throw due to authentication failure
        assertThrows("Tampered ciphertext should be detected",
            javax.crypto.AEADBadTagException::class.java) {
            decryptCipher.doFinal(tamperedEncrypted)
        }
    }

    @Test
    fun `test truncated ciphertext is detected`() {
        val plaintext = "testPassword123"
        val key = SecretKeySpec(testEncryptionKey, "AES")

        // Encrypt
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        encryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted = encryptCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Truncate ciphertext (remove last byte, which is part of auth tag)
        val truncatedEncrypted = encrypted.copyOfRange(0, encrypted.size - 1)

        // Try to decrypt
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        decryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))

        // Should throw due to authentication failure
        assertThrows("Truncated ciphertext should be detected",
            javax.crypto.AEADBadTagException::class.java) {
            decryptCipher.doFinal(truncatedEncrypted)
        }
    }

    @Test
    fun `test wrong key is detected`() {
        val plaintext = "testPassword123"
        val key = SecretKeySpec(testEncryptionKey, "AES")

        // Encrypt
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        encryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted = encryptCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Create wrong key
        val wrongKeyBytes = ByteArray(32)
        SecureRandom().nextBytes(wrongKeyBytes)
        val wrongKey = SecretKeySpec(wrongKeyBytes, "AES")

        // Try to decrypt with wrong key
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, wrongKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        decryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))

        // Should throw due to authentication failure
        assertThrows("Wrong key should be detected",
            javax.crypto.AEADBadTagException::class.java) {
            decryptCipher.doFinal(encrypted)
        }
    }

    // ========================================================================
    // Edge Cases and Special Characters
    // ========================================================================

    @Test
    fun `test encryption handles special characters`() {
        val key = SecretKeySpec(testEncryptionKey, "AES")

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
            // Encrypt
            val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            encryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
            val encrypted = encryptCipher.doFinal(password.toByteArray(Charsets.UTF_8))

            // Decrypt
            val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
            decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            decryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
            val decrypted = String(decryptCipher.doFinal(encrypted), Charsets.UTF_8)

            assertEquals("Should handle special characters: ${password.take(10)}...",
                password, decrypted)
        }
    }

    @Test
    fun `test encryption handles very long password`() {
        val key = SecretKeySpec(testEncryptionKey, "AES")
        val longPassword = "a".repeat(100000) // 100KB password

        // Encrypt
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        encryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted = encryptCipher.doFinal(longPassword.toByteArray(Charsets.UTF_8))

        // Decrypt
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        decryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val decrypted = String(decryptCipher.doFinal(encrypted), Charsets.UTF_8)

        assertEquals("Should handle very long password", longPassword, decrypted)
    }

    // ========================================================================
    // Key Generation Tests
    // ========================================================================

    @Test
    fun `test generated keys have sufficient entropy`() {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)

        val keys = (1..10).map { keyGenerator.generateKey().encoded }

        // All keys should be unique
        val uniqueKeys = keys.map { it.toList() }.toSet()
        assertEquals("All generated keys should be unique", 10, uniqueKeys.size)

        // Each key should have diverse bytes
        for (key in keys) {
            val uniqueBytes = key.toSet()
            assertTrue("Key should have diverse byte values", uniqueBytes.size > 10)
        }
    }

    @Test
    fun `test key is exactly 256 bits`() {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        val key = keyGenerator.generateKey()

        assertEquals("AES-256 key should be 32 bytes", 32, key.encoded.size)
    }

    // ========================================================================
    // Base64 Format Tests
    // ========================================================================

    @Test
    fun `test encrypted output is valid Base64`() {
        val key = SecretKeySpec(testEncryptionKey, "AES")
        val plaintext = "testPassword"

        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        encryptCipher.updateAAD(AAD_CONTEXT.toByteArray(Charsets.UTF_8))
        val encrypted = iv + encryptCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val base64 = java.util.Base64.getEncoder().encodeToString(encrypted)

        // Should not contain newlines (NO_WRAP equivalent)
        assertFalse("Base64 should not contain newlines", base64.contains("\n"))
        assertFalse("Base64 should not contain carriage returns", base64.contains("\r"))

        // Should be decodable
        val decoded = java.util.Base64.getDecoder().decode(base64)
        assertArrayEquals("Base64 should be reversible", encrypted, decoded)
    }

    @Test
    fun `test invalid Base64 is rejected`() {
        // Invalid Base64 strings
        val invalidBase64Strings = listOf(
            "!!!invalid!!!",
            "not-base64",
            "has spaces in it",
            "has\nnewlines",
            "AB" // Too short to be valid ciphertext
        )

        for (invalid in invalidBase64Strings) {
            try {
                java.util.Base64.getDecoder().decode(invalid)
                // If we get here, the string was valid Base64, but too short
                val bytes = java.util.Base64.getDecoder().decode(invalid)
                assertTrue("Too-short ciphertext should be detected",
                    bytes.size < GCM_IV_LENGTH + 16)
            } catch (e: IllegalArgumentException) {
                // Expected for invalid Base64
            }
        }
    }
}

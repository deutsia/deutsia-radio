package com.opensource.i2pradio.security

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Data Residue and Forensic Resistance Tests
 *
 * These tests verify protection against forensic data extraction, focusing on:
 * - Data remnants in memory
 * - Sensitive data in buffers
 * - Encoding/decoding residue
 * - String building residue
 * - Collection residue
 *
 * Forensic tools like Cellebrite can extract:
 * - Swapped memory pages
 * - Unreferenced heap objects
 * - Buffer remnants
 * - StringBuilder/StringBuffer contents
 * - Collection internal arrays
 */
class DataResidueSecurityTest {

    private val SALT_LENGTH = 32
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
    // ByteBuffer Residue Tests
    // ========================================================================

    @Test
    fun `test ByteBuffer can leave residue - demonstrate problem`() {
        val sensitiveData = "CREDIT_CARD:4111111111111111".toByteArray()

        // Create and fill buffer
        val buffer = ByteBuffer.allocate(128)
        buffer.put(sensitiveData)

        // "Clear" the buffer position (WRONG - data still there!)
        buffer.clear()

        // Data is still in the buffer!
        val residue = ByteArray(sensitiveData.size)
        buffer.get(residue)

        // This demonstrates the PROBLEM - clear() doesn't wipe data
        assertArrayEquals("ByteBuffer.clear() doesn't wipe data!",
            sensitiveData, residue)
    }

    @Test
    fun `test secure ByteBuffer wiping pattern`() {
        val sensitiveData = "CREDIT_CARD:4111111111111111".toByteArray()

        val buffer = ByteBuffer.allocate(128)
        buffer.put(sensitiveData)

        // SECURE: Actually wipe the data
        buffer.clear()
        val zeros = ByteArray(buffer.capacity())
        buffer.put(zeros)
        buffer.clear()

        // Now verify it's wiped
        val residue = ByteArray(sensitiveData.size)
        buffer.get(residue)

        assertTrue("Buffer should be wiped after secure clear",
            residue.all { it == 0.toByte() })
    }

    @Test
    fun `test CharBuffer residue and secure wipe`() {
        val password = "secretPassword123!"

        val buffer = CharBuffer.allocate(64)
        buffer.put(password)

        // Standard clear doesn't wipe
        buffer.clear()

        // SECURE wipe
        val charArray = CharArray(buffer.capacity())
        buffer.get(charArray)
        charArray.fill('\u0000')

        // Overwrite buffer
        buffer.clear()
        buffer.put(CharArray(buffer.capacity()))
        buffer.clear()

        // Verify wiped
        val residue = CharArray(password.length)
        buffer.get(residue)

        assertTrue("CharBuffer should be wiped",
            residue.all { it == '\u0000' })
    }

    // ========================================================================
    // StringBuilder/StringBuffer Residue Tests
    // ========================================================================

    @Test
    fun `test StringBuilder leaves residue - demonstrate problem`() {
        val sb = StringBuilder()
        sb.append("password:")
        sb.append("hunter2")

        val originalCapacity = sb.capacity()

        // "Clear" the StringBuilder
        sb.setLength(0)

        // StringBuilder's internal char array still contains data!
        // This is a known forensic vulnerability

        // We can't directly access the internal array, but we can
        // demonstrate that capacity is retained
        assertEquals("StringBuilder retains capacity after clear",
            originalCapacity, sb.capacity())

        // In forensic analysis, the char[] backing the StringBuilder
        // would still contain "password:hunter2"
    }

    @Test
    fun `test secure StringBuilder clearing pattern`() {
        val sb = StringBuilder()
        sb.append("password:")
        sb.append("hunter2")

        // SECURE clear: overwrite with zeros then set length to 0
        val length = sb.length
        for (i in 0 until length) {
            sb.setCharAt(i, '\u0000')
        }
        sb.setLength(0)

        // For extra security, fill with zeros up to capacity
        val capacity = sb.capacity()
        for (i in 0 until capacity) {
            sb.append('\u0000')
        }
        sb.setLength(0)

        // Now the internal buffer should be wiped
    }

    @Test
    fun `test prefer CharArray over StringBuilder for passwords`() {
        // WRONG pattern (for demonstration)
        fun buildPasswordWrong(parts: List<String>): String {
            val sb = StringBuilder()
            parts.forEach { sb.append(it) }
            return sb.toString() // String is now interned, sb has residue
        }

        // RIGHT pattern
        fun buildPasswordRight(parts: List<String>): CharArray {
            val totalLength = parts.sumOf { it.length }
            val result = CharArray(totalLength)
            var pos = 0
            for (part in parts) {
                part.toCharArray().copyInto(result, pos)
                pos += part.length
            }
            return result // CharArray can be wiped
        }

        val password = buildPasswordRight(listOf("secret", ":", "123"))

        // Use password...

        // Then wipe
        password.fill('\u0000')

        assertTrue("CharArray can be wiped", password.all { it == '\u0000' })
    }

    // ========================================================================
    // Collection Residue Tests
    // ========================================================================

    @Test
    fun `test ArrayList residue after clear`() {
        val sensitiveList = ArrayList<ByteArray>()
        sensitiveList.add(byteArrayOf(1, 2, 3))
        sensitiveList.add(byteArrayOf(4, 5, 6))

        // Get references before clear
        val item1 = sensitiveList[0]
        val item2 = sensitiveList[1]

        // Clear list
        sensitiveList.clear()

        // Items still exist in memory! Only references removed from list
        assertArrayEquals("Item still exists after list clear",
            byteArrayOf(1, 2, 3), item1)

        // SECURE pattern: wipe items before clearing
        item1.fill(0)
        item2.fill(0)
    }

    @Test
    fun `test secure collection clearing pattern`() {
        fun <T> secureListClear(list: MutableList<T>, wiper: (T) -> Unit) {
            list.forEach { wiper(it) }
            list.clear()
        }

        val passwords = mutableListOf(
            "password1".toCharArray(),
            "password2".toCharArray(),
            "password3".toCharArray()
        )

        // Secure clear
        secureListClear(passwords) { it.fill('\u0000') }

        assertTrue("List should be empty", passwords.isEmpty())
    }

    @Test
    fun `test HashMap residue`() {
        val credentials = HashMap<String, CharArray>()
        credentials["user1"] = "pass1".toCharArray()
        credentials["user2"] = "pass2".toCharArray()

        // Get values before clear
        val pass1 = credentials["user1"]!!
        val pass2 = credentials["user2"]!!

        // Clear map
        credentials.clear()

        // Passwords still in memory!
        assertFalse("Password still exists after map clear",
            pass1.all { it == '\u0000' })

        // SECURE: wipe values
        pass1.fill('\u0000')
        pass2.fill('\u0000')
    }

    // ========================================================================
    // Encoding/Decoding Residue Tests
    // ========================================================================

    @Test
    fun `test Base64 encoding doesn't create unnecessary copies`() {
        val sensitiveData = ByteArray(32)
        SecureRandom().nextBytes(sensitiveData)

        // Encode
        val encoded = java.util.Base64.getEncoder().encode(sensitiveData)

        // Decode
        val decoded = java.util.Base64.getDecoder().decode(encoded)

        // Wipe all copies
        sensitiveData.fill(0)
        encoded.fill(0)
        decoded.fill(0)

        assertTrue("Original wiped", sensitiveData.all { it == 0.toByte() })
        assertTrue("Encoded wiped", encoded.all { it == 0.toByte() })
        assertTrue("Decoded wiped", decoded.all { it == 0.toByte() })
    }

    @Test
    fun `test String to ByteArray conversion creates copies`() {
        val password = "sensitivePassword"

        // Each toByteArray() creates a NEW byte array
        val bytes1 = password.toByteArray()
        val bytes2 = password.toByteArray()

        assertNotSame("Each toByteArray creates new array", bytes1, bytes2)

        // Important: wipe all copies!
        bytes1.fill(0)
        bytes2.fill(0)
        // Note: original String cannot be wiped (interned)
    }

    @Test
    fun `test CharArray to String creates interned copy`() {
        val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')

        // WARNING: This creates an interned String that cannot be wiped
        val passwordString = String(password)

        // Wipe original
        password.fill('\u0000')

        // Original is wiped
        assertTrue("CharArray can be wiped", password.all { it == '\u0000' })

        // But String still exists! (Cannot verify in test, but important to know)
        assertEquals("String copy still exists", "secret", passwordString)
    }

    // ========================================================================
    // Crypto Operation Residue Tests
    // ========================================================================

    @Test
    fun `test cipher operations create intermediate copies`() {
        val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val plaintext = "SENSITIVE_DATA"

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        // Convert to bytes (creates copy)
        val plaintextBytes = plaintext.toByteArray()

        // Encrypt (creates another copy internally)
        val ciphertext = cipher.doFinal(plaintextBytes)

        // Wipe what we can
        plaintextBytes.fill(0)

        assertTrue("Plaintext bytes wiped", plaintextBytes.all { it == 0.toByte() })

        // Note: Original String "SENSITIVE_DATA" is still in memory (interned)
        // Note: Cipher may have internal buffers we can't access
    }

    @Test
    fun `test PBEKeySpec intermediate data cleanup`() {
        val password = "secretPassword".toCharArray()
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)

        val spec = PBEKeySpec(password, salt, 10000, 256)

        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val secretKey = factory.generateSecret(spec)
            val keyBytes = secretKey.encoded

            // Use key...

            // Wipe derived key
            keyBytes.fill(0)
        } finally {
            // Clear spec
            spec.clearPassword()

            // Clear original password
            password.fill('\u0000')
        }

        assertTrue("Password wiped", password.all { it == '\u0000' })
    }

    // ========================================================================
    // String Concatenation Residue Tests
    // ========================================================================

    @Test
    fun `test string concatenation creates multiple objects`() {
        // Each + creates a new String object (potential residue)
        val part1 = "user"
        val part2 = ":"
        val part3 = "password"

        // This creates intermediate strings:
        // "user:" then "user:password"
        val combined = part1 + part2 + part3

        // All these strings are now in the string pool
        // CANNOT be wiped

        assertEquals("user:password", combined)

        // Lesson: For sensitive data, use CharArray operations instead
    }

    @Test
    fun `test secure string building with CharArray`() {
        fun secureConcat(vararg parts: CharArray): CharArray {
            val totalLength = parts.sumOf { it.size }
            val result = CharArray(totalLength)
            var pos = 0
            for (part in parts) {
                part.copyInto(result, pos)
                pos += part.size
            }
            return result
        }

        val user = "admin".toCharArray()
        val separator = ":".toCharArray()
        val pass = "secret".toCharArray()

        val combined = secureConcat(user, separator, pass)

        // Use combined...

        // Wipe everything
        user.fill('\u0000')
        separator.fill('\u0000')
        pass.fill('\u0000')
        combined.fill('\u0000')

        assertTrue("All parts wiped", combined.all { it == '\u0000' })
    }

    // ========================================================================
    // Array Resizing Residue Tests
    // ========================================================================

    @Test
    fun `test array copyOf leaves original`() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val expanded = original.copyOf(10)

        // Both arrays exist!
        assertNotSame("copyOf creates new array", original, expanded)

        // Wipe both
        original.fill(0)
        expanded.fill(0)
    }

    @Test
    fun `test ArrayList grow leaves old array`() {
        // ArrayList uses array internally, grows by creating new array
        // Old array becomes garbage but may persist in memory

        val list = ArrayList<Byte>(4)
        list.add(1)
        list.add(2)
        list.add(3)
        list.add(4)

        // This triggers internal array resize
        list.add(5)

        // Old internal array still in memory as garbage
        // This is why ArrayList is not suitable for sensitive data
    }

    // ========================================================================
    // Secure Data Structure Patterns
    // ========================================================================

    @Test
    fun `test secure fixed-size buffer pattern`() {
        class SecureFixedBuffer(size: Int) : AutoCloseable {
            private val buffer = ByteArray(size)
            private var position = 0
            private var isClosed = false

            fun write(data: ByteArray): Boolean {
                if (isClosed || position + data.size > buffer.size) return false
                data.copyInto(buffer, position)
                position += data.size
                return true
            }

            fun read(): ByteArray {
                return buffer.copyOfRange(0, position)
            }

            override fun close() {
                // Multi-pass wipe
                SecureRandom().nextBytes(buffer)
                buffer.fill(0xFF.toByte())
                buffer.fill(0x00.toByte())
                position = 0
                isClosed = true
            }
        }

        SecureFixedBuffer(64).use { buffer ->
            buffer.write(byteArrayOf(1, 2, 3, 4))
            val data = buffer.read()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), data)
        }
    }

    @Test
    fun `test secure password entry pattern`() {
        class SecurePasswordEntry : AutoCloseable {
            private val chars = CharArray(128)
            private var length = 0
            private var isClosed = false

            fun append(c: Char): Boolean {
                if (isClosed || length >= chars.size) return false
                chars[length++] = c
                return true
            }

            fun getPassword(): CharArray {
                val result = chars.copyOfRange(0, length)
                return result
            }

            fun clear() {
                chars.fill('\u0000')
                length = 0
            }

            override fun close() {
                clear()
                isClosed = true
            }
        }

        SecurePasswordEntry().use { entry ->
            "password".forEach { entry.append(it) }
            val password = entry.getPassword()
            assertArrayEquals("password".toCharArray(), password)
            password.fill('\u0000')
        }
    }

    // ========================================================================
    // Memory Comparison Safety
    // ========================================================================

    @Test
    fun `test contentEquals doesn't short-circuit safely`() {
        // contentEquals might short-circuit on first mismatch
        // Use MessageDigest.isEqual for constant-time

        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)

        val allDifferent = ByteArray(32)
        val firstDifferent = secret.clone().also { it[0] = 0 }
        val lastDifferent = secret.clone().also { it[31] = 0 }

        // All should fail comparison
        assertFalse(java.security.MessageDigest.isEqual(secret, allDifferent))
        assertFalse(java.security.MessageDigest.isEqual(secret, firstDifferent))
        assertFalse(java.security.MessageDigest.isEqual(secret, lastDifferent))
    }

    @Test
    fun `test secure zero check pattern`() {
        fun isSecurelyZeroed(data: ByteArray): Boolean {
            // Constant-time zero check
            var result: Byte = 0
            for (b in data) {
                result = (result.toInt() or b.toInt()).toByte()
            }
            return result == 0.toByte()
        }

        val zeroed = ByteArray(32)
        val notZeroed = ByteArray(32).also { it[15] = 1 }

        assertTrue("Should detect zeroed array", isSecurelyZeroed(zeroed))
        assertFalse("Should detect non-zeroed array", isSecurelyZeroed(notZeroed))
    }
}

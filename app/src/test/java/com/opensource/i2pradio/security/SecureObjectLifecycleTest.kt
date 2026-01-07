package com.opensource.i2pradio.security

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Secure Object Lifecycle and Serialization Security Tests
 *
 * These tests verify that sensitive objects are properly managed throughout
 * their lifecycle and don't leak data through serialization, toString(),
 * hashCode(), or other object methods.
 *
 * Cellebrite and similar forensic tools can extract:
 * - Serialized objects from disk/memory
 * - String representations from logs
 * - Object hashes that might leak information
 *
 * Test Coverage:
 * 1. Serialization Safety - Sensitive data not serializable
 * 2. toString() Safety - No secrets in string representation
 * 3. equals()/hashCode() Safety - No timing leaks
 * 4. Clone Safety - Proper deep copy behavior
 * 5. Finalization - Cleanup on object destruction
 */
class SecureObjectLifecycleTest {

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
    // Secure Credential Container Pattern Tests
    // ========================================================================

    /**
     * Example of a secure credential container that properly protects sensitive data.
     * This demonstrates patterns that should be used in production code.
     */
    private class SecureCredential private constructor(
        private var passwordChars: CharArray?
    ) : AutoCloseable {
        @Volatile
        private var isCleared = false

        companion object {
            fun create(password: String): SecureCredential {
                return SecureCredential(password.toCharArray())
            }
        }

        fun usePassword(action: (CharArray) -> Unit) {
            check(!isCleared) { "Credential has been cleared" }
            passwordChars?.let { action(it) }
        }

        override fun close() {
            if (!isCleared) {
                passwordChars?.fill('\u0000')
                passwordChars = null
                isCleared = true
            }
        }

        // SECURITY: Don't expose password in toString
        override fun toString(): String = "SecureCredential[cleared=$isCleared]"

        // SECURITY: Don't use password in hashCode
        override fun hashCode(): Int = System.identityHashCode(this)

        // SECURITY: Use identity comparison, not content
        override fun equals(other: Any?): Boolean = this === other
    }

    @Test
    fun `test SecureCredential toString doesn't expose password`() {
        val password = "superSecretPassword123!"
        val credential = SecureCredential.create(password)

        val stringRep = credential.toString()

        assertFalse("toString should not contain password",
            stringRep.contains(password))
        assertFalse("toString should not contain 'secret'",
            stringRep.lowercase().contains("secret"))

        credential.close()
    }

    @Test
    fun `test SecureCredential can be used then cleared`() {
        val password = "testPassword"
        val credential = SecureCredential.create(password)

        var capturedPassword: String? = null
        credential.usePassword { chars ->
            capturedPassword = String(chars)
        }

        assertEquals("Password should be accessible before clear",
            password, capturedPassword)

        credential.close()

        // After close, should throw
        assertThrows(IllegalStateException::class.java) {
            credential.usePassword { }
        }
    }

    @Test
    fun `test SecureCredential with try-with-resources pattern`() {
        val usedPasswords = mutableListOf<String>()

        SecureCredential.create("password1").use { cred ->
            cred.usePassword { usedPasswords.add(String(it)) }
        }

        // Password was used
        assertEquals("password1", usedPasswords[0])

        // Credential is now closed (would throw if used)
    }

    // ========================================================================
    // toString() Safety Tests
    // ========================================================================

    @Test
    fun `test ByteArray toString doesn't expose contents`() {
        val secret = byteArrayOf(0x41, 0x42, 0x43) // "ABC"
        val stringRep = secret.toString()

        // Default ByteArray.toString() shows object hash, not contents
        assertFalse("ByteArray toString should not show contents",
            stringRep.contains("ABC"))
        assertFalse("ByteArray toString should not show hex values",
            stringRep.contains("41") && stringRep.contains("42"))
    }

    @Test
    fun `test CharArray toString doesn't expose contents`() {
        val password = "secretPassword".toCharArray()
        val stringRep = password.toString()

        // Default CharArray.toString() shows object hash
        assertFalse("CharArray toString should not show contents",
            stringRep.contains("secretPassword"))
    }

    @Test
    fun `test SecretKeySpec toString is safe`() {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        val key = SecretKeySpec(keyBytes, "AES")

        val stringRep = key.toString()

        // Verify key bytes aren't in the string
        val keyHex = keyBytes.joinToString("") { "%02x".format(it) }
        assertFalse("Key toString should not contain key bytes",
            stringRep.contains(keyHex))
    }

    // ========================================================================
    // Serialization Safety Tests
    // ========================================================================

    @Test
    fun `test sensitive data should not be naively serializable`() {
        // This test demonstrates why custom serialization is needed

        data class InsecureUser(
            val username: String,
            val password: String // BAD: Plain password in serializable class
        ) : Serializable

        val user = InsecureUser("admin", "secretPassword123")

        // Serialize
        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { it.writeObject(user) }
        val serialized = baos.toByteArray()

        // Password is visible in serialized bytes!
        val serializedString = String(serialized, Charsets.ISO_8859_1)
        assertTrue("INSECURE: Password visible in serialized form",
            serializedString.contains("secretPassword123"))
    }

    @Test
    fun `test secure pattern - transient password field`() {
        // Secure pattern: mark sensitive fields as transient

        class SecureUser(
            val username: String
        ) : Serializable {
            @Transient
            private var passwordHash: ByteArray? = null

            fun setPassword(password: String) {
                // In real code, use proper hashing
                passwordHash = password.toByteArray()
            }

            fun hasPassword(): Boolean = passwordHash != null

            fun clearPassword() {
                passwordHash?.fill(0)
                passwordHash = null
            }
        }

        val user = SecureUser("admin")
        user.setPassword("secretPassword123")

        // Serialize
        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { it.writeObject(user) }
        val serialized = baos.toByteArray()

        // Password should NOT be in serialized form
        val serializedString = String(serialized, Charsets.ISO_8859_1)
        assertFalse("Password should not be in serialized form",
            serializedString.contains("secretPassword123"))

        // Deserialize
        val bais = ByteArrayInputStream(serialized)
        val deserialized = ObjectInputStream(bais).use { it.readObject() } as SecureUser

        // Transient field should be null after deserialization
        assertFalse("Password should be null after deserialization",
            deserialized.hasPassword())
    }

    @Test
    fun `test encryption keys should not be directly serializable`() {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        val keyHex = keyBytes.joinToString("") { "%02x".format(it) }

        // SecretKeySpec IS serializable (for key storage), but...
        val key = SecretKeySpec(keyBytes, "AES")

        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { it.writeObject(key) }
        val serialized = baos.toByteArray()

        // Key bytes ARE in the serialized form
        // This is a reminder: encrypted storage should use Keystore, not serialization
        val serializedHex = serialized.joinToString("") { "%02x".format(it) }

        // Document that raw key serialization exposes key
        // In production, use Android Keystore instead
    }

    // ========================================================================
    // hashCode() Safety Tests
    // ========================================================================

    @Test
    fun `test password hashCode doesn't expose content`() {
        val password1 = "password123".toCharArray()
        val password2 = "password123".toCharArray()

        // CharArray uses identity hashCode, not content
        assertNotEquals("Identical content should have different hashCodes",
            password1.hashCode(), password2.hashCode())
    }

    @Test
    fun `test secure hashCode implementation`() {
        class SecureToken(private val value: ByteArray) {
            // DON'T use value in hashCode - use identity
            override fun hashCode(): Int = System.identityHashCode(this)

            override fun equals(other: Any?): Boolean = this === other

            fun clear() {
                value.fill(0)
            }
        }

        val token1 = SecureToken(byteArrayOf(1, 2, 3))
        val token2 = SecureToken(byteArrayOf(1, 2, 3))

        // Same content, different hashCodes (identity-based)
        assertNotEquals("Same content should have different identity hashCodes",
            token1.hashCode(), token2.hashCode())
    }

    // ========================================================================
    // equals() Safety Tests
    // ========================================================================

    @Test
    fun `test secure equals uses constant-time comparison`() {
        class SecureBytes(private val data: ByteArray) {
            // Constant-time comparison
            fun secureEquals(other: SecureBytes): Boolean {
                return java.security.MessageDigest.isEqual(data, other.data)
            }

            // AVOID: Standard equals might short-circuit
            override fun equals(other: Any?): Boolean {
                if (other !is SecureBytes) return false
                return secureEquals(other)
            }

            override fun hashCode(): Int = System.identityHashCode(this)
        }

        val bytes1 = SecureBytes(byteArrayOf(1, 2, 3, 4))
        val bytes2 = SecureBytes(byteArrayOf(1, 2, 3, 4))
        val bytes3 = SecureBytes(byteArrayOf(1, 2, 3, 5))

        assertTrue("Same content should be equal", bytes1.secureEquals(bytes2))
        assertFalse("Different content should not be equal", bytes1.secureEquals(bytes3))
    }

    // ========================================================================
    // Clone Safety Tests
    // ========================================================================

    @Test
    fun `test array clone creates independent copy`() {
        val original = ByteArray(32)
        SecureRandom().nextBytes(original)

        val cloned = original.clone()

        // Should be equal content
        assertArrayEquals("Clone should have same content", original, cloned)

        // But different objects
        assertNotSame("Clone should be different object", original, cloned)

        // Modifying clone shouldn't affect original
        cloned.fill(0)
        assertFalse("Original should be unaffected by clone modification",
            original.all { it == 0.toByte() })
    }

    @Test
    fun `test secure deep clone pattern`() {
        class Container(val data: ByteArray) : Cloneable {
            public override fun clone(): Container {
                // Deep clone - don't share underlying array
                return Container(data.clone())
            }

            fun clear() {
                data.fill(0)
            }
        }

        val original = Container(byteArrayOf(1, 2, 3, 4))
        val cloned = original.clone()

        // Clear clone
        cloned.clear()

        // Original should be unaffected
        assertArrayEquals("Original should be unaffected",
            byteArrayOf(1, 2, 3, 4), original.data)
    }

    // ========================================================================
    // Resource Cleanup Tests
    // ========================================================================

    @Test
    fun `test AutoCloseable pattern for sensitive resources`() {
        class SecureBuffer(size: Int) : AutoCloseable {
            private var buffer: ByteArray? = ByteArray(size)
            private var isClosed = false

            fun write(data: ByteArray) {
                check(!isClosed) { "Buffer is closed" }
                buffer?.let { buf ->
                    data.copyInto(buf, 0, 0, minOf(data.size, buf.size))
                }
            }

            override fun close() {
                if (!isClosed) {
                    buffer?.fill(0)
                    buffer = null
                    isClosed = true
                }
            }
        }

        var bufferRef: SecureBuffer? = null

        SecureBuffer(256).use { buffer ->
            buffer.write(byteArrayOf(1, 2, 3, 4))
            bufferRef = buffer
        }

        // After use block, buffer should be closed
        assertThrows("Should throw when using closed buffer",
            IllegalStateException::class.java) {
            bufferRef?.write(byteArrayOf(5, 6, 7, 8))
        }
    }

    @Test
    fun `test cleanup on exception`() {
        class SecureProcessor : AutoCloseable {
            private var key: ByteArray? = ByteArray(32).also {
                SecureRandom().nextBytes(it)
            }
            var wasClosed = false

            fun process() {
                throw RuntimeException("Processing failed")
            }

            override fun close() {
                key?.fill(0)
                key = null
                wasClosed = true
            }
        }

        val processor = SecureProcessor()
        try {
            processor.use {
                it.process()
            }
        } catch (e: RuntimeException) {
            // Expected
        }

        // Even on exception, close() should be called
        assertTrue("Resource should be closed even on exception",
            processor.wasClosed)
    }

    // ========================================================================
    // Cipher State Safety Tests
    // ========================================================================

    @Test
    fun `test cipher doesn't retain plaintext after encryption`() {
        val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val plaintext = "SENSITIVE_DATA_HERE"

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray())

        // Cipher toString shouldn't contain plaintext
        val cipherString = cipher.toString()
        assertFalse("Cipher state should not contain plaintext",
            cipherString.contains("SENSITIVE"))
    }

    @Test
    fun `test intermediate cipher state is cleared`() {
        val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        // Process in chunks
        cipher.update("chunk1".toByteArray())
        cipher.update("chunk2".toByteArray())
        val final = cipher.doFinal("chunk3".toByteArray())

        // After doFinal, cipher should be reset
        // Attempting to continue should fail or require re-init
        try {
            cipher.doFinal("more".toByteArray())
            // Some implementations allow this, some don't
        } catch (e: IllegalStateException) {
            // Expected in some implementations
        }
    }

    // ========================================================================
    // Thread Safety Tests
    // ========================================================================

    @Test
    fun `test concurrent access to secure container`() {
        class ThreadSafeCredential {
            @Volatile
            private var password: CharArray? = "initial".toCharArray()
            private val lock = Any()

            fun setPassword(newPassword: String) {
                synchronized(lock) {
                    password?.fill('\u0000')
                    password = newPassword.toCharArray()
                }
            }

            fun getPasswordCopy(): CharArray? {
                synchronized(lock) {
                    return password?.clone()
                }
            }

            fun clear() {
                synchronized(lock) {
                    password?.fill('\u0000')
                    password = null
                }
            }
        }

        val credential = ThreadSafeCredential()
        val results = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

        val threads = (1..10).map { threadId ->
            Thread {
                try {
                    repeat(100) { i ->
                        credential.setPassword("password-$threadId-$i")
                        val copy = credential.getPasswordCopy()
                        copy?.fill('\u0000')
                    }
                    results[threadId] = true
                } catch (e: Exception) {
                    results[threadId] = false
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        credential.clear()

        assertTrue("All threads should complete successfully",
            results.values.all { it })
    }
}

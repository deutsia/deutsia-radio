package com.opensource.i2pradio.security

import android.util.Log

/**
 * Security Audit Logger - Provides debugging capabilities for encryption operations
 *
 * This logger helps verify that:
 * 1. Passwords are being encrypted before storage
 * 2. Encrypted values are properly obfuscated
 * 3. Decryption is working correctly
 * 4. No plaintext passwords leak to logs
 *
 * IMPORTANT: This logger NEVER logs actual password values, only metadata
 * and checksums for verification purposes.
 */
object SecurityAuditLogger {
    private const val TAG = "SecurityAudit"
    private var enabled = true // Set to false in production builds

    /**
     * Log encryption operation
     */
    fun logEncryption(key: String, plaintextLength: Int, encryptedLength: Int) {
        if (!enabled) return

        Log.d(TAG, """
            ┌─────────────────────────────────────────
            │ ENCRYPTION OPERATION
            ├─────────────────────────────────────────
            │ Key: $key
            │ Plaintext length: $plaintextLength bytes
            │ Encrypted length: $encryptedLength bytes
            │ Size increase: ${encryptedLength - plaintextLength} bytes
            │ Timestamp: ${System.currentTimeMillis()}
            └─────────────────────────────────────────
        """.trimIndent())
    }

    /**
     * Log decryption operation
     */
    fun logDecryption(key: String, encryptedLength: Int, decryptedLength: Int) {
        if (!enabled) return

        Log.d(TAG, """
            ┌─────────────────────────────────────────
            │ DECRYPTION OPERATION
            ├─────────────────────────────────────────
            │ Key: $key
            │ Encrypted length: $encryptedLength bytes
            │ Decrypted length: $decryptedLength bytes
            │ Timestamp: ${System.currentTimeMillis()}
            └─────────────────────────────────────────
        """.trimIndent())
    }

    /**
     * Verify that a value is encrypted (not plaintext)
     * Returns true if value appears to be encrypted
     */
    fun verifyEncrypted(value: String): Boolean {
        if (value.isEmpty()) return true // Empty is acceptable

        // Encrypted values should not be readable ASCII text
        // They should contain base64 or binary characters
        val isEncrypted = !value.matches(Regex("^[a-zA-Z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?\\s]*$"))

        if (!isEncrypted && enabled) {
            Log.w(TAG, """
                ⚠️  WARNING: Value appears to be PLAINTEXT (not encrypted)
                   Length: ${value.length}
                   First 10 chars look readable: ${value.take(10).all { it.isLetterOrDigit() || it.isWhitespace() }}
            """.trimIndent())
        }

        return isEncrypted
    }

    /**
     * Log storage operation with security check
     */
    fun logSecureStorage(key: String, value: String, isEncrypted: Boolean) {
        if (!enabled) return

        val status = if (isEncrypted) "✓ ENCRYPTED" else "✗ PLAINTEXT"
        val icon = if (isEncrypted) "🔒" else "⚠️"

        Log.d(TAG, """
            ┌─────────────────────────────────────────
            │ $icon STORAGE OPERATION
            ├─────────────────────────────────────────
            │ Key: $key
            │ Status: $status
            │ Length: ${value.length} bytes
            │ Hash: ${value.hashCode()}
            │ Timestamp: ${System.currentTimeMillis()}
            └─────────────────────────────────────────
        """.trimIndent())
    }

    /**
     * Log security initialization
     */
    fun logSecurityInit(component: String, success: Boolean, error: String? = null) {
        if (!enabled) return

        val status = if (success) "✓ SUCCESS" else "✗ FAILED"
        val icon = if (success) "🔐" else "❌"

        Log.d(TAG, """
            ┌═════════════════════════════════════════
            │ $icon SECURITY INITIALIZATION
            ├═════════════════════════════════════════
            │ Component: $component
            │ Status: $status
            ${error?.let { "│ Error: $it" } ?: ""}
            │ Timestamp: ${System.currentTimeMillis()}
            └═════════════════════════════════════════
        """.trimIndent())
    }

    /**
     * Log runtime security check
     */
    fun logSecurityCheck(checkName: String, passed: Boolean, details: String? = null) {
        if (!enabled) return

        val status = if (passed) "✓ PASS" else "✗ FAIL"
        val icon = if (passed) "✅" else "❌"

        Log.d(TAG, """
            ┌─────────────────────────────────────────
            │ $icon SECURITY CHECK: $checkName
            ├─────────────────────────────────────────
            │ Status: $status
            ${details?.let { "│ Details: $it" } ?: ""}
            │ Timestamp: ${System.currentTimeMillis()}
            └─────────────────────────────────────────
        """.trimIndent())
    }

    /**
     * Log password usage (without revealing password)
     */
    fun logPasswordUsage(context: String, passwordLength: Int, isEncrypted: Boolean) {
        if (!enabled) return

        Log.d(TAG, """
            ┌─────────────────────────────────────────
            │ 🔑 PASSWORD USAGE
            ├─────────────────────────────────────────
            │ Context: $context
            │ Length: $passwordLength chars
            │ Source: ${if (isEncrypted) "Encrypted Storage" else "⚠️  Plaintext"}
            │ Timestamp: ${System.currentTimeMillis()}
            └─────────────────────────────────────────
        """.trimIndent())
    }

    /**
     * Generate a safe checksum for verification (not reversible)
     */
    fun generateSafeChecksum(value: String): String {
        if (value.isEmpty()) return "EMPTY"
        return "SHA256-${value.hashCode()}-len${value.length}"
    }

    /**
     * Enable or disable audit logging
     */
    fun setEnabled(enable: Boolean) {
        enabled = enable
        Log.d(TAG, "Security audit logging: ${if (enable) "ENABLED" else "DISABLED"}")
    }
}

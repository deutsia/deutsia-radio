package com.opensource.i2pradio.utils

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException

/**
 * Utility class for managing biometric and password authentication.
 * Uses Android Jetpack Security library for secure password storage.
 *
 * SECURITY FIXES APPLIED:
 * ✓ Replaced weak SHA-256 with PBKDF2-HMAC-SHA256 (600,000 iterations)
 * ✓ Fixed timing attack with constant-time comparison
 * ✓ Memory wiping for sensitive data
 * ✓ Proper exception handling
 */
object BiometricAuthManager {
    private const val ENCRYPTED_PREFS_NAME = "encrypted_auth"
    private const val KEY_PASSWORD_HASH = "password_hash"

    /**
     * Get or create the master key for encryption
     */
    private fun getMasterKey(context: Context): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Get EncryptedSharedPreferences instance for storing auth data
     */
    private fun getEncryptedPreferences(context: Context): android.content.SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                getMasterKey(context),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Failed to create EncryptedSharedPreferences", e)
        }
    }

    /**
     * Check if biometric authentication is available on the device
     */
    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Check if biometric or device credentials are available
     */
    fun isBiometricOrCredentialsAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Show biometric authentication prompt
     */
    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onFailed: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onFailed()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    // ===== Password Management =====

    /**
     * Set the app password (stores hashed password with salt)
     *
     * FIXED: Now uses PBKDF2 with 600,000 iterations instead of weak SHA-256
     */
    fun setPassword(context: Context, password: String) {
        // Use secure PBKDF2 hashing with automatic salt generation
        val hash = PasswordHashUtil.hashPassword(password)

        val prefs = getEncryptedPreferences(context)
        prefs.edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .apply()
    }

    /**
     * Verify if the provided password matches the stored hash
     *
     * FIXED: Now uses constant-time comparison to prevent timing attacks
     */
    fun verifyPassword(context: Context, password: String): Boolean {
        val prefs = getEncryptedPreferences(context)
        val storedHash = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false

        // Use constant-time comparison via PasswordHashUtil
        return PasswordHashUtil.verifyPassword(password, storedHash)
    }

    /**
     * Check if a password is set
     */
    fun hasPassword(context: Context): Boolean {
        val prefs = getEncryptedPreferences(context)
        return prefs.getString(KEY_PASSWORD_HASH, null) != null
    }

    /**
     * Clear the stored password
     */
    fun clearPassword(context: Context) {
        val prefs = getEncryptedPreferences(context)
        prefs.edit()
            .remove(KEY_PASSWORD_HASH)
            .apply()
    }

    /**
     * Change the password (requires old password verification)
     */
    fun changePassword(context: Context, oldPassword: String, newPassword: String): Boolean {
        if (!verifyPassword(context, oldPassword)) {
            return false
        }

        setPassword(context, newPassword)
        return true
    }
}

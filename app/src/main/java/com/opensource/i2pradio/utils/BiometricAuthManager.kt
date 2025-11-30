package com.opensource.i2pradio.utils

import android.content.Context
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Utility class for managing biometric and password authentication.
 * Uses Android Jetpack Security library for secure password storage.
 */
object BiometricAuthManager {
    private const val ENCRYPTED_PREFS_NAME = "encrypted_auth"
    private const val KEY_PASSWORD_HASH = "password_hash"
    private const val KEY_PASSWORD_SALT = "password_salt"

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
     * Generate a random salt for password hashing
     */
    private fun generateSalt(): ByteArray {
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /**
     * Hash a password with salt using SHA-256
     */
    private fun hashPassword(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.DEFAULT)
    }

    /**
     * Set the app password (stores hashed password with salt)
     */
    fun setPassword(context: Context, password: String) {
        val salt = generateSalt()
        val hash = hashPassword(password, salt)

        val prefs = getEncryptedPreferences(context)
        prefs.edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .putString(KEY_PASSWORD_SALT, Base64.encodeToString(salt, Base64.DEFAULT))
            .apply()
    }

    /**
     * Verify if the provided password matches the stored hash
     */
    fun verifyPassword(context: Context, password: String): Boolean {
        val prefs = getEncryptedPreferences(context)
        val storedHash = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        val saltString = prefs.getString(KEY_PASSWORD_SALT, null) ?: return false

        val salt = Base64.decode(saltString, Base64.DEFAULT)
        val hash = hashPassword(password, salt)

        return hash == storedHash
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
            .remove(KEY_PASSWORD_SALT)
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

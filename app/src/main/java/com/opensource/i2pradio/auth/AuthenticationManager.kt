package com.opensource.i2pradio.auth

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.opensource.i2pradio.ui.PreferencesHelper
import java.security.MessageDigest

/**
 * AuthenticationManager handles biometric and password authentication for the app.
 * Provides methods to check authentication availability, perform authentication,
 * and manage authentication settings.
 */
object AuthenticationManager {

    /**
     * Check if biometric authentication is available on the device.
     */
    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Get a descriptive message about biometric availability status.
     */
    fun getBiometricStatusMessage(context: Context): String {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> "Biometric authentication available"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No biometric hardware detected"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric hardware unavailable"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No biometric credentials enrolled"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security update required"
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "Biometric authentication unsupported"
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "Biometric status unknown"
            else -> "Biometric authentication not available"
        }
    }

    /**
     * Show biometric authentication prompt.
     * @param activity The FragmentActivity to show the prompt on
     * @param title Title of the authentication prompt
     * @param subtitle Subtitle of the authentication prompt
     * @param onSuccess Callback when authentication succeeds
     * @param onError Callback when authentication fails with error message
     * @param allowPasswordFallback If true, allows fallback to password authentication
     */
    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        allowPasswordFallback: Boolean = false
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    PreferencesHelper.setLastAuthTimestamp(activity, System.currentTimeMillis())
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            // User clicked "Use Password" button
                            if (allowPasswordFallback) {
                                onError("FALLBACK_TO_PASSWORD")
                            } else {
                                onError("Authentication cancelled")
                            }
                        }
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> {
                            onError("Authentication cancelled")
                        }
                        else -> {
                            onError(errString.toString())
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Called when biometric is valid but not recognized
                    // Don't call onError here as the user can retry
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        if (allowPasswordFallback && PreferencesHelper.isPasswordAuthEnabled(activity)) {
            promptInfo.setNegativeButtonText("Use Password")
        } else {
            promptInfo.setNegativeButtonText("Cancel")
        }

        biometricPrompt.authenticate(promptInfo.build())
    }

    /**
     * Hash a password using SHA-256.
     * @param password The password to hash
     * @return The hashed password as a hex string
     */
    fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify a password against the stored hash.
     * @param password The password to verify
     * @param context The context to retrieve stored hash from
     * @return True if password matches, false otherwise
     */
    fun verifyPassword(password: String, context: Context): Boolean {
        val storedHash = PreferencesHelper.getPasswordHash(context) ?: return false
        val inputHash = hashPassword(password)
        return storedHash == inputHash
    }

    /**
     * Set up a new password.
     * @param password The new password to set
     * @param context The context to store the password hash
     */
    fun setPassword(password: String, context: Context) {
        val hash = hashPassword(password)
        PreferencesHelper.setPasswordHash(context, hash)
    }

    /**
     * Check if password authentication is configured.
     * @param context The context to check
     * @return True if password is set, false otherwise
     */
    fun isPasswordConfigured(context: Context): Boolean {
        return PreferencesHelper.getPasswordHash(context) != null
    }

    /**
     * Clear all authentication settings and data.
     * @param context The context to clear settings from
     */
    fun clearAuthentication(context: Context) {
        PreferencesHelper.setBiometricAuthEnabled(context, false)
        PreferencesHelper.setPasswordAuthEnabled(context, false)
        PreferencesHelper.setPasswordHash(context, "")
        PreferencesHelper.setLastAuthTimestamp(context, 0L)
    }

    /**
     * Validate password strength.
     * @param password The password to validate
     * @return Error message if password is weak, null if valid
     */
    fun validatePasswordStrength(password: String): String? {
        return when {
            password.length < 4 -> "Password must be at least 4 characters"
            password.length > 32 -> "Password must be at most 32 characters"
            else -> null
        }
    }
}

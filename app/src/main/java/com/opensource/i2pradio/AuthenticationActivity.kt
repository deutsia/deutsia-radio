package com.opensource.i2pradio

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.opensource.i2pradio.ui.PreferencesHelper
import com.opensource.i2pradio.utils.BiometricAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity that displays the authentication screen to unlock the app.
 * Supports both password and biometric authentication.
 */
class AuthenticationActivity : AppCompatActivity() {

    private lateinit var passwordInputLayout: TextInputLayout
    private lateinit var passwordInput: TextInputEditText
    private lateinit var errorText: TextView
    private lateinit var unlockButton: MaterialButton
    private lateinit var biometricButton: MaterialButton
    private var progressBar: ProgressBar? = null
    private var isVerifying = false  // Prevent multiple verification attempts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_authentication)

        // Initialize views
        passwordInputLayout = findViewById(R.id.passwordInputLayout)
        passwordInput = findViewById(R.id.passwordInput)
        errorText = findViewById(R.id.errorText)
        unlockButton = findViewById(R.id.unlockButton)
        biometricButton = findViewById(R.id.biometricButton)

        // Setup unlock button
        unlockButton.setOnClickListener {
            attemptPasswordUnlock()
        }

        // Handle enter key on password input
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptPasswordUnlock()
                true
            } else {
                false
            }
        }

        // Initialize progress bar (optional - may not be in all layouts)
        progressBar = findViewById(R.id.progressBar)

        // Setup biometric button
        val isBiometricEnabled = PreferencesHelper.isBiometricEnabled(this)
        val isBiometricAvailable = BiometricAuthManager.isBiometricAvailable(this)
        val isDbEncryptionEnabled = com.opensource.i2pradio.utils.DatabaseEncryptionManager.isDatabaseEncryptionEnabled(this)

        // Disable biometric auth if database encryption is enabled (requires password for key derivation)
        if (isBiometricEnabled && isBiometricAvailable && !isDbEncryptionEnabled) {
            biometricButton.visibility = View.VISIBLE
            biometricButton.setOnClickListener {
                showBiometricPrompt()
            }

            // Auto-show biometric prompt on launch
            showBiometricPrompt()
        } else {
            biometricButton.visibility = View.GONE
        }

        // Focus on password input
        passwordInput.requestFocus()
    }

    /**
     * Attempt to unlock with password
     * Runs password verification on background thread to avoid UI blocking
     * (PBKDF2 with 600k iterations is CPU-intensive)
     */
    private fun attemptPasswordUnlock() {
        // Prevent multiple simultaneous verification attempts
        if (isVerifying) return

        val password = passwordInput.text.toString()

        if (password.isEmpty()) {
            showError(getString(R.string.auth_error_wrong_password))
            return
        }

        // Show loading state
        isVerifying = true
        unlockButton.isEnabled = false
        biometricButton.isEnabled = false
        progressBar?.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        // Run password verification on background thread
        // PBKDF2 with 600k iterations is CPU-intensive and would block UI
        lifecycleScope.launch {
            val isValid = withContext(Dispatchers.Default) {
                BiometricAuthManager.verifyPassword(this@AuthenticationActivity, password)
            }

            // Back on main thread
            isVerifying = false
            unlockButton.isEnabled = true
            biometricButton.isEnabled = true
            progressBar?.visibility = View.GONE

            if (isValid) {
                // Set session password for database decryption (only if encryption is enabled)
                if (com.opensource.i2pradio.utils.DatabaseEncryptionManager.isDatabaseEncryptionEnabled(this@AuthenticationActivity)) {
                    // SECURITY: Convert to CharArray for secure memory handling
                    val passwordChars = password.toCharArray()
                    com.opensource.i2pradio.data.RadioDatabase.setSessionPassword(passwordChars)
                    // Zero out local CharArray copy (RadioDatabase made its own copy)
                    passwordChars.fill(0.toChar())
                }
                unlockSuccess()
            } else {
                showError(getString(R.string.auth_error_wrong_password))
                passwordInput.text?.clear()
            }
        }
    }

    /**
     * Show biometric authentication prompt
     */
    private fun showBiometricPrompt() {
        BiometricAuthManager.showBiometricPrompt(
            activity = this,
            title = getString(R.string.auth_biometric_title),
            subtitle = getString(R.string.auth_biometric_subtitle),
            negativeButtonText = getString(R.string.auth_biometric_negative),
            onSuccess = {
                unlockSuccess()
            },
            onError = { errorMessage ->
                // Don't show error for user cancellation
                if (!errorMessage.contains("Cancel", ignoreCase = true)) {
                    showError(errorMessage)
                }
            },
            onFailed = {
                showError(getString(R.string.auth_error_wrong_password))
            }
        )
    }

    /**
     * Show error message
     */
    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE

        // Shake animation for password input
        passwordInputLayout.animate()
            .translationX(-10f)
            .setDuration(50)
            .withEndAction {
                passwordInputLayout.animate()
                    .translationX(10f)
                    .setDuration(50)
                    .withEndAction {
                        passwordInputLayout.animate()
                            .translationX(-10f)
                            .setDuration(50)
                            .withEndAction {
                                passwordInputLayout.animate()
                                    .translationX(0f)
                                    .setDuration(50)
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    /**
     * Called when authentication is successful
     */
    private fun unlockSuccess() {
        // Clear any error messages
        errorText.visibility = View.GONE

        // Finish this activity and return to the app
        finish()
    }

    /**
     * Prevent back button from bypassing authentication
     */
    override fun onBackPressed() {
        // Move task to back instead of finishing
        // This will minimize the app without closing it
        moveTaskToBack(true)
    }
}

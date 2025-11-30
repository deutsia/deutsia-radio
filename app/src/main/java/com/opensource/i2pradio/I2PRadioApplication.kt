package com.opensource.i2pradio

import android.app.Application
import android.util.Log
import com.opensource.i2pradio.security.SecurePreferencesManager
import com.opensource.i2pradio.security.SecurityAuditLogger
import com.opensource.i2pradio.security.SecurityMigration
import com.opensource.i2pradio.security.RuntimeSecurityChecker
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.tor.TorService
import com.opensource.i2pradio.ui.PreferencesHelper
import com.opensource.i2pradio.util.SecureImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class I2PRadioApplication : Application() {

    // Application-level coroutine scope
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val torStateListener: (TorManager.TorState) -> Unit = { _ ->
        // Invalidate image loader cache when Tor state changes
        // This ensures images use the correct proxy settings
        SecureImageLoader.invalidateCache()
    }

    override fun onCreate() {
        super.onCreate()

        // ═══════════════════════════════════════════════════════════════
        // SECURITY INITIALIZATION
        // ═══════════════════════════════════════════════════════════════
        Log.i("I2PRadioApp", "═══════════════════════════════════════════")
        Log.i("I2PRadioApp", "    SECURITY SUBSYSTEM INITIALIZATION")
        Log.i("I2PRadioApp", "═══════════════════════════════════════════")

        // 1. Initialize encrypted storage
        val secureStorageInitialized = SecurePreferencesManager.initialize(this)
        if (!secureStorageInitialized) {
            Log.e("I2PRadioApp", "❌ CRITICAL: Failed to initialize secure storage!")
        }

        // 2. Run security migration (plaintext -> encrypted) in background
        applicationScope.launch(Dispatchers.IO) {
            val migrationSuccess = SecurityMigration.migrateToEncryptedStorage(this@I2PRadioApplication)
            if (migrationSuccess) {
                Log.i("I2PRadioApp", "✓ Security migration completed successfully")
            } else {
                Log.w("I2PRadioApp", "⚠️  Security migration failed or incomplete")
            }

            // 3. Run comprehensive security checks
            val securityResults = RuntimeSecurityChecker.runSecurityChecks(this@I2PRadioApplication)
            val securityReport = RuntimeSecurityChecker.generateSecurityReport(securityResults)

            Log.i("I2PRadioApp", "\n$securityReport")

            // Check for critical failures
            val criticalFailures = securityResults.filter {
                !it.passed && it.severity == RuntimeSecurityChecker.Severity.CRITICAL
            }

            if (criticalFailures.isNotEmpty()) {
                Log.e("I2PRadioApp", "❌ CRITICAL SECURITY ISSUES DETECTED:")
                criticalFailures.forEach { failure ->
                    Log.e("I2PRadioApp", "  - ${failure.checkName}: ${failure.details}")
                }
            } else {
                Log.i("I2PRadioApp", "✓ All critical security checks passed")
            }
        }

        Log.i("I2PRadioApp", "═══════════════════════════════════════════\n")
        // ═══════════════════════════════════════════════════════════════

        // Dynamic colors are now applied at Activity level in MainActivity
        // to allow toggling Material You on/off without requiring app restart

        // Initialize TorManager early if Tor is enabled
        // This ensures Force Tor settings work immediately on app launch
        if (PreferencesHelper.isEmbeddedTorEnabled(this)) {
            TorManager.initialize(this)
            // Add listener to invalidate image loader when Tor state changes
            TorManager.addStateListener(torStateListener)
            // Auto-start Tor if enabled and not already connected
            if (PreferencesHelper.isAutoStartTorEnabled(this) &&
                TorManager.state != TorManager.TorState.CONNECTED &&
                TorManager.state != TorManager.TorState.STARTING) {
                TorService.start(this)
            }
        }
    }
}

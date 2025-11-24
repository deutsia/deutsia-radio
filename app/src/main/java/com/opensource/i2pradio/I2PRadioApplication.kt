package com.opensource.i2pradio

import android.app.Application
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.tor.TorService
import com.opensource.i2pradio.ui.PreferencesHelper
import com.opensource.i2pradio.util.SecureImageLoader

class I2PRadioApplication : Application() {

    private val torStateListener: (TorManager.TorState) -> Unit = { _ ->
        // Invalidate image loader cache when Tor state changes
        // This ensures images use the correct proxy settings
        SecureImageLoader.invalidateCache()
    }

    override fun onCreate() {
        super.onCreate()
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

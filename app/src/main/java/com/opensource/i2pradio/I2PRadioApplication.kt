package com.opensource.i2pradio

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.opensource.i2pradio.ui.PreferencesHelper

class I2PRadioApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Apply Material You dynamic colors if enabled and on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            PreferencesHelper.isMaterialYouEnabled(this)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }
}

package com.opensource.i2pradio

import android.app.Application

class I2PRadioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Dynamic colors are now applied at Activity level in MainActivity
        // to allow toggling Material You on/off without requiring app restart
    }
}

package com.opensource.i2pradio.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object PreferencesHelper {
    private const val PREFS_NAME = "I2PRadioPrefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_PRESETS_INITIALIZED = "presets_initialized"
    private const val KEY_MATERIAL_YOU_ENABLED = "material_you_enabled"

    fun saveThemeMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode)
            .apply()
    }

    fun getThemeMode(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    fun setMaterialYouEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MATERIAL_YOU_ENABLED, enabled)
            .apply()
    }

    fun isMaterialYouEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MATERIAL_YOU_ENABLED, true)
    }

    fun setPresetsInitialized(context: Context, initialized: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRESETS_INITIALIZED, initialized)
            .apply()
    }

    fun arePresetsInitialized(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRESETS_INITIALIZED, false)
    }
}

package com.opensource.i2pradio.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object PreferencesHelper {
    private const val PREFS_NAME = "I2PRadioPrefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_PRESETS_INITIALIZED = "presets_initialized"
    private const val KEY_MATERIAL_YOU_ENABLED = "material_you_enabled"
    private const val KEY_RECORDING_FORMAT = "recording_format"
    private const val KEY_EMBEDDED_TOR_ENABLED = "embedded_tor_enabled"
    private const val KEY_AUTO_START_TOR = "auto_start_tor"
    private const val KEY_TOR_FOR_CLEARNET = "tor_for_clearnet"

    // Recording format constants
    const val FORMAT_MP3 = "mp3"
    const val FORMAT_M4A = "m4a"
    const val FORMAT_OGG = "ogg"
    const val FORMAT_OPUS = "opus"
    const val FORMAT_WAV = "wav"

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

    fun setRecordingFormat(context: Context, format: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDING_FORMAT, format)
            .apply()
    }

    fun getRecordingFormat(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDING_FORMAT, FORMAT_MP3) ?: FORMAT_MP3
    }

    fun getRecordingFormatDisplayName(format: String): String {
        return when (format) {
            FORMAT_MP3 -> "MP3"
            FORMAT_M4A -> "M4A (AAC)"
            FORMAT_OGG -> "OGG (Vorbis)"
            FORMAT_OPUS -> "Opus"
            FORMAT_WAV -> "WAV"
            else -> "MP3"
        }
    }

    fun getRecordingFormatMimeType(format: String): String {
        return when (format) {
            FORMAT_MP3 -> "audio/mpeg"
            FORMAT_M4A -> "audio/mp4"
            FORMAT_OGG -> "audio/ogg"
            FORMAT_OPUS -> "audio/opus"
            FORMAT_WAV -> "audio/wav"
            else -> "audio/mpeg"
        }
    }

    // Embedded Tor preferences
    fun setEmbeddedTorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EMBEDDED_TOR_ENABLED, enabled)
            .apply()
    }

    fun isEmbeddedTorEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EMBEDDED_TOR_ENABLED, false)
    }

    fun setAutoStartTor(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START_TOR, enabled)
            .apply()
    }

    fun isAutoStartTorEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_TOR, true) // Auto-start by default when Tor is enabled
    }

    // Route clearnet streams through Tor for anonymity/censorship bypass
    fun setTorForClearnet(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TOR_FOR_CLEARNET, enabled)
            .apply()
    }

    fun isTorForClearnetEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOR_FOR_CLEARNET, false)
    }
}

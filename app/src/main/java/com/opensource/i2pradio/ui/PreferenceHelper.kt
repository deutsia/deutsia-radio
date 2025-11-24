package com.opensource.i2pradio.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object PreferencesHelper {
    private const val PREFS_NAME = "I2PRadioPrefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_PRESETS_INITIALIZED = "presets_initialized"
    private const val KEY_MATERIAL_YOU_ENABLED = "material_you_enabled"
    private const val KEY_EMBEDDED_TOR_ENABLED = "embedded_tor_enabled"
    private const val KEY_AUTO_START_TOR = "auto_start_tor"
    private const val KEY_SLEEP_TIMER_MINUTES = "sleep_timer_minutes"
    private const val KEY_SORT_ORDER = "sort_order"
    private const val KEY_EQUALIZER_ENABLED = "equalizer_enabled"
    private const val KEY_EQUALIZER_PRESET = "equalizer_preset"
    private const val KEY_EQUALIZER_BANDS = "equalizer_bands"
    private const val KEY_BASS_BOOST_STRENGTH = "bass_boost_strength"
    private const val KEY_VIRTUALIZER_STRENGTH = "virtualizer_strength"
    private const val KEY_RECORDING_DIRECTORY_URI = "recording_directory_uri"
    private const val KEY_GENRE_FILTER = "genre_filter"
    private const val KEY_FORCE_TOR_ALL = "force_tor_all"
    private const val KEY_FORCE_TOR_EXCEPT_I2P = "force_tor_except_i2p"
    private const val KEY_RECORD_ACROSS_STATIONS = "record_across_stations"
    private const val KEY_RECORD_ALL_STATIONS = "record_all_stations"

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

    // Sleep timer preferences (0 = off)
    fun setSleepTimerMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SLEEP_TIMER_MINUTES, minutes)
            .apply()
    }

    fun getSleepTimerMinutes(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SLEEP_TIMER_MINUTES, 0)
    }

    // Sort order preferences
    fun setSortOrder(context: Context, sortOrder: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SORT_ORDER, sortOrder)
            .apply()
    }

    fun getSortOrder(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SORT_ORDER, "DEFAULT") ?: "DEFAULT"
    }

    // Equalizer preferences
    fun setEqualizerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EQUALIZER_ENABLED, enabled)
            .apply()
    }

    fun isEqualizerEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EQUALIZER_ENABLED, false)
    }

    fun setEqualizerPreset(context: Context, preset: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_EQUALIZER_PRESET, preset)
            .apply()
    }

    fun getEqualizerPreset(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_EQUALIZER_PRESET, 0)
    }

    // Store band levels as comma-separated string
    fun setEqualizerBands(context: Context, bands: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EQUALIZER_BANDS, bands)
            .apply()
    }

    fun getEqualizerBands(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EQUALIZER_BANDS, null)
    }

    // Bass Boost preferences
    fun setBassBoostStrength(context: Context, strength: Short) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BASS_BOOST_STRENGTH, strength.toInt())
            .apply()
    }

    fun getBassBoostStrength(context: Context): Short {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_BASS_BOOST_STRENGTH, 0).toShort()
    }

    // Virtualizer (Surround Sound) preferences
    fun setVirtualizerStrength(context: Context, strength: Short) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VIRTUALIZER_STRENGTH, strength.toInt())
            .apply()
    }

    fun getVirtualizerStrength(context: Context): Short {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_VIRTUALIZER_STRENGTH, 0).toShort()
    }

    // Recording directory preferences
    fun setRecordingDirectoryUri(context: Context, uri: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDING_DIRECTORY_URI, uri)
            .apply()
    }

    fun getRecordingDirectoryUri(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDING_DIRECTORY_URI, null)
    }

    // Genre filter preferences (null = All Genres)
    fun setGenreFilter(context: Context, genre: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GENRE_FILTER, genre)
            .apply()
    }

    fun getGenreFilter(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GENRE_FILTER, null)
    }

    // Force Tor settings - bulletproof mode for maximum privacy
    // These are mutually exclusive: only one can be enabled at a time

    /**
     * Force ALL traffic through Tor - no exceptions, no leaks.
     * When enabled, all network traffic goes through Tor SOCKS proxy.
     * If Tor is not connected, network requests will FAIL (no fallback to clearnet).
     */
    fun setForceTorAll(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_TOR_ALL, enabled)
            .apply()
        // If enabling this, disable the other option (mutual exclusivity)
        if (enabled) {
            setForceTorExceptI2P(context, false)
        }
    }

    fun isForceTorAll(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_TOR_ALL, false)
    }

    /**
     * Force ALL traffic through Tor EXCEPT I2P traffic.
     * I2P traffic uses the I2P HTTP proxy directly.
     * All other traffic (clearnet) goes through Tor SOCKS proxy.
     * If Tor is not connected, non-I2P requests will FAIL (no fallback).
     */
    fun setForceTorExceptI2P(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_TOR_EXCEPT_I2P, enabled)
            .apply()
        // If enabling this, disable the other option (mutual exclusivity)
        if (enabled) {
            setForceTorAll(context, false)
        }
    }

    fun isForceTorExceptI2P(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_TOR_EXCEPT_I2P, false)
    }

    // Record across stations setting
    // When disabled (default), switching stations while recording will stop and save the current recording
    // When enabled, recording continues across station switches (records the original stream)

    /**
     * Set whether recording should continue when switching to a different station.
     * When disabled (default), switching stations stops and saves the current recording.
     * When enabled, recording continues on the original stream even when playback switches.
     */
    fun setRecordAcrossStations(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RECORD_ACROSS_STATIONS, enabled)
            .apply()
    }

    /**
     * Check if recording should continue when switching stations.
     * Default: false (recording stops when switching stations)
     */
    fun isRecordAcrossStationsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RECORD_ACROSS_STATIONS, false)
    }

    // Record All Stations setting
    // When enabled, recording switches to the new station's stream when you switch stations
    // All audio from different stations is recorded into the same file

    /**
     * Set whether recording should switch to new station streams.
     * When enabled, switching stations makes the recording fetch the new stream,
     * continuing the same recording file with content from multiple stations.
     */
    fun setRecordAllStations(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RECORD_ALL_STATIONS, enabled)
            .apply()
        // If enabling this, also enable record across stations (required)
        if (enabled) {
            setRecordAcrossStations(context, true)
        }
    }

    /**
     * Check if recording should switch to new station streams.
     * Default: false (recording stays on original stream)
     */
    fun isRecordAllStationsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RECORD_ALL_STATIONS, false)
    }
}

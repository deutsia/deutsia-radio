package com.opensource.i2pradio.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object PreferencesHelper {
    private const val PREFS_NAME = "DeutsiaRadioPrefs"

    // Broadcast actions
    const val BROADCAST_BANDWIDTH_UPDATED = "com.opensource.i2pradio.BANDWIDTH_UPDATED"

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
    private const val KEY_COLOR_SCHEME = "color_scheme"

    // Custom proxy settings (global defaults)
    private const val KEY_CUSTOM_PROXY_ENABLED = "custom_proxy_enabled"
    private const val KEY_CUSTOM_PROXY_HOST = "custom_proxy_host"
    private const val KEY_CUSTOM_PROXY_PORT = "custom_proxy_port"
    private const val KEY_CUSTOM_PROXY_PROTOCOL = "custom_proxy_protocol"
    private const val KEY_CUSTOM_PROXY_USERNAME = "custom_proxy_username"
    private const val KEY_CUSTOM_PROXY_PASSWORD = "custom_proxy_password"
    private const val KEY_CUSTOM_PROXY_AUTH_TYPE = "custom_proxy_auth_type"
    private const val KEY_CUSTOM_PROXY_DNS_RESOLUTION = "custom_proxy_dns_resolution"
    private const val KEY_CUSTOM_PROXY_CONNECTION_TIMEOUT = "custom_proxy_connection_timeout"
    private const val KEY_CUSTOM_PROXY_BYPASS_LOCAL = "custom_proxy_bypass_local"
    private const val KEY_FORCE_CUSTOM_PROXY = "force_custom_proxy"
    private const val KEY_FORCE_CUSTOM_PROXY_EXCEPT_TOR_I2P = "force_custom_proxy_except_tor_i2p"
    private const val KEY_CUSTOM_PROXY_APPLIED_TO_CLEARNET = "custom_proxy_applied_to_clearnet"
    private const val KEY_BANDWIDTH_USAGE_TOTAL = "bandwidth_usage_total"
    private const val KEY_BANDWIDTH_USAGE_SESSION = "bandwidth_usage_session"
    private const val KEY_BANDWIDTH_USAGE_LAST_RESET = "bandwidth_usage_last_reset"

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
        // If enabling this, disable the other options (mutual exclusivity)
        if (enabled) {
            setForceTorExceptI2P(context, false)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FORCE_CUSTOM_PROXY, false)
                .putBoolean(KEY_FORCE_CUSTOM_PROXY_EXCEPT_TOR_I2P, false)
                .apply()
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
        // If enabling this, disable the other options (mutual exclusivity)
        if (enabled) {
            setForceTorAll(context, false)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FORCE_CUSTOM_PROXY, false)
                .putBoolean(KEY_FORCE_CUSTOM_PROXY_EXCEPT_TOR_I2P, false)
                .apply()
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

    // Color Scheme preferences
    // Available schemes: "default", "peach", "green", "purple", "orange"
    // Each scheme has light/dark mode variants that work independently from Material You

    /**
     * Set the color scheme.
     * Available values: "default", "peach", "green", "purple", "orange"
     * Default: "default" (blue theme)
     */
    fun setColorScheme(context: Context, scheme: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COLOR_SCHEME, scheme)
            .apply()
    }

    /**
     * Get the selected color scheme.
     * Returns: "default", "peach", "green", "purple", or "orange"
     * Default: "default"
     */
    fun getColorScheme(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COLOR_SCHEME, "default") ?: "default"
    }

    // Custom Proxy Settings - Global defaults that can be applied to stations
    // These settings allow users to configure a custom HTTP/SOCKS proxy with authentication
    // for enhanced privacy and network routing control

    /**
     * Enable/disable global custom proxy.
     * When enabled, this provides default proxy settings that can be used for new stations.
     */
    fun setCustomProxyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CUSTOM_PROXY_ENABLED, enabled)
            .apply()
    }

    fun isCustomProxyEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CUSTOM_PROXY_ENABLED, false)
    }

    /**
     * Set custom proxy host (IP address or hostname)
     */
    fun setCustomProxyHost(context: Context, host: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_PROXY_HOST, host)
            .apply()
    }

    fun getCustomProxyHost(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PROXY_HOST, "") ?: ""
    }

    /**
     * Set custom proxy port (typically 8080 for HTTP, 1080 for SOCKS)
     */
    fun setCustomProxyPort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CUSTOM_PROXY_PORT, port)
            .apply()
    }

    fun getCustomProxyPort(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CUSTOM_PROXY_PORT, 8080)
    }

    /**
     * Set custom proxy protocol (HTTP, HTTPS, SOCKS4, SOCKS5)
     */
    fun setCustomProxyProtocol(context: Context, protocol: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_PROXY_PROTOCOL, protocol)
            .apply()
    }

    fun getCustomProxyProtocol(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PROXY_PROTOCOL, "HTTPS") ?: "HTTPS"
    }

    /**
     * Set custom proxy username (optional, for authenticated proxies)
     */
    fun setCustomProxyUsername(context: Context, username: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_PROXY_USERNAME, username)
            .apply()
    }

    fun getCustomProxyUsername(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PROXY_USERNAME, "") ?: ""
    }

    /**
     * Set custom proxy password (optional, for authenticated proxies)
     */
    fun setCustomProxyPassword(context: Context, password: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_PROXY_PASSWORD, password)
            .apply()
    }

    fun getCustomProxyPassword(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PROXY_PASSWORD, "") ?: ""
    }

    /**
     * Set proxy authentication type (NONE, BASIC, DIGEST)
     */
    fun setCustomProxyAuthType(context: Context, authType: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_PROXY_AUTH_TYPE, authType)
            .apply()
    }

    fun getCustomProxyAuthType(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PROXY_AUTH_TYPE, "NONE") ?: "NONE"
    }

    /**
     * Set whether to resolve DNS through the proxy (recommended for privacy)
     */
    fun setCustomProxyDnsResolution(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CUSTOM_PROXY_DNS_RESOLUTION, enabled)
            .apply()
    }

    fun isCustomProxyDnsResolutionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CUSTOM_PROXY_DNS_RESOLUTION, true)
    }

    /**
     * Set custom proxy connection timeout in seconds (0 = use default 30s)
     */
    fun setCustomProxyConnectionTimeout(context: Context, timeoutSeconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CUSTOM_PROXY_CONNECTION_TIMEOUT, timeoutSeconds)
            .apply()
    }

    fun getCustomProxyConnectionTimeout(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CUSTOM_PROXY_CONNECTION_TIMEOUT, 30)
    }

    /**
     * Set whether to bypass proxy for local/private addresses
     */
    fun setCustomProxyBypassLocal(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CUSTOM_PROXY_BYPASS_LOCAL, enabled)
            .apply()
    }

    fun isCustomProxyBypassLocalEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CUSTOM_PROXY_BYPASS_LOCAL, false)
    }

    // Force Custom Proxy setting - forces all traffic through the configured custom proxy
    /**
     * Force ALL traffic through the custom proxy.
     * When enabled, all network traffic goes through the configured custom proxy.
     * If custom proxy is not configured, network requests will FAIL (no fallback).
     * Mutually exclusive with Force Tor modes.
     */
    fun setForceCustomProxy(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_CUSTOM_PROXY, enabled)
            .apply()
        // If enabling this, disable Force Tor options (mutual exclusivity)
        if (enabled) {
            setForceTorAll(context, false)
            setForceTorExceptI2P(context, false)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FORCE_CUSTOM_PROXY_EXCEPT_TOR_I2P, false)
                .apply()
        }
    }

    fun isForceCustomProxy(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_CUSTOM_PROXY, false)
    }

    /**
     * Force Custom Proxy Except Tor/I2P setting - forces clearnet traffic through custom proxy
     * but allows Tor and I2P stations to use their native proxies.
     * When enabled, clearnet traffic goes through the configured custom proxy.
     * Tor and I2P stations use their native Tor SOCKS proxy and I2P HTTP proxy respectively.
     * Mutually exclusive with Force Tor modes and Force Custom Proxy.
     */
    fun setForceCustomProxyExceptTorI2P(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_CUSTOM_PROXY_EXCEPT_TOR_I2P, enabled)
            .apply()
        // If enabling this, disable other force options (mutual exclusivity)
        if (enabled) {
            setForceTorAll(context, false)
            setForceTorExceptI2P(context, false)
            setForceCustomProxy(context, false)
        }
    }

    fun isForceCustomProxyExceptTorI2P(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_CUSTOM_PROXY_EXCEPT_TOR_I2P, false)
    }

    /**
     * Track whether custom proxy has been applied to all clearnet stations.
     * This allows the UI to toggle between "Apply" and "Unapply" states.
     */
    fun setCustomProxyAppliedToClearnet(context: Context, applied: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CUSTOM_PROXY_APPLIED_TO_CLEARNET, applied)
            .apply()
    }

    fun isCustomProxyAppliedToClearnet(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CUSTOM_PROXY_APPLIED_TO_CLEARNET, false)
    }

    // Bandwidth Usage Tracking
    /**
     * Add bandwidth usage in bytes.
     * Updates both total lifetime usage and current session usage.
     * Broadcasts an update for real-time UI refresh.
     */
    fun addBandwidthUsage(context: Context, bytes: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTotal = prefs.getLong(KEY_BANDWIDTH_USAGE_TOTAL, 0L)
        val currentSession = prefs.getLong(KEY_BANDWIDTH_USAGE_SESSION, 0L)

        prefs.edit()
            .putLong(KEY_BANDWIDTH_USAGE_TOTAL, currentTotal + bytes)
            .putLong(KEY_BANDWIDTH_USAGE_SESSION, currentSession + bytes)
            .apply()

        // Broadcast bandwidth update for real-time UI refresh
        // Only broadcast every ~100KB to avoid excessive updates
        if ((currentSession + bytes) / 102400 > currentSession / 102400) {
            val intent = android.content.Intent(BROADCAST_BANDWIDTH_UPDATED)
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context)
                .sendBroadcast(intent)
        }
    }

    /**
     * Get total bandwidth usage in bytes (lifetime).
     */
    fun getTotalBandwidthUsage(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_BANDWIDTH_USAGE_TOTAL, 0L)
    }

    /**
     * Get session bandwidth usage in bytes (since last reset).
     */
    fun getSessionBandwidthUsage(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_BANDWIDTH_USAGE_SESSION, 0L)
    }

    /**
     * Reset session bandwidth usage counter.
     */
    fun resetSessionBandwidthUsage(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_BANDWIDTH_USAGE_SESSION, 0L)
            .putLong(KEY_BANDWIDTH_USAGE_LAST_RESET, System.currentTimeMillis())
            .apply()
    }

    /**
     * Get the timestamp of the last bandwidth usage reset.
     */
    fun getLastBandwidthResetTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_BANDWIDTH_USAGE_LAST_RESET, 0L)
    }

    /**
     * Format bytes as human-readable string (KB, MB, GB).
     */
    fun formatBandwidth(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

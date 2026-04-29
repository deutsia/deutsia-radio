package com.opensource.i2pradio.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.utils.PasswordEncryptionUtil
import org.json.JSONObject

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
    private const val KEY_APP_LANGUAGE = "app_language"

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

    // Authentication settings
    private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_REQUIRE_AUTH_ON_LAUNCH = "require_auth_on_launch"

    // UI settings
    private const val KEY_TOAST_MESSAGES_DISABLED = "toast_messages_disabled"

    // Network & API settings
    private const val KEY_DISABLE_RADIOBROWSER_API = "disable_radiobrowser_api"
    private const val KEY_DISABLE_RADIO_REGISTRY_API = "disable_radio_registry_api"
    private const val KEY_DISABLE_COVER_ART = "disable_cover_art"

    // Integrations
    private const val KEY_ANDROID_AUTO_ENABLED = "android_auto_enabled"
    private const val KEY_AA_FIRST_CONNECT_HANDLED = "aa_first_connect_handled"
    private const val KEY_AA_ALLOW_PROXY_STATIONS = "aa_allow_proxy_stations"

    // Currently playing station persistence
    private const val KEY_CURRENT_STATION_JSON = "current_station_json"

    // Playback queue
    private const val KEY_DISCOVER_ENABLED = "discover_enabled"
    private const val KEY_QUEUE_CONTEXT_IDS = "queue_context_station_ids"
    private const val KEY_QUEUE_CONTEXT_INDEX = "queue_context_index"
    private const val KEY_QUEUE_MANUAL_IDS = "queue_manual_station_ids"

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

    // Language preferences
    // Available languages: system (follow device), ar, de, es, fa, fr, hi, it, ja, ko, my, pt, ru, tr, uk, vi, zh

    /**
     * Set the app language.
     * Available values: "system", "ar", "de", "es", "fa", "fr", "hi", "it", "ja", "ko", "my", "pt", "ru", "tr", "uk", "vi", "zh"
     * Default: "system" (follow device language)
     */
    fun setAppLanguage(context: Context, languageCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, languageCode)
            .apply()
    }

    /**
     * Get the selected app language.
     * Returns: language code or "system" for device default
     * Default: "system"
     */
    fun getAppLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LANGUAGE, "system") ?: "system"
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
     * Password is stored encrypted using EncryptedSharedPreferences
     */
    fun setCustomProxyPassword(context: Context, password: String) {
        // Migrate old plain-text password if it exists
        val oldPassword = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PROXY_PASSWORD, null)

        if (oldPassword != null && oldPassword.isNotEmpty()) {
            // Remove old plain-text password
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CUSTOM_PROXY_PASSWORD)
                .apply()
        }

        // Save password encrypted
        PasswordEncryptionUtil.saveCustomProxyPassword(context, password)
    }

    fun getCustomProxyPassword(context: Context): String {
        // Check for old plain-text password first
        val oldPassword = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PROXY_PASSWORD, null)

        if (oldPassword != null && oldPassword.isNotEmpty()) {
            // Migrate to encrypted storage
            setCustomProxyPassword(context, oldPassword)
            return oldPassword
        }

        // Return encrypted password
        return PasswordEncryptionUtil.getCustomProxyPassword(context)
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

    // ===== Authentication Preferences =====

    /**
     * Enable or disable app lock
     */
    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_APP_LOCK_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if app lock is enabled
     */
    fun isAppLockEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_APP_LOCK_ENABLED, false)
    }

    /**
     * Enable or disable biometric authentication
     */
    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if biometric authentication is enabled
     */
    fun isBiometricEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    /**
     * Set whether to require authentication on app launch
     */
    fun setRequireAuthOnLaunch(context: Context, required: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUIRE_AUTH_ON_LAUNCH, required)
            .apply()
    }

    /**
     * Check if authentication is required on app launch
     */
    fun isRequireAuthOnLaunch(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_AUTH_ON_LAUNCH, true)
    }

    // ===== UI Settings =====

    /**
     * Set whether toast messages are disabled
     */
    fun setToastMessagesDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TOAST_MESSAGES_DISABLED, disabled)
            .apply()
    }

    /**
     * Check if toast messages are disabled
     */
    fun isToastMessagesDisabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOAST_MESSAGES_DISABLED, false)
    }

    // ===== Network & API Settings =====

    /**
     * Set whether RadioBrowser API is disabled.
     * When disabled, the app won't fetch stations from RadioBrowser (radio-browser.info).
     */
    fun setRadioBrowserApiDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISABLE_RADIOBROWSER_API, disabled)
            .apply()
    }

    /**
     * Check if RadioBrowser API is disabled.
     * Default: false (API enabled)
     */
    fun isRadioBrowserApiDisabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLE_RADIOBROWSER_API, false)
    }

    /**
     * Set whether Radio Registry API is disabled.
     * When disabled, the app won't fetch Tor/I2P stations from the Radio Registry API.
     */
    fun setRadioRegistryApiDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISABLE_RADIO_REGISTRY_API, disabled)
            .apply()
    }

    /**
     * Check if Radio Registry API is disabled.
     * Default: false (API enabled)
     */
    fun isRadioRegistryApiDisabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLE_RADIO_REGISTRY_API, false)
    }

    /**
     * Set whether cover art loading is disabled.
     * When disabled, station artwork won't be loaded from external servers.
     */
    fun setCoverArtDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISABLE_COVER_ART, disabled)
            .apply()
    }

    /**
     * Check if cover art loading is disabled.
     * Default: false (cover art enabled)
     */
    fun isCoverArtDisabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLE_COVER_ART, false)
    }

    /**
     * Check if both APIs are disabled (offline mode).
     * In this mode, only local library stations are available.
     */
    fun isOfflineMode(context: Context): Boolean {
        return isRadioBrowserApiDisabled(context) && isRadioRegistryApiDisabled(context)
    }

    // ===== Integrations =====

    /**
     * Set whether Android Auto support is enabled.
     *
     * When disabled (the default), the MediaLibraryService component stays
     * disabled at the PackageManager level, so Android Auto cannot bind to
     * the app and the app never appears in the car's media source list.
     *
     * When enabled, the user explicitly opts in to exposing station metadata
     * and the browse tree to Google's Android Auto app.
     *
     * This preference only records the user's intent; the actual component
     * enable/disable is performed via AndroidAutoComponentManager.
     */
    fun setAndroidAutoEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ANDROID_AUTO_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if Android Auto support is enabled.
     * Default: false (private by default — the app is not visible to AA).
     *
     * Note: this returns the user's stored *preference*, which is distinct
     * from whether AA is actually active right now. When any force-proxy
     * setting is on, Android Auto is blocked regardless of this preference
     * — see [isAndroidAutoBlockedByForceProxy].
     */
    fun isAndroidAutoEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANDROID_AUTO_ENABLED, false)
    }

    /**
     * Is Android Auto currently blocked because a force-proxy setting is on?
     *
     * The Android Auto media library service runs its own ExoPlayer with the
     * default HTTP stack and does not route through Tor, I2P, or the custom
     * proxy. If the user has turned on any "force everything through X"
     * setting, allowing AA playback would silently bypass that routing and
     * leak clearnet traffic. So AA is treated as hard-blocked whenever any
     * force-proxy is active: the component stays disabled and the toggle
     * in Settings is greyed out.
     */
    fun isAndroidAutoBlockedByForceProxy(context: Context): Boolean {
        return isForceTorAll(context) ||
            isForceTorExceptI2P(context) ||
            isForceCustomProxy(context) ||
            isForceCustomProxyExceptTorI2P(context)
    }

    /**
     * Has the user already been shown the "AA is now connected for the first
     * time" notification (Moment 2 of the AA opt-in flow)? This is set to true
     * the first time we observe Android Auto bind to our media library
     * service so that we don't post the notification again on every reconnect.
     */
    fun hasAndroidAutoFirstConnectBeenHandled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AA_FIRST_CONNECT_HANDLED, false)
    }

    fun setAndroidAutoFirstConnectHandled(context: Context, handled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AA_FIRST_CONNECT_HANDLED, handled)
            .apply()
    }

    /**
     * Should Tor / I2P / custom-proxy stations be exposed in the Android Auto
     * browse tree (and be playable from AA)?
     *
     * Default: false (privacy-by-default — proxy-routed stations stay hidden
     * from AA). The user can opt in via Settings after reading the warning
     * about metadata leakage to Google's AA process: even when audio is
     * routed through the station's proxy, AA still hands the station name,
     * track metadata, and artwork to Google so it can render the now-playing
     * card on the head unit. So the audio is anonymous; the *fact you're
     * listening* is not.
     *
     * When enabled, [DeutsiaMediaLibraryService] will (a) include proxy
     * stations in the browse tree and (b) wire its ExoPlayer through the
     * matching per-station proxy so the audio still routes correctly.
     *
     * Force-proxy still hard-blocks AA entirely regardless of this flag —
     * see [isAndroidAutoBlockedByForceProxy].
     */
    fun isAndroidAutoProxyStationsAllowed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AA_ALLOW_PROXY_STATIONS, false)
    }

    fun setAndroidAutoProxyStationsAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AA_ALLOW_PROXY_STATIONS, allowed)
            .apply()
    }

    // ===== Currently Playing Station Persistence =====

    /**
     * Save the currently playing station to persistent storage.
     * This ensures the UI can restore the station info when MainActivity is recreated.
     */
    fun saveCurrentStation(context: Context, station: RadioStation?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (station == null) {
            // Clear the saved station
            prefs.edit()
                .remove(KEY_CURRENT_STATION_JSON)
                .apply()
            return
        }

        try {
            // Serialize RadioStation to JSON
            val json = JSONObject().apply {
                put("id", station.id)
                put("name", station.name)
                put("streamUrl", station.streamUrl)
                put("proxyHost", station.proxyHost)
                put("proxyPort", station.proxyPort)
                put("useProxy", station.useProxy)
                put("proxyType", station.proxyType)
                put("genre", station.genre)
                put("coverArtUri", station.coverArtUri ?: "")
                put("isPreset", station.isPreset)
                put("addedTimestamp", station.addedTimestamp)
                put("isLiked", station.isLiked)
                put("lastPlayedAt", station.lastPlayedAt)
                put("source", station.source)
                put("radioBrowserUuid", station.radioBrowserUuid ?: "")
                put("lastVerified", station.lastVerified)
                put("cachedAt", station.cachedAt)
                put("bitrate", station.bitrate)
                put("codec", station.codec)
                put("hlsHint", station.hlsHint)
                put("codecHint", station.codecHint)
                put("country", station.country)
                put("countryCode", station.countryCode)
                put("homepage", station.homepage)
                put("customProxyProtocol", station.customProxyProtocol)
                put("proxyUsername", station.proxyUsername)
                put("proxyPassword", station.proxyPassword)
                put("proxyAuthType", station.proxyAuthType)
                put("proxyDnsResolution", station.proxyDnsResolution)
                put("proxyConnectionTimeout", station.proxyConnectionTimeout)
                put("proxyBypassLocalAddresses", station.proxyBypassLocalAddresses)
            }

            prefs.edit()
                .putString(KEY_CURRENT_STATION_JSON, json.toString())
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("PreferencesHelper", "Error saving current station", e)
        }
    }

    /**
     * Restore the currently playing station from persistent storage.
     * Returns null if no station was saved or if deserialization fails.
     */
    fun getCurrentStation(context: Context): RadioStation? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_CURRENT_STATION_JSON, null) ?: return null

        return try {
            val json = JSONObject(jsonString)
            RadioStation(
                id = json.getLong("id"),
                name = json.getString("name"),
                streamUrl = json.getString("streamUrl"),
                proxyHost = json.getString("proxyHost"),
                proxyPort = json.getInt("proxyPort"),
                useProxy = json.getBoolean("useProxy"),
                proxyType = json.getString("proxyType"),
                genre = json.getString("genre"),
                coverArtUri = json.getString("coverArtUri").let { if (it.isEmpty()) null else it },
                isPreset = json.getBoolean("isPreset"),
                addedTimestamp = json.getLong("addedTimestamp"),
                isLiked = json.getBoolean("isLiked"),
                lastPlayedAt = json.getLong("lastPlayedAt"),
                source = json.getString("source"),
                radioBrowserUuid = json.getString("radioBrowserUuid").let { if (it.isEmpty()) null else it },
                lastVerified = json.getLong("lastVerified"),
                cachedAt = json.getLong("cachedAt"),
                bitrate = json.getInt("bitrate"),
                codec = json.getString("codec"),
                // hlsHint/codecHint may be missing for stations saved before the
                // v9 migration - default to safe values if absent.
                hlsHint = json.optBoolean("hlsHint", false),
                codecHint = json.optString("codecHint", ""),
                country = json.getString("country"),
                countryCode = json.getString("countryCode"),
                homepage = json.getString("homepage"),
                customProxyProtocol = json.getString("customProxyProtocol"),
                proxyUsername = json.getString("proxyUsername"),
                proxyPassword = json.getString("proxyPassword"),
                proxyAuthType = json.getString("proxyAuthType"),
                proxyDnsResolution = json.getBoolean("proxyDnsResolution"),
                proxyConnectionTimeout = json.getInt("proxyConnectionTimeout"),
                proxyBypassLocalAddresses = json.getBoolean("proxyBypassLocalAddresses")
            )
        } catch (e: Exception) {
            android.util.Log.e("PreferencesHelper", "Error restoring current station", e)
            null
        }
    }

    /**
     * Clear the currently playing station from persistent storage.
     */
    fun clearCurrentStation(context: Context) {
        saveCurrentStation(context, null)
    }

    // ===== Discover (local "play similar" suggestions) =====

    /**
     * Whether the local Discover feature is enabled. When on, the service
     * picks a similar station from the user's library after the queue
     * exhausts. Off by default — Discover has no privacy cost (it's
     * local-only), but the behavior change of "playback continues past the
     * end of my list" should be opt-in so it doesn't surprise existing
     * users on upgrade.
     */
    fun isDiscoverEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISCOVER_ENABLED, false)
    }

    fun setDiscoverEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISCOVER_ENABLED, enabled)
            .apply()
    }

    // ===== Playback queue persistence =====

    /**
     * Save the playback queue state so it survives a service rebirth.
     * Stored as comma-separated id strings; ad-hoc Browse stations (id=0)
     * are skipped because they can't be rehydrated from the local DB.
     */
    fun saveQueueState(
        context: Context,
        contextStationIds: List<Long>,
        contextIndex: Int,
        manualStationIds: List<Long>
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_QUEUE_CONTEXT_IDS, contextStationIds.filter { it > 0L }.joinToString(","))
            .putInt(KEY_QUEUE_CONTEXT_INDEX, contextIndex)
            .putString(KEY_QUEUE_MANUAL_IDS, manualStationIds.filter { it > 0L }.joinToString(","))
            .apply()
    }

    /** Restore the persisted queue context-id list, in order. */
    fun getQueueContextIds(context: Context): List<Long> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE_CONTEXT_IDS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    /** Restore the persisted queue context cursor, or -1 if none. */
    fun getQueueContextIndex(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_QUEUE_CONTEXT_INDEX, -1)
    }

    /** Restore the persisted manual-queue id list. */
    fun getQueueManualIds(context: Context): List<Long> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE_MANUAL_IDS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    /** Wipe the persisted queue (e.g. after the user explicitly clears it). */
    fun clearQueueState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_QUEUE_CONTEXT_IDS)
            .remove(KEY_QUEUE_CONTEXT_INDEX)
            .remove(KEY_QUEUE_MANUAL_IDS)
            .apply()
    }
}

package com.opensource.i2pradio.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.opensource.i2pradio.ui.PreferencesHelper

/**
 * Manages the built-in Android Equalizer attached to the audio session.
 * This provides an in-app equalizer with custom UI instead of relying on external apps.
 * Also manages BassBoost and Virtualizer (Surround Sound) effects.
 */
class EqualizerManager(private val context: Context) {

    companion object {
        private const val TAG = "EqualizerManager"
        const val BASS_BOOST_MAX = 1000.toShort()
        const val VIRTUALIZER_MAX = 1000.toShort()

        // Fixed 5-band EQ frequencies (in Hz) for consistent UI
        val FIXED_BAND_FREQUENCIES = listOf(60, 230, 910, 3600, 14000)
        const val FIXED_BAND_COUNT = 5
    }

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentAudioSessionId: Int = 0

    // Equalizer properties (populated after initialization)
    var numberOfBands: Short = 0
        private set
    var bandLevelRange: ShortArray = shortArrayOf(0, 0)
        private set
    var presetNames: List<String> = emptyList()
        private set
    var centerFrequencies: List<Int> = emptyList()
        private set

    // Bass Boost and Virtualizer availability
    var isBassBoostSupported: Boolean = false
        private set
    var isVirtualizerSupported: Boolean = false
        private set

    // Mapping from fixed band indices to native band indices
    private var fixedToNativeBandMap: IntArray = IntArray(FIXED_BAND_COUNT) { it }

    // Stored levels for the 5 fixed bands (for UI consistency)
    private var fixedBandLevels: ShortArray = ShortArray(FIXED_BAND_COUNT) { 0 }

    /**
     * Initialize the equalizer for the given audio session.
     * Must be called after ExoPlayer is prepared and playing.
     */
    fun initialize(audioSessionId: Int): Boolean {
        if (audioSessionId == 0) {
            Log.w(TAG, "Cannot initialize equalizer with audio session 0")
            return false
        }

        // Release existing equalizer if attached to different session
        if (equalizer != null && currentAudioSessionId != audioSessionId) {
            release()
        }

        // Already initialized for this session
        if (equalizer != null && currentAudioSessionId == audioSessionId) {
            return true
        }

        return try {
            equalizer = Equalizer(0, audioSessionId).apply {
                // Get equalizer properties - use this@EqualizerManager to reference outer class properties
                this@EqualizerManager.numberOfBands = this.numberOfBands
                this@EqualizerManager.bandLevelRange = this.bandLevelRange

                // Get preset names
                val presets = mutableListOf<String>()
                for (i in 0 until this.numberOfPresets) {
                    presets.add(this.getPresetName(i.toShort()))
                }
                this@EqualizerManager.presetNames = presets

                // Get center frequencies for each band
                val frequencies = mutableListOf<Int>()
                for (i in 0 until this@EqualizerManager.numberOfBands) {
                    frequencies.add(this.getCenterFreq(i.toShort()) / 1000) // Convert mHz to Hz
                }
                this@EqualizerManager.centerFrequencies = frequencies

                // Build mapping from fixed bands to native bands
                buildFixedToNativeBandMap()

                // Enable equalizer if previously enabled
                enabled = PreferencesHelper.isEqualizerEnabled(context)

                // Restore saved band levels
                restoreFixedBandLevels()
            }

            // Initialize Bass Boost
            // Note: strengthSupported can return false on some devices even when the effect works
            // So we try to enable and use the effect regardless, and only mark as unsupported
            // if instantiation fails entirely
            try {
                bassBoost = BassBoost(0, audioSessionId).apply {
                    // Mark as supported if we could instantiate it
                    this@EqualizerManager.isBassBoostSupported = true
                    // Restore saved strength value
                    val savedStrength = PreferencesHelper.getBassBoostStrength(context)
                    try {
                        // Always set strength first (including 0 to ensure clean state)
                        setStrength(savedStrength)
                        // Enable based on saved strength value (not equalizer enabled state)
                        enabled = savedStrength > 0
                        Log.d(TAG, "BassBoost restored: enabled=$enabled, strength=$savedStrength")
                    } catch (e: Exception) {
                        Log.w(TAG, "BassBoost setStrength failed: ${e.message}")
                    }
                }
                Log.d(TAG, "BassBoost initialized, strengthSupported=${bassBoost?.strengthSupported}")
            } catch (e: Exception) {
                Log.w(TAG, "BassBoost not available", e)
                isBassBoostSupported = false
            }

            // Initialize Virtualizer (Surround Sound)
            // Same approach: try to use it regardless of strengthSupported
            try {
                virtualizer = Virtualizer(0, audioSessionId).apply {
                    // Mark as supported if we could instantiate it
                    this@EqualizerManager.isVirtualizerSupported = true
                    // Restore saved strength value
                    val savedStrength = PreferencesHelper.getVirtualizerStrength(context)
                    try {
                        // Always set strength first (including 0 to ensure clean state)
                        setStrength(savedStrength)
                        // Enable based on saved strength value (not equalizer enabled state)
                        enabled = savedStrength > 0
                        Log.d(TAG, "Virtualizer restored: enabled=$enabled, strength=$savedStrength")
                    } catch (e: Exception) {
                        Log.w(TAG, "Virtualizer setStrength failed: ${e.message}")
                    }
                }
                Log.d(TAG, "Virtualizer initialized, strengthSupported=${virtualizer?.strengthSupported}")
            } catch (e: Exception) {
                Log.w(TAG, "Virtualizer not available", e)
                isVirtualizerSupported = false
            }

            currentAudioSessionId = audioSessionId
            Log.d(TAG, "Equalizer initialized: $numberOfBands bands, session $audioSessionId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize equalizer", e)
            equalizer = null
            false
        }
    }

    /**
     * Check if equalizer is initialized and ready
     */
    fun isInitialized(): Boolean = equalizer != null || isPreviewMode

    // Preview mode flag - allows UI to show without active audio session
    private var isPreviewMode: Boolean = false

    /**
     * Initialize for preview mode - shows UI with saved settings but no audio effect.
     * Settings configured in preview mode are saved and applied when playback starts.
     */
    fun initializeForPreview() {
        isPreviewMode = true

        // Set default band level range for preview (typical Android values)
        bandLevelRange = shortArrayOf(-1500, 1500)
        numberOfBands = FIXED_BAND_COUNT.toShort()

        // Create preset names for UI
        presetNames = listOf("Custom", "Flat", "Bass Boost", "Treble Boost", "Rock", "Pop", "Classical", "Jazz")

        // Use fixed frequencies as center frequencies for preview
        centerFrequencies = FIXED_BAND_FREQUENCIES

        // Default band mapping (1:1 for preview)
        fixedToNativeBandMap = IntArray(FIXED_BAND_COUNT) { it }

        // Restore saved band levels for preview
        val bandsString = PreferencesHelper.getEqualizerBands(context)
        if (bandsString != null) {
            val levels = bandsString.split(",").mapNotNull { it.toShortOrNull() }
            levels.forEachIndexed { index, level ->
                if (index < FIXED_BAND_COUNT) {
                    fixedBandLevels[index] = level
                }
            }
        }

        // Mark bass boost and virtualizer as available for preview
        isBassBoostSupported = true
        isVirtualizerSupported = true

        Log.d(TAG, "Initialized in preview mode - settings will be applied when playback starts")
    }

    /**
     * Check if equalizer is enabled
     */
    fun isEnabled(): Boolean = equalizer?.enabled ?: PreferencesHelper.isEqualizerEnabled(context)

    /**
     * Enable or disable the equalizer and audio effects
     */
    fun setEnabled(enabled: Boolean) {
        // Always save the preference (even in preview mode)
        PreferencesHelper.setEqualizerEnabled(context, enabled)
        Log.d(TAG, "Equalizer enabled: $enabled (preview: $isPreviewMode)")

        // Apply to equalizer if active (not in preview mode)
        equalizer?.let { eq ->
            eq.enabled = enabled
        }
        // Bass boost and virtualizer work independently - they're controlled by their own strength values
        // So we don't disable them when equalizer is disabled
    }

    /**
     * Get the current level for a specific fixed band (0-4)
     */
    fun getBandLevel(band: Short): Short {
        return fixedBandLevels.getOrNull(band.toInt()) ?: 0
    }

    /**
     * Set the level for a specific fixed band (0-4)
     * @param band The fixed band index (0 to 4)
     * @param level The level in millibels (within bandLevelRange)
     */
    fun setBandLevel(band: Short, level: Short) {
        if (band < 0 || band >= FIXED_BAND_COUNT) return

        // Store the level for this fixed band (always, even in preview mode)
        fixedBandLevels[band.toInt()] = level
        saveFixedBandLevels()

        // Apply to equalizer if active (not in preview mode)
        equalizer?.let { eq ->
            try {
                val nativeBand = fixedToNativeBandMap[band.toInt()]
                eq.setBandLevel(nativeBand.toShort(), level)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set band level", e)
            }
        }
    }

    /**
     * Build mapping from fixed 5-band frequencies to native equalizer bands.
     * Each fixed band is mapped to the closest native band.
     */
    private fun buildFixedToNativeBandMap() {
        if (centerFrequencies.isEmpty()) return

        for (fixedBand in 0 until FIXED_BAND_COUNT) {
            val targetFreq = FIXED_BAND_FREQUENCIES[fixedBand]
            var closestNativeBand = 0
            var closestDiff = Int.MAX_VALUE

            for (nativeBand in centerFrequencies.indices) {
                val diff = kotlin.math.abs(centerFrequencies[nativeBand] - targetFreq)
                if (diff < closestDiff) {
                    closestDiff = diff
                    closestNativeBand = nativeBand
                }
            }
            fixedToNativeBandMap[fixedBand] = closestNativeBand
        }

        Log.d(TAG, "Fixed band mapping: ${fixedToNativeBandMap.contentToString()}")
        Log.d(TAG, "Native frequencies: $centerFrequencies")
    }

    /**
     * Apply a preset
     * @param preset The preset index (0 to numberOfPresets-1)
     */
    fun usePreset(preset: Short) {
        equalizer?.let { eq ->
            try {
                eq.usePreset(preset)
                PreferencesHelper.setEqualizerPreset(context, preset.toInt())
                // Sync fixed band levels from native bands
                syncFixedBandLevelsFromNative()
                saveFixedBandLevels()
                Log.d(TAG, "Applied preset: ${presetNames.getOrNull(preset.toInt())}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply preset", e)
            }
        }
    }

    /**
     * Sync fixed band levels from native equalizer bands (after preset applied)
     */
    private fun syncFixedBandLevelsFromNative() {
        equalizer?.let { eq ->
            for (fixedBand in 0 until FIXED_BAND_COUNT) {
                val nativeBand = fixedToNativeBandMap[fixedBand]
                fixedBandLevels[fixedBand] = eq.getBandLevel(nativeBand.toShort())
            }
        }
    }

    /**
     * Get the current preset index, or -1 if no preset is active
     */
    fun getCurrentPreset(): Short {
        return try {
            equalizer?.currentPreset ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Get all fixed band levels as an array (5 bands)
     */
    fun getAllBandLevels(): ShortArray {
        return fixedBandLevels.copyOf()
    }

    /**
     * Reset all bands to flat (0 dB)
     */
    fun resetToFlat() {
        // Reset fixed band levels (always, even in preview mode)
        for (i in 0 until FIXED_BAND_COUNT) {
            fixedBandLevels[i] = 0
        }
        saveFixedBandLevels()

        // Reset bass boost and virtualizer strength
        PreferencesHelper.setBassBoostStrength(context, 0)
        PreferencesHelper.setVirtualizerStrength(context, 0)

        // Apply to equalizer if active
        equalizer?.let { eq ->
            for (band in 0 until numberOfBands) {
                eq.setBandLevel(band.toShort(), 0)
            }
        }

        // Apply to bass boost and virtualizer if active
        try {
            bassBoost?.setStrength(0)
            virtualizer?.setStrength(0)
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting effects: ${e.message}")
        }

        Log.d(TAG, "Reset to flat")
    }

    /**
     * Save current fixed band levels to preferences
     */
    private fun saveFixedBandLevels() {
        val bandsString = fixedBandLevels.joinToString(",")
        PreferencesHelper.setEqualizerBands(context, bandsString)
    }

    /**
     * Restore fixed band levels from preferences and apply to native bands
     */
    private fun restoreFixedBandLevels() {
        val bandsString = PreferencesHelper.getEqualizerBands(context) ?: return
        val levels = bandsString.split(",").mapNotNull { it.toShortOrNull() }

        equalizer?.let { eq ->
            levels.forEachIndexed { index, level ->
                if (index < FIXED_BAND_COUNT) {
                    fixedBandLevels[index] = level
                    val nativeBand = fixedToNativeBandMap[index]
                    try {
                        eq.setBandLevel(nativeBand.toShort(), level)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restore fixed band $index level", e)
                    }
                }
            }
            Log.d(TAG, "Restored ${levels.size} fixed band levels")
        }
    }

    /**
     * Get the number of fixed bands (always 5)
     */
    fun getFixedBandCount(): Int = FIXED_BAND_COUNT

    /**
     * Get the frequency for a fixed band
     */
    fun getFixedBandFrequency(band: Int): Int {
        return FIXED_BAND_FREQUENCIES.getOrElse(band) { 0 }
    }

    /**
     * Format a frequency value for display
     */
    fun formatFrequency(freqHz: Int): String {
        return if (freqHz >= 1000) {
            "${freqHz / 1000}kHz"
        } else {
            "${freqHz}Hz"
        }
    }

    /**
     * Convert millibels to decibels for display
     */
    fun millibelsToDb(millibels: Short): Float {
        return millibels / 100f
    }

    // ========== Bass Boost Methods ==========

    /**
     * Get current bass boost strength (0-1000)
     */
    fun getBassBoostStrength(): Short {
        return try {
            bassBoost?.roundedStrength ?: PreferencesHelper.getBassBoostStrength(context)
        } catch (e: Exception) {
            PreferencesHelper.getBassBoostStrength(context)
        }
    }

    /**
     * Set bass boost strength (0-1000)
     */
    fun setBassBoostStrength(strength: Short) {
        // Always save preference (even in preview mode)
        PreferencesHelper.setBassBoostStrength(context, strength)
        Log.d(TAG, "Bass boost strength set to: $strength (preview: $isPreviewMode)")

        // Apply to bass boost if active
        bassBoost?.let { bb ->
            try {
                // Always set strength first (including 0 to fully reset the effect)
                bb.setStrength(strength)
                // Enable/disable based on strength value (independent of equalizer state)
                bb.enabled = strength > 0
                Log.d(TAG, "Bass boost applied: enabled=${bb.enabled}, strength=$strength")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set bass boost strength", e)
            }
        }
    }

    // ========== Virtualizer (Surround Sound) Methods ==========

    /**
     * Get current virtualizer strength (0-1000)
     */
    fun getVirtualizerStrength(): Short {
        return try {
            virtualizer?.roundedStrength ?: PreferencesHelper.getVirtualizerStrength(context)
        } catch (e: Exception) {
            PreferencesHelper.getVirtualizerStrength(context)
        }
    }

    /**
     * Set virtualizer strength (0-1000)
     */
    fun setVirtualizerStrength(strength: Short) {
        // Always save preference (even in preview mode)
        PreferencesHelper.setVirtualizerStrength(context, strength)
        Log.d(TAG, "Virtualizer strength set to: $strength (preview: $isPreviewMode)")

        // Apply to virtualizer if active
        virtualizer?.let { virt ->
            try {
                // Always set strength first (including 0 to fully reset the effect)
                virt.setStrength(strength)
                // Enable/disable based on strength value (independent of equalizer state)
                virt.enabled = strength > 0
                Log.d(TAG, "Virtualizer applied: enabled=${virt.enabled}, strength=$strength")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set virtualizer strength", e)
            }
        }
    }

    /**
     * Release the equalizer resources
     */
    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing equalizer", e)
        }
        try {
            bassBoost?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing bass boost", e)
        }
        try {
            virtualizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtualizer", e)
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        currentAudioSessionId = 0
        Log.d(TAG, "Equalizer and effects released")
    }
}

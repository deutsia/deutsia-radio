package com.opensource.i2pradio.audio

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import com.opensource.i2pradio.ui.PreferencesHelper

/**
 * Manages the built-in Android Equalizer attached to the audio session.
 * This provides an in-app equalizer with custom UI instead of relying on external apps.
 */
class EqualizerManager(private val context: Context) {

    companion object {
        private const val TAG = "EqualizerManager"
    }

    private var equalizer: Equalizer? = null
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

                // Enable equalizer if previously enabled
                enabled = PreferencesHelper.isEqualizerEnabled(context)

                // Restore saved band levels
                restoreBandLevels()
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
    fun isInitialized(): Boolean = equalizer != null

    /**
     * Check if equalizer is enabled
     */
    fun isEnabled(): Boolean = equalizer?.enabled ?: false

    /**
     * Enable or disable the equalizer
     */
    fun setEnabled(enabled: Boolean) {
        equalizer?.let { eq ->
            eq.enabled = enabled
            PreferencesHelper.setEqualizerEnabled(context, enabled)
            Log.d(TAG, "Equalizer enabled: $enabled")
        }
    }

    /**
     * Get the current level for a specific band
     */
    fun getBandLevel(band: Short): Short {
        return equalizer?.getBandLevel(band) ?: 0
    }

    /**
     * Set the level for a specific band
     * @param band The band index (0 to numberOfBands-1)
     * @param level The level in millibels (within bandLevelRange)
     */
    fun setBandLevel(band: Short, level: Short) {
        equalizer?.let { eq ->
            try {
                eq.setBandLevel(band, level)
                saveBandLevels()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set band level", e)
            }
        }
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
                // Save the resulting band levels
                saveBandLevels()
                Log.d(TAG, "Applied preset: ${presetNames.getOrNull(preset.toInt())}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply preset", e)
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
     * Get all band levels as an array
     */
    fun getAllBandLevels(): ShortArray {
        val eq = equalizer ?: return shortArrayOf()
        return ShortArray(numberOfBands.toInt()) { band ->
            eq.getBandLevel(band.toShort())
        }
    }

    /**
     * Reset all bands to flat (0 dB)
     */
    fun resetToFlat() {
        equalizer?.let { eq ->
            for (band in 0 until numberOfBands) {
                eq.setBandLevel(band.toShort(), 0)
            }
            saveBandLevels()
            Log.d(TAG, "Reset to flat")
        }
    }

    /**
     * Save current band levels to preferences
     */
    private fun saveBandLevels() {
        val levels = getAllBandLevels()
        val bandsString = levels.joinToString(",")
        PreferencesHelper.setEqualizerBands(context, bandsString)
    }

    /**
     * Restore band levels from preferences
     */
    private fun restoreBandLevels() {
        val bandsString = PreferencesHelper.getEqualizerBands(context) ?: return
        val levels = bandsString.split(",").mapNotNull { it.toShortOrNull() }

        equalizer?.let { eq ->
            levels.forEachIndexed { index, level ->
                if (index < numberOfBands) {
                    try {
                        eq.setBandLevel(index.toShort(), level)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restore band $index level", e)
                    }
                }
            }
            Log.d(TAG, "Restored ${levels.size} band levels")
        }
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

    /**
     * Release the equalizer resources
     */
    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing equalizer", e)
        }
        equalizer = null
        currentAudioSessionId = 0
        Log.d(TAG, "Equalizer released")
    }
}

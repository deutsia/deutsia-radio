package com.opensource.i2pradio.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import com.opensource.i2pradio.R
import com.opensource.i2pradio.audio.EqualizerManager

/**
 * Bottom sheet dialog for the built-in equalizer.
 * Displays band sliders, presets, bass boost, surround sound, and enable/disable toggle.
 */
class EqualizerBottomSheet(
    private val equalizerManager: EqualizerManager
) : BottomSheetDialogFragment() {

    private lateinit var enableSwitch: MaterialSwitch
    private lateinit var presetDropdown: AutoCompleteTextView
    private lateinit var bandSlidersContainer: LinearLayout
    private lateinit var resetButton: MaterialButton
    private lateinit var minDbLabel: TextView
    private lateinit var maxDbLabel: TextView
    private lateinit var dbScaleContainer: LinearLayout

    // Bass Boost and Surround Sound
    private lateinit var bassBoostContainer: LinearLayout
    private lateinit var bassBoostSlider: Slider
    private lateinit var surroundSoundContainer: LinearLayout
    private lateinit var surroundSoundSlider: Slider

    private val bandSliders = mutableListOf<SeekBar>()

    // Flag to prevent feedback loops when updating UI from preset
    private var isUpdatingFromPreset = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_equalizer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        enableSwitch = view.findViewById(R.id.equalizerEnableSwitch)
        presetDropdown = view.findViewById(R.id.presetDropdown)
        bandSlidersContainer = view.findViewById(R.id.bandSlidersContainer)
        resetButton = view.findViewById(R.id.resetButton)
        minDbLabel = view.findViewById(R.id.minDbLabel)
        maxDbLabel = view.findViewById(R.id.maxDbLabel)
        dbScaleContainer = view.findViewById(R.id.dbScaleContainer)

        // Bass Boost and Surround Sound
        bassBoostContainer = view.findViewById(R.id.bassBoostContainer)
        bassBoostSlider = view.findViewById(R.id.bassBoostSlider)
        surroundSoundContainer = view.findViewById(R.id.surroundSoundContainer)
        surroundSoundSlider = view.findViewById(R.id.surroundSoundSlider)

        setupEnableSwitch()
        setupPresetDropdown()
        setupBandSliders()
        setupBassBoostSlider()
        setupSurroundSoundSlider()
        setupResetButton()
        setupDbLabels()
        updateUIState()
    }

    private fun setupEnableSwitch() {
        enableSwitch.isChecked = equalizerManager.isEnabled()
        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            equalizerManager.setEnabled(isChecked)
            updateUIState()
        }
    }

    private fun setupPresetDropdown() {
        val presets = mutableListOf(getString(R.string.equalizer_custom))
        presets.addAll(equalizerManager.presetNames)

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            presets
        )
        presetDropdown.setAdapter(adapter)

        // Set initial preset
        val currentPreset = equalizerManager.getCurrentPreset()
        if (currentPreset >= 0 && currentPreset < equalizerManager.presetNames.size) {
            presetDropdown.setText(equalizerManager.presetNames[currentPreset.toInt()], false)
        } else {
            presetDropdown.setText(getString(R.string.equalizer_custom), false)
        }

        presetDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                // Custom - do nothing, user will adjust sliders manually
            } else {
                // Apply preset (position - 1 because "Custom" is at index 0)
                isUpdatingFromPreset = true
                equalizerManager.usePreset((position - 1).toShort())
                updateBandSliders()
                isUpdatingFromPreset = false
            }
        }
    }

    private fun setupBandSliders() {
        bandSlidersContainer.removeAllViews()
        bandSliders.clear()

        val inflater = LayoutInflater.from(context)
        val levelRange = equalizerManager.bandLevelRange
        val minLevel = levelRange[0].toInt()
        val maxLevel = levelRange[1].toInt()
        val range = maxLevel - minLevel

        for (band in 0 until equalizerManager.numberOfBands) {
            val bandView = inflater.inflate(R.layout.item_equalizer_band, bandSlidersContainer, false)

            val slider = bandView.findViewById<SeekBar>(R.id.bandSlider)
            val freqLabel = bandView.findViewById<TextView>(R.id.bandFrequency)

            // Set frequency label
            val freq = equalizerManager.centerFrequencies.getOrNull(band) ?: 0
            freqLabel.text = formatFrequencyLabel(freq)

            // Configure slider
            slider.max = range
            val currentLevel = equalizerManager.getBandLevel(band.toShort()).toInt()
            slider.progress = currentLevel - minLevel

            // Set listener
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !isUpdatingFromPreset) {
                        val level = (minLevel + progress).toShort()
                        equalizerManager.setBandLevel(band.toShort(), level)
                        // Set dropdown to "Custom" when user manually adjusts
                        presetDropdown.setText(getString(R.string.equalizer_custom), false)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            bandSliders.add(slider)
            bandSlidersContainer.addView(bandView)
        }
    }

    private fun setupBassBoostSlider() {
        // Hide if not supported
        if (!equalizerManager.isBassBoostSupported) {
            bassBoostContainer.visibility = View.GONE
            return
        }

        bassBoostContainer.visibility = View.VISIBLE
        bassBoostSlider.value = equalizerManager.getBassBoostStrength().toFloat()

        bassBoostSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                equalizerManager.setBassBoostStrength(value.toInt().toShort())
            }
        }
    }

    private fun setupSurroundSoundSlider() {
        // Hide if not supported
        if (!equalizerManager.isVirtualizerSupported) {
            surroundSoundContainer.visibility = View.GONE
            return
        }

        surroundSoundContainer.visibility = View.VISIBLE
        surroundSoundSlider.value = equalizerManager.getVirtualizerStrength().toFloat()

        surroundSoundSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                equalizerManager.setVirtualizerStrength(value.toInt().toShort())
            }
        }
    }

    private fun setupResetButton() {
        resetButton.setOnClickListener {
            equalizerManager.resetToFlat()
            updateBandSliders()
            presetDropdown.setText(getString(R.string.equalizer_custom), false)

            // Reset bass boost and surround sound
            if (equalizerManager.isBassBoostSupported) {
                equalizerManager.setBassBoostStrength(0)
                bassBoostSlider.value = 0f
            }
            if (equalizerManager.isVirtualizerSupported) {
                equalizerManager.setVirtualizerStrength(0)
                surroundSoundSlider.value = 0f
            }
        }
    }

    private fun setupDbLabels() {
        val levelRange = equalizerManager.bandLevelRange
        val minDb = equalizerManager.millibelsToDb(levelRange[0])
        val maxDb = equalizerManager.millibelsToDb(levelRange[1])

        minDbLabel.text = String.format("%.0f dB", minDb)
        maxDbLabel.text = String.format("+%.0f dB", maxDb)
    }

    private fun updateBandSliders() {
        val levelRange = equalizerManager.bandLevelRange
        val minLevel = levelRange[0].toInt()

        for (band in 0 until equalizerManager.numberOfBands) {
            val currentLevel = equalizerManager.getBandLevel(band.toShort()).toInt()
            bandSliders.getOrNull(band)?.progress = currentLevel - minLevel
        }
    }

    /**
     * Format frequency for display with proper spacing
     */
    private fun formatFrequencyLabel(freqHz: Int): String {
        return if (freqHz >= 1000) {
            "${freqHz / 1000} kHz"
        } else {
            "$freqHz Hz"
        }
    }

    private fun updateUIState() {
        val isEnabled = equalizerManager.isEnabled()
        presetDropdown.isEnabled = isEnabled
        resetButton.isEnabled = isEnabled

        val alpha = if (isEnabled) 1f else 0.5f
        view?.findViewById<TextInputLayout>(R.id.presetInputLayout)?.alpha = alpha
        bandSlidersContainer.alpha = alpha
        dbScaleContainer.alpha = alpha
        bassBoostContainer.alpha = alpha
        surroundSoundContainer.alpha = alpha
        resetButton.alpha = alpha

        // Enable/disable all sliders
        bandSliders.forEach { it.isEnabled = isEnabled }
        bassBoostSlider.isEnabled = isEnabled
        surroundSoundSlider.isEnabled = isEnabled
    }

    companion object {
        const val TAG = "EqualizerBottomSheet"

        fun newInstance(equalizerManager: EqualizerManager): EqualizerBottomSheet {
            return EqualizerBottomSheet(equalizerManager)
        }
    }
}

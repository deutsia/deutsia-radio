package com.opensource.i2pradio.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.opensource.i2pradio.R
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.tor.TorService

class SettingsFragment : Fragment() {

    private lateinit var recordingFormatButton: MaterialButton

    // Tor UI elements
    private var embeddedTorSwitch: MaterialSwitch? = null
    private var torStatusContainer: View? = null
    private var torStatusIcon: ImageView? = null
    private var torStatusText: TextView? = null
    private var torStatusDetail: TextView? = null
    private var torActionButton: MaterialButton? = null
    private var torClearnetContainer: View? = null
    private var torClearnetSwitch: MaterialSwitch? = null

    private val torStateListener: (TorManager.TorState) -> Unit = { state ->
        activity?.runOnUiThread {
            updateTorStatusUI(state)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val themeButton = view.findViewById<MaterialButton>(R.id.themeButton)
        val githubButton = view.findViewById<MaterialButton>(R.id.githubButton)
        val materialYouSwitch = view.findViewById<MaterialSwitch>(R.id.materialYouSwitch)
        val materialYouContainer = view.findViewById<View>(R.id.materialYouContainer)
        recordingFormatButton = view.findViewById(R.id.recordingFormatButton)

        // Tor UI elements
        embeddedTorSwitch = view.findViewById(R.id.embeddedTorSwitch)
        torStatusContainer = view.findViewById(R.id.torStatusContainer)
        torStatusIcon = view.findViewById(R.id.torStatusIcon)
        torStatusText = view.findViewById(R.id.torStatusText)
        torStatusDetail = view.findViewById(R.id.torStatusDetail)
        torActionButton = view.findViewById(R.id.torActionButton)
        torClearnetContainer = view.findViewById(R.id.torClearnetContainer)
        torClearnetSwitch = view.findViewById(R.id.torClearnetSwitch)

        // Show Material You option only on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            materialYouContainer?.visibility = View.VISIBLE
            materialYouSwitch?.isChecked = PreferencesHelper.isMaterialYouEnabled(requireContext())
            materialYouSwitch?.setOnCheckedChangeListener { switch, isChecked ->
                // Animate the switch with a smooth bounce effect
                switch.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(100)
                    .setInterpolator(OvershootInterpolator(2f))
                    .withEndAction {
                        switch.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150)
                            .setInterpolator(OvershootInterpolator(1.5f))
                            .start()
                    }
                    .start()

                PreferencesHelper.setMaterialYouEnabled(requireContext(), isChecked)
                // Delay recreate to allow the animation to complete
                Handler(Looper.getMainLooper()).postDelayed({
                    activity?.recreate()
                }, 300)
            }
        } else {
            materialYouContainer?.visibility = View.GONE
        }

        // Update theme button text
        updateThemeButtonText(themeButton)

        // Theme selector
        themeButton.setOnClickListener {
            showThemeDialog(themeButton)
        }

        // GitHub button
        githubButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yourusername/i2p-radio"))
            startActivity(intent)
        }

        // Recording format
        updateRecordingFormatButtonText()
        recordingFormatButton.setOnClickListener {
            showRecordingFormatDialog()
        }

        // Setup Tor controls
        setupTorControls()

        return view
    }

    override fun onResume() {
        super.onResume()
        TorManager.addStateListener(torStateListener)
    }

    override fun onPause() {
        super.onPause()
        TorManager.removeStateListener(torStateListener)
    }

    private fun setupTorControls() {
        // Initialize switch state
        embeddedTorSwitch?.isChecked = PreferencesHelper.isEmbeddedTorEnabled(requireContext())

        // Show/hide status container based on switch state
        updateTorContainerVisibility(embeddedTorSwitch?.isChecked == true)

        // Update initial Tor status
        updateTorStatusUI(TorManager.state)

        // Handle switch toggle
        embeddedTorSwitch?.setOnCheckedChangeListener { switch, isChecked ->
            // Animate the switch
            switch.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .setDuration(100)
                .setInterpolator(OvershootInterpolator(2f))
                .withEndAction {
                    switch.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
                .start()

            PreferencesHelper.setEmbeddedTorEnabled(requireContext(), isChecked)
            updateTorContainerVisibility(isChecked)

            if (isChecked) {
                // Auto-start Tor when enabled
                if (PreferencesHelper.isAutoStartTorEnabled(requireContext())) {
                    TorService.start(requireContext())
                }
            } else {
                // Stop Tor when disabled
                TorService.stop(requireContext())
            }
        }

        // Handle action button
        torActionButton?.setOnClickListener {
            when (TorManager.state) {
                TorManager.TorState.STOPPED, TorManager.TorState.ERROR -> {
                    TorService.start(requireContext())
                }
                TorManager.TorState.CONNECTED -> {
                    TorService.stop(requireContext())
                }
                TorManager.TorState.STARTING -> {
                    // Do nothing while starting
                }
            }
        }

        // Setup clearnet through Tor toggle
        torClearnetSwitch?.isChecked = PreferencesHelper.isTorForClearnetEnabled(requireContext())
        torClearnetSwitch?.setOnCheckedChangeListener { switch, isChecked ->
            // Animate the switch
            switch.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .setDuration(100)
                .setInterpolator(OvershootInterpolator(2f))
                .withEndAction {
                    switch.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
                .start()

            PreferencesHelper.setTorForClearnet(requireContext(), isChecked)
        }
    }

    private fun updateTorContainerVisibility(show: Boolean) {
        torStatusContainer?.visibility = if (show) View.VISIBLE else View.GONE
        torClearnetContainer?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateTorStatusUI(state: TorManager.TorState) {
        when (state) {
            TorManager.TorState.STOPPED -> {
                torStatusIcon?.setImageResource(R.drawable.ic_tor_off)
                torStatusText?.text = "Disconnected"
                torStatusDetail?.text = "Tor is not running"
                torActionButton?.text = "Start"
                torActionButton?.isEnabled = true
            }
            TorManager.TorState.STARTING -> {
                torStatusIcon?.setImageResource(R.drawable.ic_tor_connecting)
                torStatusText?.text = "Connecting..."
                torStatusDetail?.text = "Establishing Tor connection"
                torActionButton?.text = "Starting..."
                torActionButton?.isEnabled = false
            }
            TorManager.TorState.CONNECTED -> {
                torStatusIcon?.setImageResource(R.drawable.ic_tor_on)
                torStatusText?.text = "Connected"
                torStatusDetail?.text = "SOCKS port: ${TorManager.socksPort}"
                torActionButton?.text = "Stop"
                torActionButton?.isEnabled = true
            }
            TorManager.TorState.ERROR -> {
                torStatusIcon?.setImageResource(R.drawable.ic_tor_off)
                torStatusText?.text = "Connection Failed"
                torStatusDetail?.text = TorManager.errorMessage ?: "Unknown error"
                torActionButton?.text = "Retry"
                torActionButton?.isEnabled = true
            }
        }
    }

    private fun updateRecordingFormatButtonText() {
        val currentFormat = PreferencesHelper.getRecordingFormat(requireContext())
        recordingFormatButton.text = PreferencesHelper.getRecordingFormatDisplayName(currentFormat)
    }

    private fun showRecordingFormatDialog() {
        val formats = arrayOf(
            PreferencesHelper.FORMAT_MP3,
            PreferencesHelper.FORMAT_M4A,
            PreferencesHelper.FORMAT_OGG,
            PreferencesHelper.FORMAT_OPUS,
            PreferencesHelper.FORMAT_WAV
        )
        val formatNames = formats.map { PreferencesHelper.getRecordingFormatDisplayName(it) }.toTypedArray()
        val currentFormat = PreferencesHelper.getRecordingFormat(requireContext())
        val selectedIndex = formats.indexOf(currentFormat).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Recording Format")
            .setSingleChoiceItems(formatNames, selectedIndex) { dialog, which ->
                PreferencesHelper.setRecordingFormat(requireContext(), formats[which])
                updateRecordingFormatButtonText()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateThemeButtonText(button: MaterialButton) {
        val currentMode = AppCompatDelegate.getDefaultNightMode()
        button.text = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> "Light"
            AppCompatDelegate.MODE_NIGHT_YES -> "Dark"
            else -> "System Default"
        }
    }

    private fun showThemeDialog(themeButton: MaterialButton) {
        val themes = arrayOf("System Default", "Light", "Dark")
        val currentMode = AppCompatDelegate.getDefaultNightMode()
        val selectedIndex = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Choose Theme")
            .setSingleChoiceItems(themes, selectedIndex) { dialog, which ->
                val newMode = when (which) {
                    0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(newMode)
                PreferencesHelper.saveThemeMode(requireContext(), newMode)
                updateThemeButtonText(themeButton)
                dialog.dismiss()
            }
            .show()
    }
}

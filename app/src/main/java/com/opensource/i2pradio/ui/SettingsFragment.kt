package com.opensource.i2pradio.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.tor.TorService

class SettingsFragment : Fragment() {

    // Tor UI elements
    private var embeddedTorSwitch: MaterialSwitch? = null
    private var torStatusContainer: View? = null
    private var torStatusIcon: ImageView? = null
    private var torStatusText: TextView? = null
    private var torStatusDetail: TextView? = null
    private var torActionButton: MaterialButton? = null
    private var forceTorAllSwitch: MaterialSwitch? = null
    private var forceTorExceptI2pSwitch: MaterialSwitch? = null
    private var forceTorAllContainer: View? = null
    private var forceTorExceptI2pContainer: View? = null

    // Recording directory UI elements
    private var recordingDirectoryPath: TextView? = null
    private var recordingDirectoryButton: MaterialButton? = null

    // Directory picker launcher
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { selectedUri ->
            // Take persistable permission so we can access this directory after app restarts
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(selectedUri, takeFlags)

            // Save the URI
            PreferencesHelper.setRecordingDirectoryUri(requireContext(), selectedUri.toString())
            updateRecordingDirectoryDisplay()
            Toast.makeText(requireContext(), "Recording directory updated", Toast.LENGTH_SHORT).show()
        }
    }

    // Service binding for equalizer
    private var radioService: RadioService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioService.RadioBinder
            radioService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService = null
            serviceBound = false
        }
    }

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

        // Tor UI elements
        embeddedTorSwitch = view.findViewById(R.id.embeddedTorSwitch)
        torStatusContainer = view.findViewById(R.id.torStatusContainer)
        torStatusIcon = view.findViewById(R.id.torStatusIcon)
        torStatusText = view.findViewById(R.id.torStatusText)
        torStatusDetail = view.findViewById(R.id.torStatusDetail)
        torActionButton = view.findViewById(R.id.torActionButton)
        forceTorAllSwitch = view.findViewById(R.id.forceTorAllSwitch)
        forceTorExceptI2pSwitch = view.findViewById(R.id.forceTorExceptI2pSwitch)
        forceTorAllContainer = view.findViewById(R.id.forceTorAllContainer)
        forceTorExceptI2pContainer = view.findViewById(R.id.forceTorExceptI2pContainer)

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
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/deutsia/i2pradio"))
            startActivity(intent)
        }

        // Sleep timer button
        val sleepTimerButton = view.findViewById<MaterialButton>(R.id.sleepTimerButton)
        updateSleepTimerButtonText(sleepTimerButton)
        sleepTimerButton.setOnClickListener {
            showSleepTimerDialog(sleepTimerButton)
        }

        // Equalizer button - opens built-in equalizer
        val equalizerButton = view.findViewById<MaterialButton>(R.id.equalizerButton)
        equalizerButton.setOnClickListener {
            openBuiltInEqualizer()
        }

        // Recording directory
        recordingDirectoryPath = view.findViewById(R.id.recordingDirectoryPath)
        recordingDirectoryButton = view.findViewById(R.id.recordingDirectoryButton)
        updateRecordingDirectoryDisplay()
        recordingDirectoryButton?.setOnClickListener {
            showRecordingDirectoryDialog()
        }

        // Setup Tor controls
        setupTorControls()

        // Bind to RadioService to get audio session ID
        val serviceIntent = Intent(requireContext(), RadioService::class.java)
        requireContext().bindService(serviceIntent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)

        return view
    }

    /**
     * Opens the built-in equalizer bottom sheet.
     * Can be opened even without an active audio session - settings will be applied when playback starts.
     */
    private fun openBuiltInEqualizer() {
        val equalizerManager = radioService?.getEqualizerManager()
        val audioSessionId = radioService?.getAudioSessionId() ?: 0

        if (equalizerManager != null) {
            // If we have an audio session, initialize the equalizer
            if (audioSessionId != 0 && !equalizerManager.isInitialized()) {
                equalizerManager.initialize(audioSessionId)
            }

            // Show the equalizer bottom sheet
            val bottomSheet = EqualizerBottomSheet.newInstance(equalizerManager)
            bottomSheet.show(parentFragmentManager, EqualizerBottomSheet.TAG)
        } else {
            // No service bound - create a temporary equalizer manager for settings preview
            val tempEqualizerManager = com.opensource.i2pradio.audio.EqualizerManager(requireContext())
            // Initialize with session 0 for preview mode (settings only, no audio effect)
            tempEqualizerManager.initializeForPreview()
            val bottomSheet = EqualizerBottomSheet.newInstance(tempEqualizerManager)
            bottomSheet.show(parentFragmentManager, EqualizerBottomSheet.TAG)
        }
    }

    private fun updateSleepTimerButtonText(button: MaterialButton) {
        val minutes = PreferencesHelper.getSleepTimerMinutes(requireContext())
        button.text = when (minutes) {
            0 -> "Off"
            else -> "$minutes min"
        }
    }

    private fun showSleepTimerDialog(button: MaterialButton) {
        val options = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes")
        val values = intArrayOf(0, 15, 30, 45, 60, 90)
        val currentMinutes = PreferencesHelper.getSleepTimerMinutes(requireContext())
        val selectedIndex = values.indexOf(currentMinutes).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Sleep Timer")
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val minutes = values[which]
                PreferencesHelper.setSleepTimerMinutes(requireContext(), minutes)
                updateSleepTimerButtonText(button)

                // Send intent to RadioService to set/cancel sleep timer
                val intent = Intent(requireContext(), com.opensource.i2pradio.RadioService::class.java).apply {
                    action = if (minutes > 0) {
                        com.opensource.i2pradio.RadioService.ACTION_SET_SLEEP_TIMER
                    } else {
                        com.opensource.i2pradio.RadioService.ACTION_CANCEL_SLEEP_TIMER
                    }
                    putExtra("minutes", minutes)
                }
                requireContext().startService(intent)

                dialog.dismiss()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        TorManager.addStateListener(torStateListener)
    }

    override fun onPause() {
        super.onPause()
        TorManager.removeStateListener(torStateListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun setupTorControls() {
        // Initialize switch state
        val torEnabled = PreferencesHelper.isEmbeddedTorEnabled(requireContext())
        embeddedTorSwitch?.isChecked = torEnabled

        // Show/hide status container based on switch state
        updateTorContainerVisibility(torEnabled)

        // IMPORTANT: Initialize TorManager if Tor is already enabled from a previous session
        // This ensures we detect Orbot's current state when the app restarts
        if (torEnabled) {
            TorManager.initialize(requireContext())
            // Also auto-start Tor if auto-start is enabled
            if (PreferencesHelper.isAutoStartTorEnabled(requireContext()) &&
                TorManager.state != TorManager.TorState.CONNECTED &&
                TorManager.state != TorManager.TorState.STARTING) {
                TorService.start(requireContext())
            }
        }

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
                // Initialize TorManager first to set up status detection
                TorManager.initialize(requireContext())
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
                TorManager.TorState.ORBOT_NOT_INSTALLED -> {
                    TorManager.openOrbotInstallPage(requireContext())
                }
            }
        }

        // Setup Force Tor switches
        setupForceTorSwitches()
    }

    private fun setupForceTorSwitches() {
        // Initialize switch states from preferences
        forceTorAllSwitch?.isChecked = PreferencesHelper.isForceTorAll(requireContext())
        forceTorExceptI2pSwitch?.isChecked = PreferencesHelper.isForceTorExceptI2P(requireContext())

        // Update container visibility based on main Tor switch
        updateForceTorContainersVisibility(embeddedTorSwitch?.isChecked == true)

        // Force Tor All switch handler
        forceTorAllSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setForceTorAll(requireContext(), isChecked)

            // Update the other switch to maintain mutual exclusivity
            if (isChecked) {
                forceTorExceptI2pSwitch?.isChecked = false
            }

            // Show warning if enabling and Tor is not connected
            if (isChecked && !TorManager.isConnected()) {
                Toast.makeText(
                    requireContext(),
                    "⚠️ Tor not connected! Streams will fail until Tor connects.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Force Tor Except I2P switch handler
        forceTorExceptI2pSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setForceTorExceptI2P(requireContext(), isChecked)

            // Update the other switch to maintain mutual exclusivity
            if (isChecked) {
                forceTorAllSwitch?.isChecked = false
            }

            // Show warning if enabling and Tor is not connected
            if (isChecked && !TorManager.isConnected()) {
                Toast.makeText(
                    requireContext(),
                    "⚠️ Tor not connected! Non-I2P streams will fail until Tor connects.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateForceTorContainersVisibility(show: Boolean) {
        forceTorAllContainer?.visibility = if (show) View.VISIBLE else View.GONE
        forceTorExceptI2pContainer?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateTorContainerVisibility(show: Boolean) {
        torStatusContainer?.visibility = if (show) View.VISIBLE else View.GONE
        // Also update Force Tor containers visibility
        updateForceTorContainersVisibility(show)
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
            TorManager.TorState.ORBOT_NOT_INSTALLED -> {
                torStatusIcon?.setImageResource(R.drawable.ic_tor_off)
                torStatusText?.text = "Orbot Required"
                torStatusDetail?.text = "Please install Orbot to use Tor"
                torActionButton?.text = "Install Orbot"
                torActionButton?.isEnabled = true
            }
        }
    }

    private fun updateRecordingDirectoryDisplay() {
        val savedUri = PreferencesHelper.getRecordingDirectoryUri(requireContext())
        if (savedUri != null) {
            try {
                val uri = Uri.parse(savedUri)
                val docFile = DocumentFile.fromTreeUri(requireContext(), uri)
                val displayName = docFile?.name ?: "Custom folder"
                recordingDirectoryPath?.text = displayName
                recordingDirectoryButton?.text = "Change"
            } catch (e: Exception) {
                recordingDirectoryPath?.text = "Default (Music/i2pradio)"
                recordingDirectoryButton?.text = "Change"
            }
        } else {
            recordingDirectoryPath?.text = "Default (Music/i2pradio)"
            recordingDirectoryButton?.text = "Change"
        }
    }

    private fun showRecordingDirectoryDialog() {
        val options = mutableListOf("Default (Music/i2pradio)", "Choose custom folder...")
        val savedUri = PreferencesHelper.getRecordingDirectoryUri(requireContext())
        if (savedUri != null) {
            options.add(1, "Clear custom folder")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Recording Directory")
            .setItems(options.toTypedArray()) { dialog, which ->
                when {
                    which == 0 -> {
                        // Default
                        PreferencesHelper.setRecordingDirectoryUri(requireContext(), null)
                        updateRecordingDirectoryDisplay()
                        Toast.makeText(requireContext(), "Using default directory", Toast.LENGTH_SHORT).show()
                    }
                    savedUri != null && which == 1 -> {
                        // Clear custom folder
                        PreferencesHelper.setRecordingDirectoryUri(requireContext(), null)
                        updateRecordingDirectoryDisplay()
                        Toast.makeText(requireContext(), "Using default directory", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Choose custom folder
                        directoryPickerLauncher.launch(null)
                    }
                }
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

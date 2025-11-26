package com.opensource.i2pradio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import com.opensource.i2pradio.data.RadioRepository
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.tor.TorService
import com.opensource.i2pradio.util.StationImportExport
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var recordAcrossStationsSwitch: MaterialSwitch? = null
    private var recordAllStationsContainer: View? = null
    private var recordAllStationsSwitch: MaterialSwitch? = null

    // Import/Export UI elements
    private var importStationsButton: MaterialButton? = null
    private var importI2pStationsButton: MaterialButton? = null
    private var importTorStationsButton: MaterialButton? = null
    private var exportStationsButton: MaterialButton? = null
    private lateinit var repository: RadioRepository

    // Import file picker launcher
    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            importStationsFromUri(selectedUri)
        }
    }

    // Export file creator launcher
    private var pendingExportFormat: StationImportExport.FileFormat? = null
    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let { selectedUri ->
            pendingExportFormat?.let { format ->
                exportStationsToUri(selectedUri, format)
            }
        }
        pendingExportFormat = null
    }

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

    // SMART debouncing mechanism for Tor state updates
    // Filters out rapid oscillations (< 100ms) during activity recreation
    // while preserving INSTANT leak warnings for real disconnections
    private val torUiUpdateHandler = Handler(Looper.getMainLooper())
    private var pendingTorState: TorManager.TorState? = null
    private var lastTorStateChangeTime: Long = 0
    private var lastDisplayedTorState: TorManager.TorState? = null

    private val torUiUpdateRunnable: Runnable = Runnable {
        pendingTorState?.let { state ->
            // Only update UI if this state has been stable for minimum duration
            val stateAge = System.currentTimeMillis() - lastTorStateChangeTime

            // CRITICAL: For disconnected states, verify they've persisted for 100ms
            // This filters out millisecond-level oscillations during activity recreation
            // while still showing real disconnections within 100ms
            when (state) {
                TorManager.TorState.STOPPED,
                TorManager.TorState.ERROR,
                TorManager.TorState.ORBOT_NOT_INSTALLED -> {
                    if (stateAge >= 100 || lastDisplayedTorState == state) {
                        // State has persisted for 100ms OR we're already showing it
                        // This is a REAL disconnection - update UI immediately
                        updateTorStatusUI(state)
                        lastDisplayedTorState = state
                        pendingTorState = null
                    } else {
                        // State changed recently - wait a bit longer to confirm
                        // Re-check in 50ms to see if it stabilizes
                        torUiUpdateHandler.postDelayed(torUiUpdateRunnable, 50)
                    }
                }
                TorManager.TorState.CONNECTED,
                TorManager.TorState.STARTING -> {
                    // CONNECTED/STARTING states - update immediately
                    // No need to wait since these are positive states
                    updateTorStatusUI(state)
                    lastDisplayedTorState = state
                    pendingTorState = null
                }
            }
        }
    }

    // General-purpose handler for UI operations
    private val uiHandler = Handler(Looper.getMainLooper())

    // Preference change listener to sync the Tor switch with preference updates from other sources
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "embedded_tor_enabled") {
            // Update the switch to match the preference value
            // This ensures sync when the toolbar button changes the preference
            val torEnabled = PreferencesHelper.isEmbeddedTorEnabled(requireContext())
            if (embeddedTorSwitch?.isChecked != torEnabled) {
                // Temporarily remove listener to prevent infinite loop
                embeddedTorSwitch?.setOnCheckedChangeListener(null)
                embeddedTorSwitch?.isChecked = torEnabled
                // Re-attach listener after update
                setupTorSwitchListener()
            }
            updateTorContainerVisibility(torEnabled)
        }
    }

    private val torStateListener: (TorManager.TorState) -> Unit = { state ->
        // Record timestamp of this state change for smart debouncing
        lastTorStateChangeTime = System.currentTimeMillis()

        // Cancel any pending update and schedule a new one
        torUiUpdateHandler.removeCallbacks(torUiUpdateRunnable)
        pendingTorState = state

        // SMART DEBOUNCING: Start checking immediately for CONNECTED,
        // but wait 100ms for disconnected states to confirm they're real
        val initialDelay = when (state) {
            TorManager.TorState.CONNECTED,
            TorManager.TorState.STARTING -> 0L  // Update immediately
            else -> 100L  // Wait 100ms to filter oscillations
        }

        torUiUpdateHandler.postDelayed(torUiUpdateRunnable, initialDelay)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val themeButton = view.findViewById<MaterialButton>(R.id.themeButton)
        val colorSchemeButton = view.findViewById<MaterialButton>(R.id.colorSchemeButton)
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
                uiHandler.postDelayed({
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

        // Update color scheme button text
        updateColorSchemeButtonText(colorSchemeButton)

        // Color scheme selector
        colorSchemeButton.setOnClickListener {
            showColorSchemeDialog(colorSchemeButton)
        }

        // GitHub button
        githubButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/deutsia/deutsia-radio"))
            startActivity(intent)
        }

        // Copy Monero address button
        val copyMoneroButton = view.findViewById<MaterialButton>(R.id.copyMoneroButton)
        copyMoneroButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Monero Address", "83GGx86c6ZePiz8tEcGYtGJYmnjuP8W9cfLx6s98WAu8YkenjLr4zFC4RxcCk3hwFUiv59wS8KRPzNUUUqTrrYXCJAk4nrN")
            clipboard.setPrimaryClip(clip)

            // Show a toast to confirm
            Toast.makeText(requireContext(), "Address copied to clipboard", Toast.LENGTH_SHORT).show()
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

        // Record across stations setting
        recordAcrossStationsSwitch = view.findViewById(R.id.recordAcrossStationsSwitch)
        recordAllStationsContainer = view.findViewById(R.id.recordAllStationsContainer)
        recordAllStationsSwitch = view.findViewById(R.id.recordAllStationsSwitch)

        // Initialize state
        val recordAcrossEnabled = PreferencesHelper.isRecordAcrossStationsEnabled(requireContext())
        val recordAllEnabled = PreferencesHelper.isRecordAllStationsEnabled(requireContext())
        recordAcrossStationsSwitch?.isChecked = recordAcrossEnabled
        recordAllStationsSwitch?.isChecked = recordAllEnabled
        recordAllStationsContainer?.visibility = if (recordAcrossEnabled) View.VISIBLE else View.GONE

        recordAcrossStationsSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setRecordAcrossStations(requireContext(), isChecked)

            // Show/hide the "Record All Stations" sub-option
            recordAllStationsContainer?.visibility = if (isChecked) View.VISIBLE else View.GONE

            // If disabling record across stations, also disable record all stations
            if (!isChecked && recordAllStationsSwitch?.isChecked == true) {
                recordAllStationsSwitch?.isChecked = false
                PreferencesHelper.setRecordAllStations(requireContext(), false)
            }
        }

        recordAllStationsSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setRecordAllStations(requireContext(), isChecked)
        }

        // Import/Export stations
        repository = RadioRepository(requireContext())
        importStationsButton = view.findViewById(R.id.importStationsButton)
        importI2pStationsButton = view.findViewById(R.id.importI2pStationsButton)
        importTorStationsButton = view.findViewById(R.id.importTorStationsButton)
        exportStationsButton = view.findViewById(R.id.exportStationsButton)
        importStationsButton?.setOnClickListener {
            showImportDialog()
        }
        importI2pStationsButton?.setOnClickListener {
            showImportCuratedListDialog("i2p")
        }
        importTorStationsButton?.setOnClickListener {
            showImportCuratedListDialog("tor")
        }
        exportStationsButton?.setOnClickListener {
            showExportDialog()
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
        // CRITICAL FIX: Don't notify immediately when adding listener during activity recreation.
        // The initial state is already set up in setupTorControls() with proper debouncing.
        // Immediate notification would cancel that debouncing and cause UI flickering.
        TorManager.addStateListener(torStateListener, notifyImmediately = false)

        // Register preference change listener to sync Tor switch with preference updates
        requireContext().getSharedPreferences("DeutsiaRadioPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onPause() {
        super.onPause()
        TorManager.removeStateListener(torStateListener)

        // Unregister preference change listener
        requireContext().getSharedPreferences("DeutsiaRadioPrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up debounce handler to prevent memory leaks
        torUiUpdateHandler.removeCallbacks(torUiUpdateRunnable)
        pendingTorState = null

        // Clean up general UI handler
        uiHandler.removeCallbacksAndMessages(null)

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

        // Update initial Tor status using smart debouncing to prevent flickering
        // during rapid state transitions (e.g., Material You toggle activity recreation)
        lastTorStateChangeTime = System.currentTimeMillis()
        pendingTorState = TorManager.state

        // Use smart debouncing: immediate for CONNECTED, 100ms filter for disconnected states
        val initialDelay = when (TorManager.state) {
            TorManager.TorState.CONNECTED,
            TorManager.TorState.STARTING -> 0L
            else -> 100L
        }
        torUiUpdateHandler.postDelayed(torUiUpdateRunnable, initialDelay)

        // Setup switch toggle listener
        setupTorSwitchListener()

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

    private fun setupTorSwitchListener() {
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

                // IMPORTANT: Reset Force Tor preferences when Tor integration is disabled
                // This prevents the app from thinking Tor is required when it's not available
                PreferencesHelper.setForceTorAll(requireContext(), false)
                PreferencesHelper.setForceTorExceptI2P(requireContext(), false)
                forceTorAllSwitch?.isChecked = false
                forceTorExceptI2pSwitch?.isChecked = false
            }
        }
    }

    private fun setupForceTorSwitches() {
        // Flag to prevent warnings during initialization
        var isInitializing = true

        // Force Tor All switch handler - set BEFORE initializing state
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

            // SECURITY: Stop any currently playing stream when proxy settings change
            // The stream was routed using the OLD settings and must be stopped immediately
            // to prevent privacy leaks (e.g., I2P stream continuing when user forces Tor)
            if (!isInitializing) {
                stopCurrentStream()
            }

            // Show warning if enabling and Tor is not connected
            // Skip warning during initialization to prevent flickering when Material You is toggled
            if (isChecked && !TorManager.isConnected() && !isInitializing) {
                Toast.makeText(
                    requireContext(),
                    "⚠️ Tor not connected! Streams will fail until Tor connects.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Force Tor Except I2P switch handler - set BEFORE initializing state
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

            // SECURITY: Stop any currently playing stream when proxy settings change
            // The stream was routed using the OLD settings and must be stopped immediately
            // to prevent privacy leaks (e.g., clearnet stream continuing when user forces Tor)
            if (!isInitializing) {
                stopCurrentStream()
            }

            // Show warning if enabling and Tor is not connected
            // Skip warning during initialization to prevent flickering when Material You is toggled
            if (isChecked && !TorManager.isConnected() && !isInitializing) {
                Toast.makeText(
                    requireContext(),
                    "⚠️ Tor not connected! Non-I2P streams will fail until Tor connects.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Initialize switch states from preferences AFTER setting up listeners
        forceTorAllSwitch?.isChecked = PreferencesHelper.isForceTorAll(requireContext())
        forceTorExceptI2pSwitch?.isChecked = PreferencesHelper.isForceTorExceptI2P(requireContext())

        // Update container visibility based on main Tor switch
        updateForceTorContainersVisibility(embeddedTorSwitch?.isChecked == true)

        // Mark initialization as complete to enable warnings for user interactions
        isInitializing = false
    }

    /**
     * Stops the current stream when proxy routing settings change.
     * This is CRITICAL for privacy/security: when the user changes how traffic is routed,
     * any existing stream is using the OLD routing settings and must be stopped immediately.
     *
     * Example privacy leak prevented:
     * - User is playing an I2P radio with "Force Tor Except I2P" (going through I2P proxy)
     * - User switches to "Force Tor All" (should go through Tor SOCKS)
     * - Without stopping, the I2P stream keeps playing through I2P proxy, violating user's intent
     */
    private fun stopCurrentStream() {
        val stopIntent = Intent(requireContext(), RadioService::class.java).apply {
            action = RadioService.ACTION_STOP
        }
        requireContext().startService(stopIntent)
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
        // Check if force Tor mode is enabled - if so, we should show connected state
        // even during transient disconnections to prevent UI glitches
        val isForceTorEnabled = PreferencesHelper.isForceTorAll(requireContext()) ||
                                PreferencesHelper.isForceTorExceptI2P(requireContext())

        when (state) {
            TorManager.TorState.STOPPED -> {
                // If force Tor is enabled and we have a proxy port, assume connected
                if (isForceTorEnabled && TorManager.isConnected()) {
                    showConnectedStateForForceTor()
                } else if (isForceTorEnabled) {
                    // Force Tor is enabled but not connected - show warning
                    showForceTorWarning()
                } else {
                    torStatusIcon?.setImageResource(R.drawable.ic_tor_off)
                    torStatusText?.text = "Disconnected"
                    torStatusDetail?.text = "Tor is not running"
                    torActionButton?.text = "Start"
                    torActionButton?.isEnabled = true
                }
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
                // If force Tor is enabled, show a more severe warning
                if (isForceTorEnabled) {
                    showForceTorWarning()
                } else {
                    torStatusIcon?.setImageResource(R.drawable.ic_tor_off)
                    torStatusText?.text = "Connection Failed"
                    torStatusDetail?.text = TorManager.errorMessage ?: "Unknown error"
                    torActionButton?.text = "Retry"
                    torActionButton?.isEnabled = true
                }
            }
            TorManager.TorState.ORBOT_NOT_INSTALLED -> {
                // If force Tor is enabled but Orbot says not installed, check if proxy is accessible
                // This prevents UI glitches during activity recreation
                if (isForceTorEnabled && TorManager.isConnected()) {
                    showConnectedStateForForceTor()
                } else if (isForceTorEnabled) {
                    showForceTorWarning()
                } else {
                    torStatusIcon?.setImageResource(R.drawable.ic_tor_off)
                    torStatusText?.text = "Orbot Required"
                    torStatusDetail?.text = "Please install Orbot to use Tor"
                    torActionButton?.text = "Install Orbot"
                    torActionButton?.isEnabled = true
                }
            }
        }
    }

    private fun showConnectedStateForForceTor() {
        torStatusIcon?.setImageResource(R.drawable.ic_tor_on)
        torStatusText?.text = "Connected"
        torStatusDetail?.text = "SOCKS port: ${TorManager.socksPort}"
        torActionButton?.text = "Stop"
        torActionButton?.isEnabled = true
    }

    private fun showForceTorWarning() {
        torStatusIcon?.setImageResource(R.drawable.ic_tor_error)
        torStatusText?.text = "Connection Failed"
        torStatusDetail?.text = "Force Tor is enabled but not connected"
        torActionButton?.text = "Retry"
        torActionButton?.isEnabled = true
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
                recordingDirectoryPath?.text = "Default (Music/deutsia_radio)"
                recordingDirectoryButton?.text = "Change"
            }
        } else {
            recordingDirectoryPath?.text = "Default (Music/deutsia_radio)"
            recordingDirectoryButton?.text = "Change"
        }
    }

    private fun showRecordingDirectoryDialog() {
        val options = mutableListOf("Default (Music/deutsia_radio)", "Choose custom folder...")
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

    private fun updateColorSchemeButtonText(button: MaterialButton) {
        val currentScheme = PreferencesHelper.getColorScheme(requireContext())
        button.text = when (currentScheme) {
            "peach" -> "Peach"
            "green" -> "Green"
            "purple" -> "Purple"
            "orange" -> "Orange"
            else -> "Blue"
        }
    }

    private fun showColorSchemeDialog(colorSchemeButton: MaterialButton) {
        val schemes = arrayOf("Blue (Default)", "Peach", "Green", "Purple", "Orange")
        val schemeValues = arrayOf("default", "peach", "green", "purple", "orange")
        val currentScheme = PreferencesHelper.getColorScheme(requireContext())
        val selectedIndex = schemeValues.indexOf(currentScheme).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Choose Color Scheme")
            .setSingleChoiceItems(schemes, selectedIndex) { dialog, which ->
                val newScheme = schemeValues[which]
                PreferencesHelper.setColorScheme(requireContext(), newScheme)
                updateColorSchemeButtonText(colorSchemeButton)
                // Recreate activity to apply new color scheme
                uiHandler.postDelayed({
                    activity?.recreate()
                }, 300)
                dialog.dismiss()
            }
            .show()
    }

    // ==================== Import/Export Functions ====================

    private fun showImportDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Import Stations")
            .setMessage("Select a file to import radio stations from.\n\nSupported formats:\n• CSV\n• JSON\n• M3U playlist\n• PLS playlist")
            .setPositiveButton("Select File") { _, _ ->
                importFileLauncher.launch(arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "application/json",
                    "audio/x-mpegurl",
                    "audio/mpegurl",
                    "audio/x-scpls",
                    "*/*"
                ))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportCuratedListDialog(type: String) {
        val (fileName, count, networkName) = when (type) {
            "i2p" -> Triple("i2p_stations.json", 14, "I2P")
            "tor" -> Triple("tor_stations.json", 3, "Tor")
            else -> return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Import $networkName Stations")
            .setMessage("Import $count curated $networkName radio stations?\n\nThese stations are pre-configured with proxy settings and ready to use.")
            .setPositiveButton("Import") { _, _ ->
                importCuratedList(fileName, networkName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importCuratedList(fileName: String, networkName: String) {
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Load JSON from assets
                val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }

                // Parse using existing import utility
                val result = StationImportExport.importFromJson(jsonString)

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext

                    if (result.stations.isEmpty()) {
                        Toast.makeText(
                            context,
                            "No stations found in $networkName list",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@withContext
                    }

                    // Import stations directly (no second confirmation needed)
                    performImport(result.stations)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext

                    Toast.makeText(
                        context,
                        "Failed to import $networkName stations: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showExportDialog() {
        val formats = StationImportExport.FileFormat.values()
        val formatNames = formats.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Export Stations")
            .setItems(formatNames) { _, which ->
                val format = formats[which]
                pendingExportFormat = format
                val filename = "i2pradio_stations.${format.extension}"
                exportFileLauncher.launch(filename)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importStationsFromUri(uri: Uri) {
        // Capture context early to avoid crashes if Fragment is destroyed during async operation
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = StationImportExport.importFromUri(context, uri)

                withContext(Dispatchers.Main) {
                    // Check if Fragment is still attached before showing UI
                    if (!isAdded) return@withContext

                    if (result.stations.isEmpty()) {
                        val errorMsg = if (result.errors.isNotEmpty()) {
                            result.errors.joinToString("\n")
                        } else {
                            "No stations found in the file"
                        }
                        AlertDialog.Builder(requireContext())
                            .setTitle("Import Failed")
                            .setMessage(errorMsg)
                            .setPositiveButton("OK", null)
                            .show()
                        return@withContext
                    }

                    // Show confirmation dialog
                    val formatName = result.format?.displayName ?: "Unknown"
                    val message = buildString {
                        append("Found ${result.stations.size} station(s) in $formatName format.\n\n")
                        if (result.errors.isNotEmpty()) {
                            append("Warnings:\n")
                            result.errors.take(3).forEach { append("• $it\n") }
                            if (result.errors.size > 3) {
                                append("• ...and ${result.errors.size - 3} more\n")
                            }
                            append("\n")
                        }
                        append("Do you want to import these stations?")
                    }

                    AlertDialog.Builder(requireContext())
                        .setTitle("Import Stations")
                        .setMessage(message)
                        .setPositiveButton("Import") { _, _ ->
                            performImport(result.stations)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Check if Fragment is still attached before showing UI
                    if (!isAdded) return@withContext

                    Toast.makeText(
                        requireContext(),
                        "Import error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun performImport(stations: List<com.opensource.i2pradio.data.RadioStation>) {
        // Capture context early to avoid crashes if Fragment is destroyed during async operation
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            var imported = 0
            for (station in stations) {
                try {
                    repository.insertStation(station)
                    imported++
                } catch (e: Exception) {
                    // Skip duplicate or invalid stations
                }
            }

            withContext(Dispatchers.Main) {
                // Check if Fragment is still attached before showing UI
                if (!isAdded) return@withContext

                Toast.makeText(
                    context,
                    "Imported $imported station(s)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun exportStationsToUri(uri: Uri, format: StationImportExport.FileFormat) {
        // Capture context early to avoid crashes if Fragment is destroyed during async operation
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stations = repository.getAllStationsSync()

                if (stations.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        // Check if Fragment is still attached before showing UI
                        if (!isAdded) return@withContext

                        Toast.makeText(
                            context,
                            "No stations to export",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    when (format) {
                        StationImportExport.FileFormat.CSV ->
                            StationImportExport.exportToCsv(stations, outputStream)
                        StationImportExport.FileFormat.JSON ->
                            StationImportExport.exportToJson(stations, outputStream)
                        StationImportExport.FileFormat.M3U ->
                            StationImportExport.exportToM3u(stations, outputStream)
                        StationImportExport.FileFormat.PLS ->
                            StationImportExport.exportToPls(stations, outputStream)
                    }
                }

                withContext(Dispatchers.Main) {
                    // Check if Fragment is still attached before showing UI
                    if (!isAdded) return@withContext

                    Toast.makeText(
                        context,
                        "Exported ${stations.size} station(s) to ${format.displayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Check if Fragment is still attached before showing UI
                    if (!isAdded) return@withContext

                    Toast.makeText(
                        context,
                        "Export error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

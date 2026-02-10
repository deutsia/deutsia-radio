package com.opensource.i2pradio.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.audio.EqualizerManager
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioRepository
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.radioregistry.RadioRegistryRepository
import com.opensource.i2pradio.data.radioregistry.RadioRegistryResult
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.tor.TorService
import com.opensource.i2pradio.util.SleepTimerUtils
import com.opensource.i2pradio.util.StationImportExport
import com.opensource.i2pradio.utils.BiometricAuthManager
import com.opensource.i2pradio.utils.DigestAuthenticator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

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
    private var i2pStationsDescription: TextView? = null
    private var torStationsDescription: TextView? = null
    private lateinit var repository: RadioRepository

    // Custom Proxy UI elements
    private var enableCustomProxySwitch: MaterialSwitch? = null
    private var customProxyConfigContainer: View? = null
    private var configureProxyButton: MaterialButton? = null
    private var forceCustomProxySwitch: MaterialSwitch? = null
    private var forceCustomProxyExceptTorI2pSwitch: MaterialSwitch? = null
    private var customProxyStatusViewSettings: CustomProxyStatusView? = null

    // Bandwidth tracking UI elements
    private var bandwidthTotalText: TextView? = null
    private var bandwidthSessionText: TextView? = null
    private var resetBandwidthButton: MaterialButton? = null

    // Authentication UI elements
    private var appLockSwitch: MaterialSwitch? = null
    private var setPasswordButton: MaterialButton? = null
    private var biometricContainer: View? = null
    private var biometricSwitch: MaterialSwitch? = null
    private var requireAuthContainer: View? = null
    private var requireAuthSwitch: MaterialSwitch? = null
    private var databaseEncryptionSwitch: MaterialSwitch? = null

    // Network & API UI elements
    private var disableRadioBrowserApiSwitch: MaterialSwitch? = null
    private var disableRadioRegistryApiSwitch: MaterialSwitch? = null
    private var disableCoverArtSwitch: MaterialSwitch? = null
    private var offlineModeNote: TextView? = null

    // ViewModel for observing current station (for miniplayer padding)
    private val radioViewModel: RadioViewModel by activityViewModels()

    // Radio Registry API repository for fetching I2P/Tor stations
    private val registryRepository: RadioRegistryRepository by lazy {
        RadioRegistryRepository(requireContext())
    }

    // Temporary cache for pending station imports (cleared after use)
    private var pendingImportStations: List<RadioStation>? = null

    // Miniplayer spacing for dynamic bottom padding
    private var settingsContentContainer: LinearLayout? = null
    private var miniplayerSpacing = 0
    private var settingsBasePadding = 0

    // Bandwidth update broadcast receiver
    private val bandwidthUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PreferencesHelper.BROADCAST_BANDWIDTH_UPDATED) {
                updateBandwidthDisplay()
            }
        }
    }

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
            if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                Toast.makeText(requireContext(), getString(R.string.recording_directory_updated), Toast.LENGTH_SHORT).show()
            }
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
                TorManager.TorState.INVIZIBLE_NOT_INSTALLED -> {
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
        val languageButton = view.findViewById<MaterialButton>(R.id.languageButton)
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

        // Miniplayer spacing for dynamic bottom padding
        settingsContentContainer = view.findViewById(R.id.settingsContentContainer)
        miniplayerSpacing = resources.getDimensionPixelSize(R.dimen.miniplayer_spacing)
        settingsBasePadding = resources.getDimensionPixelSize(R.dimen.browse_base_padding)

        // Observe current station to dynamically adjust bottom padding for miniplayer
        radioViewModel.currentStation.observe(viewLifecycleOwner) { station ->
            updateBottomPaddingForMiniplayer(station != null)
        }

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
                    MainActivity.prepareForUiRecreate()
                    activity?.recreate()
                }, 300)
            }
        } else {
            materialYouContainer?.visibility = View.GONE
        }

        // Disable Toast Messages switch
        val disableToastsSwitch = view.findViewById<MaterialSwitch>(R.id.disableToastsSwitch)
        disableToastsSwitch?.isChecked = PreferencesHelper.isToastMessagesDisabled(requireContext())
        disableToastsSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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
            PreferencesHelper.setToastMessagesDisabled(requireContext(), isChecked)
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

        // Update language button text
        updateLanguageButtonText(languageButton)

        // Language selector
        languageButton.setOnClickListener {
            showLanguageDialog(languageButton)
        }

        // GitHub button
        githubButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/deutsia/i2pradio"))
            startActivity(intent)
        }

        // Copy Monero address button
        val copyMoneroButton = view.findViewById<MaterialButton>(R.id.copyMoneroButton)
        copyMoneroButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Monero Address", "83GGx86c6ZePiz8tEcGYtGJYmnjuP8W9cfLx6s98WAu8YkenjLr4zFC4RxcCk3hwFUiv59wS8KRPzNUUUqTrrYXCJAk4nrN")
            clipboard.setPrimaryClip(clip)

            // Show a toast to confirm
            if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                Toast.makeText(requireContext(), getString(R.string.toast_address_copied), Toast.LENGTH_SHORT).show()
            }
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
        i2pStationsDescription = view.findViewById(R.id.i2pStationsDescription)
        torStationsDescription = view.findViewById(R.id.torStationsDescription)

        // Clear any leftover cached stations from the old caching system
        lifecycleScope.launch(Dispatchers.IO) {
            repository.clearUnlikedCachedStations()
        }

        // Update station counts dynamically
        updateStationCounts()

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

        // Custom Proxy UI elements
        enableCustomProxySwitch = view.findViewById(R.id.enableCustomProxySwitch)
        customProxyConfigContainer = view.findViewById(R.id.customProxyConfigContainer)
        configureProxyButton = view.findViewById(R.id.configureProxyButton)
        forceCustomProxySwitch = view.findViewById(R.id.forceCustomProxySwitch)
        forceCustomProxyExceptTorI2pSwitch = view.findViewById(R.id.forceCustomProxyExceptTorI2pSwitch)
        customProxyStatusViewSettings = view.findViewById(R.id.customProxyStatusViewSettings)

        // Bandwidth tracking UI elements
        bandwidthTotalText = view.findViewById(R.id.bandwidthTotalText)
        bandwidthSessionText = view.findViewById(R.id.bandwidthSessionText)
        resetBandwidthButton = view.findViewById(R.id.resetBandwidthButton)

        // Authentication UI elements
        appLockSwitch = view.findViewById(R.id.appLockSwitch)
        setPasswordButton = view.findViewById(R.id.setPasswordButton)
        biometricContainer = view.findViewById(R.id.biometricContainer)
        biometricSwitch = view.findViewById(R.id.biometricSwitch)
        requireAuthContainer = view.findViewById(R.id.requireAuthContainer)
        requireAuthSwitch = view.findViewById(R.id.requireAuthSwitch)
        databaseEncryptionSwitch = view.findViewById(R.id.databaseEncryptionSwitch)

        // Network & API UI elements
        disableRadioBrowserApiSwitch = view.findViewById(R.id.disableRadioBrowserApiSwitch)
        disableRadioRegistryApiSwitch = view.findViewById(R.id.disableRadioRegistryApiSwitch)
        disableCoverArtSwitch = view.findViewById(R.id.disableCoverArtSwitch)
        offlineModeNote = view.findViewById(R.id.offlineModeNote)

        // Setup authentication controls
        setupAuthenticationControls()
        setupDatabaseEncryptionControls()

        // Setup Network & API controls
        setupNetworkApiControls()

        // Setup custom proxy controls
        setupCustomProxyControls()

        // Setup bandwidth display
        setupBandwidthDisplay()

        // Setup Tor controls
        setupTorControls()

        // Bind to RadioService to get audio session ID
        val serviceIntent = Intent(requireContext(), RadioService::class.java)
        requireContext().bindService(serviceIntent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)

        return view
    }

    /**
     * Opens the built-in equalizer using the app's own UI.
     * Uses Android's Equalizer API attached to the audio session.
     * If no audio is playing, opens in preview mode where settings are saved
     * and will be applied when playback starts.
     */
    private fun openBuiltInEqualizer() {
        var equalizerManager = radioService?.getEqualizerManager()
        val audioSessionId = radioService?.getAudioSessionId() ?: 0

        // If we have an active audio session, try to initialize the real equalizer
        if (equalizerManager != null && audioSessionId > 0) {
            if (!equalizerManager.isInitialized()) {
                val initialized = equalizerManager.initialize(audioSessionId)
                if (!initialized) {
                    // Fall back to preview mode if initialization fails
                    equalizerManager.initializeForPreview()
                }
            }
        } else {
            // No active audio session - use preview mode
            // Create a temporary EqualizerManager for preview if service isn't bound
            if (equalizerManager == null) {
                equalizerManager = EqualizerManager(requireContext())
            }
            if (!equalizerManager.isInitialized()) {
                equalizerManager.initializeForPreview()
            }
        }

        // Show the equalizer bottom sheet
        val bottomSheet = EqualizerBottomSheet.newInstance(equalizerManager)
        bottomSheet.show(parentFragmentManager, EqualizerBottomSheet.TAG)
    }

    private fun updateSleepTimerButtonText(button: MaterialButton) {
        val minutes = PreferencesHelper.getSleepTimerMinutes(requireContext())
        button.text = when (minutes) {
            0 -> getString(R.string.button_off)
            else -> SleepTimerUtils.formatDuration(minutes)
        }
    }

    private fun showSleepTimerDialog(button: MaterialButton) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_sleep_timer, null)

        val sleepTimerSwitch = dialogView.findViewById<MaterialSwitch>(R.id.sleepTimerSwitch)
        val durationText = dialogView.findViewById<TextView>(R.id.sleepTimerDurationText)
        val slider = dialogView.findViewById<Slider>(R.id.sleepTimerSlider)
        val sliderMinLabel = dialogView.findViewById<TextView>(R.id.sliderMinLabel)
        val sliderMaxLabel = dialogView.findViewById<TextView>(R.id.sliderMaxLabel)

        // Preset buttons - Row 1: Minutes
        val preset10min = dialogView.findViewById<MaterialButton>(R.id.preset10min)
        val preset15min = dialogView.findViewById<MaterialButton>(R.id.preset15min)
        val preset30min = dialogView.findViewById<MaterialButton>(R.id.preset30min)
        val preset45min = dialogView.findViewById<MaterialButton>(R.id.preset45min)
        // Row 2: Hours
        val preset1hour = dialogView.findViewById<MaterialButton>(R.id.preset1hour)
        val preset2hours = dialogView.findViewById<MaterialButton>(R.id.preset2hours)
        val preset3hours = dialogView.findViewById<MaterialButton>(R.id.preset3hours)
        val preset4hours = dialogView.findViewById<MaterialButton>(R.id.preset4hours)

        val currentMinutes = PreferencesHelper.getSleepTimerMinutes(requireContext())
        val isEnabled = currentMinutes > 0

        // Precision mode state
        var isPrecisionMode = false
        var actualValue = if (isEnabled) currentMinutes else 30
        val precisionRange = 15 // ±15 minutes in precision mode
        val handler = Handler(Looper.getMainLooper())
        var precisionModeRunnable: Runnable? = null

        fun enterPrecisionMode() {
            if (isPrecisionMode) return
            isPrecisionMode = true
            actualValue = slider.value.toInt()

            // Calculate precision range centered on current value
            val minVal = (actualValue - precisionRange).coerceAtLeast(1)
            val maxVal = (actualValue + precisionRange).coerceAtMost(720)

            slider.valueFrom = minVal.toFloat()
            slider.valueTo = maxVal.toFloat()
            slider.value = actualValue.toFloat()

            // Update labels to show zoomed range
            sliderMinLabel.text = SleepTimerUtils.formatDuration(minVal)
            sliderMaxLabel.text = SleepTimerUtils.formatDuration(maxVal)
        }

        fun exitPrecisionMode() {
            if (!isPrecisionMode) return
            isPrecisionMode = false
            actualValue = slider.value.toInt()

            // Restore full range
            slider.valueFrom = 1f
            slider.valueTo = 720f
            slider.value = actualValue.toFloat()

            // Restore labels
            sliderMinLabel.text = getString(R.string.settings_sleep_timer_min)
            sliderMaxLabel.text = getString(R.string.settings_sleep_timer_max)
        }

        // Initialize UI state
        sleepTimerSwitch.isChecked = isEnabled
        slider.isEnabled = isEnabled
        slider.value = if (isEnabled) currentMinutes.toFloat().coerceIn(1f, 720f) else 30f
        durationText.text = if (isEnabled) SleepTimerUtils.formatDuration(currentMinutes) else getString(R.string.button_off)
        durationText.alpha = if (isEnabled) 1f else 0.5f

        // Update duration text when slider changes
        slider.addOnChangeListener { _, value, _ ->
            durationText.text = SleepTimerUtils.formatDuration(value.toInt())
        }

        // Precision mode: detect long press on slider
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) {
                // Start timer for precision mode
                precisionModeRunnable = Runnable { enterPrecisionMode() }
                handler.postDelayed(precisionModeRunnable!!, 500)
            }

            override fun onStopTrackingTouch(s: Slider) {
                // Cancel precision mode timer and exit precision mode
                precisionModeRunnable?.let { handler.removeCallbacks(it) }
                exitPrecisionMode()
            }
        })

        // Toggle switch enables/disables slider
        sleepTimerSwitch.setOnCheckedChangeListener { _, isChecked ->
            slider.isEnabled = isChecked
            durationText.alpha = if (isChecked) 1f else 0.5f
            if (isChecked) {
                durationText.text = SleepTimerUtils.formatDuration(slider.value.toInt())
            } else {
                durationText.text = getString(R.string.button_off)
            }
        }

        // Helper to set slider value and enable switch
        fun setPresetValue(minutes: Int) {
            sleepTimerSwitch.isChecked = true
            if (isPrecisionMode) exitPrecisionMode()
            slider.value = minutes.toFloat()
        }

        // Preset button click handlers - Row 1: Minutes
        preset10min.setOnClickListener { setPresetValue(10) }
        preset15min.setOnClickListener { setPresetValue(15) }
        preset30min.setOnClickListener { setPresetValue(30) }
        preset45min.setOnClickListener { setPresetValue(45) }
        // Row 2: Hours
        preset1hour.setOnClickListener { setPresetValue(60) }
        preset2hours.setOnClickListener { setPresetValue(120) }
        preset3hours.setOnClickListener { setPresetValue(180) }
        preset4hours.setOnClickListener { setPresetValue(240) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_sleep_timer))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val minutes = if (sleepTimerSwitch.isChecked) slider.value.toInt() else 0
                PreferencesHelper.setSleepTimerMinutes(requireContext(), minutes)
                updateSleepTimerButtonText(button)

                // Send intent to RadioService to set/cancel sleep timer
                val intent = Intent(requireContext(), RadioService::class.java).apply {
                    action = if (minutes > 0) {
                        RadioService.ACTION_SET_SLEEP_TIMER
                    } else {
                        RadioService.ACTION_CANCEL_SLEEP_TIMER
                    }
                    putExtra("minutes", minutes)
                }
                requireContext().startService(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // CRITICAL FIX: Don't notify immediately when adding listener during activity recreation.
        // The initial state is already set up in setupTorControls() with proper debouncing.
        // Immediate notification would cancel that debouncing and cause UI flickering.
        TorManager.addStateListener(torStateListener, notifyImmediately = false)

        // FIX: Sync UI with current Tor state when resuming from pause.
        // This handles the case where Tor state changed while the fragment was paused
        // (e.g., user switched tabs and Tor disconnected in the meantime).
        // We manually trigger the listener to ensure UI reflects current state.
        val currentState = TorManager.state
        if (lastDisplayedTorState != currentState) {
            torStateListener(currentState)
        }

        // Register preference change listener to sync Tor switch with preference updates
        requireContext().getSharedPreferences("DeutsiaRadioPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        // Register bandwidth update receiver for real-time updates
        val bandwidthFilter = IntentFilter(PreferencesHelper.BROADCAST_BANDWIDTH_UPDATED)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(bandwidthUpdateReceiver, bandwidthFilter)
    }

    override fun onPause() {
        super.onPause()
        TorManager.removeStateListener(torStateListener)

        // Unregister preference change listener
        requireContext().getSharedPreferences("DeutsiaRadioPrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)

        // Unregister bandwidth update receiver
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(bandwidthUpdateReceiver)
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
                TorManager.TorState.INVIZIBLE_NOT_INSTALLED -> {
                    TorManager.openInviZibleInstallPage(requireContext())
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
                // MUTUAL EXCLUSIVITY: Disable Custom Proxy when Tor is enabled
                // This prevents privacy leaks from having both configured but unclear routing
                PreferencesHelper.setCustomProxyEnabled(requireContext(), false)
                enableCustomProxySwitch?.isChecked = false

                // Clear custom proxy settings
                PreferencesHelper.setCustomProxyHost(requireContext(), "")
                PreferencesHelper.setCustomProxyPort(requireContext(), 8080)
                PreferencesHelper.setCustomProxyProtocol(requireContext(), "HTTP")
                PreferencesHelper.setCustomProxyUsername(requireContext(), "")
                PreferencesHelper.setCustomProxyPassword(requireContext(), "")
                PreferencesHelper.setCustomProxyAuthType(requireContext(), "None")
                PreferencesHelper.setCustomProxyConnectionTimeout(requireContext(), 30)

                // Disable Force Custom Proxy switches
                PreferencesHelper.setForceCustomProxy(requireContext(), false)
                PreferencesHelper.setForceCustomProxyExceptTorI2P(requireContext(), false)
                forceCustomProxySwitch?.isChecked = false
                forceCustomProxyExceptTorI2pSwitch?.isChecked = false

                // Update custom proxy container visibility
                updateCustomProxyContainerVisibility(false)

                // Update custom proxy status view
                updateCustomProxyStatusView()

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

            // Update the other switches to maintain mutual exclusivity
            if (isChecked) {
                forceTorExceptI2pSwitch?.isChecked = false
                forceCustomProxySwitch?.isChecked = false

                // Broadcast proxy mode change to update MainActivity UI
                val broadcastIntent = Intent(com.opensource.i2pradio.MainActivity.BROADCAST_PROXY_MODE_CHANGED)
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(broadcastIntent)
            }

            // SECURITY: Stop any currently playing stream when proxy settings change
            // The stream was routed using the OLD settings and must be stopped immediately
            // to prevent privacy leaks (e.g., I2P stream continuing when user forces Tor)
            if (!isInitializing) {
                stopCurrentStream()
            }

            // Show warning if enabling and Tor is not connected
            // Skip warning during initialization to prevent flickering when Material You is toggled
            // Note: This is a privacy/security warning - always show regardless of toast setting
            if (isChecked && !TorManager.isConnected() && !isInitializing) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.warning_tor_not_connected),
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

            // Update the other switches to maintain mutual exclusivity
            if (isChecked) {
                forceTorAllSwitch?.isChecked = false
                forceCustomProxySwitch?.isChecked = false

                // Broadcast proxy mode change to update MainActivity UI
                val broadcastIntent = Intent(com.opensource.i2pradio.MainActivity.BROADCAST_PROXY_MODE_CHANGED)
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(broadcastIntent)
            }

            // SECURITY: Stop any currently playing stream when proxy settings change
            // The stream was routed using the OLD settings and must be stopped immediately
            // to prevent privacy leaks (e.g., clearnet stream continuing when user forces Tor)
            if (!isInitializing) {
                stopCurrentStream()
            }

            // Show warning if enabling and Tor is not connected
            // Skip warning during initialization to prevent flickering when Material You is toggled
            // Note: This is a privacy/security warning - always show regardless of toast setting
            if (isChecked && !TorManager.isConnected() && !isInitializing) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.warning_tor_not_connected_except_i2p),
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
        android.util.Log.d("SettingsFragment", "Stopped current stream due to proxy routing settings change")
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

    private fun updateCustomProxyContainerVisibility(show: Boolean) {
        customProxyConfigContainer?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateTorStatusUI(state: TorManager.TorState) {
        // Check if force Tor mode is enabled - if so, we should show connected state
        // even during transient disconnections to prevent UI glitches
        val isForceTorEnabled = PreferencesHelper.isForceTorAll(requireContext()) ||
                                PreferencesHelper.isForceTorExceptI2P(requireContext())

        // Grey out the action button during force mode - users shouldn't be able to stop Tor manually
        // Keep it visible but disabled to provide better UX feedback
        torActionButton?.apply {
            isEnabled = !isForceTorEnabled
            alpha = if (isForceTorEnabled) 0.5f else 1.0f
        }

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
                    torStatusText?.text = getString(R.string.settings_tor_disconnected_status)
                    torStatusDetail?.text = getString(R.string.settings_tor_not_running_detail)
                    torActionButton?.text = getString(R.string.button_start_tor)
                    torActionButton?.isEnabled = true
                }
            }
            TorManager.TorState.STARTING -> {
                torStatusIcon?.setImageResource(R.drawable.ic_tor_connecting)
                torStatusText?.text = getString(R.string.settings_tor_connecting_status)
                torStatusDetail?.text = getString(R.string.settings_tor_establishing_connection)
                torActionButton?.text = getString(R.string.button_starting)
                torActionButton?.isEnabled = false
            }
            TorManager.TorState.CONNECTED -> {
                torStatusIcon?.setImageResource(R.drawable.ic_tor_on)
                torStatusText?.text = getString(R.string.settings_tor_connected_status)
                torStatusDetail?.text = getString(R.string.settings_tor_socks_port, TorManager.socksPort)
                torActionButton?.text = getString(R.string.button_stop_tor)
                // Don't override isEnabled here - it's already set based on force mode at line 887
            }
            TorManager.TorState.ERROR -> {
                // If force Tor is enabled, show a more severe warning
                if (isForceTorEnabled) {
                    showForceTorWarning()
                } else {
                    torStatusIcon?.setImageResource(R.drawable.ic_tor_off)
                    torStatusText?.text = getString(R.string.settings_tor_connection_failed)
                    torStatusDetail?.text = TorManager.errorMessage ?: getString(R.string.error_unknown)
                    torActionButton?.text = getString(R.string.button_retry)
                    torActionButton?.isEnabled = true
                }
            }
            TorManager.TorState.INVIZIBLE_NOT_INSTALLED -> {
                // If force Tor is enabled but InviZible Pro says not installed, check if proxy is accessible
                // This prevents UI glitches during activity recreation
                if (isForceTorEnabled && TorManager.isConnected()) {
                    showConnectedStateForForceTor()
                } else if (isForceTorEnabled) {
                    showForceTorWarning()
                } else {
                    torStatusIcon?.setImageResource(R.drawable.ic_tor_off)
                    torStatusText?.text = getString(R.string.settings_tor_invizible_required_status)
                    torStatusDetail?.text = getString(R.string.settings_tor_install_invizible_message)
                    torActionButton?.text = getString(R.string.button_install_invizible_caps)
                    torActionButton?.isEnabled = true
                }
            }
        }
    }

    private fun showConnectedStateForForceTor() {
        torStatusIcon?.setImageResource(R.drawable.ic_tor_on)
        torStatusText?.text = getString(R.string.settings_tor_connected_status)
        torStatusDetail?.text = getString(R.string.settings_tor_socks_port, TorManager.socksPort)
        torActionButton?.text = getString(R.string.button_stop_tor)
        // Keep button disabled and greyed out in force mode (handled by updateTorStatusUI)
        torActionButton?.isEnabled = false
        torActionButton?.alpha = 0.5f
    }

    private fun showForceTorWarning() {
        torStatusIcon?.setImageResource(R.drawable.ic_tor_error)
        torStatusText?.text = getString(R.string.settings_tor_force_warning)
        torStatusDetail?.text = getString(R.string.settings_tor_force_warning_detail)
        torActionButton?.text = getString(R.string.button_retry)
        // Keep button disabled and greyed out in force mode (handled by updateTorStatusUI)
        torActionButton?.isEnabled = false
        torActionButton?.alpha = 0.5f
    }

    private fun updateRecordingDirectoryDisplay() {
        val savedUri = PreferencesHelper.getRecordingDirectoryUri(requireContext())
        if (savedUri != null) {
            try {
                val uri = Uri.parse(savedUri)
                val docFile = DocumentFile.fromTreeUri(requireContext(), uri)
                val displayName = docFile?.name ?: getString(R.string.folder_custom)
                recordingDirectoryPath?.text = displayName
                recordingDirectoryButton?.text = getString(R.string.button_change)
            } catch (e: Exception) {
                recordingDirectoryPath?.text = getString(R.string.settings_recording_directory_default_label)
                recordingDirectoryButton?.text = getString(R.string.button_change)
            }
        } else {
            recordingDirectoryPath?.text = getString(R.string.settings_recording_directory_default_label)
            recordingDirectoryButton?.text = getString(R.string.button_change)
        }
    }

    private fun showRecordingDirectoryDialog() {
        val options = mutableListOf(
            getString(R.string.settings_recording_directory_default),
            getString(R.string.settings_recording_directory_choose)
        )
        val savedUri = PreferencesHelper.getRecordingDirectoryUri(requireContext())
        if (savedUri != null) {
            options.add(1, getString(R.string.settings_recording_directory_clear))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_recording_directory))
            .setItems(options.toTypedArray()) { dialog, which ->
                when {
                    which == 0 -> {
                        // Default
                        PreferencesHelper.setRecordingDirectoryUri(requireContext(), null)
                        updateRecordingDirectoryDisplay()
                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                            Toast.makeText(requireContext(), getString(R.string.toast_using_default_directory), Toast.LENGTH_SHORT).show()
                        }
                    }
                    savedUri != null && which == 1 -> {
                        // Clear custom folder
                        PreferencesHelper.setRecordingDirectoryUri(requireContext(), null)
                        updateRecordingDirectoryDisplay()
                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                            Toast.makeText(requireContext(), getString(R.string.toast_using_default_directory), Toast.LENGTH_SHORT).show()
                        }
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
            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.settings_theme_light)
            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.settings_theme_dark)
            else -> getString(R.string.settings_theme_system_default)
        }
    }

    private fun showThemeDialog(themeButton: MaterialButton) {
        val themes = arrayOf(
            getString(R.string.settings_theme_system_default),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark)
        )
        val currentMode = AppCompatDelegate.getDefaultNightMode()
        val selectedIndex = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_theme))
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
            "classic" -> getString(R.string.settings_color_classic)
            "blue" -> getString(R.string.settings_color_blue)
            "peach" -> getString(R.string.settings_color_peach)
            "green" -> getString(R.string.settings_color_green)
            "purple" -> getString(R.string.settings_color_purple)
            "orange" -> getString(R.string.settings_color_orange)
            else -> getString(R.string.settings_color_classic) // default to Classic for backward compatibility
        }
    }

    private fun showColorSchemeDialog(colorSchemeButton: MaterialButton) {
        val schemes = arrayOf(
            getString(R.string.settings_color_classic_default),
            getString(R.string.settings_color_blue),
            getString(R.string.settings_color_peach),
            getString(R.string.settings_color_green),
            getString(R.string.settings_color_purple),
            getString(R.string.settings_color_orange)
        )
        val schemeValues = arrayOf("classic", "blue", "peach", "green", "purple", "orange")
        var currentScheme = PreferencesHelper.getColorScheme(requireContext())
        // Migrate "default" to "classic" for backward compatibility
        if (currentScheme == "default") {
            currentScheme = "classic"
        }
        val selectedIndex = schemeValues.indexOf(currentScheme).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_color_scheme))
            .setSingleChoiceItems(schemes, selectedIndex) { dialog, which ->
                val newScheme = schemeValues[which]
                PreferencesHelper.setColorScheme(requireContext(), newScheme)
                updateColorSchemeButtonText(colorSchemeButton)
                // Recreate activity to apply new color scheme
                uiHandler.postDelayed({
                    MainActivity.prepareForUiRecreate()
                    activity?.recreate()
                }, 300)
                dialog.dismiss()
            }
            .show()
    }

    private fun updateLanguageButtonText(button: MaterialButton) {
        val currentLanguage = PreferencesHelper.getAppLanguage(requireContext())
        button.text = when (currentLanguage) {
            "ar" -> getString(R.string.language_arabic)
            "de" -> getString(R.string.language_german)
            "es" -> getString(R.string.language_spanish)
            "fa" -> getString(R.string.language_farsi)
            "fr" -> getString(R.string.language_french)
            "hi" -> getString(R.string.language_hindi)
            "it" -> getString(R.string.language_italian)
            "ja" -> getString(R.string.language_japanese)
            "ko" -> getString(R.string.language_korean)
            "my" -> getString(R.string.language_burmese)
            "pt" -> getString(R.string.language_portuguese)
            "ru" -> getString(R.string.language_russian)
            "tr" -> getString(R.string.language_turkish)
            "uk" -> getString(R.string.language_ukrainian)
            "vi" -> getString(R.string.language_vietnamese)
            "zh" -> getString(R.string.language_chinese)
            else -> getString(R.string.language_system_default)
        }
    }

    private fun showLanguageDialog(languageButton: MaterialButton) {
        val languages = arrayOf(
            getString(R.string.language_system_default),
            getString(R.string.language_arabic),
            getString(R.string.language_german),
            getString(R.string.language_english),
            getString(R.string.language_spanish),
            getString(R.string.language_farsi),
            getString(R.string.language_french),
            getString(R.string.language_hindi),
            getString(R.string.language_italian),
            getString(R.string.language_japanese),
            getString(R.string.language_korean),
            getString(R.string.language_burmese),
            getString(R.string.language_portuguese),
            getString(R.string.language_russian),
            getString(R.string.language_turkish),
            getString(R.string.language_ukrainian),
            getString(R.string.language_vietnamese),
            getString(R.string.language_chinese)
        )
        val languageCodes = arrayOf(
            "system", "ar", "de", "en", "es", "fa", "fr", "hi", "it", "ja", "ko", "my", "pt", "ru", "tr", "uk", "vi", "zh"
        )
        val currentLanguage = PreferencesHelper.getAppLanguage(requireContext())
        val selectedIndex = languageCodes.indexOf(currentLanguage).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.language_choose))
            .setSingleChoiceItems(languages, selectedIndex) { dialog, which ->
                val newLanguage = languageCodes[which]
                PreferencesHelper.setAppLanguage(requireContext(), newLanguage)
                updateLanguageButtonText(languageButton)

                // Apply the new language
                LocaleHelper.setLocale(requireContext(), newLanguage)

                // Recreate activity to apply new language
                uiHandler.postDelayed({
                    MainActivity.prepareForUiRecreate()
                    activity?.recreate()
                }, 300)
                dialog.dismiss()
            }
            .show()
    }

    // ==================== Import/Export Functions ====================

    private fun updateStationCounts() {
        // Use static descriptions without fetching counts
        i2pStationsDescription?.text = getString(R.string.settings_add_i2p_stations_description_generic)
        torStationsDescription?.text = getString(R.string.settings_add_tor_stations_description_generic)
    }

    private fun showImportDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_import_stations))
            .setMessage(getString(R.string.import_message))
            .setPositiveButton(getString(R.string.button_select_file)) { _, _ ->
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
            .setNegativeButton(getString(R.string.button_cancel), null)
            .show()
    }

    /**
     * Show import dialog for curated I2P/Tor station lists.
     * Fetches stations from the API and caches them for the import step.
     */
    private fun showImportCuratedListDialog(type: String) {
        val networkName = when (type) {
            "i2p" -> "I2P"
            "tor" -> "Tor"
            else -> return
        }

        val context = requireContext()

        // Clear any previous pending import
        pendingImportStations = null

        lifecycleScope.launch(Dispatchers.IO) {
            // Fetch all stations from API (using bulk download endpoint including dead stations)
            val apiResult = try {
                when (type) {
                    "tor" -> registryRepository.downloadAllTorStations()
                    "i2p" -> registryRepository.downloadAllI2pStations()
                    else -> null
                }
            } catch (e: Exception) {
                RadioRegistryResult.Error(e.message ?: "Unknown error", e)
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext

                when (apiResult) {
                    is RadioRegistryResult.Success -> {
                        val stations = apiResult.data.map { it.toRadioStation() }

                        if (stations.isEmpty()) {
                            if (!PreferencesHelper.isToastMessagesDisabled(context)) {
                                Toast.makeText(
                                    context,
                                    getString(R.string.no_stations_found_in_list, networkName),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@withContext
                        }

                        // Cache stations for the import step
                        pendingImportStations = stations

                        MaterialAlertDialogBuilder(context)
                            .setTitle(getString(R.string.import_curated_title, networkName))
                            .setMessage(getString(R.string.import_curated_message, stations.size, networkName))
                            .setPositiveButton(getString(R.string.button_import)) { _, _ ->
                                performPendingImport(networkName)
                            }
                            .setNegativeButton(getString(R.string.button_cancel)) { _, _ ->
                                pendingImportStations = null
                            }
                            .setOnCancelListener {
                                pendingImportStations = null
                            }
                            .show()
                    }
                    is RadioRegistryResult.Error -> {
                        if (!PreferencesHelper.isToastMessagesDisabled(context)) {
                            Toast.makeText(
                                context,
                                getString(R.string.failed_to_fetch_stations, networkName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    else -> {
                        // Loading state - shouldn't happen in this flow
                    }
                }
            }
        }
    }

    /**
     * Import the cached pending stations to the user's library.
     * Uses stations fetched in showImportCuratedListDialog to avoid refetching.
     */
    private fun performPendingImport(networkName: String) {
        val stations = pendingImportStations
        if (stations.isNullOrEmpty()) {
            return
        }

        // Clear the cache immediately
        pendingImportStations = null

        performImport(stations)
    }

    private fun showExportDialog() {
        val formats = StationImportExport.FileFormat.values()
        val formatNames = formats.map { it.displayName }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_export_stations_title))
            .setItems(formatNames) { _, which ->
                val format = formats[which]
                pendingExportFormat = format
                val filename = "i2pradio_stations.${format.extension}"
                exportFileLauncher.launch(filename)
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
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
                            getString(R.string.no_stations_found_in_list, "file")
                        }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.dialog_import_failed))
                            .setMessage(errorMsg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        return@withContext
                    }

                    // Show confirmation dialog
                    val formatName = result.format?.displayName ?: "Unknown"
                    val message = buildString {
                        append(getString(R.string.found_stations_message, result.stations.size, formatName))
                        if (result.errors.isNotEmpty()) {
                            append(getString(R.string.warnings_header))
                            result.errors.take(3).forEach { append("• $it\n") }
                            if (result.errors.size > 3) {
                                append(getString(R.string.more_warnings, result.errors.size - 3))
                            }
                            append("\n")
                        }
                        append(getString(R.string.import_confirmation))
                    }

                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.dialog_import_stations))
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.button_import)) { _, _ ->
                            performImport(result.stations)
                        }
                        .setNegativeButton(getString(R.string.button_cancel), null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Check if Fragment is still attached before showing UI
                    if (!isAdded) return@withContext

                    if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.import_error_message, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
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

                if (!PreferencesHelper.isToastMessagesDisabled(context)) {
                    Toast.makeText(
                        context,
                        getString(R.string.imported_stations_success, imported),
                        Toast.LENGTH_SHORT
                    ).show()
                }
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

                        if (!PreferencesHelper.isToastMessagesDisabled(context)) {
                            Toast.makeText(
                                context,
                                getString(R.string.no_stations_to_export),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
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

                    if (!PreferencesHelper.isToastMessagesDisabled(context)) {
                        Toast.makeText(
                            context,
                            getString(R.string.exported_stations_success, stations.size, format.displayName),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Check if Fragment is still attached before showing UI
                    if (!isAdded) return@withContext

                    if (!PreferencesHelper.isToastMessagesDisabled(context)) {
                        Toast.makeText(
                            context,
                            getString(R.string.export_error_message, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun setupCustomProxyControls() {
        // Initialize Enable Custom Proxy switch state
        val customProxyEnabled = PreferencesHelper.isCustomProxyEnabled(requireContext())
        enableCustomProxySwitch?.isChecked = customProxyEnabled

        // Show/hide config container based on switch state
        updateCustomProxyContainerVisibility(customProxyEnabled)

        // Handle Enable Custom Proxy switch toggle
        enableCustomProxySwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setCustomProxyEnabled(requireContext(), isChecked)
            updateCustomProxyContainerVisibility(isChecked)

            if (isChecked) {
                // MUTUAL EXCLUSIVITY: Disable Tor when Custom Proxy is enabled
                PreferencesHelper.setEmbeddedTorEnabled(requireContext(), false)
                embeddedTorSwitch?.isChecked = false

                // Stop Tor service
                TorService.stop(requireContext())

                // Disable Force Tor switches
                PreferencesHelper.setForceTorAll(requireContext(), false)
                PreferencesHelper.setForceTorExceptI2P(requireContext(), false)
                forceTorAllSwitch?.isChecked = false
                forceTorExceptI2pSwitch?.isChecked = false

                // Update Tor container visibility
                updateTorContainerVisibility(false)
            } else {
                // When disabled, also disable Force Custom Proxy settings
                PreferencesHelper.setForceCustomProxy(requireContext(), false)
                PreferencesHelper.setForceCustomProxyExceptTorI2P(requireContext(), false)
                forceCustomProxySwitch?.isChecked = false
                forceCustomProxyExceptTorI2pSwitch?.isChecked = false
            }

            // Broadcast proxy mode change to update MainActivity UI
            val broadcastIntent = Intent(com.opensource.i2pradio.MainActivity.BROADCAST_PROXY_MODE_CHANGED)
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(broadcastIntent)

            // Update status view
            updateCustomProxyStatusView()
        }

        // Configure Proxy button
        configureProxyButton?.setOnClickListener {
            showConfigureProxyDialog()
        }

        // Update custom proxy status view
        updateCustomProxyStatusView()

        // Force Custom Proxy switch
        val forceCustomProxyEnabled = PreferencesHelper.isForceCustomProxy(requireContext())
        forceCustomProxySwitch?.isChecked = forceCustomProxyEnabled

        forceCustomProxySwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setForceCustomProxy(requireContext(), isChecked)

            // Update Force Tor switches and other force proxy switches to maintain mutual exclusivity
            if (isChecked) {
                forceTorAllSwitch?.isChecked = false
                forceTorExceptI2pSwitch?.isChecked = false
                forceCustomProxyExceptTorI2pSwitch?.isChecked = false
            }

            // Broadcast proxy mode change to update MainActivity UI
            val broadcastIntent = Intent(com.opensource.i2pradio.MainActivity.BROADCAST_PROXY_MODE_CHANGED)
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(broadcastIntent)

            // Update status view
            updateCustomProxyStatusView()

            // Stop current stream when proxy settings change
            stopCurrentStream()

            // Show warning if enabling and custom proxy is not configured
            // Note: This is a privacy/security warning - always show regardless of toast setting
            if (isChecked) {
                val host = PreferencesHelper.getCustomProxyHost(requireContext())
                if (host.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "⚠️ Custom proxy not configured! Configure proxy first.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Force Custom Proxy Except Tor/I2P switch
        val forceCustomProxyExceptEnabled = PreferencesHelper.isForceCustomProxyExceptTorI2P(requireContext())
        forceCustomProxyExceptTorI2pSwitch?.isChecked = forceCustomProxyExceptEnabled

        forceCustomProxyExceptTorI2pSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setForceCustomProxyExceptTorI2P(requireContext(), isChecked)

            // Update Force Tor switches and other force proxy switches to maintain mutual exclusivity
            if (isChecked) {
                forceTorAllSwitch?.isChecked = false
                forceTorExceptI2pSwitch?.isChecked = false
                forceCustomProxySwitch?.isChecked = false
            }

            // Broadcast proxy mode change to update MainActivity UI
            val broadcastIntent = Intent(com.opensource.i2pradio.MainActivity.BROADCAST_PROXY_MODE_CHANGED)
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(broadcastIntent)

            // Update status view
            updateCustomProxyStatusView()

            // Stop current stream when proxy settings change
            stopCurrentStream()

            // Show warning if enabling and custom proxy is not configured
            // Note: This is a privacy/security warning - always show regardless of toast setting
            if (isChecked) {
                val host = PreferencesHelper.getCustomProxyHost(requireContext())
                if (host.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "⚠️ Custom proxy not configured! Configure proxy first.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateCustomProxyStatusView() {
        val isForceCustomProxy = PreferencesHelper.isForceCustomProxy(requireContext()) ||
                                 PreferencesHelper.isForceCustomProxyExceptTorI2P(requireContext())
        val proxyHost = PreferencesHelper.getCustomProxyHost(requireContext())
        val protocol = PreferencesHelper.getCustomProxyProtocol(requireContext())
        val port = PreferencesHelper.getCustomProxyPort(requireContext())
        customProxyStatusViewSettings?.updateStateFromConfig(isForceCustomProxy, proxyHost, protocol, port)
    }

    private fun setupBandwidthDisplay() {
        updateBandwidthDisplay()

        resetBandwidthButton?.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_reset_session_bandwidth))
                .setMessage(getString(R.string.reset_bandwidth_message))
                .setPositiveButton(getString(R.string.button_reset)) { _, _ ->
                    PreferencesHelper.resetSessionBandwidthUsage(requireContext())
                    updateBandwidthDisplay()
                    if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                        Toast.makeText(requireContext(), getString(R.string.toast_session_bandwidth_reset), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.button_cancel), null)
                .show()
        }
    }

    private fun updateBandwidthDisplay() {
        val total = PreferencesHelper.getTotalBandwidthUsage(requireContext())
        val session = PreferencesHelper.getSessionBandwidthUsage(requireContext())

        bandwidthTotalText?.text = PreferencesHelper.formatBandwidth(total)
        bandwidthSessionText?.text = PreferencesHelper.formatBandwidth(session)
    }

    private fun showConfigureProxyDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_configure_proxy, null)

        val hostInput = dialogView.findViewById<TextInputEditText>(R.id.proxyHostInput)
        val portInput = dialogView.findViewById<TextInputEditText>(R.id.proxyPortInput)
        val protocolInput = dialogView.findViewById<AutoCompleteTextView>(R.id.proxyProtocolInput)
        val usernameInput = dialogView.findViewById<TextInputEditText>(R.id.proxyUsernameInput)
        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.proxyPasswordInput)
        val authTypeInput = dialogView.findViewById<AutoCompleteTextView>(R.id.proxyAuthTypeInput)
        val timeoutInput = dialogView.findViewById<TextInputEditText>(R.id.proxyTimeoutInput)
        val testButton = dialogView.findViewById<MaterialButton>(R.id.testConnectionButton)
        val testResultText = dialogView.findViewById<TextView>(R.id.testResultText)
        val socks4DnsWarning = dialogView.findViewById<TextView>(R.id.socks4DnsWarning)

        // Helper function to update SOCKS4 warning visibility
        fun updateSocks4Warning(protocol: String) {
            socks4DnsWarning.visibility = if (protocol.uppercase() == "SOCKS4") View.VISIBLE else View.GONE
        }

        // Set up dropdowns
        val protocols = arrayOf(
            getString(R.string.default_protocol_http),
            getString(R.string.default_protocol_https),
            getString(R.string.default_protocol_socks4),
            getString(R.string.default_protocol_socks5)
        )
        val protocolAdapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_text, protocols)
        protocolInput.setAdapter(protocolAdapter)

        val authTypes = arrayOf(
            getString(R.string.default_proxy_none),
            getString(R.string.default_auth_basic),
            getString(R.string.default_auth_digest)
        )
        val authTypeAdapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_text, authTypes)
        authTypeInput.setAdapter(authTypeAdapter)

        // Load current settings
        hostInput.setText(PreferencesHelper.getCustomProxyHost(requireContext()))
        portInput.setText(PreferencesHelper.getCustomProxyPort(requireContext()).toString())
        protocolInput.setText(PreferencesHelper.getCustomProxyProtocol(requireContext()), false)
        usernameInput.setText(PreferencesHelper.getCustomProxyUsername(requireContext()))
        passwordInput.setText(PreferencesHelper.getCustomProxyPassword(requireContext()))
        authTypeInput.setText(PreferencesHelper.getCustomProxyAuthType(requireContext()), false)
        timeoutInput.setText(PreferencesHelper.getCustomProxyConnectionTimeout(requireContext()).toString())

        // Show SOCKS4 warning if currently selected
        updateSocks4Warning(PreferencesHelper.getCustomProxyProtocol(requireContext()))

        // Update SOCKS4 warning when protocol changes
        protocolInput.setOnItemClickListener { _, _, position, _ ->
            val selectedProtocol = protocols[position]
            updateSocks4Warning(selectedProtocol)
        }

        // Test Connection button
        testButton.setOnClickListener {
            val host = hostInput.text?.toString() ?: ""
            val port = portInput.text?.toString()?.toIntOrNull() ?: 8080
            val protocol = protocolInput.text?.toString()?.uppercase() ?: "HTTP"
            val username = usernameInput.text?.toString() ?: ""
            val password = passwordInput.text?.toString() ?: ""
            val authType = authTypeInput.text?.toString()?.uppercase() ?: "NONE"

            if (host.isEmpty()) {
                testResultText.text = getString(R.string.validation_enter_proxy_host)
                testResultText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                testResultText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            testResultText.text = getString(R.string.testing_connection)
            testResultText.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
            testResultText.visibility = View.VISIBLE
            testButton.isEnabled = false

            // Test proxy connection in background
            lifecycleScope.launch(Dispatchers.IO) {
                val result = testProxyConnection(host, port, protocol, username, password, authType)
                withContext(Dispatchers.Main) {
                    testButton.isEnabled = true
                    testResultText.text = result.message
                    testResultText.setTextColor(
                        resources.getColor(
                            if (result.success) android.R.color.holo_green_dark
                            else android.R.color.holo_red_dark,
                            null
                        )
                    )
                    testResultText.visibility = View.VISIBLE
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_configure_global_proxy))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.button_save)) { _, _ ->
                val host = hostInput.text?.toString() ?: ""
                val port = portInput.text?.toString()?.toIntOrNull() ?: 8080
                val protocol = protocolInput.text?.toString() ?: "HTTP"
                val username = usernameInput.text?.toString() ?: ""
                val password = passwordInput.text?.toString() ?: ""
                val authType = authTypeInput.text?.toString() ?: "None"
                val timeout = timeoutInput.text?.toString()?.toIntOrNull() ?: 30

                PreferencesHelper.setCustomProxyHost(requireContext(), host)
                PreferencesHelper.setCustomProxyPort(requireContext(), port)
                PreferencesHelper.setCustomProxyProtocol(requireContext(), protocol)
                PreferencesHelper.setCustomProxyUsername(requireContext(), username)
                PreferencesHelper.setCustomProxyPassword(requireContext(), password)
                PreferencesHelper.setCustomProxyAuthType(requireContext(), authType)
                PreferencesHelper.setCustomProxyConnectionTimeout(requireContext(), timeout)

                // MUTUAL EXCLUSIVITY: Disable Tor when Custom Proxy is configured
                // This prevents privacy leaks from having both configured but unclear routing
                if (host.isNotEmpty()) {
                    // Enable custom proxy
                    PreferencesHelper.setCustomProxyEnabled(requireContext(), true)
                    enableCustomProxySwitch?.isChecked = true
                    updateCustomProxyContainerVisibility(true)

                    // Disable Tor integration
                    PreferencesHelper.setEmbeddedTorEnabled(requireContext(), false)
                    embeddedTorSwitch?.isChecked = false

                    // Stop Tor service
                    TorService.stop(requireContext())

                    // Disable Force Tor switches
                    PreferencesHelper.setForceTorAll(requireContext(), false)
                    PreferencesHelper.setForceTorExceptI2P(requireContext(), false)
                    forceTorAllSwitch?.isChecked = false
                    forceTorExceptI2pSwitch?.isChecked = false

                    // Update Tor container visibility
                    updateTorContainerVisibility(false)
                }

                // Broadcast proxy mode change to update MainActivity UI
                val broadcastIntent = Intent(com.opensource.i2pradio.MainActivity.BROADCAST_PROXY_MODE_CHANGED)
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(broadcastIntent)

                // Update status view in settings
                updateCustomProxyStatusView()

                if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                    Toast.makeText(requireContext(), getString(R.string.toast_custom_proxy_saved), Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(getString(R.string.button_clear)) { _, _ ->
                // Disable custom proxy
                PreferencesHelper.setCustomProxyEnabled(requireContext(), false)
                enableCustomProxySwitch?.isChecked = false
                updateCustomProxyContainerVisibility(false)

                // Clear all proxy settings
                PreferencesHelper.setCustomProxyHost(requireContext(), "")
                PreferencesHelper.setCustomProxyPort(requireContext(), 8080)
                PreferencesHelper.setCustomProxyProtocol(requireContext(), "HTTP")
                PreferencesHelper.setCustomProxyUsername(requireContext(), "")
                PreferencesHelper.setCustomProxyPassword(requireContext(), "")
                PreferencesHelper.setCustomProxyAuthType(requireContext(), "None")
                PreferencesHelper.setCustomProxyConnectionTimeout(requireContext(), 30)

                // Disable Force Custom Proxy switches
                PreferencesHelper.setForceCustomProxy(requireContext(), false)
                PreferencesHelper.setForceCustomProxyExceptTorI2P(requireContext(), false)
                forceCustomProxySwitch?.isChecked = false
                forceCustomProxyExceptTorI2pSwitch?.isChecked = false

                // Broadcast proxy mode change to update MainActivity UI
                val broadcastIntent = Intent(com.opensource.i2pradio.MainActivity.BROADCAST_PROXY_MODE_CHANGED)
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(broadcastIntent)

                // Update status view in settings
                updateCustomProxyStatusView()

                if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                    Toast.makeText(requireContext(), getString(R.string.toast_custom_proxy_cleared), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
            .show()
    }

    private suspend fun testProxyConnection(
        host: String,
        port: Int,
        protocol: String,
        username: String,
        password: String,
        authType: String
    ): TestResult {
        android.util.Log.d("ProxyTest", "===== PROXY CONNECTION TEST START =====")
        android.util.Log.d("ProxyTest", "Proxy: $protocol://$host:$port")
        android.util.Log.d("ProxyTest", "Auth: ${if (username.isNotEmpty()) "enabled (user: $username, type: $authType)" else "disabled"}")

        return try {
            // Create OkHttp client with proxy configuration
            val javaProxyType = when (protocol) {
                "SOCKS4", "SOCKS5" -> java.net.Proxy.Type.SOCKS
                "HTTP", "HTTPS" -> java.net.Proxy.Type.HTTP
                else -> java.net.Proxy.Type.HTTP
            }
            android.util.Log.d("ProxyTest", "Java proxy type: $javaProxyType")

            val clientBuilder = okhttp3.OkHttpClient.Builder()
                .proxy(java.net.Proxy(javaProxyType, InetSocketAddress(host, port)))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request()
                    android.util.Log.d("ProxyTest", "REQUEST: ${request.method} ${request.url}")
                    try {
                        val response = chain.proceed(request)
                        android.util.Log.d("ProxyTest", "RESPONSE: ${response.code} ${response.message}")
                        android.util.Log.d("ProxyTest", "Response headers: ${response.headers}")
                        response
                    } catch (e: Exception) {
                        android.util.Log.e("ProxyTest", "Interceptor error: ${e.javaClass.simpleName}: ${e.message}")
                        throw e
                    }
                }

            // Add authentication if credentials are provided
            if (username.isNotEmpty() && password.isNotEmpty()) {
                android.util.Log.d("ProxyTest", "Adding proxy authentication (type: $authType)")
                clientBuilder.proxyAuthenticator { _, response ->
                    android.util.Log.d("ProxyTest", "Proxy authentication requested (${response.code})")

                    // Check if we've already tried authentication to avoid infinite loops
                    val previousAuth = response.request.header("Proxy-Authorization")
                    if (previousAuth != null) {
                        android.util.Log.w("ProxyTest", "Authentication already attempted - credentials may be incorrect")
                        return@proxyAuthenticator null
                    }

                    when (authType.uppercase()) {
                        "BASIC" -> {
                            android.util.Log.d("ProxyTest", "Using Basic authentication")
                            val credential = okhttp3.Credentials.basic(username, password)
                            response.request.newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        }
                        "DIGEST" -> {
                            android.util.Log.d("ProxyTest", "Using Digest authentication")
                            DigestAuthenticator.authenticate(response, username, password)
                        }
                        else -> {
                            android.util.Log.w("ProxyTest", "Unknown auth type: $authType, falling back to Basic")
                            val credential = okhttp3.Credentials.basic(username, password)
                            response.request.newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        }
                    }
                }
            }

            val client = clientBuilder.build()

            // Test with a simple, reliable endpoint that works well with proxies
            // Using example.com which is designed to be accessible from anywhere
            val testUrl = "http://example.com/"
            android.util.Log.d("ProxyTest", "Testing connectivity to: $testUrl")

            val request = okhttp3.Request.Builder()
                .url(testUrl)
                .get() // Use GET to ensure we get a response body
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                android.util.Log.d("ProxyTest", "Response code: ${response.code}")
                android.util.Log.d("ProxyTest", "Response body length: ${responseBody?.length ?: 0}")
                android.util.Log.d("ProxyTest", "Response body preview: ${responseBody?.take(100)}")

                when {
                    response.isSuccessful -> {
                        if (responseBody != null && responseBody.isNotEmpty()) {
                            android.util.Log.d("ProxyTest", "✓ SUCCESS: Proxy is working correctly!")
                            TestResult(true, "✓ Proxy working! (HTTP ${response.code}, ${responseBody.length} bytes received)")
                        } else {
                            android.util.Log.w("ProxyTest", "⚠ WARNING: Got HTTP ${response.code} but empty body")
                            TestResult(true, "⚠ Proxy responds but returns empty content (HTTP ${response.code})")
                        }
                    }
                    response.code == 407 -> {
                        android.util.Log.e("ProxyTest", "❌ Proxy authentication required (407)")
                        TestResult(false, "❌ Proxy auth required - check username/password")
                    }
                    response.code == 403 -> {
                        android.util.Log.e("ProxyTest", "❌ Proxy forbidden (403)")
                        TestResult(false, "❌ Proxy blocked request (HTTP 403) - proxy may block this domain")
                    }
                    else -> {
                        android.util.Log.e("ProxyTest", "❌ Unexpected response: ${response.code}")
                        TestResult(false, "❌ Proxy responded with error: HTTP ${response.code}")
                    }
                }
            }
        } catch (e: java.net.UnknownHostException) {
            android.util.Log.e("ProxyTest", "❌ Unknown host error", e)
            TestResult(false, "❌ Cannot resolve proxy host '$host' - check hostname")
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.e("ProxyTest", "❌ Connection timeout", e)
            TestResult(false, "❌ Connection timeout - proxy not responding on $host:$port")
        } catch (e: java.net.ConnectException) {
            android.util.Log.e("ProxyTest", "❌ Connection refused", e)
            TestResult(false, "❌ Connection refused - proxy not running or wrong port ($port)")
        } catch (e: javax.net.ssl.SSLException) {
            android.util.Log.e("ProxyTest", "❌ SSL/TLS error", e)
            TestResult(false, "❌ SSL error: ${e.message?.take(50)}")
        } catch (e: java.io.IOException) {
            android.util.Log.e("ProxyTest", "❌ Network IO error", e)
            TestResult(false, "❌ Network error: ${e.message?.take(60)}")
        } catch (e: Exception) {
            android.util.Log.e("ProxyTest", "❌ Unexpected error", e)
            TestResult(false, "❌ Error: ${e.javaClass.simpleName}: ${e.message?.take(50)}")
        } finally {
            android.util.Log.d("ProxyTest", "===== PROXY CONNECTION TEST END =====")
        }
    }

    private data class TestResult(val success: Boolean, val message: String)

    /**
     * Helper function to determine if a station is a clearnet station
     * (not I2P or Tor). This checks both the proxy type and the stream URL.
     *
     * CRITICAL: URL is checked FIRST as it is the ground truth - a .onion or .i2p
     * domain is ALWAYS Tor/I2P regardless of proxy configuration.
     */
    private fun isClearnetStation(station: RadioStation): Boolean {
        val streamUrl = station.streamUrl.lowercase()

        // Check URL FIRST - this is ground truth
        // A .onion or .i2p URL is ALWAYS Tor/I2P, regardless of proxy settings
        if (streamUrl.contains(".onion") || streamUrl.contains(".i2p")) {
            return false
        }

        // Then check proxy type as a secondary indicator
        val proxyType = ProxyType.fromString(station.proxyType)
        if (proxyType == ProxyType.I2P || proxyType == ProxyType.TOR) {
            return false
        }

        return true
    }

    /**
     * Setup authentication controls (app lock, biometric, password)
     */
    private fun setupAuthenticationControls() {
        // Initialize app lock state
        val isAppLockEnabled = PreferencesHelper.isAppLockEnabled(requireContext())
        appLockSwitch?.isChecked = isAppLockEnabled
        updateAuthenticationUIVisibility()

        // App Lock Switch
        appLockSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            if (isChecked) {
                // Enabling app lock - check if password is set
                if (!BiometricAuthManager.hasPassword(requireContext())) {
                    // No password set, show password setup dialog
                    showSetPasswordDialog()
                } else {
                    // Password already exists, just enable
                    PreferencesHelper.setAppLockEnabled(requireContext(), true)
                    updateAuthenticationUIVisibility()
                }
            } else {
                // Disabling app lock
                PreferencesHelper.setAppLockEnabled(requireContext(), false)
                updateAuthenticationUIVisibility()
            }
        }

        // Set Password Button
        setPasswordButton?.setOnClickListener {
            if (BiometricAuthManager.hasPassword(requireContext())) {
                showChangePasswordDialog()
            } else {
                showSetPasswordDialog()
            }
        }

        // Biometric Switch
        biometricSwitch?.isChecked = PreferencesHelper.isBiometricEnabled(requireContext())
        biometricSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setBiometricEnabled(requireContext(), isChecked)
        }

        // Require Auth on Launch Switch
        requireAuthSwitch?.isChecked = PreferencesHelper.isRequireAuthOnLaunch(requireContext())
        requireAuthSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setRequireAuthOnLaunch(requireContext(), isChecked)
        }
    }

    /**
     * Update visibility of authentication UI elements based on app lock state
     */
    private fun updateAuthenticationUIVisibility() {
        val isAppLockEnabled = PreferencesHelper.isAppLockEnabled(requireContext())
        val hasPassword = BiometricAuthManager.hasPassword(requireContext())
        val isBiometricAvailable = BiometricAuthManager.isBiometricAvailable(requireContext())

        if (isAppLockEnabled && hasPassword) {
            setPasswordButton?.visibility = View.VISIBLE
            setPasswordButton?.text = getString(R.string.settings_change_password)
            requireAuthContainer?.visibility = View.VISIBLE

            // Show biometric option only if hardware is available
            if (isBiometricAvailable) {
                biometricContainer?.visibility = View.VISIBLE
            } else {
                biometricContainer?.visibility = View.GONE
            }
        } else {
            setPasswordButton?.visibility = View.GONE
            biometricContainer?.visibility = View.GONE
            requireAuthContainer?.visibility = View.GONE
        }
    }

    /**
     * Show dialog to set password for the first time
     */
    private fun showSetPasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_configure_proxy, null)
        val container = dialogView.findViewById<View>(R.id.proxyConfigContainer)

        // Create password input fields
        val passwordLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            hint = getString(R.string.auth_dialog_new_password)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val passwordInput = TextInputEditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passwordLayout.addView(passwordInput)

        val confirmLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            hint = getString(R.string.auth_dialog_confirm_password)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val confirmInput = TextInputEditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        confirmLayout.addView(confirmInput)

        // Add to container
        (container as? android.view.ViewGroup)?.apply {
            removeAllViews()
            addView(passwordLayout)
            addView(confirmLayout)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.auth_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.auth_dialog_set) { _, _ ->
                val password = passwordInput.text.toString()
                val confirm = confirmInput.text.toString()

                when {
                    password.length < 4 -> {
                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                            Toast.makeText(requireContext(), R.string.auth_error_password_too_short, Toast.LENGTH_SHORT).show()
                        }
                        appLockSwitch?.isChecked = false
                    }
                    password != confirm -> {
                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                            Toast.makeText(requireContext(), R.string.auth_error_passwords_dont_match, Toast.LENGTH_SHORT).show()
                        }
                        appLockSwitch?.isChecked = false
                    }
                    else -> {
                        BiometricAuthManager.setPassword(requireContext(), password)
                        PreferencesHelper.setAppLockEnabled(requireContext(), true)
                        updateAuthenticationUIVisibility()
                        updateDatabaseEncryptionSwitchState()
                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                            Toast.makeText(requireContext(), R.string.auth_password_set, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                appLockSwitch?.isChecked = false
            }
            .create()

        dialog.show()
    }

    /**
     * Show dialog to change existing password
     */
    private fun showChangePasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_configure_proxy, null)
        val container = dialogView.findViewById<View>(R.id.proxyConfigContainer)

        // Create password input fields
        val currentLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            hint = getString(R.string.auth_dialog_current_password)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val currentInput = TextInputEditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        currentLayout.addView(currentInput)

        val newLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            hint = getString(R.string.auth_dialog_new_password)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val newInput = TextInputEditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        newLayout.addView(newInput)

        val confirmLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            hint = getString(R.string.auth_dialog_confirm_password)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val confirmInput = TextInputEditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        confirmLayout.addView(confirmInput)

        // Add to container
        (container as? android.view.ViewGroup)?.apply {
            removeAllViews()
            addView(currentLayout)
            addView(newLayout)
            addView(confirmLayout)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.auth_dialog_change_title)
            .setMessage(R.string.auth_dialog_change_message)
            .setView(dialogView)
            .setPositiveButton(R.string.auth_dialog_change) { _, _ ->
                val currentPassword = currentInput.text.toString()
                val newPassword = newInput.text.toString()
                val confirm = confirmInput.text.toString()

                when {
                    newPassword.length < 4 -> {
                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                            Toast.makeText(requireContext(), R.string.auth_error_password_too_short, Toast.LENGTH_SHORT).show()
                        }
                    }
                    newPassword != confirm -> {
                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                            Toast.makeText(requireContext(), R.string.auth_error_passwords_dont_match, Toast.LENGTH_SHORT).show()
                        }
                    }
                    !BiometricAuthManager.changePassword(requireContext(), currentPassword, newPassword) -> {
                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                            Toast.makeText(requireContext(), R.string.auth_error_current_password_wrong, Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {
                        // Password changed successfully
                        // If database encryption is enabled, re-key the database with new password
                        if (com.opensource.i2pradio.utils.DatabaseEncryptionManager.isDatabaseEncryptionEnabled(requireContext())) {
                            // Show progress dialog for re-keying
                            val progressDialog = MaterialAlertDialogBuilder(requireContext())
                                .setMessage(R.string.auth_rekeying_database)
                                .setCancelable(false)
                                .create()
                            progressDialog.show()

                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        // Close database
                                        com.opensource.i2pradio.data.RadioDatabase.closeDatabase()

                                        // Give database time to fully close
                                        Thread.sleep(500)

                                        // Re-key database with new password
                                        com.opensource.i2pradio.utils.DatabaseEncryptionManager.rekeyDatabase(
                                            requireContext(),
                                            "radio_database",
                                            currentPassword,
                                            newPassword
                                        )
                                    }

                                    withContext(Dispatchers.Main) {
                                        progressDialog.dismiss()
                                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                                            Toast.makeText(requireContext(), R.string.auth_password_changed, Toast.LENGTH_SHORT).show()
                                        }

                                        // Restart app to apply changes
                                        restartApp()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        progressDialog.dismiss()
                                        android.util.Log.e("SettingsFragment", "Failed to re-key database", e)
                                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                                            Toast.makeText(requireContext(), getString(R.string.password_change_rekey_failed, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        } else {
                            // No database encryption, just show success message
                            if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                                Toast.makeText(requireContext(), R.string.auth_password_changed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
    }

    /**
     * Setup database encryption controls
     */
    private fun setupDatabaseEncryptionControls() {
        val isEncryptionEnabled = com.opensource.i2pradio.utils.DatabaseEncryptionManager.isDatabaseEncryptionEnabled(requireContext())
        databaseEncryptionSwitch?.isChecked = isEncryptionEnabled

        // Update switch enabled state based on whether password is set
        updateDatabaseEncryptionSwitchState()

        databaseEncryptionSwitch?.setOnCheckedChangeListener { switch, isChecked ->
            // Prevent toggling during processing
            switch.isEnabled = false

            if (isChecked) {
                // Check if app password is set before allowing encryption
                if (!com.opensource.i2pradio.utils.BiometricAuthManager.hasPassword(requireContext())) {
                    // Show dialog explaining password requirement
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_database_encryption_password_required_title)
                        .setMessage(R.string.settings_database_encryption_password_required_message)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            // Reset switch and re-enable
                            switch.isChecked = false
                            switch.isEnabled = true
                        }
                        .setOnCancelListener {
                            // Reset switch and re-enable
                            switch.isChecked = false
                            switch.isEnabled = true
                        }
                        .show()
                    return@setOnCheckedChangeListener
                }

                // Show password prompt for enabling encryption
                showPasswordPromptForEncryption(switch as MaterialSwitch)
            } else {
                // Show password prompt for disabling encryption
                showPasswordPromptForDecryption(switch as MaterialSwitch)
            }
        }
    }

    /**
     * Update database encryption switch enabled state based on password availability
     */
    private fun updateDatabaseEncryptionSwitchState() {
        val hasPassword = com.opensource.i2pradio.utils.BiometricAuthManager.hasPassword(requireContext())
        val isEncryptionEnabled = com.opensource.i2pradio.utils.DatabaseEncryptionManager.isDatabaseEncryptionEnabled(requireContext())

        // Enable switch only if password is set OR encryption is already enabled
        // (allow disabling even if password was removed)
        databaseEncryptionSwitch?.isEnabled = hasPassword || isEncryptionEnabled
    }

    /**
     * Setup Network & API controls for privacy settings.
     * Allows disabling RadioBrowser API, Radio Registry API, and cover art loading.
     */
    private fun setupNetworkApiControls() {
        // Initialize switch states from preferences
        disableRadioBrowserApiSwitch?.isChecked = PreferencesHelper.isRadioBrowserApiDisabled(requireContext())
        disableRadioRegistryApiSwitch?.isChecked = PreferencesHelper.isRadioRegistryApiDisabled(requireContext())
        disableCoverArtSwitch?.isChecked = PreferencesHelper.isCoverArtDisabled(requireContext())

        // Update offline mode note visibility
        updateOfflineModeNoteVisibility()

        // RadioBrowser API switch
        disableRadioBrowserApiSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setRadioBrowserApiDisabled(requireContext(), isChecked)
            updateOfflineModeNoteVisibility()
        }

        // Radio Registry API switch
        disableRadioRegistryApiSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setRadioRegistryApiDisabled(requireContext(), isChecked)
            updateOfflineModeNoteVisibility()
        }

        // Cover Art switch
        disableCoverArtSwitch?.setOnCheckedChangeListener { switch, isChecked ->
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

            PreferencesHelper.setCoverArtDisabled(requireContext(), isChecked)
            // Invalidate image loader cache when setting changes
            com.opensource.i2pradio.util.SecureImageLoader.invalidateCache()

            // When cover art is disabled, clear the entire cache to remove external images
            if (isChecked) {
                com.opensource.i2pradio.util.SecureImageLoader.clearRemoteImageCache(requireContext())
            }

            // Recreate activity to refresh all views with updated cover art setting
            // Use a short delay to let the animation complete
            view?.postDelayed({
                activity?.recreate()
            }, 300)
        }
    }

    /**
     * Update the visibility of the offline mode note based on API settings.
     * Shows when both RadioBrowser and Radio Registry APIs are disabled.
     */
    private fun updateOfflineModeNoteVisibility() {
        val isOfflineMode = PreferencesHelper.isOfflineMode(requireContext())
        offlineModeNote?.visibility = if (isOfflineMode) View.VISIBLE else View.GONE
    }

    /**
     * Restart the app to apply database encryption changes
     */
    private fun restartApp() {
        val intent = requireActivity().packageManager
            .getLaunchIntentForPackage(requireActivity().packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.let { startActivity(it) }
        requireActivity().finish()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Show password prompt for enabling database encryption
     */
    private fun showPasswordPromptForEncryption(switch: MaterialSwitch) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_configure_proxy, null)
        val container = dialogView.findViewById<View>(R.id.proxyConfigContainer)

        // Create password input field
        val passwordLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            hint = getString(R.string.auth_password_hint)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val passwordInput = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passwordLayout.addView(passwordInput)

        // Add to container
        (container as? android.view.ViewGroup)?.apply {
            removeAllViews()
            addView(passwordLayout)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.auth_enter_password_to_encrypt)
            .setMessage(R.string.auth_password_for_encryption)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = passwordInput.text.toString()

                // Verify password matches stored password
                if (!com.opensource.i2pradio.utils.BiometricAuthManager.verifyPassword(requireContext(), password)) {
                    if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                        Toast.makeText(requireContext(), R.string.auth_error_wrong_password, Toast.LENGTH_SHORT).show()
                    }
                    switch.isChecked = false
                    switch.isEnabled = true
                    return@setPositiveButton
                }

                // Show progress dialog
                val progressDialog = MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.auth_encrypting_database)
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                // Perform encryption on background thread
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            // Enable database encryption with user password
                            val passphrase = com.opensource.i2pradio.utils.DatabaseEncryptionManager.enableDatabaseEncryption(requireContext(), password)

                            // Close current database instance
                            com.opensource.i2pradio.data.RadioDatabase.closeDatabase()

                            // Give database time to fully close and release file handles
                            Thread.sleep(500)

                            // Encrypt the database file
                            val dbName = "radio_database"
                            com.opensource.i2pradio.utils.DatabaseEncryptionManager.encryptDatabase(requireContext(), dbName, passphrase)
                        }

                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                                Toast.makeText(requireContext(), R.string.settings_database_encryption_enabled, Toast.LENGTH_SHORT).show()
                            }

                            // Restart the app
                            restartApp()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            android.util.Log.e("SettingsFragment", "Failed to enable database encryption", e)
                            if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                                Toast.makeText(requireContext(), "${getString(R.string.settings_database_encryption_error)}: ${e.message}", Toast.LENGTH_LONG).show()
                            }

                            // Disable encryption setting
                            com.opensource.i2pradio.utils.DatabaseEncryptionManager.disableDatabaseEncryption(requireContext())
                            switch.isChecked = false
                            switch.isEnabled = true
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                switch.isChecked = false
                switch.isEnabled = true
            }
            .setOnCancelListener {
                switch.isChecked = false
                switch.isEnabled = true
            }
            .create()

        dialog.show()
    }

    /**
     * Show password prompt for disabling database encryption
     */
    private fun showPasswordPromptForDecryption(switch: MaterialSwitch) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_configure_proxy, null)
        val container = dialogView.findViewById<View>(R.id.proxyConfigContainer)

        // Create password input field
        val passwordLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            hint = getString(R.string.auth_password_hint)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val passwordInput = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passwordLayout.addView(passwordInput)

        // Add to container
        (container as? android.view.ViewGroup)?.apply {
            removeAllViews()
            addView(passwordLayout)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.auth_enter_password_to_decrypt)
            .setMessage(R.string.auth_password_for_decryption)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = passwordInput.text.toString()

                // Verify password matches stored password
                if (!com.opensource.i2pradio.utils.BiometricAuthManager.verifyPassword(requireContext(), password)) {
                    if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                        Toast.makeText(requireContext(), R.string.auth_error_wrong_password, Toast.LENGTH_SHORT).show()
                    }
                    switch.isChecked = true
                    switch.isEnabled = true
                    return@setPositiveButton
                }

                // Show progress dialog
                val progressDialog = MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.auth_decrypting_database)
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                // Perform decryption on background thread
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            // Get current passphrase using password
                            val passphrase = com.opensource.i2pradio.utils.DatabaseEncryptionManager.getDatabasePassphrase(requireContext(), password)
                                ?: throw IllegalStateException("No encryption salt found")

                            // Close current database instance
                            com.opensource.i2pradio.data.RadioDatabase.closeDatabase()

                            // Give database time to fully close and release file handles
                            Thread.sleep(500)

                            // Decrypt the database file
                            val dbName = "radio_database"
                            com.opensource.i2pradio.utils.DatabaseEncryptionManager.decryptDatabase(requireContext(), dbName, passphrase)

                            // Disable database encryption
                            com.opensource.i2pradio.utils.DatabaseEncryptionManager.disableDatabaseEncryption(requireContext())

                            // Kill process immediately to prevent Room from trying to access
                            // the decrypted database with the old encryption SupportFactory.
                            // We can't switch to Main thread first because Room's background
                            // threads would race and crash.
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            android.util.Log.e("SettingsFragment", "Failed to disable database encryption", e)
                            if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                                Toast.makeText(requireContext(), "${getString(R.string.settings_database_encryption_error)}: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            switch.isChecked = true
                            switch.isEnabled = true
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                switch.isChecked = true
                switch.isEnabled = true
            }
            .setOnCancelListener {
                switch.isChecked = true
                switch.isEnabled = true
            }
            .create()

        dialog.show()
    }

    /**
     * Update bottom padding on settings container based on miniplayer visibility.
     * When a station is playing, the miniplayer is visible and we need extra padding.
     * When nothing is playing, we still apply a small base padding for visual spacing.
     */
    private fun updateBottomPaddingForMiniplayer(isMiniplayerVisible: Boolean) {
        val bottomPadding = if (isMiniplayerVisible) miniplayerSpacing else settingsBasePadding

        settingsContentContainer?.setPadding(
            settingsContentContainer?.paddingLeft ?: 0,
            settingsContentContainer?.paddingTop ?: 0,
            settingsContentContainer?.paddingRight ?: 0,
            bottomPadding
        )
    }
}

package com.opensource.i2pradio.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.audiofx.AudioEffect
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
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

        // Equalizer button - opens system/external equalizer
        val equalizerButton = view.findViewById<MaterialButton>(R.id.equalizerButton)
        equalizerButton.setOnClickListener {
            openSystemEqualizer()
        }

        // Setup Tor controls
        setupTorControls()

        // Bind to RadioService to get audio session ID
        val serviceIntent = Intent(requireContext(), RadioService::class.java)
        requireContext().bindService(serviceIntent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)

        return view
    }

    /**
     * Opens the system equalizer or external equalizer app (like Wavelet).
     * This is the same approach used by Auxio and other music players.
     */
    private fun openSystemEqualizer() {
        val audioSessionId = radioService?.getAudioSessionId() ?: 0
        if (audioSessionId == 0) {
            Toast.makeText(requireContext(), "Start playing a station first", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, requireContext().packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }

        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), "No equalizer app found. Install an equalizer like Wavelet.", Toast.LENGTH_LONG).show()
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
    }

    private fun updateTorContainerVisibility(show: Boolean) {
        torStatusContainer?.visibility = if (show) View.VISIBLE else View.GONE
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

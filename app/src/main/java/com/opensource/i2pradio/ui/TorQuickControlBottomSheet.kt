package com.opensource.i2pradio.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.opensource.i2pradio.R
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.tor.TorService

/**
 * A bottom sheet dialog for quick Tor connection management.
 * Provides a seamless UI for users to:
 * - See current Tor connection status
 * - Start/Stop Tor with one tap
 * - Install Orbot if not installed
 * - View connection details
 */
class TorQuickControlBottomSheet : BottomSheetDialogFragment() {

    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var connectionProgress: ProgressBar
    private lateinit var primaryActionButton: MaterialButton
    private lateinit var secondaryActionButton: MaterialButton
    private lateinit var orbotInfoCard: MaterialCardView
    private lateinit var connectionDetailsContainer: MaterialCardView
    private lateinit var proxyHostText: TextView
    private lateinit var proxyPortText: TextView

    private val torStateListener: (TorManager.TorState) -> Unit = { state ->
        activity?.runOnUiThread {
            updateUI(state)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_tor_control, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        statusIcon = view.findViewById(R.id.torStatusIcon)
        statusTitle = view.findViewById(R.id.torStatusTitle)
        statusSubtitle = view.findViewById(R.id.torStatusSubtitle)
        connectionProgress = view.findViewById(R.id.connectionProgress)
        primaryActionButton = view.findViewById(R.id.primaryActionButton)
        secondaryActionButton = view.findViewById(R.id.secondaryActionButton)
        orbotInfoCard = view.findViewById(R.id.orbotInfoCard)
        connectionDetailsContainer = view.findViewById(R.id.connectionDetailsContainer)
        proxyHostText = view.findViewById(R.id.proxyHostText)
        proxyPortText = view.findViewById(R.id.proxyPortText)

        // Setup button listeners
        primaryActionButton.setOnClickListener {
            handlePrimaryAction()
        }

        secondaryActionButton.setOnClickListener {
            handleSecondaryAction()
        }

        // Setup Orbot info card click
        orbotInfoCard.setOnClickListener {
            openOrbotInStore()
        }

        // Update UI with current state
        updateUI(TorManager.state)
    }

    override fun onResume() {
        super.onResume()
        TorManager.addStateListener(torStateListener)
    }

    override fun onPause() {
        super.onPause()
        TorManager.removeStateListener(torStateListener)
    }

    private fun updateUI(state: TorManager.TorState) {
        when (state) {
            TorManager.TorState.STOPPED -> showStoppedState()
            TorManager.TorState.STARTING -> showConnectingState()
            TorManager.TorState.CONNECTED -> showConnectedState()
            TorManager.TorState.ERROR -> showErrorState()
            TorManager.TorState.ORBOT_NOT_INSTALLED -> showOrbotNotInstalledState()
        }
    }

    private fun showStoppedState() {
        statusIcon.setImageResource(R.drawable.ic_tor_off)
        statusIcon.alpha = 0.6f
        statusTitle.text = "Tor Disconnected"
        statusSubtitle.text = "Tap Connect to browse anonymously via Tor"
        connectionProgress.visibility = View.GONE
        connectionDetailsContainer.visibility = View.GONE
        orbotInfoCard.visibility = View.GONE

        primaryActionButton.text = "Connect to Tor"
        primaryActionButton.setIconResource(R.drawable.ic_tor_on)
        primaryActionButton.isEnabled = true
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.text = "Open Orbot"
        secondaryActionButton.visibility = View.VISIBLE
        secondaryActionButton.isEnabled = TorManager.isOrbotInstalled(requireContext())
    }

    private fun showConnectingState() {
        statusIcon.setImageResource(R.drawable.ic_tor_connecting)
        statusIcon.alpha = 1f
        statusTitle.text = "Connecting to Tor..."
        statusSubtitle.text = "Establishing secure connection via Orbot"
        connectionProgress.visibility = View.VISIBLE
        connectionDetailsContainer.visibility = View.GONE
        orbotInfoCard.visibility = View.GONE

        // Pulse animation on icon
        statusIcon.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                statusIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(500)
                    .start()
            }
            .start()

        primaryActionButton.text = "Connecting..."
        primaryActionButton.icon = null
        primaryActionButton.isEnabled = false
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.text = "Cancel"
        secondaryActionButton.visibility = View.VISIBLE
        secondaryActionButton.isEnabled = true
    }

    private fun showConnectedState() {
        statusIcon.setImageResource(R.drawable.ic_tor_on)
        statusIcon.alpha = 1f
        statusTitle.text = "Connected to Tor"
        statusSubtitle.text = "Your connection is private and anonymous"
        connectionProgress.visibility = View.GONE
        orbotInfoCard.visibility = View.GONE

        // Show connection details
        connectionDetailsContainer.visibility = View.VISIBLE
        proxyHostText.text = TorManager.socksHost
        proxyPortText.text = TorManager.socksPort.toString()

        // Bounce animation on connect
        statusIcon.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                statusIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start()
            }
            .start()

        primaryActionButton.text = "Disconnect"
        primaryActionButton.setIconResource(R.drawable.ic_tor_off)
        primaryActionButton.isEnabled = true
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.text = "Open Orbot"
        secondaryActionButton.visibility = View.VISIBLE
        secondaryActionButton.isEnabled = true
    }

    private fun showErrorState() {
        statusIcon.setImageResource(R.drawable.ic_tor_error)
        statusIcon.alpha = 1f
        statusTitle.text = "Connection Failed"
        statusSubtitle.text = TorManager.errorMessage ?: "Could not connect to Tor network"
        connectionProgress.visibility = View.GONE
        connectionDetailsContainer.visibility = View.GONE
        orbotInfoCard.visibility = View.GONE

        // Shake animation on error
        statusIcon.animate()
            .translationX(10f)
            .setDuration(50)
            .withEndAction {
                statusIcon.animate()
                    .translationX(-10f)
                    .setDuration(50)
                    .withEndAction {
                        statusIcon.animate()
                            .translationX(0f)
                            .setDuration(50)
                            .start()
                    }
                    .start()
            }
            .start()

        primaryActionButton.text = "Retry Connection"
        primaryActionButton.setIconResource(R.drawable.ic_tor_on)
        primaryActionButton.isEnabled = true
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.text = "Open Orbot"
        secondaryActionButton.visibility = View.VISIBLE
        secondaryActionButton.isEnabled = TorManager.isOrbotInstalled(requireContext())
    }

    private fun showOrbotNotInstalledState() {
        statusIcon.setImageResource(R.drawable.ic_orbot)
        statusIcon.alpha = 1f
        statusTitle.text = "Orbot Required"
        statusSubtitle.text = "Install Orbot to connect to the Tor network"
        connectionProgress.visibility = View.GONE
        connectionDetailsContainer.visibility = View.GONE
        orbotInfoCard.visibility = View.VISIBLE

        primaryActionButton.text = "Install Orbot"
        primaryActionButton.setIconResource(R.drawable.ic_download)
        primaryActionButton.isEnabled = true
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.visibility = View.GONE
    }

    private fun handlePrimaryAction() {
        when (TorManager.state) {
            TorManager.TorState.STOPPED, TorManager.TorState.ERROR -> {
                // Enable Tor in preferences and start
                PreferencesHelper.setEmbeddedTorEnabled(requireContext(), true)
                TorService.start(requireContext())
            }
            TorManager.TorState.CONNECTED -> {
                TorService.stop(requireContext())
            }
            TorManager.TorState.STARTING -> {
                // Do nothing
            }
            TorManager.TorState.ORBOT_NOT_INSTALLED -> {
                openOrbotInStore()
            }
        }
    }

    private fun handleSecondaryAction() {
        when (TorManager.state) {
            TorManager.TorState.STARTING -> {
                // Cancel connecting
                TorService.stop(requireContext())
            }
            else -> {
                // Open Orbot app
                openOrbotApp()
            }
        }
    }

    private fun openOrbotApp() {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage("org.torproject.android")
            if (intent != null) {
                startActivity(intent)
            } else {
                openOrbotInStore()
            }
        } catch (e: Exception) {
            openOrbotInStore()
        }
    }

    private fun openOrbotInStore() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.torproject.android"))
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/org.torproject.android/"))
            startActivity(intent)
        }
    }

    companion object {
        const val TAG = "TorQuickControlBottomSheet"

        fun newInstance(): TorQuickControlBottomSheet {
            return TorQuickControlBottomSheet()
        }
    }
}

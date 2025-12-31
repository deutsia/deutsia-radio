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
    private lateinit var invizibleInfoCard: MaterialCardView
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
        invizibleInfoCard = view.findViewById(R.id.invizibleInfoCard)
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

        // Setup InviZible Pro info card click
        invizibleInfoCard.setOnClickListener {
            openInviZibleInStore()
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
        // Check if Force Tor mode is enabled
        val isForceTorEnabled = PreferencesHelper.isForceTorAll(requireContext()) ||
                                PreferencesHelper.isForceTorExceptI2P(requireContext())

        when (state) {
            TorManager.TorState.STOPPED -> {
                if (isForceTorEnabled && !TorManager.isConnected()) {
                    showBlockedState()
                } else {
                    showStoppedState()
                }
            }
            TorManager.TorState.STARTING -> showConnectingState()
            TorManager.TorState.CONNECTED -> showConnectedState()
            TorManager.TorState.ERROR -> {
                if (isForceTorEnabled) {
                    showBlockedState()
                } else {
                    showErrorState()
                }
            }
            TorManager.TorState.INVIZIBLE_NOT_INSTALLED -> {
                if (isForceTorEnabled && !TorManager.isConnected()) {
                    showBlockedState()
                } else {
                    showInviZibleNotInstalledState()
                }
            }
        }
    }

    private fun showStoppedState() {
        statusIcon.clearColorFilter()
        statusIcon.setImageResource(R.drawable.ic_tor_off)
        statusIcon.alpha = 0.6f
        statusTitle.text = getString(R.string.tor_control_title_stopped)
        statusSubtitle.text = getString(R.string.tor_control_subtitle_stopped)
        connectionProgress.visibility = View.GONE
        connectionDetailsContainer.visibility = View.GONE
        invizibleInfoCard.visibility = View.GONE

        primaryActionButton.text = getString(R.string.tor_connect)
        primaryActionButton.setIconResource(R.drawable.ic_tor_on)
        primaryActionButton.isEnabled = true
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.text = getString(R.string.tor_control_button_open_invizible)
        secondaryActionButton.visibility = View.VISIBLE
        secondaryActionButton.isEnabled = TorManager.isInviZibleInstalled(requireContext())
    }

    private fun showConnectingState() {
        statusIcon.clearColorFilter()
        statusIcon.setImageResource(R.drawable.ic_tor_connecting)
        statusIcon.alpha = 1f
        statusTitle.text = getString(R.string.tor_control_title_connecting)
        statusSubtitle.text = getString(R.string.tor_control_subtitle_connecting)
        connectionProgress.visibility = View.VISIBLE
        connectionDetailsContainer.visibility = View.GONE
        invizibleInfoCard.visibility = View.GONE

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

        primaryActionButton.text = getString(R.string.tor_control_button_connecting)
        primaryActionButton.icon = null
        primaryActionButton.isEnabled = false
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.text = getString(R.string.tor_control_button_cancel)
        secondaryActionButton.visibility = View.VISIBLE
        secondaryActionButton.isEnabled = true
    }

    private fun showConnectedState() {
        statusIcon.clearColorFilter()
        statusIcon.setImageResource(R.drawable.ic_tor_on)
        statusIcon.alpha = 1f

        // Check if force Tor mode is enabled
        val isForceTorEnabled = PreferencesHelper.isForceTorAll(requireContext()) ||
                                PreferencesHelper.isForceTorExceptI2P(requireContext())

        if (isForceTorEnabled) {
            statusTitle.text = getString(R.string.tor_control_title_connected_force)
            statusSubtitle.text = getString(R.string.tor_control_subtitle_connected_force)
        } else {
            statusTitle.text = getString(R.string.tor_control_title_connected)
            statusSubtitle.text = getString(R.string.tor_control_subtitle_connected)
        }

        connectionProgress.visibility = View.GONE
        invizibleInfoCard.visibility = View.GONE

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

        primaryActionButton.text = getString(R.string.tor_control_button_disconnect)
        primaryActionButton.setIconResource(R.drawable.ic_tor_off)
        // Disable disconnect button if force Tor mode is enabled
        primaryActionButton.isEnabled = !isForceTorEnabled
        primaryActionButton.alpha = if (isForceTorEnabled) 0.5f else 1.0f
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.text = getString(R.string.tor_control_button_open_invizible)
        secondaryActionButton.visibility = View.VISIBLE
        secondaryActionButton.isEnabled = true
    }

    private fun showErrorState() {
        statusIcon.clearColorFilter()
        statusIcon.setImageResource(R.drawable.ic_tor_error)
        statusIcon.alpha = 1f
        statusTitle.text = getString(R.string.tor_control_title_error)
        statusSubtitle.text = TorManager.errorMessage ?: getString(R.string.tor_control_subtitle_error)
        connectionProgress.visibility = View.GONE
        connectionDetailsContainer.visibility = View.GONE
        invizibleInfoCard.visibility = View.GONE

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

        primaryActionButton.text = getString(R.string.tor_control_button_retry)
        primaryActionButton.setIconResource(R.drawable.ic_tor_on)
        primaryActionButton.isEnabled = true
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.text = getString(R.string.tor_control_button_open_invizible)
        secondaryActionButton.visibility = View.VISIBLE
        secondaryActionButton.isEnabled = TorManager.isInviZibleInstalled(requireContext())
    }

    private fun showInviZibleNotInstalledState() {
        statusIcon.clearColorFilter()
        statusIcon.setImageResource(R.drawable.ic_invizible)
        statusIcon.alpha = 1f
        statusTitle.text = getString(R.string.tor_control_title_invizible_required)
        statusSubtitle.text = getString(R.string.tor_control_subtitle_invizible_required)
        connectionProgress.visibility = View.GONE
        connectionDetailsContainer.visibility = View.GONE
        invizibleInfoCard.visibility = View.VISIBLE

        primaryActionButton.text = getString(R.string.tor_control_button_install)
        primaryActionButton.setIconResource(R.drawable.ic_download)
        primaryActionButton.isEnabled = true
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.visibility = View.GONE
    }

    /**
     * Shows the "Streams Blocked" state when Force Tor mode is enabled but Tor is disconnected.
     * Reassures the user that no data has leaked - streams are simply blocked.
     */
    private fun showBlockedState() {
        statusIcon.setImageResource(R.drawable.ic_tor_off)
        statusIcon.alpha = 1f
        statusIcon.setColorFilter(requireContext().getColor(R.color.tor_blocked))
        statusTitle.text = getString(R.string.tor_control_title_blocked)
        statusSubtitle.text = getString(R.string.tor_control_subtitle_blocked)
        connectionProgress.visibility = View.GONE
        connectionDetailsContainer.visibility = View.GONE
        invizibleInfoCard.visibility = View.GONE

        // Gentle pulse animation
        statusIcon.animate()
            .alpha(0.6f)
            .setDuration(800)
            .withEndAction {
                statusIcon.animate()
                    .alpha(1f)
                    .setDuration(800)
                    .start()
            }
            .start()

        primaryActionButton.text = getString(R.string.tor_connect)
        primaryActionButton.setIconResource(R.drawable.ic_tor_on)
        primaryActionButton.isEnabled = true
        primaryActionButton.visibility = View.VISIBLE

        secondaryActionButton.text = getString(R.string.tor_control_button_open_invizible)
        secondaryActionButton.visibility = View.VISIBLE
        secondaryActionButton.isEnabled = TorManager.isInviZibleInstalled(requireContext())
    }

    private fun handlePrimaryAction() {
        when (TorManager.state) {
            TorManager.TorState.STOPPED, TorManager.TorState.ERROR -> {
                // Enable Tor in preferences and start
                PreferencesHelper.setEmbeddedTorEnabled(requireContext(), true)
                TorService.start(requireContext())
            }
            TorManager.TorState.CONNECTED -> {
                // Disable Tor in preferences and stop
                PreferencesHelper.setEmbeddedTorEnabled(requireContext(), false)
                TorService.stop(requireContext())
            }
            TorManager.TorState.STARTING -> {
                // Do nothing
            }
            TorManager.TorState.INVIZIBLE_NOT_INSTALLED -> {
                openInviZibleInStore()
            }
        }
    }

    private fun handleSecondaryAction() {
        when (TorManager.state) {
            TorManager.TorState.STARTING -> {
                // Cancel connecting - also disable preference
                PreferencesHelper.setEmbeddedTorEnabled(requireContext(), false)
                TorService.stop(requireContext())
            }
            else -> {
                // Open InviZible Pro app
                openInviZibleApp()
            }
        }
    }

    private fun openInviZibleApp() {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage("pan.alexander.tordnscrypt.gp")
            if (intent != null) {
                startActivity(intent)
            } else {
                openInviZibleInStore()
            }
        } catch (e: Exception) {
            openInviZibleInStore()
        }
    }

    private fun openInviZibleInStore() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=pan.alexander.tordnscrypt.gp"))
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/en/packages/pan.alexander.tordnscrypt.stable/"))
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

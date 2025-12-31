package com.opensource.i2pradio.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.opensource.i2pradio.R
import com.opensource.i2pradio.tor.TorManager

/**
 * A compact Tor status indicator view for the toolbar.
 * Shows connection status with icon and optional text, with animations
 * for state transitions.
 */
class TorStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val statusIcon: ImageView
    private val statusText: TextView
    private val statusContainer: View
    private var pulseAnimator: ObjectAnimator? = null
    private var rotationAnimator: ObjectAnimator? = null

    private var showText: Boolean = true
    private var compactMode: Boolean = false
    private var onClickAction: (() -> Unit)? = null

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_tor_status, this, true)
        statusIcon = view.findViewById(R.id.torStatusIcon)
        statusText = view.findViewById(R.id.torStatusText)
        statusContainer = view.findViewById(R.id.torStatusContainer)

        // Set click listener
        statusContainer.setOnClickListener {
            onClickAction?.invoke()
        }

        // Enable ripple effect
        statusContainer.isClickable = true
        statusContainer.isFocusable = true
    }

    fun setOnStatusClickListener(action: () -> Unit) {
        onClickAction = action
    }

    fun setCompactMode(compact: Boolean) {
        compactMode = compact
        statusText.visibility = if (compact) View.GONE else View.VISIBLE
    }

    fun setShowText(show: Boolean) {
        showText = show
        if (!compactMode) {
            statusText.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    fun updateState(state: TorManager.TorState) {
        stopAnimations()

        // Check if force Tor mode is enabled - if so, we should show connected state
        // even during transient disconnections to prevent UI glitches
        val isForceTorEnabled = PreferencesHelper.isForceTorAll(context) ||
                                PreferencesHelper.isForceTorExceptI2P(context)

        when (state) {
            TorManager.TorState.STOPPED -> {
                // If force Tor is enabled and we have a proxy port, assume connected
                if (isForceTorEnabled && TorManager.isConnected()) {
                    showConnectedStateForForceTor()
                } else if (isForceTorEnabled) {
                    // Force Tor is enabled but not connected - show warning
                    showForceTorWarning()
                } else {
                    statusIcon.setImageResource(R.drawable.ic_tor_off)
                    statusIcon.alpha = 0.5f
                    statusText.text = context.getString(R.string.tor_status_off)
                    statusText.setTextColor(context.getColor(R.color.tor_disconnected))
                    contentDescription = context.getString(R.string.tor_status_off_description)
                }
            }
            TorManager.TorState.STARTING -> {
                statusIcon.setImageResource(R.drawable.ic_tor_connecting)
                statusIcon.alpha = 1f
                statusText.text = context.getString(R.string.tor_status_connecting)
                statusText.setTextColor(context.getColor(R.color.tor_connecting))
                contentDescription = context.getString(R.string.tor_status_connecting_description)
                startConnectingAnimation()
            }
            TorManager.TorState.CONNECTED -> {
                statusIcon.setImageResource(R.drawable.ic_tor_on)
                statusIcon.alpha = 1f
                statusText.text = context.getString(R.string.tor_status_connected)
                statusText.setTextColor(context.getColor(R.color.tor_connected))
                contentDescription = context.getString(R.string.tor_status_connected_description)
                showConnectedAnimation()
            }
            TorManager.TorState.ERROR -> {
                // If force Tor is enabled, show a more severe warning
                if (isForceTorEnabled) {
                    showForceTorWarning()
                } else {
                    statusIcon.setImageResource(R.drawable.ic_tor_error)
                    statusIcon.alpha = 1f
                    statusText.text = context.getString(R.string.tor_status_error)
                    statusText.setTextColor(context.getColor(R.color.tor_error))
                    contentDescription = context.getString(R.string.tor_status_error_description)
                    showErrorAnimation()
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
                    statusIcon.setImageResource(R.drawable.ic_tor_off)
                    statusIcon.alpha = 0.5f
                    statusText.text = context.getString(R.string.tor_status_install_invizible)
                    statusText.setTextColor(context.getColor(R.color.tor_disconnected))
                    contentDescription = context.getString(R.string.tor_status_install_invizible_description)
                }
            }
        }
    }

    private fun showConnectedStateForForceTor() {
        statusIcon.setImageResource(R.drawable.ic_tor_on)
        statusIcon.alpha = 1f
        statusText.text = context.getString(R.string.tor_status_connected)
        statusText.setTextColor(context.getColor(R.color.tor_connected))
        contentDescription = context.getString(R.string.tor_status_connected_force_description)
        showConnectedAnimation()
    }

    /**
     * Shows "Tor Required" state when Force Tor is enabled but Tor is disconnected.
     * Uses orange (caution) color instead of red (error) because streams are blocked,
     * not leaking - the user is protected, just unable to stream.
     */
    private fun showForceTorWarning() {
        statusIcon.setImageResource(R.drawable.ic_tor_off)
        statusIcon.alpha = 1f
        statusText.text = context.getString(R.string.tor_status_tor_required)
        statusText.setTextColor(context.getColor(R.color.tor_blocked))
        contentDescription = context.getString(R.string.tor_status_tor_required_description)
        // Gentle pulse animation instead of error shake - it's caution, not danger
        showBlockedAnimation()
    }

    private fun showBlockedAnimation() {
        // Gentle pulse animation to draw attention without alarm
        pulseAnimator = ObjectAnimator.ofFloat(statusIcon, "alpha", 0.6f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun startConnectingAnimation() {
        // Pulse animation for connecting state
        pulseAnimator = ObjectAnimator.ofFloat(statusIcon, "alpha", 0.4f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Rotation animation for the icon
        rotationAnimator = ObjectAnimator.ofFloat(statusIcon, "rotation", 0f, 360f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun showConnectedAnimation() {
        // Quick scale bounce when connected
        statusIcon.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(150)
            .withEndAction {
                statusIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private fun showErrorAnimation() {
        // Shake animation for error
        val shake = ObjectAnimator.ofFloat(statusIcon, "translationX", 0f, 10f, -10f, 10f, -10f, 5f, -5f, 0f)
        shake.duration = 500
        shake.start()
    }

    private fun stopAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        rotationAnimator?.cancel()
        rotationAnimator = null
        statusIcon.rotation = 0f
        statusIcon.scaleX = 1f
        statusIcon.scaleY = 1f
        statusIcon.translationX = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimations()
    }
}

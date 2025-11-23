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

        when (state) {
            TorManager.TorState.STOPPED -> {
                statusIcon.setImageResource(R.drawable.ic_tor_off)
                statusIcon.alpha = 0.5f
                statusText.text = "Tor Off"
                statusText.setTextColor(context.getColor(R.color.tor_disconnected))
                contentDescription = "Tor is disconnected. Tap to connect."
            }
            TorManager.TorState.STARTING -> {
                statusIcon.setImageResource(R.drawable.ic_tor_connecting)
                statusIcon.alpha = 1f
                statusText.text = "Connecting..."
                statusText.setTextColor(context.getColor(R.color.tor_connecting))
                contentDescription = "Tor is connecting..."
                startConnectingAnimation()
            }
            TorManager.TorState.CONNECTED -> {
                statusIcon.setImageResource(R.drawable.ic_tor_on)
                statusIcon.alpha = 1f
                statusText.text = "Tor Connected"
                statusText.setTextColor(context.getColor(R.color.tor_connected))
                contentDescription = "Tor is connected. Tap to view details."
                showConnectedAnimation()
            }
            TorManager.TorState.ERROR -> {
                statusIcon.setImageResource(R.drawable.ic_tor_error)
                statusIcon.alpha = 1f
                statusText.text = "Tor Error"
                statusText.setTextColor(context.getColor(R.color.tor_error))
                contentDescription = "Tor connection failed. Tap to retry."
                showErrorAnimation()
            }
            TorManager.TorState.ORBOT_NOT_INSTALLED -> {
                statusIcon.setImageResource(R.drawable.ic_tor_off)
                statusIcon.alpha = 0.5f
                statusText.text = "Install Orbot"
                statusText.setTextColor(context.getColor(R.color.tor_disconnected))
                contentDescription = "Orbot is not installed. Tap to install."
            }
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

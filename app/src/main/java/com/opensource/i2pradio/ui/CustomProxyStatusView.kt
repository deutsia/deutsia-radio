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

/**
 * A compact custom proxy status indicator view for the toolbar.
 * Shows connection status with icon and optional text, with animations
 * for state transitions.
 */
class CustomProxyStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val statusIcon: ImageView
    private val statusText: TextView
    private val statusContainer: View
    private var pulseAnimator: ObjectAnimator? = null

    private var showText: Boolean = true
    private var compactMode: Boolean = false
    private var onClickAction: (() -> Unit)? = null

    /**
     * Custom proxy connection states
     */
    enum class ProxyState {
        CONNECTED,      // Proxy is configured (settings saved)
        NOT_CONFIGURED, // No proxy configured
        LEAK_WARNING    // Force custom proxy enabled but proxy not configured (privacy leak)
    }

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_custom_proxy_status, this, true)
        statusIcon = view.findViewById(R.id.customProxyStatusIcon)
        statusText = view.findViewById(R.id.customProxyStatusText)
        statusContainer = view.findViewById(R.id.customProxyStatusContainer)

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

    fun updateState(state: ProxyState) {
        stopAnimations()

        when (state) {
            ProxyState.CONNECTED -> {
                statusIcon.setImageResource(R.drawable.ic_proxy_custom_on)
                statusIcon.alpha = 1f
                statusText.text = "Proxy Configured"
                statusText.setTextColor(context.getColor(R.color.tor_connected))
                contentDescription = "Custom proxy is configured. Tap to view details."
                showConnectedAnimation()
            }
            ProxyState.NOT_CONFIGURED -> {
                statusIcon.setImageResource(R.drawable.ic_proxy_custom_off)
                statusIcon.alpha = 1f
                statusText.text = "Proxy Off"
                statusText.setTextColor(context.getColor(R.color.tor_disconnected))
                contentDescription = "Custom proxy is not configured. Tap to configure."
            }
            ProxyState.LEAK_WARNING -> {
                statusIcon.setImageResource(R.drawable.ic_proxy_custom_error)
                statusIcon.alpha = 1f
                statusText.text = "Leak Warning"
                statusText.setTextColor(context.getColor(R.color.tor_error))
                contentDescription = "Force custom proxy enabled but not configured. Privacy may be compromised."
                showLeakWarningAnimation()
            }
        }
    }

    /**
     * Update state based on current proxy configuration
     */
    fun updateStateFromConfig(forceEnabled: Boolean, proxyHost: String) {
        val state = when {
            // Force enabled but no proxy configured = privacy leak warning
            forceEnabled && proxyHost.isEmpty() -> ProxyState.LEAK_WARNING
            // Proxy configured (whether force is enabled or not) = show as connected
            proxyHost.isNotEmpty() -> ProxyState.CONNECTED
            // No force, no proxy configured = not configured
            else -> ProxyState.NOT_CONFIGURED
        }
        updateState(state)
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

    private fun showLeakWarningAnimation() {
        // Pulse animation for leak warning
        pulseAnimator = ObjectAnimator.ofFloat(statusIcon, "alpha", 0.4f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        statusIcon.scaleX = 1f
        statusIcon.scaleY = 1f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimations()
    }
}

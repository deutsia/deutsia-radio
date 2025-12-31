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
    private val statusDetail: TextView
    private val statusContainer: View
    private var pulseAnimator: ObjectAnimator? = null

    private var showText: Boolean = true
    private var compactMode: Boolean = false
    private var onClickAction: (() -> Unit)? = null

    /**
     * Custom proxy connection states
     */
    enum class ProxyState {
        CONNECTED,       // Proxy is configured (settings saved)
        NOT_CONFIGURED,  // No proxy configured
        LEAK_WARNING,    // Force custom proxy enabled but proxy not configured (legacy name)
        PROXY_REQUIRED   // Force custom proxy enabled but proxy not configured (streams blocked)
    }

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_custom_proxy_status, this, true)
        statusIcon = view.findViewById(R.id.customProxyStatusIcon)
        statusText = view.findViewById(R.id.customProxyStatusText)
        statusDetail = view.findViewById(R.id.customProxyStatusDetail)
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
        statusDetail.visibility = if (compact) View.GONE else View.VISIBLE
    }

    fun setShowText(show: Boolean) {
        showText = show
        if (!compactMode) {
            statusText.visibility = if (show) View.VISIBLE else View.GONE
            statusDetail.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    fun updateState(state: ProxyState, protocol: String = "", port: Int = 0) {
        stopAnimations()

        when (state) {
            ProxyState.CONNECTED -> {
                statusIcon.setImageResource(R.drawable.ic_proxy_custom_on)
                statusIcon.alpha = 1f
                // Main status text - using neutral color to match Tor section style
                statusText.text = context.getString(R.string.custom_proxy_status_connected)
                // Use theme attribute for colorOnSurface (neutral, not green)
                val colorOnSurface = com.google.android.material.R.attr.colorOnSurface
                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(colorOnSurface, typedValue, true)
                statusText.setTextColor(typedValue.data)

                // Detail text with protocol and port (matching Tor format: "SOCKS port: 9050")
                statusDetail.text = if (protocol.isNotEmpty() && port > 0) {
                    "$protocol port: $port"
                } else {
                    context.getString(R.string.custom_proxy_status_configured)
                }
                // Use theme attribute for colorOnSurfaceVariant (neutral, not green)
                val colorOnSurfaceVariant = com.google.android.material.R.attr.colorOnSurfaceVariant
                context.theme.resolveAttribute(colorOnSurfaceVariant, typedValue, true)
                statusDetail.setTextColor(typedValue.data)

                contentDescription = context.getString(R.string.custom_proxy_configured_description)
                showConnectedAnimation()
            }
            ProxyState.NOT_CONFIGURED -> {
                statusIcon.setImageResource(R.drawable.ic_proxy_custom_off)
                statusIcon.alpha = 1f
                statusText.text = context.getString(R.string.custom_proxy_status_not_configured)
                statusText.setTextColor(context.getColor(R.color.tor_disconnected))
                statusDetail.text = context.getString(R.string.custom_proxy_status_no_proxy)
                statusDetail.setTextColor(context.getColor(R.color.tor_disconnected))
                contentDescription = context.getString(R.string.custom_proxy_not_configured_description)
            }
            ProxyState.LEAK_WARNING, ProxyState.PROXY_REQUIRED -> {
                // Use orange (caution) instead of red (error) because streams are blocked,
                // not leaking - the user is protected, just unable to stream without config
                statusIcon.setImageResource(R.drawable.ic_proxy_custom_off)
                statusIcon.alpha = 1f
                statusText.text = context.getString(R.string.custom_proxy_status_proxy_required)
                statusText.setTextColor(context.getColor(R.color.proxy_blocked))
                statusDetail.text = context.getString(R.string.custom_proxy_status_proxy_required_detail)
                statusDetail.setTextColor(context.getColor(R.color.proxy_blocked))
                contentDescription = context.getString(R.string.custom_proxy_status_proxy_required_description)
                showProxyRequiredAnimation()
            }
        }
    }

    /**
     * Update state based on current proxy configuration
     */
    fun updateStateFromConfig(forceEnabled: Boolean, proxyHost: String, protocol: String = "", port: Int = 0) {
        val state = when {
            // Force enabled but no proxy configured = streams blocked (not leaking)
            forceEnabled && proxyHost.isEmpty() -> ProxyState.PROXY_REQUIRED
            // Proxy configured (whether force is enabled or not) = show as connected
            proxyHost.isNotEmpty() -> ProxyState.CONNECTED
            // No force, no proxy configured = not configured
            else -> ProxyState.NOT_CONFIGURED
        }
        updateState(state, protocol, port)
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

    /**
     * Gentle pulse animation for "Proxy Required" state.
     * Uses slower, subtler animation since this is a caution (not error) state.
     */
    private fun showProxyRequiredAnimation() {
        pulseAnimator = ObjectAnimator.ofFloat(statusIcon, "alpha", 0.6f, 1f).apply {
            duration = 1000
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

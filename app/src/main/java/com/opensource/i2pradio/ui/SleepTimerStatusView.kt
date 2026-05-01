package com.opensource.i2pradio.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.opensource.i2pradio.R
import com.opensource.i2pradio.util.SleepTimerUtils

/**
 * Compact sleep timer indicator for the toolbar. Shows the remaining time
 * as a countdown and ticks once a second while the timer is active. The
 * view collapses to GONE when no timer is set, so it takes no toolbar
 * space in the common case.
 *
 * The remaining time is supplied externally (typically from
 * RadioService.getSleepTimerRemainingMillis() or the BROADCAST_SLEEP_TIMER_STATE_CHANGED
 * intent) so this view is independent of how the timer is scheduled.
 */
class SleepTimerStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val statusText: TextView
    private val statusContainer: View

    private var endTimeMillis: Long = 0L
    private var onClickAction: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (refreshLabel()) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_sleep_timer_status, this, true)
        statusText = view.findViewById(R.id.sleepTimerStatusText)
        statusContainer = view.findViewById(R.id.sleepTimerStatusContainer)

        statusContainer.setOnClickListener {
            onClickAction?.invoke()
        }
        statusContainer.isClickable = true
        statusContainer.isFocusable = true

        visibility = View.GONE
    }

    fun setOnSleepTimerClickListener(action: () -> Unit) {
        onClickAction = action
    }

    /**
     * Update the countdown from the remaining millis reported by the service.
     * Stores the absolute end time so the per-second tick stays drift-free
     * even if the runnable is delayed.
     */
    fun updateRemaining(remainingMs: Long) {
        handler.removeCallbacks(tickRunnable)
        if (remainingMs > 0L) {
            endTimeMillis = System.currentTimeMillis() + remainingMs
            visibility = View.VISIBLE
            refreshLabel()
            handler.postDelayed(tickRunnable, 1000L)
        } else {
            endTimeMillis = 0L
            visibility = View.GONE
        }
    }

    private fun refreshLabel(): Boolean {
        val remaining = endTimeMillis - System.currentTimeMillis()
        if (remaining <= 0L) {
            visibility = View.GONE
            endTimeMillis = 0L
            return false
        }
        statusText.text = SleepTimerUtils.formatCountdown(remaining)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tickRunnable)
    }
}

package com.opensource.i2pradio.ui

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import coil.load
import com.google.android.material.button.MaterialButton
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.RadioStation

class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val coverImage: ImageView
    private val stationName: TextView
    private val genreText: TextView
    private val playPauseButton: MaterialButton
    private val closeButton: MaterialButton

    private var currentStation: RadioStation? = null
    private var isPlaying: Boolean = false
    private var onClickListener: (() -> Unit)? = null
    private var onCloseListener: (() -> Unit)? = null
    private var onPlayPauseToggleListener: ((Boolean) -> Unit)? = null
    private var previousPlayingState: Boolean? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)

        coverImage = findViewById(R.id.miniPlayerCoverImage)
        stationName = findViewById(R.id.miniPlayerStationName)
        genreText = findViewById(R.id.miniPlayerGenre)
        playPauseButton = findViewById(R.id.miniPlayerPlayPause)
        closeButton = findViewById(R.id.miniPlayerClose)

        // Click whole view to go to Now Playing with expand animation
        setOnClickListener {
            // Scale up animation for expand effect
            animate()
                .scaleX(1.02f)
                .scaleY(1.02f)
                .setDuration(100)
                .withEndAction {
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(0f)
                        .setDuration(150)
                        .withEndAction {
                            onClickListener?.invoke()
                        }
                        .start()
                }
                .start()
        }

        playPauseButton.setOnClickListener {
            togglePlayPause()
        }

        closeButton.setOnClickListener {
            onCloseListener?.invoke()
        }
    }

    fun setOnCloseListener(listener: () -> Unit) {
        onCloseListener = listener
    }

    fun setOnPlayPauseToggleListener(listener: (Boolean) -> Unit) {
        onPlayPauseToggleListener = listener
    }

    fun setStation(station: RadioStation?) {
        currentStation = station

        if (station == null) {
            // Fade out animation
            animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    visibility = GONE
                }
                .start()
            return
        }

        // Always ensure we're visible when setting a station
        // Cancel any pending animations first
        animate().cancel()

        if (visibility != VISIBLE || alpha < 1f) {
            alpha = 0f
            visibility = VISIBLE
            // Slide up and fade in animation
            translationY = 50f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        stationName.text = station.name
        val proxyIndicator = if (station.useProxy) " • I2P" else ""
        genreText.text = "${station.genre}$proxyIndicator"

        if (station.coverArtUri != null) {
            coverImage.load(station.coverArtUri) {
                crossfade(true)
                placeholder(R.drawable.ic_radio)
                error(R.drawable.ic_radio)
            }
        } else {
            coverImage.setImageResource(R.drawable.ic_radio)
        }
    }

    fun setPlayingState(playing: Boolean) {
        // Only animate if state actually changed
        if (previousPlayingState != null && previousPlayingState != playing) {
            try {
                val animDrawable = if (playing) {
                    AnimatedVectorDrawableCompat.create(context, R.drawable.avd_play_to_pause)
                } else {
                    AnimatedVectorDrawableCompat.create(context, R.drawable.avd_pause_to_play)
                }

                animDrawable?.let {
                    playPauseButton.icon = it
                    it.start()
                } ?: run {
                    // Fallback to static icon if AVD creation fails
                    playPauseButton.setIconResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                }
            } catch (e: Exception) {
                // Fallback to static icon on AVD inflation error (e.g., path morph issues on some Android versions)
                android.util.Log.w("MiniPlayerView", "AVD animation failed, using static icon", e)
                playPauseButton.setIconResource(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        } else {
            // Initial state - no animation
            playPauseButton.setIconResource(
                if (playing) R.drawable.ic_pause
                else R.drawable.ic_play
            )
        }

        isPlaying = playing
        previousPlayingState = playing
    }

    fun setOnMiniPlayerClickListener(listener: () -> Unit) {
        onClickListener = listener
    }

    /**
     * Show mini player with fade-in animation (used when returning from Now Playing)
     */
    fun showWithAnimation() {
        if (currentStation != null) {
            animate().cancel()
            if (visibility != VISIBLE) {
                alpha = 0f
                translationY = 30f
                visibility = VISIBLE
            }
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /**
     * Hide mini player without animation (used when navigating to Now Playing)
     */
    fun hideForNowPlaying() {
        animate().cancel()
        alpha = 0f
        translationY = 30f
    }

    private fun togglePlayPause() {
        val station = currentStation ?: return

        val newPlayingState = !isPlaying

        if (isPlaying) {
            val intent = Intent(context, RadioService::class.java).apply {
                action = RadioService.ACTION_PAUSE
            }
            context.startService(intent)
        } else {
            val intent = Intent(context, RadioService::class.java).apply {
                action = RadioService.ACTION_PLAY
                putExtra("stream_url", station.streamUrl)
                putExtra("proxy_host", if (station.useProxy) station.proxyHost else "")
                putExtra("proxy_port", station.proxyPort)
            }
            context.startService(intent)
        }

        // Notify listener to update ViewModel - this triggers the animation via setPlayingState
        onPlayPauseToggleListener?.invoke(newPlayingState)
    }
}
package com.opensource.i2pradio.ui

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.RadioStation

class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val card: MaterialCardView
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

        card = findViewById(R.id.miniPlayerCard)
        coverImage = findViewById(R.id.miniPlayerCoverImage)
        stationName = findViewById(R.id.miniPlayerStationName)
        genreText = findViewById(R.id.miniPlayerGenre)
        playPauseButton = findViewById(R.id.miniPlayerPlayPause)
        closeButton = findViewById(R.id.miniPlayerClose)

        // Click the card to go to Now Playing with expand animation
        card.setOnClickListener {
            // Scale up animation for expand effect
            card.animate()
                .scaleX(1.02f)
                .scaleY(1.02f)
                .setDuration(100)
                .withEndAction {
                    card.animate()
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
            card.animate()
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
        card.animate().cancel()

        if (visibility != VISIBLE || card.alpha < 1f) {
            card.alpha = 0f
            visibility = VISIBLE
            // Slide up and fade in animation
            card.translationY = 50f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        stationName.text = station.name
        val proxyIndicator = if (station.useProxy) " • I2P" else ""
        genreText.text = "${station.genre}$proxyIndicator"

        // Always use Coil to load images to properly clear cached state
        // when switching between stations with/without cover art
        coverImage.load(station.coverArtUri ?: R.drawable.ic_radio) {
            crossfade(true)
            placeholder(R.drawable.ic_radio)
            error(R.drawable.ic_radio)
        }
    }

    fun setPlayingState(playing: Boolean) {
        val newIconRes = if (playing) R.drawable.ic_pause else R.drawable.ic_play

        // Only animate if state actually changed
        if (previousPlayingState != null && previousPlayingState != playing) {
            // Simple fade animation for icon transition
            playPauseButton.animate()
                .alpha(0f)
                .setDuration(100)
                .withEndAction {
                    playPauseButton.setIconResource(newIconRes)
                    playPauseButton.animate()
                        .alpha(1f)
                        .setDuration(100)
                        .start()
                }
                .start()
        } else {
            // Initial state - no animation
            playPauseButton.setIconResource(newIconRes)
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
            card.animate().cancel()
            // Always reset state before animating to ensure proper display
            if (visibility != VISIBLE || card.alpha < 1f) {
                card.alpha = 0f
                card.translationY = 30f
                visibility = VISIBLE
            }
            card.animate()
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
        card.animate().cancel()
        card.alpha = 0f
        card.translationY = 30f
        visibility = INVISIBLE
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
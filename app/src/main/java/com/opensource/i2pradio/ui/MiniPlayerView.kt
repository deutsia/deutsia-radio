package com.opensource.i2pradio.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import coil.request.CachePolicy
import com.opensource.i2pradio.util.loadSecure
import com.opensource.i2pradio.util.loadSecurePrivacy
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.ProxyType
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
    private val likeButton: MaterialButton
    private val progressIndicator: LinearProgressIndicator

    private var currentStation: RadioStation? = null
    private var isPlaying: Boolean = false
    private var onClickListener: (() -> Unit)? = null
    private var onCloseListener: (() -> Unit)? = null
    private var onPlayPauseToggleListener: ((Boolean) -> Unit)? = null
    private var onLikeToggleListener: ((RadioStation) -> Unit)? = null
    private var previousPlayingState: Boolean? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)

        card = findViewById(R.id.miniPlayerCard)
        coverImage = findViewById(R.id.miniPlayerCoverImage)
        stationName = findViewById(R.id.miniPlayerStationName)
        genreText = findViewById(R.id.miniPlayerGenre)
        playPauseButton = findViewById(R.id.miniPlayerPlayPause)
        closeButton = findViewById(R.id.miniPlayerClose)
        likeButton = findViewById(R.id.miniPlayerLikeButton)
        progressIndicator = findViewById(R.id.miniPlayerProgress)

        // Click the card to go to Now Playing with smooth expand animation
        card.setOnClickListener {
            animateExpand()
        }

        playPauseButton.setOnClickListener {
            togglePlayPause()
        }

        closeButton.setOnClickListener {
            onCloseListener?.invoke()
        }

        likeButton.setOnClickListener {
            currentStation?.let { station ->
                onLikeToggleListener?.invoke(station)
            }
        }
    }

    fun setOnCloseListener(listener: () -> Unit) {
        onCloseListener = listener
    }

    fun setOnPlayPauseToggleListener(listener: (Boolean) -> Unit) {
        onPlayPauseToggleListener = listener
    }

    fun setOnLikeToggleListener(listener: (RadioStation) -> Unit) {
        onLikeToggleListener = listener
    }

    fun updateLikeState(isLiked: Boolean) {
        val iconRes = if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        val tintColor = if (isLiked) {
            context.getColor(com.google.android.material.R.color.design_default_color_error)
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        likeButton.setIconResource(iconRes)
        if (isLiked) {
            likeButton.setIconTintResource(com.google.android.material.R.color.design_default_color_error)
        } else {
            likeButton.iconTint = android.content.res.ColorStateList.valueOf(
                context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant)).use {
                    it.getColor(0, 0)
                }
            )
        }
    }

    /**
     * Update cover art with cache invalidation for real-time updates.
     * Uses loadSecure to route remote URLs through Tor when Force Tor is enabled.
     * For privacy stations (Tor/I2P), uses loadSecurePrivacy to route through Tor when available.
     */
    fun updateCoverArt(coverArtUri: String?) {
        if (coverArtUri != null) {
            // Start with fitCenter for placeholder (scales vector up to fill container), switch to centerCrop on successful load
            coverImage.scaleType = ImageView.ScaleType.FIT_CENTER
            // Use privacy loader for Tor/I2P stations to route cover art through Tor
            val isPrivacyStation = currentStation?.getProxyTypeEnum().let {
                it == ProxyType.TOR || it == ProxyType.I2P
            }
            val imageLoadBuilder: coil.request.ImageRequest.Builder.() -> Unit = {
                crossfade(true)
                // Force refresh by disabling cache for this request
                memoryCachePolicy(CachePolicy.WRITE_ONLY)
                diskCachePolicy(CachePolicy.WRITE_ONLY)
                placeholder(R.drawable.ic_radio)
                error(R.drawable.ic_radio)
                listener(
                    onStart = {
                        // Ensure fitCenter during placeholder phase so vector icon fills container
                        coverImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    onSuccess = { _, _ ->
                        // Real bitmap loaded - use centerCrop for best appearance
                        coverImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    },
                    onError = { _, _ ->
                        // Error loading - use fitCenter so vector placeholder fills container
                        coverImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                )
            }
            if (isPrivacyStation) {
                coverImage.loadSecurePrivacy(coverArtUri, imageLoadBuilder)
            } else {
                coverImage.loadSecure(coverArtUri, imageLoadBuilder)
            }
        } else {
            // No cover art - use fitCenter so vector placeholder fills container
            coverImage.scaleType = ImageView.ScaleType.FIT_CENTER
            // Force reload to get fresh drawable with current theme colors (Material You)
            coverImage.setImageDrawable(null)
            coverImage.load(R.drawable.ic_radio) {
                crossfade(true)
                // Disable all caching to force fresh drawable with current theme
                memoryCachePolicy(CachePolicy.DISABLED)
                diskCachePolicy(CachePolicy.DISABLED)
            }
        }
        // Update the current station's cover art reference
        currentStation = currentStation?.copy(coverArtUri = coverArtUri)
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
        val proxyIndicator = if (station.useProxy) {
            when (station.getProxyTypeEnum()) {
                ProxyType.I2P -> " • I2P"
                ProxyType.TOR -> " • Tor"
                ProxyType.CUSTOM -> " • Custom"
                ProxyType.NONE -> ""
            }
        } else ""
        genreText.text = "${station.genre}$proxyIndicator"

        // Update like button state
        updateLikeState(station.isLiked)

        // Handle cover art update properly - clear old image when switching stations
        // Use loadSecure to route remote URLs through Tor when Force Tor is enabled
        // For privacy stations (Tor/I2P), use loadSecurePrivacy to route through Tor when available
        if (station.coverArtUri != null) {
            // Start with fitCenter for placeholder (scales vector up to fill container), switch to centerCrop on successful load
            coverImage.scaleType = ImageView.ScaleType.FIT_CENTER
            val isPrivacyStation = station.getProxyTypeEnum().let {
                it == ProxyType.TOR || it == ProxyType.I2P
            }
            val imageLoadBuilder: coil.request.ImageRequest.Builder.() -> Unit = {
                crossfade(true)
                placeholder(R.drawable.ic_radio)
                error(R.drawable.ic_radio)
                listener(
                    onStart = {
                        // Ensure fitCenter during placeholder phase so vector icon fills container
                        coverImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    onSuccess = { _, _ ->
                        // Real bitmap loaded - use centerCrop for best appearance
                        coverImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    },
                    onError = { _, _ ->
                        // Error loading - use fitCenter so vector placeholder fills container
                        coverImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                )
            }
            if (isPrivacyStation) {
                coverImage.loadSecurePrivacy(station.coverArtUri, imageLoadBuilder)
            } else {
                coverImage.loadSecure(station.coverArtUri, imageLoadBuilder)
            }
        } else {
            // No cover art - use fitCenter so vector placeholder fills container
            coverImage.scaleType = ImageView.ScaleType.FIT_CENTER
            // Explicitly clear any cached/loading state and set default drawable
            // Force reload to get fresh drawable with current theme colors (Material You)
            coverImage.setImageDrawable(null)
            coverImage.load(R.drawable.ic_radio) {
                crossfade(true)
                // Disable all caching to force fresh drawable with current theme
                memoryCachePolicy(CachePolicy.DISABLED)
                diskCachePolicy(CachePolicy.DISABLED)
            }
        }

        // Don't change progress indicator visibility here - let setBufferingState() handle it
        // The RadioService will broadcast buffering state which will properly show/hide the indicator
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

    /**
     * Set the buffering/loading state of the miniplayer.
     * Shows or hides the progress indicator.
     */
    fun setBufferingState(buffering: Boolean) {
        progressIndicator.visibility = if (buffering) VISIBLE else GONE
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

    /**
     * Animate expand effect when clicking on mini player to go to Now Playing.
     * Creates a smooth scale-up and fade-out animation that simulates expansion.
     */
    private fun animateExpand() {
        // Cancel any pending animations
        card.animate().cancel()

        // Create a smooth expand animation using AnimatorSet for precise control
        val scaleX = ObjectAnimator.ofFloat(card, "scaleX", 1f, 1.05f, 1.02f)
        val scaleY = ObjectAnimator.ofFloat(card, "scaleY", 1f, 1.05f, 1.02f)
        val alpha = ObjectAnimator.ofFloat(card, "alpha", 1f, 0.8f, 0f)
        val translationY = ObjectAnimator.ofFloat(card, "translationY", 0f, -20f)

        val animatorSet = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha, translationY)
            duration = 250
            interpolator = AccelerateDecelerateInterpolator()
        }

        animatorSet.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Reset card state for when it reappears
                card.scaleX = 1f
                card.scaleY = 1f
                card.translationY = 0f
                // Navigate to Now Playing
                onClickListener?.invoke()
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })

        animatorSet.start()
    }

    private fun togglePlayPause() {
        val station = currentStation ?: return

        if (isPlaying) {
            // Pause - can use regular startService since service is already running
            val intent = Intent(context, RadioService::class.java).apply {
                action = RadioService.ACTION_PAUSE
            }
            context.startService(intent)
            // Notify listener to update ViewModel
            onPlayPauseToggleListener?.invoke(false)
        } else {
            // Play - use startForegroundService for Android 8+ compatibility
            val proxyType = station.getProxyTypeEnum()
            val intent = Intent(context, RadioService::class.java).apply {
                action = RadioService.ACTION_PLAY
                putExtra("stream_url", station.streamUrl)
                putExtra("station_name", station.name)
                putExtra("proxy_host", if (station.useProxy) station.proxyHost else "")
                putExtra("proxy_port", station.proxyPort)
                putExtra("proxy_type", proxyType.name)
                putExtra("cover_art_uri", station.coverArtUri)
            }
            ContextCompat.startForegroundService(context, intent)
            // Don't update playing state here - wait for service to confirm playback started
            // The service will broadcast PLAYBACK_STATE_CHANGED when ready
        }
    }
}
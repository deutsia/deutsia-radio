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

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)

        coverImage = findViewById(R.id.miniPlayerCoverImage)
        stationName = findViewById(R.id.miniPlayerStationName)
        genreText = findViewById(R.id.miniPlayerGenre)
        playPauseButton = findViewById(R.id.miniPlayerPlayPause)
        closeButton = findViewById(R.id.miniPlayerClose)

        // Click whole view to go to Now Playing
        setOnClickListener {
            onClickListener?.invoke()
        }

        playPauseButton.setOnClickListener {
            togglePlayPause()
        }

        closeButton.setOnClickListener {
            stopPlayback()
        }
    }

    fun setStation(station: RadioStation?) {
        currentStation = station

        if (station == null) {
            // Fade out animation
            animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    visibility = GONE
                }
                .start()
            return
        }

        if (visibility == GONE) {
            alpha = 0f
            visibility = VISIBLE
            animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        }

        stationName.text = station.name
        val proxyIndicator = if (station.useProxy) " • I2P" else ""
        genreText.text = "${station.genre}$proxyIndicator"

        if (station.coverArtUri != null) {
            coverImage.load(station.coverArtUri) {
                crossfade(true)
                placeholder(android.R.drawable.ic_dialog_info)
                error(android.R.drawable.ic_dialog_info)
            }
        } else {
            coverImage.setImageResource(android.R.drawable.ic_dialog_info)
        }
    }

    fun setPlayingState(playing: Boolean) {
        isPlaying = playing
        playPauseButton.setIconResource(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    fun setOnMiniPlayerClickListener(listener: () -> Unit) {
        onClickListener = listener
    }

    private fun togglePlayPause() {
        val station = currentStation ?: return

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
    }

    private fun stopPlayback() {
        val intent = Intent(context, RadioService::class.java).apply {
            action = RadioService.ACTION_STOP
        }
        context.startService(intent)
    }
}
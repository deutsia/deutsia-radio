package com.opensource.i2pradio.ui

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService

class NowPlayingFragment : Fragment() {
    private val viewModel: RadioViewModel by activityViewModels()

    private lateinit var coverArt: ImageView
    private lateinit var stationName: TextView
    private lateinit var genreText: TextView
    private lateinit var metadataText: TextView
    private lateinit var playPauseButton: FloatingActionButton
    private lateinit var recordButton: MaterialButton
    private lateinit var volumeButton: FloatingActionButton
    private lateinit var audioManager: AudioManager
    private lateinit var emptyState: View
    private lateinit var playingContent: View
    private lateinit var bufferingIndicator: CircularProgressIndicator
    private var recordingIndicator: LinearLayout? = null
    private var recordingTimeText: TextView? = null

    private val recordingHandler = Handler(Looper.getMainLooper())
    private var previousPlayingState: Boolean? = null
    private var previousStationId: Long? = null
    private lateinit var infoContainer: View
    private lateinit var controlsContainer: View

    // Recording timer runnable - uses ViewModel state for elapsed time calculation
    private val recordingUpdateRunnable = object : Runnable {
        override fun run() {
            val recordingState = viewModel.recordingState.value
            if (recordingState?.isRecording == true) {
                val elapsed = viewModel.getRecordingElapsedTime()
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 1000) / 60
                recordingTimeText?.text = String.format("%02d:%02d", minutes, seconds)
                recordingHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_now_playing, container, false)

        coverArt = view.findViewById(R.id.nowPlayingCoverArt)
        stationName = view.findViewById(R.id.nowPlayingStationName)
        genreText = view.findViewById(R.id.nowPlayingGenre)
        metadataText = view.findViewById(R.id.nowPlayingMetadata)
        playPauseButton = view.findViewById(R.id.playPauseButton)
        recordButton = view.findViewById(R.id.recordButton)
        volumeButton = view.findViewById(R.id.volumeButton)
        emptyState = view.findViewById(R.id.emptyPlayingState)
        bufferingIndicator = view.findViewById(R.id.bufferingIndicator)
        playingContent = view.findViewById(R.id.nowPlayingCoverCard)
        recordingIndicator = view.findViewById(R.id.recordingIndicator)
        recordingTimeText = view.findViewById(R.id.recordingTime)

        // Initialize audio manager and volume control
        audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

        // Volume button click shows volume dialog
        volumeButton.setOnClickListener {
            showVolumeDialog()
        }

        // Back button
        val backButton = view.findViewById<MaterialButton>(R.id.backButton)
        backButton.setOnClickListener {
            (activity as? MainActivity)?.switchToRadiosTab()
        }

        // Get references to containers
        infoContainer = view.findViewById(R.id.nowPlayingInfoContainer)
        controlsContainer = view.findViewById(R.id.nowPlayingControlsContainer)

        // Observe current station
        viewModel.currentStation.observe(viewLifecycleOwner) { station ->
            if (station == null) {
                // Fade out content
                playingContent.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        playingContent.visibility = View.GONE
                    }
                    .start()
                infoContainer.animate().alpha(0f).setDuration(200).start()
                controlsContainer.animate().alpha(0f).setDuration(200).start()

                // Show empty state
                emptyState.alpha = 0f
                emptyState.visibility = View.VISIBLE
                emptyState.animate().alpha(1f).setDuration(300).start()

                // Stop recording if station is cleared (handled by ViewModel)
                previousStationId = null
            } else {
                val isNewStation = previousStationId != station.id
                previousStationId = station.id

                // Hide empty state
                emptyState.visibility = View.GONE

                // Show content with animation if this is a new station
                if (isNewStation) {
                    // Animate cover card
                    playingContent.alpha = 0f
                    playingContent.scaleX = 0.9f
                    playingContent.scaleY = 0.9f
                    playingContent.visibility = View.VISIBLE
                    playingContent.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(350)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                    // Animate info container with delay
                    infoContainer.alpha = 0f
                    infoContainer.translationY = 20f
                    infoContainer.visibility = View.VISIBLE
                    infoContainer.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay(100)
                        .setDuration(300)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                    // Animate controls with staggered delay
                    controlsContainer.alpha = 0f
                    controlsContainer.translationY = 30f
                    controlsContainer.visibility = View.VISIBLE
                    controlsContainer.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay(200)
                        .setDuration(300)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    playingContent.visibility = View.VISIBLE
                    infoContainer.visibility = View.VISIBLE
                    controlsContainer.visibility = View.VISIBLE
                }

                stationName.text = station.name

                val proxyIndicator = if (station.useProxy) " • I2P" else ""
                genreText.text = "${station.genre}$proxyIndicator"

                // Handle cover art update properly - clear old image when switching stations
                if (station.coverArtUri != null) {
                    coverArt.load(station.coverArtUri) {
                        crossfade(true)
                        placeholder(R.drawable.ic_radio)
                        error(R.drawable.ic_radio)
                    }
                } else {
                    // Explicitly clear any cached/loading state and set default drawable
                    coverArt.load(R.drawable.ic_radio) {
                        crossfade(true)
                    }
                }

                metadataText.visibility = View.GONE
            }
        }

        // Observe playing state with animated transition
        viewModel.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            updatePlayPauseButton(isPlaying)
        }

        // Play/Pause button with bounce animation
        playPauseButton.setOnClickListener {
            // Bounce animation
            playPauseButton.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(80)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    playPauseButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(OvershootInterpolator(2f))
                        .start()
                }
                .start()

            val isPlaying = viewModel.isPlaying.value ?: false
            val station = viewModel.getCurrentStation()

            if (isPlaying) {
                val intent = Intent(requireContext(), RadioService::class.java).apply {
                    action = RadioService.ACTION_PAUSE
                }
                requireContext().startService(intent)
                viewModel.setPlaying(false)
            } else if (station != null) {
                val intent = Intent(requireContext(), RadioService::class.java).apply {
                    action = RadioService.ACTION_PLAY
                    putExtra("stream_url", station.streamUrl)
                    putExtra("proxy_host", if (station.useProxy) station.proxyHost else "")
                    putExtra("proxy_port", station.proxyPort)
                }
                requireContext().startService(intent)
                viewModel.setPlaying(true)
            }
        }

        // Record button - uses ViewModel for state management
        recordButton.setOnClickListener {
            val recordingState = viewModel.recordingState.value
            if (recordingState?.isRecording == true) {
                viewModel.stopRecording()
                Toast.makeText(requireContext(), "Recording saved to Music folder", Toast.LENGTH_SHORT).show()
            } else {
                if (viewModel.startRecording()) {
                    Toast.makeText(requireContext(), "Recording started", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "No station playing", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Observe recording state to update UI (survives configuration changes)
        viewModel.recordingState.observe(viewLifecycleOwner) { state ->
            updateRecordingUI(state)
        }

        return view
    }

    /**
     * Updates the recording UI based on the current recording state.
     * This is called whenever the recording state changes and ensures the UI
     * correctly reflects the state after configuration changes (e.g., screen rotation).
     */
    private fun updateRecordingUI(state: RecordingState) {
        if (state.isRecording) {
            recordButton.setIconResource(R.drawable.ic_stop_record)
            recordButton.setIconTintResource(android.R.color.white)
            recordingIndicator?.visibility = View.VISIBLE
            // Calculate and display current elapsed time
            val elapsed = viewModel.getRecordingElapsedTime()
            val seconds = (elapsed / 1000) % 60
            val minutes = (elapsed / 1000) / 60
            recordingTimeText?.text = String.format("%02d:%02d", minutes, seconds)
            // Start the update runnable if not already running
            recordingHandler.removeCallbacks(recordingUpdateRunnable)
            recordingHandler.post(recordingUpdateRunnable)
        } else {
            recordButton.setIconResource(R.drawable.ic_fiber_manual_record)
            // Remove tint to show the icon's original red color
            recordButton.iconTint = null
            recordingIndicator?.visibility = View.GONE
            recordingHandler.removeCallbacks(recordingUpdateRunnable)
        }
    }

    private fun showVolumeDialog() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val slider = Slider(requireContext()).apply {
            valueFrom = 0f
            valueTo = maxVolume.toFloat()
            value = currentVolume.toFloat()
            stepSize = 1f

            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.toInt(), 0)
                }
            }
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
            addView(slider)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Volume")
            .setView(container)
            .setPositiveButton("Done", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recordingHandler.removeCallbacks(recordingUpdateRunnable)
        previousPlayingState = null
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        val newIconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play

        // Only animate if state actually changed
        if (previousPlayingState != null && previousPlayingState != isPlaying) {
            // Simple fade animation for icon transition
            playPauseButton.animate()
                .alpha(0f)
                .setDuration(100)
                .withEndAction {
                    playPauseButton.setImageResource(newIconRes)
                    playPauseButton.animate()
                        .alpha(1f)
                        .setDuration(100)
                        .start()
                }
                .start()
        } else {
            // Initial state - no animation
            playPauseButton.setImageResource(newIconRes)
        }

        previousPlayingState = isPlaying
    }
}

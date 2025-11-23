package com.opensource.i2pradio.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.load
import coil.request.CachePolicy
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NowPlayingFragment : Fragment() {
    private val viewModel: RadioViewModel by activityViewModels()
    private lateinit var repository: RadioRepository

    private lateinit var rootContainer: ConstraintLayout
    private lateinit var coverArt: ImageView
    private lateinit var stationName: TextView
    private lateinit var genreText: TextView
    private lateinit var metadataText: TextView
    private lateinit var streamInfoText: TextView
    private lateinit var likeButton: MaterialButton
    private lateinit var playPauseButton: FloatingActionButton
    private lateinit var recordButton: FloatingActionButton
    private lateinit var volumeButton: FloatingActionButton
    private lateinit var audioManager: AudioManager
    private lateinit var emptyState: View
    private lateinit var playingContent: View
    private lateinit var bufferingIndicator: CircularProgressIndicator
    private var recordingIndicator: MaterialCardView? = null
    private var recordingDot: View? = null
    private var recordingTimeText: TextView? = null

    private val recordingHandler = Handler(Looper.getMainLooper())
    private var previousPlayingState: Boolean? = null
    private var previousStationId: Long? = null
    private var isBuffering: Boolean = false
    private lateinit var infoContainer: View
    private lateinit var controlsContainer: View

    // Broadcast receiver for metadata and stream info updates
    private val metadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RadioService.BROADCAST_METADATA_CHANGED -> {
                    val metadata = intent.getStringExtra(RadioService.EXTRA_METADATA)
                    if (!metadata.isNullOrBlank()) {
                        metadataText.text = metadata
                        metadataText.visibility = View.VISIBLE
                    }
                }
                RadioService.BROADCAST_STREAM_INFO_CHANGED -> {
                    val bitrate = intent.getIntExtra(RadioService.EXTRA_BITRATE, 0)
                    val codec = intent.getStringExtra(RadioService.EXTRA_CODEC) ?: "Unknown"
                    updateStreamInfo(bitrate, codec)
                }
            }
        }
    }

    // Animation for recording dot blink
    private val blinkAnimation: AlphaAnimation by lazy {
        AlphaAnimation(1f, 0.3f).apply {
            duration = 500
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
    }

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

        repository = RadioRepository(requireContext())

        rootContainer = view.findViewById(R.id.rootContainer)
        coverArt = view.findViewById(R.id.nowPlayingCoverArt)
        stationName = view.findViewById(R.id.nowPlayingStationName)
        genreText = view.findViewById(R.id.nowPlayingGenre)
        metadataText = view.findViewById(R.id.nowPlayingMetadata)
        streamInfoText = view.findViewById(R.id.nowPlayingStreamInfo)
        likeButton = view.findViewById(R.id.likeButton)
        playPauseButton = view.findViewById(R.id.playPauseButton)
        recordButton = view.findViewById(R.id.recordButton)
        volumeButton = view.findViewById(R.id.volumeButton)
        emptyState = view.findViewById(R.id.emptyPlayingState)
        bufferingIndicator = view.findViewById(R.id.bufferingIndicator)
        playingContent = view.findViewById(R.id.nowPlayingCoverCard)
        recordingIndicator = view.findViewById(R.id.recordingIndicator)
        recordingDot = view.findViewById(R.id.recordingDot)
        recordingTimeText = view.findViewById(R.id.recordingTime)

        // Initialize audio manager and volume control
        audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

        // Register broadcast receiver for metadata updates
        val filter = IntentFilter().apply {
            addAction(RadioService.BROADCAST_METADATA_CHANGED)
            addAction(RadioService.BROADCAST_STREAM_INFO_CHANGED)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(metadataReceiver, filter)

        // Like button click handler
        likeButton.setOnClickListener {
            viewModel.getCurrentStation()?.let { station ->
                CoroutineScope(Dispatchers.IO).launch {
                    repository.toggleLike(station.id)
                    // Refresh the station to update UI
                    val updatedStation = repository.getStationById(station.id)
                    CoroutineScope(Dispatchers.Main).launch {
                        updatedStation?.let {
                            viewModel.setCurrentStation(it)
                            updateLikeButton(it.isLiked)
                        }
                    }
                }
            }
        }

        // Setup Edge-to-Edge: Handle window insets for status bar
        setupEdgeToEdge()

        // Enable marquee for metadata text
        metadataText.isSelected = true

        // Volume button click shows volume bottom sheet
        volumeButton.setOnClickListener {
            showVolumeBottomSheet()
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

                val proxyIndicator = if (station.useProxy) {
                    when (station.getProxyTypeEnum()) {
                        ProxyType.I2P -> " • I2P"
                        ProxyType.TOR -> " • Tor"
                        ProxyType.NONE -> ""
                    }
                } else ""
                genreText.text = "${station.genre}$proxyIndicator"

                // Update like button state
                updateLikeButton(station.isLiked)

                // Reset metadata and stream info for new station
                if (isNewStation) {
                    metadataText.visibility = View.GONE
                    streamInfoText.visibility = View.GONE
                }

                // Handle cover art update properly - switch scaleType based on content
                if (station.coverArtUri != null) {
                    coverArt.load(station.coverArtUri) {
                        crossfade(true)
                        memoryCachePolicy(CachePolicy.ENABLED)
                        placeholder(R.drawable.ic_radio)
                        error(R.drawable.ic_radio)
                        listener(
                            onSuccess = { _, _ ->
                                // Real bitmap loaded - use centerCrop for best appearance
                                coverArt.scaleType = ImageView.ScaleType.CENTER_CROP
                            },
                            onError = { _, _ ->
                                // Error loading - use centerInside for vector placeholder
                                coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                            }
                        )
                    }
                } else {
                    // No cover art - use centerInside for vector placeholder
                    coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
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
            // Change icon to stop square
            recordButton.setImageResource(R.drawable.ic_stop_record)
            // Get colors from the current theme context to reflect theme changes properly
            val ctx = requireContext()
            // Change icon tint to white for contrast
            recordButton.imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnError, android.graphics.Color.WHITE)
            )
            // Change FAB background to error container (red) for recording state
            recordButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorErrorContainer, android.graphics.Color.RED)
            )

            // Show recording indicator with animation
            recordingIndicator?.apply {
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(200).start()
            }

            // Start blinking animation on recording dot
            recordingDot?.startAnimation(blinkAnimation)

            // Calculate and display current elapsed time
            val elapsed = viewModel.getRecordingElapsedTime()
            val seconds = (elapsed / 1000) % 60
            val minutes = (elapsed / 1000) / 60
            recordingTimeText?.text = String.format("%02d:%02d", minutes, seconds)

            // Start the update runnable if not already running
            recordingHandler.removeCallbacks(recordingUpdateRunnable)
            recordingHandler.post(recordingUpdateRunnable)
        } else {
            // Restore record icon with default blue/primary color
            recordButton.setImageResource(R.drawable.ic_fiber_manual_record)
            // Get colors from the current theme context to reflect theme changes properly
            // Using requireContext() ensures we get colors from the recreated activity's theme
            val ctx = requireContext()
            recordButton.imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, android.graphics.Color.WHITE)
            )
            recordButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.BLUE)
            )

            // Stop blinking animation
            recordingDot?.clearAnimation()

            // Hide recording indicator with animation
            recordingIndicator?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                recordingIndicator?.visibility = View.GONE
            }?.start()

            recordingHandler.removeCallbacks(recordingUpdateRunnable)
        }
    }

    /**
     * Shows a Material 3 BottomSheet with a volume slider.
     * Better UX than an AlertDialog for volume control.
     */
    private fun showVolumeBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(requireContext())

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Create the content view programmatically
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 48)

            // Title
            val title = TextView(requireContext()).apply {
                text = "Volume"
                textSize = 20f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface))
                setPadding(16, 0, 0, 24)
            }
            addView(title)

            // Volume slider
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
            addView(slider)

            // Volume icon row
            val iconRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
                gravity = android.view.Gravity.CENTER_HORIZONTAL

                val volumeIcon = ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_volume)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(
                            this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    layoutParams = LinearLayout.LayoutParams(48, 48)
                }
                addView(volumeIcon)
            }
            addView(iconRow)
        }

        bottomSheetDialog.setContentView(container)
        bottomSheetDialog.show()
    }

    /**
     * Setup Edge-to-Edge: Apply window insets for proper status bar handling.
     * This ensures proper spacing on devices with notches, punch holes, etc.
     */
    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply top padding for status bar
            view.updatePadding(top = insets.top)
            windowInsets
        }
    }

    /**
     * Sets the buffering state and updates UI accordingly.
     * Disables play/pause button during buffering to prevent spam-clicking.
     */
    private fun setBufferingState(buffering: Boolean) {
        isBuffering = buffering
        bufferingIndicator.visibility = if (buffering) View.VISIBLE else View.GONE
        playPauseButton.isEnabled = !buffering
        playPauseButton.alpha = if (buffering) 0.5f else 1f
    }

    override fun onResume() {
        super.onResume()
        // Force refresh recording button colors when resuming (handles theme changes)
        // This ensures colors update properly when switching themes, as the LiveData
        // observer might not fire if the recording state hasn't changed
        viewModel.recordingState.value?.let { state ->
            updateRecordingUI(state)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recordingHandler.removeCallbacks(recordingUpdateRunnable)
        recordingDot?.clearAnimation()
        previousPlayingState = null
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(metadataReceiver)
    }

    private fun updateLikeButton(isLiked: Boolean) {
        val iconRes = if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        likeButton.setIconResource(iconRes)
        if (isLiked) {
            likeButton.setIconTintResource(com.google.android.material.R.color.design_default_color_error)
        } else {
            likeButton.iconTint = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    requireContext(), com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE
                )
            )
        }
    }

    private fun updateStreamInfo(bitrate: Int, codec: String) {
        if (bitrate > 0) {
            val bitrateKbps = bitrate / 1000
            streamInfoText.text = "$bitrateKbps kbps • $codec"
            streamInfoText.visibility = View.VISIBLE
        } else if (codec.isNotBlank() && codec != "Unknown") {
            streamInfoText.text = codec
            streamInfoText.visibility = View.VISIBLE
        }
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

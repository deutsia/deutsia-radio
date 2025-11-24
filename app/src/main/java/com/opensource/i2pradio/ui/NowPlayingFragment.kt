package com.opensource.i2pradio.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import com.opensource.i2pradio.audio.EqualizerManager
import android.os.IBinder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import com.opensource.i2pradio.util.loadSecure
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
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NowPlayingFragment : Fragment() {
    private val viewModel: RadioViewModel by activityViewModels()
    private lateinit var repository: RadioRepository
    private lateinit var radioBrowserRepository: RadioBrowserRepository

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
    private lateinit var equalizerButton: MaterialButton

    // Buffer bar UI elements
    private var bufferBarContainer: View? = null
    private var playbackElapsedTime: TextView? = null
    private var bufferProgressBar: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private var liveIndicator: TextView? = null

    // Volume control state (0.0 to 1.0 for player volume)
    private var savedVolume: Float = 1f
    private var isMuted: Boolean = false

    // Service binding for equalizer
    private var radioService: RadioService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioService.RadioBinder
            radioService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService = null
            serviceBound = false
        }
    }

    private val recordingHandler = Handler(Looper.getMainLooper())
    private var previousPlayingState: Boolean? = null
    private var previousStationId: Long? = null
    private var isBuffering: Boolean = false
    private lateinit var infoContainer: View
    private lateinit var controlsContainer: View

    // Broadcast receiver for metadata, stream info, playback state, recording updates, cover art, and time updates
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
                RadioService.BROADCAST_PLAYBACK_STATE_CHANGED -> {
                    val isBuffering = intent.getBooleanExtra(RadioService.EXTRA_IS_BUFFERING, false)
                    val isPlaying = intent.getBooleanExtra(RadioService.EXTRA_IS_PLAYING, false)
                    setBufferingState(isBuffering)
                    // Sync playing state from service to ViewModel
                    if (isPlaying != viewModel.isPlaying.value) {
                        viewModel.setPlaying(isPlaying)
                    }
                    // Show/hide buffer bar based on playing state
                    updateBufferBarVisibility(isPlaying)
                }
                RadioService.BROADCAST_RECORDING_ERROR -> {
                    val errorMessage = intent.getStringExtra(RadioService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                    // Reset recording state in ViewModel
                    viewModel.onRecordingError()
                    Toast.makeText(context, "Recording failed: $errorMessage", Toast.LENGTH_LONG).show()
                }
                RadioService.BROADCAST_RECORDING_COMPLETE -> {
                    val filePath = intent.getStringExtra(RadioService.EXTRA_FILE_PATH) ?: ""
                    val fileSize = intent.getLongExtra(RadioService.EXTRA_FILE_SIZE, 0L)
                    val sizeKB = fileSize / 1024
                    val fileName = filePath.substringAfterLast("/")
                    Toast.makeText(context, "Recording saved: $fileName (${sizeKB}KB)", Toast.LENGTH_LONG).show()
                }
                RadioService.BROADCAST_COVER_ART_CHANGED -> {
                    val coverArtUri = intent.getStringExtra(RadioService.EXTRA_COVER_ART_URI)
                    val stationId = intent.getLongExtra(RadioService.EXTRA_STATION_ID, -1L)
                    // Update cover art immediately
                    updateCoverArt(coverArtUri)
                    // Also notify ViewModel for other observers
                    viewModel.updateCoverArt(coverArtUri, stationId)
                }
                RadioService.BROADCAST_PLAYBACK_TIME_UPDATE -> {
                    val elapsedMs = intent.getLongExtra(RadioService.EXTRA_PLAYBACK_ELAPSED_MS, 0L)
                    val bufferedPositionMs = intent.getLongExtra(RadioService.EXTRA_BUFFERED_POSITION_MS, 0L)
                    val currentPositionMs = intent.getLongExtra(RadioService.EXTRA_CURRENT_POSITION_MS, 0L)
                    updatePlaybackTime(elapsedMs, bufferedPositionMs, currentPositionMs)
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
        radioBrowserRepository = RadioBrowserRepository(requireContext())

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
        equalizerButton = view.findViewById(R.id.equalizerButton)

        // Buffer bar UI elements
        bufferBarContainer = view.findViewById(R.id.bufferBarContainer)
        playbackElapsedTime = view.findViewById(R.id.playbackElapsedTime)
        bufferProgressBar = view.findViewById(R.id.bufferProgressBar)
        liveIndicator = view.findViewById(R.id.liveIndicator)

        // Initialize audio manager and volume control
        audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

        // Bind to RadioService to get audio session ID for equalizer
        val serviceIntent = Intent(requireContext(), RadioService::class.java)
        requireContext().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Register broadcast receiver for metadata, stream info, playback state, recording updates, cover art, and time
        val filter = IntentFilter().apply {
            addAction(RadioService.BROADCAST_METADATA_CHANGED)
            addAction(RadioService.BROADCAST_STREAM_INFO_CHANGED)
            addAction(RadioService.BROADCAST_PLAYBACK_STATE_CHANGED)
            addAction(RadioService.BROADCAST_RECORDING_ERROR)
            addAction(RadioService.BROADCAST_RECORDING_COMPLETE)
            addAction(RadioService.BROADCAST_COVER_ART_CHANGED)
            addAction(RadioService.BROADCAST_PLAYBACK_TIME_UPDATE)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(metadataReceiver, filter)

        // Like button click handler
        likeButton.setOnClickListener {
            viewModel.getCurrentStation()?.let { station ->
                CoroutineScope(Dispatchers.IO).launch {
                    // Check if this is a global radio (has radioBrowserUuid)
                    if (!station.radioBrowserUuid.isNullOrEmpty()) {
                        // For global radios, use RadioBrowserRepository which handles unsaved stations
                        val stationInfo = radioBrowserRepository.getStationInfoByUuid(station.radioBrowserUuid)
                        if (stationInfo != null && stationInfo.isLiked) {
                            // Station exists and is liked - unlike it
                            radioBrowserRepository.toggleLikeByUuid(station.radioBrowserUuid)
                        } else {
                            // Station doesn't exist or not liked - save and like it
                            // Convert to RadioBrowserStation format for saving
                            val radioBrowserStation = com.opensource.i2pradio.data.radiobrowser.RadioBrowserStation(
                                stationuuid = station.radioBrowserUuid,
                                name = station.name,
                                url = station.streamUrl,
                                urlResolved = station.streamUrl,
                                homepage = station.homepage ?: "",
                                favicon = station.coverArtUri ?: "",
                                tags = station.genre,
                                country = station.country ?: "",
                                countrycode = station.countryCode ?: "",
                                state = "",
                                language = "",
                                languagecodes = "",
                                votes = 0,
                                lastchangetime = "",
                                codec = station.codec ?: "",
                                bitrate = station.bitrate,
                                hls = false,
                                lastcheckok = true,
                                clickcount = 0,
                                clicktrend = 0,
                                sslError = false,
                                geoLat = null,
                                geoLong = null
                            )
                            radioBrowserRepository.saveStationAsLiked(radioBrowserStation)
                        }
                        // Refresh station to get updated like state
                        val updatedStation = radioBrowserRepository.getStationInfoByUuid(station.radioBrowserUuid)
                        CoroutineScope(Dispatchers.Main).launch {
                            updatedStation?.let {
                                viewModel.updateCurrentStationLikeState(it.isLiked)
                                updateLikeButton(it.isLiked)
                            }
                        }
                    } else {
                        // For non-global radios (user stations, bundled stations), use regular toggle
                        repository.toggleLike(station.id)
                        val updatedStation = repository.getStationById(station.id)
                        CoroutineScope(Dispatchers.Main).launch {
                            updatedStation?.let {
                                viewModel.updateCurrentStationLikeState(it.isLiked)
                                updateLikeButton(it.isLiked)
                            }
                        }
                    }
                }
            }
        }

        // Equalizer button click handler - opens built-in equalizer
        equalizerButton.setOnClickListener {
            openBuiltInEqualizer()
        }

        // Setup Edge-to-Edge: Handle window insets for status bar
        setupEdgeToEdge()

        // Enable marquee for metadata text
        metadataText.isSelected = true

        // Initialize volume state - will be synced when service connects
        updateVolumeButtonIcon()

        // Volume button tap toggles mute
        volumeButton.setOnClickListener {
            toggleMute()
        }

        // Volume button long press opens volume slider
        volumeButton.setOnLongClickListener {
            showVolumeBottomSheet()
            true
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
                // Use loadSecure to route remote URLs through Tor when Force Tor is enabled
                if (station.coverArtUri != null) {
                    coverArt.loadSecure(station.coverArtUri) {
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
                // Pause - can use regular startService since service is already running
                val intent = Intent(requireContext(), RadioService::class.java).apply {
                    action = RadioService.ACTION_PAUSE
                }
                requireContext().startService(intent)
                viewModel.setPlaying(false)
            } else if (station != null) {
                // Play - use startForegroundService for Android 8+ compatibility
                val proxyType = station.getProxyTypeEnum()
                val intent = Intent(requireContext(), RadioService::class.java).apply {
                    action = RadioService.ACTION_PLAY
                    putExtra("stream_url", station.streamUrl)
                    putExtra("station_name", station.name)
                    putExtra("proxy_host", if (station.useProxy) station.proxyHost else "")
                    putExtra("proxy_port", station.proxyPort)
                    putExtra("proxy_type", proxyType.name)
                    putExtra("cover_art_uri", station.coverArtUri)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
                // Show buffering state while connecting - service will update when ready
                viewModel.setBuffering(true)
            }
        }

        // Record button - uses ViewModel for state management
        recordButton.setOnClickListener {
            val recordingState = viewModel.recordingState.value
            if (recordingState?.isRecording == true) {
                viewModel.stopRecording()
                Toast.makeText(requireContext(), "Stopping recording...", Toast.LENGTH_SHORT).show()
            } else {
                if (viewModel.startRecording()) {
                    Toast.makeText(requireContext(), "Starting recording...", Toast.LENGTH_SHORT).show()
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
            // Change icon tint to white for contrast on error background
            recordButton.imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnErrorContainer, android.graphics.Color.WHITE)
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
            // Restore record icon with primary container color (consistent with volume and play/pause button)
            recordButton.setImageResource(R.drawable.ic_fiber_manual_record)
            // Get colors from the current theme context to reflect theme changes properly
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

    // Recording pulse animation removed - no longer used

    /**
     * Toggle mute state - only affects radio stream, not system-wide
     */
    private fun toggleMute() {
        val currentVolume = radioService?.getPlayerVolume() ?: 1f

        if (isMuted || currentVolume == 0f) {
            // Unmute - restore previous volume or set to 100%
            val restoreVolume = if (savedVolume > 0f) savedVolume else 1f
            radioService?.setPlayerVolume(restoreVolume)
            isMuted = false
        } else {
            // Mute - save current volume and set to 0
            savedVolume = currentVolume
            radioService?.setPlayerVolume(0f)
            isMuted = true
        }
        updateVolumeButtonIcon()
    }

    /**
     * Update the volume button icon based on mute state
     */
    private fun updateVolumeButtonIcon() {
        val playerVolume = radioService?.getPlayerVolume() ?: 1f
        val iconRes = if (isMuted || playerVolume == 0f) {
            R.drawable.ic_volume_off
        } else {
            R.drawable.ic_volume
        }
        volumeButton.setImageResource(iconRes)
    }

    /**
     * Shows a Material 3 BottomSheet with a vertical volume slider.
     * Long-press on volume button opens this.
     * Controls only the radio stream volume, not system-wide.
     */
    private fun showVolumeBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(requireContext())

        // Player volume is 0.0 to 1.0
        val currentVolume = radioService?.getPlayerVolume() ?: 1f

        // Create the content view programmatically with vertical slider
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(48, 32, 48, 48)

            // Title
            val title = TextView(requireContext()).apply {
                text = "Radio Volume"
                textSize = 20f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface))
                gravity = android.view.Gravity.CENTER
            }
            addView(title)

            // Subtitle explaining this is radio-only
            val subtitle = TextView(requireContext()).apply {
                text = "Adjusts radio stream only"
                textSize = 12f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                gravity = android.view.Gravity.CENTER
                alpha = 0.7f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4
                }
            }
            addView(subtitle)

            // Volume icon at top
            val volumeIcon = ImageView(requireContext()).apply {
                val iconRes = if (currentVolume == 0f) R.drawable.ic_volume_off else R.drawable.ic_volume
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(64, 64).apply {
                    topMargin = 24
                    bottomMargin = 16
                }
            }
            addView(volumeIcon)

            // Vertical volume slider using rotated horizontal slider
            val sliderContainer = android.widget.FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    350  // Fixed height for the vertical slider area
                )
            }

            val slider = Slider(requireContext()).apply {
                valueFrom = 0f
                valueTo = 100f  // Use 0-100 for easier percentage display
                value = currentVolume * 100f
                stepSize = 1f

                // Rotate to make it vertical
                rotation = 270f

                layoutParams = android.widget.FrameLayout.LayoutParams(
                    350,  // This becomes the "height" of the vertical slider
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }

                addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        val playerVolume = value / 100f
                        radioService?.setPlayerVolume(playerVolume)
                        // Update mute state and icon
                        isMuted = playerVolume == 0f
                        updateVolumeButtonIcon()
                        // Update icon in dialog
                        val newIconRes = if (playerVolume == 0f) R.drawable.ic_volume_off else R.drawable.ic_volume
                        volumeIcon.setImageResource(newIconRes)
                    }
                }
            }
            sliderContainer.addView(slider)
            addView(sliderContainer)

            // Percentage text
            val percentText = TextView(requireContext()).apply {
                text = "${(currentVolume * 100).toInt()}%"
                textSize = 16f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8
                }
            }
            addView(percentText)

            // Update percentage when slider changes
            slider.addOnChangeListener { _, value, _ ->
                percentText.text = "${value.toInt()}%"
            }
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
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    /**
     * Opens the built-in equalizer using the app's own UI.
     * Uses Android's Equalizer API attached to the audio session.
     */
    private fun openBuiltInEqualizer() {
        val equalizerManager = radioService?.getEqualizerManager()
        if (equalizerManager == null) {
            Toast.makeText(requireContext(), getString(R.string.equalizer_no_audio), Toast.LENGTH_SHORT).show()
            return
        }

        val audioSessionId = radioService?.getAudioSessionId() ?: 0
        if (audioSessionId == 0) {
            Toast.makeText(requireContext(), getString(R.string.equalizer_no_audio), Toast.LENGTH_SHORT).show()
            return
        }

        // Initialize equalizer if not already done
        if (!equalizerManager.isInitialized()) {
            val initialized = equalizerManager.initialize(audioSessionId)
            if (!initialized) {
                Toast.makeText(requireContext(), getString(R.string.equalizer_init_failed), Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Show the equalizer bottom sheet
        val bottomSheet = EqualizerBottomSheet.newInstance(equalizerManager)
        bottomSheet.show(parentFragmentManager, EqualizerBottomSheet.TAG)
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

    /**
     * Update the cover art image with cache invalidation for real-time updates.
     * Uses loadSecure to route remote URLs through Tor when Force Tor is enabled.
     */
    private fun updateCoverArt(coverArtUri: String?) {
        if (coverArtUri != null) {
            coverArt.loadSecure(coverArtUri) {
                crossfade(true)
                // Force refresh by disabling cache for this request
                memoryCachePolicy(coil.request.CachePolicy.WRITE_ONLY)
                diskCachePolicy(coil.request.CachePolicy.WRITE_ONLY)
                placeholder(R.drawable.ic_radio)
                error(R.drawable.ic_radio)
                listener(
                    onSuccess = { _, _ ->
                        coverArt.scaleType = ImageView.ScaleType.CENTER_CROP
                    },
                    onError = { _, _ ->
                        coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                )
            }
        } else {
            coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            coverArt.load(R.drawable.ic_radio) {
                crossfade(true)
            }
        }
    }

    /**
     * Update the playback elapsed time display and buffer health indicator.
     * Format: MM:SS for times under an hour, HH:MM:SS for longer times.
     * Buffer health shows how much audio is buffered ahead (max 15s = 100%).
     */
    private fun updatePlaybackTime(elapsedMs: Long, bufferedPositionMs: Long, currentPositionMs: Long) {
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val timeString = if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
        playbackElapsedTime?.text = timeString

        // Update progress bar with actual buffer health
        // Buffer health = how much audio is buffered ahead of current position
        // Max buffer target is 15 seconds (15000ms) based on ExoPlayer settings
        bufferProgressBar?.let { bar ->
            val bufferAheadMs = bufferedPositionMs - currentPositionMs
            // Calculate percentage (0-100) based on 15 second max buffer
            val maxBufferMs = 15_000L
            val bufferPercent = ((bufferAheadMs.coerceIn(0, maxBufferMs) * 100) / maxBufferMs).toInt()
            bar.setProgressCompat(bufferPercent, true)
        }
    }

    /**
     * Show or hide the buffer bar based on playback state.
     */
    private fun updateBufferBarVisibility(isPlaying: Boolean) {
        if (isPlaying && viewModel.getCurrentStation() != null) {
            if (bufferBarContainer?.visibility != View.VISIBLE) {
                bufferBarContainer?.alpha = 0f
                bufferBarContainer?.visibility = View.VISIBLE
                bufferBarContainer?.animate()?.alpha(1f)?.setDuration(200)?.start()
            }
        } else {
            bufferBarContainer?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                bufferBarContainer?.visibility = View.GONE
            }?.start()
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

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
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.load
import coil.request.CachePolicy
import com.opensource.i2pradio.util.loadSecure
import com.opensource.i2pradio.util.loadSecurePrivacy
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var addToLibraryButton: MaterialButton? = null
    private lateinit var playPauseButton: FloatingActionButton
    private lateinit var recordButton: FloatingActionButton
    private lateinit var volumeButton: FloatingActionButton
    private lateinit var audioManager: AudioManager
    private lateinit var emptyState: View
    private lateinit var playingContent: View
    private lateinit var bufferingIndicator: CircularProgressIndicator
    private var recordingIndicator: MaterialCardView? = null
    private var recordingDot: View? = null
    private var volumeBottomSheet: BottomSheetDialog? = null
    private var recordingTimeText: TextView? = null
    private lateinit var equalizerButton: MaterialButton

    // Buffer bar UI elements
    private var bufferBarContainer: View? = null
    private var playbackElapsedTime: TextView? = null
    private var bufferProgressBar: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private var liveIndicator: TextView? = null

    // Loading spinner for play/pause button
    private var playPauseSpinner: CircularProgressIndicator? = null

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

            // Sync UI state with service after reconnection (e.g., after Material You toggle)
            // This ensures buffer bar, metadata, and other UI elements are properly updated
            radioService?.let { svc ->
                val isPlaying = svc.isPlaying()
                val isBuffering = svc.isBuffering()

                // Update buffering state
                setBufferingState(isBuffering)

                // Update buffer bar visibility based on playing state
                updateBufferBarVisibility(isPlaying)

                // Only restore metadata if we're currently playing
                // This prevents stale metadata from previous stations appearing
                // when the activity is recreated (e.g., Material You toggle)
                if (isPlaying) {
                    val artist = svc.getCurrentArtist()
                    val title = svc.getCurrentTitle()
                    val rawMetadata = svc.getCurrentMetadata()

                    // Display formatted artist - title if both are present, otherwise raw metadata
                    val displayText = when {
                        !artist.isNullOrBlank() && !title.isNullOrBlank() -> "$artist — $title"
                        !rawMetadata.isNullOrBlank() -> rawMetadata
                        else -> null
                    }

                    if (!displayText.isNullOrBlank()) {
                        metadataText.text = displayText
                        metadataText.visibility = View.VISIBLE
                    }

                    // Restore stream info if available
                    val bitrate = svc.getCurrentBitrate()
                    val codec = svc.getCurrentCodec()
                    if (bitrate > 0 || (!codec.isNullOrBlank() && codec != "Unknown")) {
                        updateStreamInfo(bitrate, codec ?: "Unknown")
                    }
                }

                // Sync ViewModel state if needed
                if (isPlaying != viewModel.isPlaying.value) {
                    viewModel.setPlaying(isPlaying)
                }
                if (isBuffering != viewModel.isBuffering.value) {
                    viewModel.setBuffering(isBuffering)
                }
            }
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

    // Flag to track if views are initialized and valid (set in onCreateView, cleared in onDestroyView)
    private var viewsInitialized = false

    // Broadcast receiver for metadata, stream info, playback state, recording updates, cover art, and time updates
    private val metadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RadioService.BROADCAST_METADATA_CHANGED -> {
                    val artist = intent.getStringExtra(RadioService.EXTRA_ARTIST)
                    val title = intent.getStringExtra(RadioService.EXTRA_TITLE)
                    val rawMetadata = intent.getStringExtra(RadioService.EXTRA_METADATA)

                    // Display formatted artist - title if both are present, otherwise raw metadata
                    val displayText = when {
                        !artist.isNullOrBlank() && !title.isNullOrBlank() -> "$artist — $title"
                        !rawMetadata.isNullOrBlank() -> rawMetadata
                        else -> null
                    }

                    if (!displayText.isNullOrBlank()) {
                        metadataText.text = displayText
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
                    val errorMessage = intent.getStringExtra(RadioService.EXTRA_ERROR_MESSAGE) ?: getString(R.string.error_unknown)
                    // Reset recording state in ViewModel
                    viewModel.onRecordingError()
                    if (context != null && !PreferencesHelper.isToastMessagesDisabled(context!!)) {
                        Toast.makeText(context, getString(R.string.recording_failed, errorMessage), Toast.LENGTH_LONG).show()
                    }
                }
                RadioService.BROADCAST_RECORDING_COMPLETE -> {
                    val filePath = intent.getStringExtra(RadioService.EXTRA_FILE_PATH) ?: ""
                    val fileSize = intent.getLongExtra(RadioService.EXTRA_FILE_SIZE, 0L)
                    val sizeKB = fileSize / 1024
                    val fileName = filePath.substringAfterLast("/")
                    if (context != null && !PreferencesHelper.isToastMessagesDisabled(context!!)) {
                        Toast.makeText(context, getString(R.string.recording_saved, fileName, sizeKB), Toast.LENGTH_LONG).show()
                    }
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
                RadioService.BROADCAST_STREAM_ERROR -> {
                    if (context != null) {
                        val errorType = intent.getStringExtra(RadioService.EXTRA_STREAM_ERROR_TYPE)
                        val errorMessage = when (errorType) {
                            RadioService.ERROR_TYPE_TOR_NOT_CONNECTED -> getString(R.string.error_tor_not_connected)
                            RadioService.ERROR_TYPE_I2P_NOT_CONNECTED -> getString(R.string.error_i2p_not_connected)
                            RadioService.ERROR_TYPE_CUSTOM_PROXY_NOT_CONFIGURED -> getString(R.string.error_custom_proxy_not_configured)
                            RadioService.ERROR_TYPE_MAX_RETRIES -> getString(R.string.error_stream_max_retries)
                            RadioService.ERROR_TYPE_STREAM_FAILED -> getString(R.string.error_stream_failed)
                            else -> getString(R.string.error_stream_failed)
                        }
                        // Privacy/security errors always show, others respect toast setting
                        // Use debounce to prevent spam (same error won't show again for 30 seconds)
                        val isPrivacyError = errorType in listOf(
                            RadioService.ERROR_TYPE_TOR_NOT_CONNECTED,
                            RadioService.ERROR_TYPE_I2P_NOT_CONNECTED,
                            RadioService.ERROR_TYPE_CUSTOM_PROXY_NOT_CONFIGURED
                        )
                        val shouldShow = isPrivacyError || !PreferencesHelper.isToastMessagesDisabled(context!!)
                        if (shouldShow && com.opensource.i2pradio.MainActivity.shouldShowErrorToast(errorType)) {
                            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                MainActivity.BROADCAST_LIKE_STATE_CHANGED -> {
                    // Handle like state changes from station list or mini player
                    val isLiked = intent.getBooleanExtra(MainActivity.EXTRA_IS_LIKED, false)
                    val stationId = intent.getLongExtra(MainActivity.EXTRA_STATION_ID, -1L)
                    val radioBrowserUuid = intent.getStringExtra(MainActivity.EXTRA_RADIO_BROWSER_UUID)

                    // Update the like button if this is the currently playing station
                    viewModel.getCurrentStation()?.let { currentStation ->
                        val isCurrentStation = if (!radioBrowserUuid.isNullOrEmpty() && !currentStation.radioBrowserUuid.isNullOrEmpty()) {
                            currentStation.radioBrowserUuid == radioBrowserUuid
                        } else {
                            currentStation.id == stationId
                        }

                        if (isCurrentStation && viewsInitialized) {
                            updateLikeButton(isLiked)
                        }
                    }
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
        addToLibraryButton = view.findViewById(R.id.addToLibraryButton)
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

        // Loading spinner for play/pause button
        playPauseSpinner = view.findViewById(R.id.playPauseSpinner)

        // Initialize audio manager and volume control
        audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

        // Bind to RadioService to get audio session ID for equalizer
        val serviceIntent = Intent(requireContext(), RadioService::class.java)
        requireContext().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Register broadcast receiver for metadata, stream info, playback state, recording updates, cover art, time, errors, and like state changes
        val filter = IntentFilter().apply {
            addAction(RadioService.BROADCAST_METADATA_CHANGED)
            addAction(RadioService.BROADCAST_STREAM_INFO_CHANGED)
            addAction(RadioService.BROADCAST_PLAYBACK_STATE_CHANGED)
            addAction(RadioService.BROADCAST_RECORDING_ERROR)
            addAction(RadioService.BROADCAST_RECORDING_COMPLETE)
            addAction(RadioService.BROADCAST_COVER_ART_CHANGED)
            addAction(RadioService.BROADCAST_PLAYBACK_TIME_UPDATE)
            addAction(RadioService.BROADCAST_STREAM_ERROR)
            addAction(MainActivity.BROADCAST_LIKE_STATE_CHANGED)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(metadataReceiver, filter)

        // Like button click handler - uses shared logic with station list
        likeButton.setOnClickListener {
            viewModel.getCurrentStation()?.let { station ->
                lifecycleScope.launch(Dispatchers.IO) {
                    // Check if this is a global radio (has radioBrowserUuid)
                    if (!station.radioBrowserUuid.isNullOrEmpty()) {
                        // For global radios, use shared StationActionHelper logic
                        // This matches BrowseStationsFragment behavior:
                        // - Liking saves the station as liked
                        // - Unliking deletes the station entirely from the library
                        val result = StationActionHelper.toggleLikeForGlobalRadio(
                            radioBrowserRepository,
                            station
                        )

                        withContext(Dispatchers.Main) {
                            // Check if views are still valid after async operation
                            if (!viewsInitialized) return@withContext
                            // Update UI
                            viewModel.updateCurrentStationLikeState(result.isLiked)
                            updateLikeButton(result.isLiked)
                            // Also update library button since station is added when liked, deleted when unliked
                            updateAddToLibraryButton(result.isLiked)

                            // Broadcast and show toast using shared helper
                            StationActionHelper.broadcastLikeStateChange(requireContext(), result)
                            StationActionHelper.showLikeToast(requireContext(), station.name, result)
                        }
                    } else {
                        // For non-global radios (user stations, bundled stations), use regular toggle
                        repository.toggleLike(station.id)
                        val updatedStation = repository.getStationById(station.id)
                        withContext(Dispatchers.Main) {
                            // Check if views are still valid after async operation
                            if (!viewsInitialized) return@withContext
                            updatedStation?.let {
                                viewModel.updateCurrentStationLikeState(it.isLiked)
                                updateLikeButton(it.isLiked)

                                // Broadcast like state change to all views
                                val broadcastIntent = Intent(com.opensource.i2pradio.MainActivity.BROADCAST_LIKE_STATE_CHANGED).apply {
                                    putExtra(com.opensource.i2pradio.MainActivity.EXTRA_IS_LIKED, it.isLiked)
                                    putExtra(com.opensource.i2pradio.MainActivity.EXTRA_STATION_ID, it.id)
                                    putExtra(com.opensource.i2pradio.MainActivity.EXTRA_RADIO_BROWSER_UUID, station.radioBrowserUuid)
                                }
                                LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(broadcastIntent)

                                // Show toast message
                                if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                                    if (it.isLiked) {
                                        Toast.makeText(
                                            requireContext(),
                                            getString(R.string.station_saved, station.name),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            requireContext(),
                                            getString(R.string.station_removed, station.name),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add to Library button click handler - uses shared logic with station list
        addToLibraryButton?.setOnClickListener {
            viewModel.getCurrentStation()?.let { station ->
                // Animate the button with scale effect
                addToLibraryButton?.animate()
                    ?.scaleX(0.85f)
                    ?.scaleY(0.85f)
                    ?.setDuration(80)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.withEndAction {
                        addToLibraryButton?.animate()
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.setDuration(150)
                            ?.setInterpolator(OvershootInterpolator(2f))
                            ?.start()
                    }
                    ?.start()

                lifecycleScope.launch(Dispatchers.IO) {
                    // Check if this is a global radio (has radioBrowserUuid)
                    if (!station.radioBrowserUuid.isNullOrEmpty()) {
                        // For global radios, use shared StationActionHelper logic
                        val result = StationActionHelper.toggleSaveForGlobalRadio(
                            radioBrowserRepository,
                            station
                        )

                        withContext(Dispatchers.Main) {
                            // Check if views are still valid after async operation
                            if (!viewsInitialized) return@withContext
                            // Update UI
                            updateAddToLibraryButton(result.isSaved)
                            // Also update like button since station may have been removed
                            if (!result.isSaved) {
                                updateLikeButton(false)
                                viewModel.updateCurrentStationLikeState(false)
                            }

                            // Broadcast and show toast using shared helper
                            StationActionHelper.broadcastSaveStateChange(requireContext(), result)
                            StationActionHelper.showSaveToast(requireContext(), station.name, result.isSaved)
                        }
                    } else {
                        // For user stations, they're always in the library, so we just toggle visibility
                        // This case shouldn't really happen for user stations as they're always local
                        withContext(Dispatchers.Main) {
                            // Check if views are still valid after async operation
                            if (!viewsInitialized) return@withContext
                            if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.station_already_in_library),
                                    Toast.LENGTH_SHORT
                                ).show()
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
            (activity as? MainActivity)?.switchToLibraryTab()
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
                        ProxyType.CUSTOM -> " • Custom"
                        ProxyType.NONE -> ""
                    }
                } else ""
                genreText.text = "${station.genre}$proxyIndicator"

                // Update like button state
                updateLikeButton(station.isLiked)

                // Update add to library button state
                if (!station.radioBrowserUuid.isNullOrEmpty()) {
                    // For global radios, check if saved in library
                    lifecycleScope.launch(Dispatchers.IO) {
                        val stationInfo = radioBrowserRepository.getStationInfoByUuid(station.radioBrowserUuid)
                        withContext(Dispatchers.Main) {
                            // Check if views are still valid after async operation
                            if (!viewsInitialized) return@withContext
                            updateAddToLibraryButton(stationInfo != null)
                            // Show/hide the button - only show for global radios
                            addToLibraryButton?.visibility = View.VISIBLE
                        }
                    }
                } else {
                    // For user stations, hide the add button (already in library)
                    addToLibraryButton?.visibility = View.GONE
                }

                // Reset metadata and stream info for new station
                if (isNewStation) {
                    metadataText.visibility = View.GONE
                    streamInfoText.visibility = View.GONE
                }

                // Handle cover art update properly - switch scaleType based on content
                // Use loadSecure to route remote URLs through Tor when Force Tor is enabled
                // For privacy stations (Tor/I2P), use loadSecurePrivacy to route through Tor when available
                if (station.coverArtUri != null) {
                    // Start with centerInside for placeholder, switch to centerCrop only on successful load
                    coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val isPrivacyStation = station.getProxyTypeEnum().let {
                        it == ProxyType.TOR || it == ProxyType.I2P
                    }
                    val imageLoadBuilder: coil.request.ImageRequest.Builder.() -> Unit = {
                        crossfade(true)
                        memoryCachePolicy(CachePolicy.ENABLED)
                        placeholder(R.drawable.ic_radio)
                        error(R.drawable.ic_radio)
                        listener(
                            onStart = {
                                // Ensure centerInside during placeholder phase
                                coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                            },
                            onSuccess = { _, _ ->
                                // Real bitmap loaded - use centerCrop for best appearance
                                coverArt.scaleType = ImageView.ScaleType.CENTER_CROP
                            },
                            onError = { _, _ ->
                                // Error loading - keep centerInside for vector placeholder
                                coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                            }
                        )
                    }
                    if (isPrivacyStation) {
                        coverArt.loadSecurePrivacy(station.coverArtUri, imageLoadBuilder)
                    } else {
                        coverArt.loadSecure(station.coverArtUri, imageLoadBuilder)
                    }
                } else {
                    // No cover art - use centerInside for vector placeholder and force reload
                    coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    // Force reload to get fresh drawable with current theme colors (Material You)
                    coverArt.setImageDrawable(null)
                    coverArt.load(R.drawable.ic_radio) {
                        crossfade(true)
                        // Disable all caching to force fresh drawable with current theme
                        memoryCachePolicy(CachePolicy.DISABLED)
                        diskCachePolicy(CachePolicy.DISABLED)
                    }
                }
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
                if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                    Toast.makeText(requireContext(), getString(R.string.recording_stopping), Toast.LENGTH_SHORT).show()
                }
            } else {
                if (viewModel.startRecording()) {
                    if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                        Toast.makeText(requireContext(), getString(R.string.recording_starting), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                        Toast.makeText(requireContext(), getString(R.string.recording_no_station), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Observe recording state to update UI (survives configuration changes)
        viewModel.recordingState.observe(viewLifecycleOwner) { state ->
            updateRecordingUI(state)
        }

        // Mark views as initialized - must be last before returning
        viewsInitialized = true

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
        // Dismiss previous bottom sheet if it exists
        volumeBottomSheet?.dismiss()
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
                text = getString(R.string.now_playing_volume_title)
                textSize = 20f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface))
                gravity = android.view.Gravity.CENTER
            }
            addView(title)

            // Subtitle explaining this is radio-only
            val subtitle = TextView(requireContext()).apply {
                text = getString(R.string.now_playing_volume_description)
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
        volumeBottomSheet = bottomSheetDialog
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
     * Shows spinners on play/pause button and cover art during buffering.
     */
    private fun setBufferingState(buffering: Boolean) {
        isBuffering = buffering

        if (buffering && viewModel.getCurrentStation() != null) {
            // Show loading spinners
            bufferingIndicator.visibility = View.VISIBLE
            playPauseButton.visibility = View.INVISIBLE
            playPauseSpinner?.visibility = View.VISIBLE
        } else {
            // Hide loading spinners
            bufferingIndicator.visibility = View.GONE
            playPauseButton.visibility = View.VISIBLE
            playPauseSpinner?.visibility = View.GONE
        }
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
        // Mark views as invalid immediately to prevent async callbacks from accessing destroyed views
        viewsInitialized = false
        super.onDestroyView()
        recordingHandler.removeCallbacks(recordingUpdateRunnable)
        recordingDot?.clearAnimation()
        previousPlayingState = null
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(metadataReceiver)

        // Clean up bottom sheet to prevent window leaks
        volumeBottomSheet?.dismiss()
        volumeBottomSheet = null

        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    /**
     * Opens the built-in equalizer using the app's own UI.
     * Uses Android's Equalizer API attached to the audio session.
     * If no audio is playing, opens in preview mode where settings are saved
     * and will be applied when playback starts.
     */
    private fun openBuiltInEqualizer() {
        var equalizerManager = radioService?.getEqualizerManager()
        val audioSessionId = radioService?.getAudioSessionId() ?: 0

        // If we have an active audio session, try to initialize the real equalizer
        if (equalizerManager != null && audioSessionId > 0) {
            if (!equalizerManager.isInitialized()) {
                val initialized = equalizerManager.initialize(audioSessionId)
                if (!initialized) {
                    // Fall back to preview mode if initialization fails
                    equalizerManager.initializeForPreview()
                }
            }
        } else {
            // No active audio session - use preview mode
            // Create a temporary EqualizerManager for preview if service isn't bound
            if (equalizerManager == null) {
                equalizerManager = EqualizerManager(requireContext())
            }
            if (!equalizerManager.isInitialized()) {
                equalizerManager.initializeForPreview()
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

    /**
     * Update the add to library button icon based on saved state.
     * Uses same icon pattern as BrowseStationsAdapter action button.
     */
    private fun updateAddToLibraryButton(isSaved: Boolean) {
        addToLibraryButton?.setIconResource(if (isSaved) R.drawable.ic_check else R.drawable.ic_add)
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
     * For privacy stations (Tor/I2P), uses loadSecurePrivacy to route through Tor when available.
     */
    private fun updateCoverArt(coverArtUri: String?) {
        if (coverArtUri != null) {
            // Start with centerInside for placeholder, switch to centerCrop only on successful load
            coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            // Check if current station is a privacy station (Tor/I2P)
            val isPrivacyStation = viewModel.currentStation.value?.getProxyTypeEnum().let {
                it == ProxyType.TOR || it == ProxyType.I2P
            }
            val imageLoadBuilder: coil.request.ImageRequest.Builder.() -> Unit = {
                crossfade(true)
                // Force refresh by disabling cache for this request
                memoryCachePolicy(coil.request.CachePolicy.WRITE_ONLY)
                diskCachePolicy(coil.request.CachePolicy.WRITE_ONLY)
                placeholder(R.drawable.ic_radio)
                error(R.drawable.ic_radio)
                listener(
                    onStart = {
                        // Ensure centerInside during placeholder phase
                        coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    },
                    onSuccess = { _, _ ->
                        // Real bitmap loaded - use centerCrop for best appearance
                        coverArt.scaleType = ImageView.ScaleType.CENTER_CROP
                    },
                    onError = { _, _ ->
                        // Error loading - keep centerInside for vector placeholder
                        coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                )
            }
            if (isPrivacyStation) {
                coverArt.loadSecurePrivacy(coverArtUri, imageLoadBuilder)
            } else {
                coverArt.loadSecure(coverArtUri, imageLoadBuilder)
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

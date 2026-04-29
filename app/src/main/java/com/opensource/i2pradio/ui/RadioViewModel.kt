package com.opensource.i2pradio.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.PlaybackQueueManager
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.RadioStationPasswordHelper
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserRepository
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserResult
import com.opensource.i2pradio.ui.PreferencesHelper
import kotlinx.coroutines.launch

/**
 * Recording state to track the current recording status.
 * Survives configuration changes (e.g., screen rotation).
 */
data class RecordingState(
    val isRecording: Boolean = false,
    val startTimeMillis: Long = 0L
)

/**
 * Cover art update event - triggers UI refresh with cache invalidation
 */
data class CoverArtUpdate(
    val coverArtUri: String?,
    val stationId: Long = -1L,
    val timestamp: Long = System.currentTimeMillis() // For cache invalidation
)

class RadioViewModel(application: Application) : AndroidViewModel(application) {
    private val _currentStation = MutableLiveData<RadioStation?>()
    val currentStation: LiveData<RadioStation?> = _currentStation

    private val _isPlaying = MutableLiveData<Boolean>(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _isBuffering = MutableLiveData<Boolean>(false)
    val isBuffering: LiveData<Boolean> = _isBuffering

    private val _recordingState = MutableLiveData(RecordingState())
    val recordingState: LiveData<RecordingState> = _recordingState

    // Cover art update event for real-time updates across all UI components
    private val _coverArtUpdate = MutableLiveData<CoverArtUpdate?>()
    val coverArtUpdate: LiveData<CoverArtUpdate?> = _coverArtUpdate

    // Miniplayer visibility state for UI components that need to adjust when miniplayer shows/hides
    private val _isMiniPlayerVisible = MutableLiveData<Boolean>(false)
    val isMiniPlayerVisible: LiveData<Boolean> = _isMiniPlayerVisible

    // Three-layer queue. The manager is a process-wide singleton; the
    // ViewModel just exposes its LiveData and orchestrates the Discover
    // network call when both eager layers are exhausted.
    val manualQueue: LiveData<List<RadioStation>> = PlaybackQueueManager.manualQueue

    fun setCurrentStation(station: RadioStation?) {
        val previousStation = _currentStation.value
        _currentStation.value = station

        // Persist the current station to SharedPreferences
        // This ensures the UI can restore the station when MainActivity is recreated
        PreferencesHelper.saveCurrentStation(getApplication(), station)

        // Stop recording if station is cleared
        if (station == null && _recordingState.value?.isRecording == true) {
            stopRecording()
            return
        }

        // Check if we should stop recording when switching to a different station
        if (_recordingState.value?.isRecording == true && station != null && previousStation != null) {
            // Check if this is actually a different station (by URL since browse stations may have id=0)
            val isDifferentStation = if (station.id != 0L && previousStation.id != 0L) {
                station.id != previousStation.id
            } else {
                station.streamUrl != previousStation.streamUrl
            }

            if (isDifferentStation) {
                val app = getApplication<Application>()
                val recordAcrossStations = PreferencesHelper.isRecordAcrossStationsEnabled(app)
                val recordAllStations = PreferencesHelper.isRecordAllStationsEnabled(app)

                if (recordAllStations) {
                    // Switch the recording to the new stream (continue same file)
                    switchRecordingToStation(station)
                } else if (!recordAcrossStations) {
                    // Stop and save recording when switching stations
                    stopRecording()
                }
                // If recordAcrossStations is true but recordAllStations is false,
                // recording continues on the original stream (no action needed)
            }
        }
    }

    /**
     * Switch the recording to a new station's stream (for "Record All Stations" feature).
     * The recording continues in the same file with content from the new stream.
     */
    private fun switchRecordingToStation(station: RadioStation) {
        val intent = Intent(getApplication(), RadioService::class.java).apply {
            action = RadioService.ACTION_SWITCH_RECORDING_STREAM
            putExtra("stream_url", station.streamUrl)
            putExtra("station_name", station.name)
            putExtra("proxy_host", station.proxyHost ?: "")
            putExtra("proxy_port", station.proxyPort)
            putExtra("proxy_type", station.getProxyTypeEnum().name)
            putExtra("hls_hint", station.hlsHint)
            putExtra("codec_hint", station.codecHint)
        }
        getApplication<Application>().startService(intent)
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun setBuffering(buffering: Boolean) {
        _isBuffering.value = buffering
    }

    fun setMiniPlayerVisible(visible: Boolean) {
        _isMiniPlayerVisible.value = visible
    }

    fun getCurrentStation(): RadioStation? = _currentStation.value

    /**
     * Update only the like state of the current station without triggering full UI refresh.
     * This prevents animations from firing when the user toggles like state.
     */
    fun updateCurrentStationLikeState(isLiked: Boolean) {
        _currentStation.value?.let { station ->
            // Create a copy with updated like state
            val updatedStation = station.copy(isLiked = isLiked)
            _currentStation.value = updatedStation
            // Persist the updated station
            PreferencesHelper.saveCurrentStation(getApplication(), updatedStation)
        }
    }

    /**
     * Update the cover art for the current station and notify all observers.
     * This triggers real-time updates across miniplayer, now playing, and station list.
     */
    fun updateCoverArt(coverArtUri: String?, stationId: Long = -1L) {
        // Update the current station's cover art if it matches
        _currentStation.value?.let { station ->
            if (stationId == -1L || station.id == stationId) {
                val updatedStation = station.copy(coverArtUri = coverArtUri)
                _currentStation.value = updatedStation
                // Persist the updated station
                PreferencesHelper.saveCurrentStation(getApplication(), updatedStation)
            }
        }
        // Trigger cover art update event for all observers
        _coverArtUpdate.value = CoverArtUpdate(coverArtUri, stationId)
    }

    /**
     * Clear the cover art update event after it has been handled.
     */
    fun clearCoverArtUpdate() {
        _coverArtUpdate.value = null
    }

    /**
     * Start recording the current station.
     * Communicates with RadioService to handle the actual file I/O.
     * @return true if recording started, false if no station is playing
     */
    fun startRecording(): Boolean {
        val station = _currentStation.value ?: return false

        _recordingState.value = RecordingState(
            isRecording = true,
            startTimeMillis = System.currentTimeMillis()
        )

        // Send recording intent to service
        val intent = Intent(getApplication(), RadioService::class.java).apply {
            action = RadioService.ACTION_START_RECORDING
            putExtra("station_name", station.name)
        }
        getApplication<Application>().startService(intent)

        return true
    }

    /**
     * Stop the current recording.
     * Communicates with RadioService to finalize the file.
     */
    fun stopRecording() {
        if (_recordingState.value?.isRecording != true) return

        _recordingState.value = RecordingState(isRecording = false, startTimeMillis = 0L)

        // Send stop recording intent to service
        val intent = Intent(getApplication(), RadioService::class.java).apply {
            action = RadioService.ACTION_STOP_RECORDING
        }
        getApplication<Application>().startService(intent)
    }

    /**
     * Called when a recording error occurs (from service broadcast).
     * Resets the recording state so the UI can update.
     */
    fun onRecordingError() {
        _recordingState.value = RecordingState(isRecording = false, startTimeMillis = 0L)
    }

    /**
     * Get the recording elapsed time in milliseconds.
     * Returns 0 if not recording.
     */
    fun getRecordingElapsedTime(): Long {
        val state = _recordingState.value ?: return 0L
        return if (state.isRecording) {
            System.currentTimeMillis() - state.startTimeMillis
        } else {
            0L
        }
    }

    // ===== Three-layer queue =====

    /**
     * Snapshot the visible filtered+sorted list at play time. The position of
     * [currentStation] inside [stations] becomes the cursor that walks
     * forward/back via [skipNext] and [skipPrevious].
     */
    fun setPlaybackContext(stations: List<RadioStation>, currentStation: RadioStation?) {
        PlaybackQueueManager.setContext(stations, currentStation)
    }

    fun addToManualQueue(station: RadioStation) {
        PlaybackQueueManager.addToManualQueue(station)
    }

    fun removeFromManualQueueAt(index: Int) {
        PlaybackQueueManager.removeFromManualQueueAt(index)
    }

    fun clearManualQueue() {
        PlaybackQueueManager.clearManualQueue()
    }

    /**
     * Pick the next station and start it. Walks the three layers in
     * priority order: manual queue, then context list, then Discover (only
     * when the user has opted in).
     */
    fun skipNext() {
        val app = getApplication<Application>()

        PlaybackQueueManager.popManualQueue()?.let { station ->
            PlaybackQueueManager.notifyNowPlaying(station)
            playStationInternal(station)
            return
        }

        PlaybackQueueManager.advanceContext()?.let { station ->
            playStationInternal(station)
            return
        }

        if (!PreferencesHelper.isDiscoverEnabled(app)) return

        val hint = PlaybackQueueManager.discoverHint(_currentStation.value) ?: return
        viewModelScope.launch {
            val repo = RadioBrowserRepository(app)
            val candidates = mutableListOf<RadioStation>()

            val byTag = if (hint.tag.isNotBlank()) repo.getByTag(hint.tag, limit = 25) else null
            if (byTag is RadioBrowserResult.Success) {
                byTag.data.forEach { candidates += repo.convertToRadioStation(it) }
            }
            if (candidates.none { acceptable(it, hint) } && hint.countryCode.isNotBlank()) {
                val byCountry = repo.getByCountryCode(hint.countryCode, limit = 25)
                if (byCountry is RadioBrowserResult.Success) {
                    byCountry.data.forEach { candidates += repo.convertToRadioStation(it) }
                }
            }

            val pick = candidates.firstOrNull { acceptable(it, hint) } ?: return@launch
            playStationInternal(pick)
        }
    }

    /**
     * Step back one entry in the context list. The manual queue is FIFO and
     * is not affected; if there's no context behind us, this is a no-op.
     */
    fun skipPrevious() {
        val previous = PlaybackQueueManager.rewindContext() ?: return
        playStationInternal(previous)
    }

    /**
     * Play [station] now and mark it as the new context cursor (used when
     * the user picks something out of the queue UI directly).
     */
    fun playFromQueue(station: RadioStation) {
        PlaybackQueueManager.notifyNowPlaying(station)
        playStationInternal(station)
    }

    private fun acceptable(candidate: RadioStation, hint: PlaybackQueueManager.DiscoverHint): Boolean {
        val uuid = candidate.radioBrowserUuid
        if (uuid.isNullOrEmpty()) return true
        return uuid !in hint.excludeUuids
    }

    private fun playStationInternal(station: RadioStation) {
        val app = getApplication<Application>()
        setCurrentStation(station)
        setBuffering(true)

        val proxyType = station.getProxyTypeEnum()
        val intent = Intent(app, RadioService::class.java).apply {
            action = RadioService.ACTION_PLAY
            putExtra("stream_url", station.streamUrl)
            putExtra("station_name", station.name)
            putExtra("proxy_host", if (station.useProxy) station.proxyHost else "")
            putExtra("proxy_port", station.proxyPort)
            putExtra("proxy_type", proxyType.name)
            putExtra("cover_art_uri", station.coverArtUri)
            putExtra("custom_proxy_protocol", station.customProxyProtocol)
            putExtra("proxy_username", station.proxyUsername)
            putExtra(
                "proxy_password",
                RadioStationPasswordHelper.getDecryptedPassword(app, station)
            )
            putExtra("proxy_auth_type", station.proxyAuthType)
            putExtra("proxy_connection_timeout", station.proxyConnectionTimeout)
            putExtra("hls_hint", station.hlsHint)
            putExtra("codec_hint", station.codecHint)
        }
        ContextCompat.startForegroundService(app, intent)
    }
}
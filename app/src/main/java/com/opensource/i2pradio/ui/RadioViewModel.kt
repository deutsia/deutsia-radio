package com.opensource.i2pradio.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.ui.PreferencesHelper

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

    fun setCurrentStation(station: RadioStation?) {
        val previousStation = _currentStation.value
        _currentStation.value = station

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
            _currentStation.value = station.copy(isLiked = isLiked)
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
                _currentStation.value = station.copy(coverArtUri = coverArtUri)
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
}
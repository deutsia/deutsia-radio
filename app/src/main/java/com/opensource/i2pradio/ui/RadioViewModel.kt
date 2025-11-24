package com.opensource.i2pradio.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.RadioStation

/**
 * Recording state to track the current recording status.
 * Survives configuration changes (e.g., screen rotation).
 */
data class RecordingState(
    val isRecording: Boolean = false,
    val startTimeMillis: Long = 0L
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

    fun setCurrentStation(station: RadioStation?) {
        _currentStation.value = station
        // Stop recording if station is cleared
        if (station == null && _recordingState.value?.isRecording == true) {
            stopRecording()
        }
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun setBuffering(buffering: Boolean) {
        _isBuffering.value = buffering
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
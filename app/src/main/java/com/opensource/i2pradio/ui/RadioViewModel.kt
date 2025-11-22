package com.opensource.i2pradio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.opensource.i2pradio.data.RadioStation

class RadioViewModel(application: Application) : AndroidViewModel(application) {
    private val _currentStation = MutableLiveData<RadioStation?>()
    val currentStation: LiveData<RadioStation?> = _currentStation

    private val _isPlaying = MutableLiveData<Boolean>(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    fun setCurrentStation(station: RadioStation?) {  // Changed to accept null!
        _currentStation.value = station
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun getCurrentStation(): RadioStation? = _currentStation.value
}
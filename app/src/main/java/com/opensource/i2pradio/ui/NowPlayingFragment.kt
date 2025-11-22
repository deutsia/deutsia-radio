package com.opensource.i2pradio.ui

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
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
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var audioManager: AudioManager
    private lateinit var emptyState: View
    private lateinit var playingContent: View
    private lateinit var bufferingIndicator: CircularProgressIndicator
    private var recordingIndicator: LinearLayout? = null
    private var recordingTimeText: TextView? = null

    private var isRecording = false
    private var recordingStartTime = 0L
    private val recordingHandler = Handler(Looper.getMainLooper())
    private val recordingUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
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
        volumeSeekBar = view.findViewById(R.id.volumeSeekBar)
        emptyState = view.findViewById(R.id.emptyPlayingState)
        bufferingIndicator = view.findViewById(R.id.bufferingIndicator)
        playingContent = view.findViewById(R.id.nowPlayingCoverCard)
        recordingIndicator = view.findViewById(R.id.recordingIndicator)
        recordingTimeText = view.findViewById(R.id.recordingTime)

        // Initialize audio manager and volume control
        audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        setupVolumeControl()

        // Back button
        val backButton = view.findViewById<MaterialButton>(R.id.backButton)
        backButton.setOnClickListener {
            (activity as? MainActivity)?.switchToRadiosTab()
        }

        // Observe current station
        viewModel.currentStation.observe(viewLifecycleOwner) { station ->
            if (station == null) {
                emptyState.visibility = View.VISIBLE
                playingContent.visibility = View.GONE
                view.findViewById<View>(R.id.nowPlayingInfoContainer)?.visibility = View.GONE
                view.findViewById<View>(R.id.nowPlayingControlsContainer)?.visibility = View.GONE
                // Stop recording if station is cleared
                if (isRecording) {
                    stopRecording()
                }
            } else {
                emptyState.visibility = View.GONE
                playingContent.visibility = View.VISIBLE
                view.findViewById<View>(R.id.nowPlayingInfoContainer)?.visibility = View.VISIBLE
                view.findViewById<View>(R.id.nowPlayingControlsContainer)?.visibility = View.VISIBLE

                stationName.text = station.name

                val proxyIndicator = if (station.useProxy) " • I2P" else ""
                genreText.text = "${station.genre}$proxyIndicator"

                if (station.coverArtUri != null) {
                    coverArt.load(station.coverArtUri) {
                        crossfade(true)
                        placeholder(R.drawable.ic_radio)
                        error(R.drawable.ic_radio)
                    }
                } else {
                    coverArt.setImageResource(R.drawable.ic_radio)
                }

                metadataText.visibility = View.GONE
            }
        }

        // Observe playing state
        viewModel.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            if (isPlaying) {
                playPauseButton.setImageResource(R.drawable.ic_pause)
            } else {
                playPauseButton.setImageResource(R.drawable.ic_play)
            }
        }

        // Play/Pause button
        playPauseButton.setOnClickListener {
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

        // Record button
        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        return view
    }

    private fun setupVolumeControl() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        volumeSeekBar.max = maxVolume
        volumeSeekBar.progress = currentVolume

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun startRecording() {
        val station = viewModel.getCurrentStation()
        if (station == null) {
            Toast.makeText(requireContext(), "No station playing", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        recordButton.setIconResource(R.drawable.ic_stop_record)
        recordButton.setIconTintResource(android.R.color.white)
        recordingIndicator?.visibility = View.VISIBLE
        recordingTimeText?.text = "00:00"
        recordingHandler.post(recordingUpdateRunnable)

        // Send recording intent to service
        val intent = Intent(requireContext(), RadioService::class.java).apply {
            action = RadioService.ACTION_START_RECORDING
            putExtra("station_name", station.name)
        }
        requireContext().startService(intent)

        Toast.makeText(requireContext(), "Recording started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        isRecording = false
        recordingHandler.removeCallbacks(recordingUpdateRunnable)
        recordButton.setIconResource(R.drawable.ic_record)
        recordButton.setIconTintResource(android.R.color.transparent)
        recordingIndicator?.visibility = View.GONE

        // Send stop recording intent to service
        val intent = Intent(requireContext(), RadioService::class.java).apply {
            action = RadioService.ACTION_STOP_RECORDING
        }
        requireContext().startService(intent)

        Toast.makeText(requireContext(), "Recording saved to Music folder", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recordingHandler.removeCallbacks(recordingUpdateRunnable)
    }
}

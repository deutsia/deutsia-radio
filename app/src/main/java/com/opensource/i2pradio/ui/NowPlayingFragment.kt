package com.opensource.i2pradio.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
    private lateinit var stopButton: MaterialButton
    private lateinit var emptyState: View
    private lateinit var playingContent: View
    private lateinit var bufferingIndicator: CircularProgressIndicator

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
        stopButton = view.findViewById(R.id.stopButton)
        emptyState = view.findViewById(R.id.emptyPlayingState)
        bufferingIndicator = view.findViewById(R.id.bufferingIndicator)
        playingContent = view.findViewById(R.id.nowPlayingCoverCard)

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

        // Stop button
        stopButton.setOnClickListener {
            val intent = Intent(requireContext(), RadioService::class.java).apply {
                action = RadioService.ACTION_STOP
            }
            requireContext().startService(intent)
            viewModel.setPlaying(false)
            viewModel.setCurrentStation(null)
        }

        return view
    }
}
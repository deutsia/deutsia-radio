package com.opensource.i2pradio.ui.browse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserRepository
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserStation
import com.opensource.i2pradio.data.radioregistry.RadioRegistryStation
import com.opensource.i2pradio.ui.RadioViewModel
import kotlinx.coroutines.launch

/**
 * Fragment for browsing Privacy Radio stations only (Tor and I2P).
 * Displayed when RadioBrowser API is disabled but Radio Registry API is enabled.
 */
class BrowseRadioRegistryOnlyFragment : Fragment() {

    // Privacy Radio views
    private lateinit var torStationsRecyclerView: RecyclerView
    private lateinit var torStationsSeeAll: TextView
    private lateinit var i2pStationsRecyclerView: RecyclerView
    private lateinit var i2pStationsSeeAll: TextView

    // Adapters
    private lateinit var torStationsAdapter: PrivacyRadioCarouselAdapter
    private lateinit var i2pStationsAdapter: PrivacyRadioCarouselAdapter

    private val viewModel: BrowseViewModel by viewModels()
    private val radioViewModel: RadioViewModel by activityViewModels()
    private lateinit var repository: RadioBrowserRepository

    // Broadcast receiver for like state changes
    private val likeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.BROADCAST_LIKE_STATE_CHANGED) {
                viewModel.refreshLikedAndSavedUuids()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_browse_radio_registry_only, container, false)

        repository = RadioBrowserRepository(requireContext())

        findViews(view)
        setupAdapters()
        observeViewModel()

        return view
    }

    private fun findViews(view: View) {
        torStationsRecyclerView = view.findViewById(R.id.torStationsRecyclerView)
        torStationsSeeAll = view.findViewById(R.id.torStationsSeeAll)
        i2pStationsRecyclerView = view.findViewById(R.id.i2pStationsRecyclerView)
        i2pStationsSeeAll = view.findViewById(R.id.i2pStationsSeeAll)
    }

    private fun setupAdapters() {
        torStationsAdapter = PrivacyRadioCarouselAdapter(
            onStationClick = { station -> playPrivacyStation(station) },
            onLikeClick = { station -> likePrivacyStation(station) },
            showRankBadge = false
        )

        i2pStationsAdapter = PrivacyRadioCarouselAdapter(
            onStationClick = { station -> playPrivacyStation(station) },
            onLikeClick = { station -> likePrivacyStation(station) },
            showRankBadge = false
        )

        // Set up RecyclerViews with skeleton adapters initially
        val skeletonTorAdapter = SkeletonCarouselAdapter(itemCount = 4)
        val skeletonI2pAdapter = SkeletonCarouselAdapter(itemCount = 4)

        torStationsRecyclerView.adapter = skeletonTorAdapter
        torStationsRecyclerView.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false
        )

        i2pStationsRecyclerView.adapter = skeletonI2pAdapter
        i2pStationsRecyclerView.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false
        )

        // See All buttons - show full list
        torStationsSeeAll.setOnClickListener {
            // TODO: Could navigate to a full list view
            if (!com.opensource.i2pradio.ui.PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                Toast.makeText(requireContext(), R.string.browse_tor_stations, Toast.LENGTH_SHORT).show()
            }
        }

        i2pStationsSeeAll.setOnClickListener {
            // TODO: Could navigate to a full list view
            if (!com.opensource.i2pradio.ui.PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                Toast.makeText(requireContext(), R.string.browse_i2p_stations, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        // Observe Privacy Radio stations
        viewModel.privacyTorStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                val currentAdapter = torStationsRecyclerView.adapter
                if (currentAdapter is SkeletonCarouselAdapter) {
                    currentAdapter.stopAllShimmers()
                }
                torStationsRecyclerView.adapter = torStationsAdapter
            }
            torStationsAdapter.submitList(stations)
        }

        viewModel.privacyI2pStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                val currentAdapter = i2pStationsRecyclerView.adapter
                if (currentAdapter is SkeletonCarouselAdapter) {
                    currentAdapter.stopAllShimmers()
                }
                i2pStationsRecyclerView.adapter = i2pStationsAdapter
            }
            i2pStationsAdapter.submitList(stations)
        }
    }

    private fun playPrivacyStation(station: RadioRegistryStation) {
        val radioStation = viewModel.getPlayableStation(station)
        radioViewModel.setCurrentStation(radioStation)
        radioViewModel.setBuffering(true)

        val intent = Intent(requireContext(), RadioService::class.java).apply {
            action = RadioService.ACTION_PLAY
            putExtra("stream_url", radioStation.streamUrl)
            putExtra("station_name", radioStation.name)
            putExtra("proxy_host", radioStation.proxyHost)
            putExtra("proxy_port", radioStation.proxyPort)
            putExtra("proxy_type", radioStation.proxyType)
            putExtra("cover_art_uri", radioStation.coverArtUri)
            putExtra("custom_proxy_protocol", "HTTP")
            putExtra("proxy_username", "")
            putExtra("proxy_password", "")
            putExtra("proxy_auth_type", "NONE")
            putExtra("proxy_connection_timeout", 30)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun likePrivacyStation(station: RadioRegistryStation) {
        val radioStation = viewModel.getPlayableStation(station)
        val browserStation = RadioBrowserStation.fromRadioStation(radioStation)
        likeStation(browserStation)
    }

    private fun likeStation(station: RadioBrowserStation) {
        val wasLiked = viewModel.likedStationUuids.value?.contains(station.stationuuid) ?: false

        viewModel.toggleLike(station)

        lifecycleScope.launch {
            val updatedStation = repository.getStationInfoByUuid(station.stationuuid)

            if (!com.opensource.i2pradio.ui.PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                if (!wasLiked) {
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

            radioViewModel.getCurrentStation()?.let { currentStation ->
                if (currentStation.radioBrowserUuid == station.stationuuid) {
                    radioViewModel.updateCurrentStationLikeState(updatedStation?.isLiked ?: false)
                }
            }

            val broadcastIntent = Intent(MainActivity.BROADCAST_LIKE_STATE_CHANGED).apply {
                putExtra(MainActivity.EXTRA_IS_LIKED, updatedStation?.isLiked ?: false)
                putExtra(MainActivity.EXTRA_STATION_ID, updatedStation?.id ?: -1L)
                putExtra(MainActivity.EXTRA_RADIO_BROWSER_UUID, station.stationuuid)
            }
            LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(broadcastIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MainActivity.BROADCAST_LIKE_STATE_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(likeStateReceiver, filter)

        viewModel.refreshLikedAndSavedUuids()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(likeStateReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up skeleton adapters
        (torStationsRecyclerView.adapter as? SkeletonCarouselAdapter)?.stopAllShimmers()
        (i2pStationsRecyclerView.adapter as? SkeletonCarouselAdapter)?.stopAllShimmers()
    }
}

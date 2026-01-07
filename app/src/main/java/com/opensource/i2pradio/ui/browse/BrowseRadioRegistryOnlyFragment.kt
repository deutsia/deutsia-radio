package com.opensource.i2pradio.ui.browse

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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

    // Discovery mode views
    private lateinit var discoveryContainer: View
    private lateinit var discoveryContentContainer: LinearLayout
    private lateinit var torStationsRecyclerView: RecyclerView
    private lateinit var torStationsSeeAll: TextView
    private lateinit var i2pStationsRecyclerView: RecyclerView
    private lateinit var i2pStationsSeeAll: TextView

    // Results mode views
    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultsTitle: TextView
    private lateinit var btnBackToDiscovery: View
    private lateinit var resultsRecyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingContainer: FrameLayout
    private lateinit var emptyStateContainer: LinearLayout

    // Adapters
    private lateinit var torStationsAdapter: PrivacyRadioCarouselAdapter
    private lateinit var i2pStationsAdapter: PrivacyRadioCarouselAdapter
    private lateinit var resultsAdapter: PrivacyRadioListAdapter

    private val viewModel: BrowseViewModel by viewModels()
    private val radioViewModel: RadioViewModel by activityViewModels()
    private lateinit var repository: RadioBrowserRepository

    private var isInResultsMode = false
    private var currentStationType: StationType = StationType.TOR

    // Miniplayer spacing for dynamic bottom padding
    private var miniplayerSpacing = 0
    private var browseBasePadding = 0

    private enum class StationType { TOR, I2P }

    // Broadcast receiver for like state changes
    private val likeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.BROADCAST_LIKE_STATE_CHANGED) {
                viewModel.refreshLikedAndSavedUuids()
                refreshCarouselLikeStates()
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
        setupResultsMode()
        setupSharedViews()
        observeViewModel()

        return view
    }

    private fun findViews(view: View) {
        // Get miniplayer spacing dimensions
        miniplayerSpacing = resources.getDimensionPixelSize(R.dimen.miniplayer_spacing)
        browseBasePadding = resources.getDimensionPixelSize(R.dimen.browse_base_padding)

        // Discovery views
        discoveryContainer = view.findViewById(R.id.discoveryContainer)
        discoveryContentContainer = view.findViewById(R.id.discoveryContentContainer)
        torStationsRecyclerView = view.findViewById(R.id.torStationsRecyclerView)
        torStationsSeeAll = view.findViewById(R.id.torStationsSeeAll)
        i2pStationsRecyclerView = view.findViewById(R.id.i2pStationsRecyclerView)
        i2pStationsSeeAll = view.findViewById(R.id.i2pStationsSeeAll)

        // Results views
        resultsContainer = view.findViewById(R.id.resultsContainer)
        resultsTitle = view.findViewById(R.id.resultsTitle)
        btnBackToDiscovery = view.findViewById(R.id.btnBackToDiscovery)
        resultsRecyclerView = view.findViewById(R.id.resultsRecyclerView)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        loadingContainer = view.findViewById(R.id.loadingContainer)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
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
        setupCarouselTouchHandling(torStationsRecyclerView)

        i2pStationsRecyclerView.adapter = skeletonI2pAdapter
        i2pStationsRecyclerView.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false
        )
        setupCarouselTouchHandling(i2pStationsRecyclerView)

        // See All buttons
        torStationsSeeAll.setOnClickListener {
            showResultsMode(StationType.TOR)
        }

        i2pStationsSeeAll.setOnClickListener {
            showResultsMode(StationType.I2P)
        }
    }

    /**
     * Set up touch handling for horizontal RecyclerViews to work with ViewPager2.
     */
    private fun setupCarouselTouchHandling(recyclerView: RecyclerView) {
        recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            private var startX = 0f
            private var startY = 0f
            private val touchSlop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.x
                        startY = e.y
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = kotlin.math.abs(e.x - startX)
                        val dy = kotlin.math.abs(e.y - startY)
                        if (dy > touchSlop && dy > dx * 2) {
                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun setupResultsMode() {
        resultsAdapter = PrivacyRadioListAdapter(
            onStationClick = { station -> playPrivacyStation(station) },
            onLikeClick = { station -> likePrivacyStation(station) },
            onAddClick = { station -> addPrivacyStation(station) },
            onRemoveClick = { station -> removePrivacyStation(station) }
        )

        resultsRecyclerView.adapter = resultsAdapter
        resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        btnBackToDiscovery.setOnClickListener {
            switchToDiscoveryMode()
        }

        swipeRefresh.setOnRefreshListener {
            when (currentStationType) {
                StationType.TOR -> viewModel.loadApiTorStations()
                StationType.I2P -> viewModel.loadApiI2pStations()
            }
        }
    }

    private fun setupSharedViews() {
        // Observe current station to dynamically adjust bottom padding for miniplayer
        radioViewModel.currentStation.observe(viewLifecycleOwner) { station ->
            updateBottomPaddingForMiniplayer(station != null)
        }
    }

    /**
     * Update bottom padding on scrollable containers based on miniplayer visibility.
     * When a station is playing, the miniplayer is visible and we need extra padding.
     * When nothing is playing, we still apply a small base padding for visual spacing.
     */
    private fun updateBottomPaddingForMiniplayer(isMiniplayerVisible: Boolean) {
        val bottomPadding = if (isMiniplayerVisible) miniplayerSpacing else browseBasePadding

        // Update discovery container padding
        discoveryContentContainer.setPadding(
            discoveryContentContainer.paddingLeft,
            discoveryContentContainer.paddingTop,
            discoveryContentContainer.paddingRight,
            bottomPadding
        )

        // Update results list padding
        resultsRecyclerView.setPadding(
            resultsRecyclerView.paddingLeft,
            resultsRecyclerView.paddingTop,
            resultsRecyclerView.paddingRight,
            bottomPadding
        )
    }

    private fun showResultsMode(stationType: StationType) {
        isInResultsMode = true
        currentStationType = stationType
        discoveryContainer.visibility = View.GONE
        resultsContainer.visibility = View.VISIBLE

        when (stationType) {
            StationType.TOR -> {
                resultsTitle.text = getString(R.string.browse_tor_stations)
                // Use the already loaded stations
                val stations = viewModel.privacyTorStations.value ?: emptyList()
                resultsAdapter.submitList(stations)
                updateEmptyState(stations.isEmpty())
            }
            StationType.I2P -> {
                resultsTitle.text = getString(R.string.browse_i2p_stations)
                val stations = viewModel.privacyI2pStations.value ?: emptyList()
                resultsAdapter.submitList(stations)
                updateEmptyState(stations.isEmpty())
            }
        }
    }

    private fun switchToDiscoveryMode() {
        isInResultsMode = false
        resultsContainer.visibility = View.GONE
        discoveryContainer.visibility = View.VISIBLE
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            emptyStateContainer.visibility = View.VISIBLE
            resultsRecyclerView.visibility = View.GONE
        } else {
            emptyStateContainer.visibility = View.GONE
            resultsRecyclerView.visibility = View.VISIBLE
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
                setupCarouselTouchHandling(torStationsRecyclerView)
            }
            torStationsAdapter.submitList(stations)

            // Update results if in results mode showing Tor stations
            if (isInResultsMode && currentStationType == StationType.TOR) {
                resultsAdapter.submitList(stations)
                updateEmptyState(stations.isEmpty())
                swipeRefresh.isRefreshing = false
            }
        }

        viewModel.privacyI2pStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                val currentAdapter = i2pStationsRecyclerView.adapter
                if (currentAdapter is SkeletonCarouselAdapter) {
                    currentAdapter.stopAllShimmers()
                }
                i2pStationsRecyclerView.adapter = i2pStationsAdapter
                setupCarouselTouchHandling(i2pStationsRecyclerView)
            }
            i2pStationsAdapter.submitList(stations)

            // Update results if in results mode showing I2P stations
            if (isInResultsMode && currentStationType == StationType.I2P) {
                resultsAdapter.submitList(stations)
                updateEmptyState(stations.isEmpty())
                swipeRefresh.isRefreshing = false
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isInResultsMode) {
                if (isLoading && resultsAdapter.itemCount == 0) {
                    loadingContainer.visibility = View.VISIBLE
                } else {
                    loadingContainer.visibility = View.GONE
                }
                swipeRefresh.isRefreshing = isLoading && resultsAdapter.itemCount > 0
            }
        }

        // Observe liked UUIDs to update adapters
        viewModel.likedStationUuids.observe(viewLifecycleOwner) { uuids ->
            torStationsAdapter.updateLikedUuids(uuids)
            i2pStationsAdapter.updateLikedUuids(uuids)
            resultsAdapter.updateLikedUuids(uuids)
        }

        // Observe saved UUIDs to update adapters
        viewModel.savedStationUuids.observe(viewLifecycleOwner) { uuids ->
            resultsAdapter.updateSavedUuids(uuids)
        }
    }

    private fun refreshCarouselLikeStates() {
        val likedUuids = viewModel.likedStationUuids.value ?: emptySet()
        torStationsAdapter.updateLikedUuids(likedUuids)
        i2pStationsAdapter.updateLikedUuids(likedUuids)
        resultsAdapter.updateLikedUuids(likedUuids)
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
        val browserStation = RadioBrowserStation.fromRegistryStation(station)
        likeStation(browserStation)
    }

    private fun addPrivacyStation(station: RadioRegistryStation) {
        val browserStation = RadioBrowserStation.fromRegistryStation(station)
        addStation(browserStation)
    }

    private fun removePrivacyStation(station: RadioRegistryStation) {
        val browserStation = RadioBrowserStation.fromRegistryStation(station)
        removeStation(browserStation)
    }

    private fun addStation(station: RadioBrowserStation) {
        viewModel.saveStation(station)
        viewModel.refreshLikedAndSavedUuids()

        if (!com.opensource.i2pradio.ui.PreferencesHelper.isToastMessagesDisabled(requireContext())) {
            Toast.makeText(
                requireContext(),
                getString(R.string.station_saved, station.name),
                Toast.LENGTH_SHORT
            ).show()
        }

        val broadcastIntent = Intent(MainActivity.BROADCAST_LIKE_STATE_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(broadcastIntent)
    }

    private fun removeStation(station: RadioBrowserStation) {
        viewModel.removeStation(station)
        viewModel.refreshLikedAndSavedUuids()

        if (!com.opensource.i2pradio.ui.PreferencesHelper.isToastMessagesDisabled(requireContext())) {
            Toast.makeText(
                requireContext(),
                getString(R.string.station_removed, station.name),
                Toast.LENGTH_SHORT
            ).show()
        }

        val broadcastIntent = Intent(MainActivity.BROADCAST_LIKE_STATE_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(broadcastIntent)
    }

    private fun likeStation(station: RadioBrowserStation) {
        val wasLiked = viewModel.likedStationUuids.value?.contains(station.stationuuid) ?: false
        val wasSaved = viewModel.savedStationUuids.value?.contains(station.stationuuid) ?: false

        viewModel.toggleLike(station)

        lifecycleScope.launch {
            val updatedStation = repository.getStationInfoByUuid(station.stationuuid)

            if (!com.opensource.i2pradio.ui.PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                if (!wasLiked) {
                    if (!wasSaved) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.station_saved, station.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.station_added_to_favorites, station.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    val stationAge = if (updatedStation != null) {
                        System.currentTimeMillis() - updatedStation.addedTimestamp
                    } else {
                        0L
                    }

                    val fiveMinutesInMillis = 5 * 60 * 1000
                    if (stationAge > fiveMinutesInMillis) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.station_removed_from_favorites, station.name),
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
        refreshCarouselLikeStates()
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

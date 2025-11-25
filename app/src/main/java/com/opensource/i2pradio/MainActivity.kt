package com.opensource.i2pradio

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import android.widget.FrameLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.DynamicColors
import com.opensource.i2pradio.ui.SettingsFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.opensource.i2pradio.data.RadioRepository
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserRepository
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.MiniPlayerView
import com.opensource.i2pradio.ui.NowPlayingFragment
import com.opensource.i2pradio.ui.PreferencesHelper
import com.opensource.i2pradio.ui.RadioViewModel
import com.opensource.i2pradio.ui.LibraryFragment
import com.opensource.i2pradio.ui.TorQuickControlBottomSheet
import com.opensource.i2pradio.ui.TorStatusView
import com.opensource.i2pradio.ui.browse.BrowseStationsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var miniPlayerContainer: FrameLayout
    private lateinit var miniPlayerView: MiniPlayerView
    private lateinit var torStatusView: TorStatusView
    private lateinit var repository: RadioRepository
    private lateinit var radioBrowserRepository: RadioBrowserRepository
    private val viewModel: RadioViewModel by viewModels()

    private var radioService: RadioService? = null
    private var isServiceBound = false
    private var miniPlayerManuallyClosed = false
    private var lastStationId: Long? = null
    private var lastStationUrl: String? = null  // Use URL to detect station changes for unsaved stations

    // Tor state listener
    private val torStateListener: (TorManager.TorState) -> Unit = { state ->
        runOnUiThread {
            torStatusView.updateState(state)
        }
    }

    // Broadcast receiver for playback state, cover art, and like state changes
    private val playbackStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RadioService.BROADCAST_PLAYBACK_STATE_CHANGED -> {
                    val isBuffering = intent.getBooleanExtra(RadioService.EXTRA_IS_BUFFERING, false)
                    val isPlaying = intent.getBooleanExtra(RadioService.EXTRA_IS_PLAYING, false)
                    viewModel.setBuffering(isBuffering)
                    viewModel.setPlaying(isPlaying)
                }
                RadioService.BROADCAST_COVER_ART_CHANGED -> {
                    val coverArtUri = intent.getStringExtra(RadioService.EXTRA_COVER_ART_URI)
                    val stationId = intent.getLongExtra(RadioService.EXTRA_STATION_ID, -1L)
                    // Update ViewModel which will trigger MiniPlayer update
                    viewModel.updateCoverArt(coverArtUri, stationId)
                }
                BROADCAST_LIKE_STATE_CHANGED -> {
                    val isLiked = intent.getBooleanExtra(EXTRA_IS_LIKED, false)
                    val stationId = intent.getLongExtra(EXTRA_STATION_ID, -1L)
                    val radioBrowserUuid = intent.getStringExtra(EXTRA_RADIO_BROWSER_UUID)

                    // Update the current station's like state if it matches
                    viewModel.getCurrentStation()?.let { currentStation ->
                        val isCurrentStation = if (!radioBrowserUuid.isNullOrEmpty()) {
                            currentStation.radioBrowserUuid == radioBrowserUuid
                        } else {
                            currentStation.id == stationId
                        }

                        if (isCurrentStation) {
                            viewModel.updateCurrentStationLikeState(isLiked)
                            miniPlayerView.updateLikeState(isLiked)
                        }
                    }
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioService.RadioBinder
            radioService = binder.getService()
            isServiceBound = true

            // After reconnecting, update ViewModel with current playback state
            // This ensures UI is synchronized after activity recreation (e.g., Material You toggle)
            radioService?.let { service ->
                viewModel.setPlaying(service.isPlaying())
                viewModel.setBuffering(service.isBuffering())
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService = null
            isServiceBound = false
        }
    }
    fun switchToLibraryTab() {
        viewPager.currentItem = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme BEFORE super.onCreate
        val savedTheme = PreferencesHelper.getThemeMode(this)
        AppCompatDelegate.setDefaultNightMode(savedTheme)

        // Apply color scheme theme (only if Material You is disabled)
        // Material You takes precedence when enabled on Android 12+
        val isMaterialYouEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                PreferencesHelper.isMaterialYouEnabled(this)

        if (!isMaterialYouEnabled) {
            // Apply custom color scheme based on user preference
            val colorScheme = PreferencesHelper.getColorScheme(this)
            val themeResId = when (colorScheme) {
                "peach" -> R.style.Theme_I2PRadio_Peach
                "green" -> R.style.Theme_I2PRadio_Green
                "purple" -> R.style.Theme_I2PRadio_Purple
                "orange" -> R.style.Theme_I2PRadio_Orange
                else -> R.style.Theme_I2PRadio // default blue
            }
            setTheme(themeResId)
        } else {
            // Apply Material You dynamic colors if enabled (Android 12+)
            DynamicColors.applyToActivityIfAvailable(this)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = RadioRepository(this)
        radioBrowserRepository = RadioBrowserRepository(this)

        lifecycleScope.launch(Dispatchers.IO) {
            repository.initializePresetStations(this@MainActivity)  // Pass context
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        miniPlayerContainer = findViewById(R.id.miniPlayerContainer)

        // Setup Tor status indicator in toolbar
        setupTorStatusView()

        setupViewPager()
        setupMiniPlayer()

        Intent(this, RadioService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Only initialize TorManager if user has enabled Orbot integration in settings
        if (PreferencesHelper.isEmbeddedTorEnabled(this)) {
            TorManager.initialize(this)
        }

        // Register broadcast receiver for playback state and cover art changes
        val filter = IntentFilter().apply {
            addAction(RadioService.BROADCAST_PLAYBACK_STATE_CHANGED)
            addAction(RadioService.BROADCAST_COVER_ART_CHANGED)
            addAction(BROADCAST_LIKE_STATE_CHANGED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(playbackStateReceiver, filter)
    }

    private fun setupTorStatusView() {
        torStatusView = findViewById(R.id.torStatusView)

        // Set compact mode for toolbar (icon + short text)
        torStatusView.setCompactMode(false)

        // Handle click to show quick control bottom sheet
        torStatusView.setOnStatusClickListener {
            showTorQuickControlBottomSheet()
        }

        // Listen for Tor state changes
        TorManager.addStateListener(torStateListener)

        // Update initial state
        torStatusView.updateState(TorManager.state)
    }

    private fun showTorQuickControlBottomSheet() {
        val bottomSheet = TorQuickControlBottomSheet.newInstance()
        bottomSheet.show(supportFragmentManager, TorQuickControlBottomSheet.TAG)
    }

    private fun setupMiniPlayer() {
        miniPlayerView = MiniPlayerView(this)
        miniPlayerContainer.addView(miniPlayerView)

        // Observe current station
        viewModel.currentStation.observe(this) { station ->
            if (station == null) {
                // Station cleared - hide mini player
                miniPlayerView.setStation(null)
                miniPlayerContainer.visibility = View.GONE
                viewModel.setMiniPlayerVisible(false)
                miniPlayerManuallyClosed = false
                lastStationId = null
                lastStationUrl = null
            } else {
                // Check if this is a new station (user clicked a new radio)
                // Use both ID and URL to detect changes - this handles unsaved browse stations
                // which all have id=0 but different URLs
                val isNewStation = if (station.id != 0L) {
                    lastStationId != station.id
                } else {
                    // For unsaved stations (id=0), compare by stream URL
                    lastStationUrl != station.streamUrl
                }

                if (isNewStation) {
                    // Reset the manually closed flag when a new station is selected
                    miniPlayerManuallyClosed = false
                    lastStationId = station.id
                    lastStationUrl = station.streamUrl

                    // Only show mini player if not manually closed
                    if (!miniPlayerManuallyClosed) {
                        miniPlayerView.setStation(station)
                        miniPlayerContainer.visibility = View.VISIBLE
                        viewModel.setMiniPlayerVisible(true)
                    }
                } else {
                    // Same station, just update like state without animations
                    miniPlayerView.updateLikeState(station.isLiked)
                }
            }
        }

        // Observe playing state
        viewModel.isPlaying.observe(this) { isPlaying ->
            miniPlayerView.setPlayingState(isPlaying)
        }

        // Observe buffering state to show/hide loading indicator
        viewModel.isBuffering.observe(this) { isBuffering ->
            miniPlayerView.setBufferingState(isBuffering)
        }

        // Observe cover art updates for real-time updates
        viewModel.coverArtUpdate.observe(this) { update ->
            update?.let {
                miniPlayerView.updateCoverArt(it.coverArtUri)
            }
        }

        // Click mini-player to go to Now Playing tab
        miniPlayerView.setOnMiniPlayerClickListener {
            viewPager.currentItem = 2  // Switch to Now Playing tab (index 2 now)
        }

        // Play/pause toggle - update ViewModel to sync state and trigger animation
        miniPlayerView.setOnPlayPauseToggleListener { isPlaying ->
            viewModel.setPlaying(isPlaying)
        }

        // Close button stops playback and hides the mini player
        miniPlayerView.setOnCloseListener {
            miniPlayerManuallyClosed = true
            // Stop the radio service
            val stopIntent = Intent(this, RadioService::class.java).apply {
                action = RadioService.ACTION_STOP
            }
            startService(stopIntent)
            // Clear the station from ViewModel
            viewModel.setCurrentStation(null)
            viewModel.setPlaying(false)
            miniPlayerView.setStation(null)
            miniPlayerContainer.visibility = View.GONE
            viewModel.setMiniPlayerVisible(false)
        }

        // Like button toggles liked state in database
        miniPlayerView.setOnLikeToggleListener { station ->
            lifecycleScope.launch(Dispatchers.IO) {
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
                    withContext(Dispatchers.Main) {
                        updatedStation?.let {
                            miniPlayerView.updateLikeState(it.isLiked)
                            viewModel.updateCurrentStationLikeState(it.isLiked)

                            // Broadcast like state change to all views
                            val broadcastIntent = Intent(BROADCAST_LIKE_STATE_CHANGED).apply {
                                putExtra(EXTRA_IS_LIKED, it.isLiked)
                                putExtra(EXTRA_STATION_ID, it.id)
                                putExtra(EXTRA_RADIO_BROWSER_UUID, station.radioBrowserUuid)
                            }
                            LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(broadcastIntent)

                            // Show toast message for both like and unlike
                            if (it.isLiked) {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.station_saved, station.name),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.station_removed, station.name),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else {
                    // For non-global radios (user stations, bundled stations), use regular toggle
                    repository.toggleLike(station.id)
                    val updatedStation = repository.getStationById(station.id)
                    withContext(Dispatchers.Main) {
                        updatedStation?.let {
                            miniPlayerView.updateLikeState(it.isLiked)
                            viewModel.updateCurrentStationLikeState(it.isLiked)

                            // Broadcast like state change to all views
                            val broadcastIntent = Intent(BROADCAST_LIKE_STATE_CHANGED).apply {
                                putExtra(EXTRA_IS_LIKED, it.isLiked)
                                putExtra(EXTRA_STATION_ID, it.id)
                                putExtra(EXTRA_RADIO_BROWSER_UUID, station.radioBrowserUuid)
                            }
                            LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(broadcastIntent)

                            // Show toast message for both like and unlike
                            if (it.isLiked) {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.station_saved, station.name),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
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

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter

        // Add smooth page transformation with fade and slight scale
        viewPager.setPageTransformer { page, position ->
            val absPosition = kotlin.math.abs(position)
            // Fade effect
            page.alpha = 1f - absPosition * 0.25f
            // Slight scale effect
            val scale = 1f - absPosition * 0.04f
            page.scaleX = scale
            page.scaleY = scale
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_radios)
                1 -> getString(R.string.tab_browse)
                2 -> getString(R.string.tab_now_playing)
                3 -> getString(R.string.tab_settings)
                else -> ""
            }
        }.attach()

        // Handle page changes to show/hide mini player
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position == 2) {
                    // On Now Playing tab - keep mini player hidden
                    miniPlayerView.hideForNowPlaying()
                    miniPlayerContainer.visibility = View.INVISIBLE
                    viewModel.setMiniPlayerVisible(false)
                } else {
                    // On other tabs - show mini player if station is playing
                    if (viewModel.currentStation.value != null && !miniPlayerManuallyClosed) {
                        miniPlayerContainer.visibility = View.VISIBLE
                        miniPlayerView.showWithAnimation()
                        viewModel.setMiniPlayerVisible(true)
                    } else {
                        viewModel.setMiniPlayerVisible(false)
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Refresh Tor status when app comes to foreground (only if Orbot integration is enabled)
        if (PreferencesHelper.isEmbeddedTorEnabled(this)) {
            TorManager.requestOrbotStatus(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove Tor state listener
        TorManager.removeStateListener(torStateListener)

        // Unregister playback state receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackStateReceiver)

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    companion object {
        // Broadcast action for like state changes
        const val BROADCAST_LIKE_STATE_CHANGED = "com.opensource.i2pradio.LIKE_STATE_CHANGED"
        const val EXTRA_STATION_ID = "station_id"
        const val EXTRA_RADIO_BROWSER_UUID = "radio_browser_uuid"
        const val EXTRA_IS_LIKED = "is_liked"
    }

    private inner class ViewPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> LibraryFragment()
                1 -> BrowseStationsFragment()
                2 -> NowPlayingFragment()
                3 -> SettingsFragment()
                else -> LibraryFragment()  // Fallback
            }
        }
    }
}
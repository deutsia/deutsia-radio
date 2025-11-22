package com.opensource.i2pradio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.opensource.i2pradio.ui.SettingsFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.opensource.i2pradio.data.RadioRepository
import com.opensource.i2pradio.ui.MiniPlayerView
import com.opensource.i2pradio.ui.NowPlayingFragment
import com.opensource.i2pradio.ui.PreferencesHelper
import com.opensource.i2pradio.ui.RadioViewModel
import com.opensource.i2pradio.ui.RadiosFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var miniPlayerContainer: FrameLayout
    private lateinit var miniPlayerView: MiniPlayerView
    private lateinit var repository: RadioRepository
    private val viewModel: RadioViewModel by viewModels()

    private var radioService: RadioService? = null
    private var isServiceBound = false
    private var miniPlayerManuallyClosed = false
    private var lastStationId: Long? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioService.RadioBinder
            radioService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService = null
            isServiceBound = false
        }
    }
    fun switchToRadiosTab() {
        viewPager.currentItem = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme BEFORE super.onCreate
        val savedTheme = PreferencesHelper.getThemeMode(this)
        AppCompatDelegate.setDefaultNightMode(savedTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = RadioRepository(this)

        CoroutineScope(Dispatchers.IO).launch {
            repository.initializePresetStations(this@MainActivity)  // Pass context
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        miniPlayerContainer = findViewById(R.id.miniPlayerContainer)

        setupViewPager()
        setupMiniPlayer()

        Intent(this, RadioService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
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
                miniPlayerManuallyClosed = false
                lastStationId = null
            } else {
                // Check if this is a new station (user clicked a new radio)
                val isNewStation = lastStationId != station.id
                if (isNewStation) {
                    // Reset the manually closed flag when a new station is selected
                    miniPlayerManuallyClosed = false
                }
                lastStationId = station.id

                // Only show mini player if not manually closed
                if (!miniPlayerManuallyClosed) {
                    miniPlayerView.setStation(station)
                    miniPlayerContainer.visibility = View.VISIBLE
                }
            }
        }

        // Observe playing state
        viewModel.isPlaying.observe(this) { isPlaying ->
            miniPlayerView.setPlayingState(isPlaying)
        }

        // Click mini-player to go to Now Playing tab
        miniPlayerView.setOnMiniPlayerClickListener {
            viewPager.currentItem = 1  // Switch to Now Playing tab
        }

        // Close button hides the mini player
        miniPlayerView.setOnCloseListener {
            miniPlayerManuallyClosed = true
            miniPlayerView.setStation(null)
            miniPlayerContainer.visibility = View.GONE
        }
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Radio Stations"
                1 -> "Now Playing"
                2 -> "Settings"
                else -> ""
            }
        }.attach()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private inner class ViewPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> RadiosFragment()
                1 -> NowPlayingFragment()
                2 -> SettingsFragment()  // We'll create this next!
                else -> RadiosFragment()  // Fallback
            }
        }
    }
}
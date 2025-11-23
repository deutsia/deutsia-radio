package com.opensource.i2pradio.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.Disposable
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.RadioRepository
import com.opensource.i2pradio.data.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RadiosFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateContainer: View
    private lateinit var fabAddRadio: FloatingActionButton
    private lateinit var sortButton: MaterialButton
    private lateinit var adapter: RadioStationAdapter
    private lateinit var repository: RadioRepository
    private val viewModel: RadioViewModel by activityViewModels()

    private var currentSortOrder = SortOrder.DEFAULT
    private var currentStationsObserver: LiveData<List<RadioStation>>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_radios, container, false)

        repository = RadioRepository(requireContext())

        recyclerView = view.findViewById(R.id.radiosRecyclerView)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        fabAddRadio = view.findViewById(R.id.fabAddRadio)
        sortButton = view.findViewById(R.id.sortButton)

        adapter = RadioStationAdapter(
            onStationClick = { station -> playStation(station) },
            onMenuClick = { station, anchor -> showStationMenu(station, anchor) }
        )
        recyclerView.adapter = adapter

        // Load saved sort order
        val savedSortOrder = PreferencesHelper.getSortOrder(requireContext())
        currentSortOrder = try {
            SortOrder.valueOf(savedSortOrder)
        } catch (e: Exception) {
            SortOrder.DEFAULT
        }
        updateSortButtonText()

        // Observe radio stations with current sort order
        observeStations()

        fabAddRadio.setOnClickListener {
            showAddRadioDialog()
        }

        sortButton.setOnClickListener {
            showSortDialog()
        }

        view.findViewById<MaterialButton>(R.id.emptyStateAddButton).setOnClickListener {
            showAddRadioDialog()
        }

        return view
    }

    private fun observeStations() {
        // Remove old observer
        currentStationsObserver?.removeObservers(viewLifecycleOwner)

        // Get new LiveData based on sort order
        currentStationsObserver = repository.getStationsSorted(currentSortOrder)
        currentStationsObserver?.observe(viewLifecycleOwner) { stations ->
            if (stations.isEmpty()) {
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                adapter.submitList(stations)
            }
        }
    }

    private fun showSortDialog() {
        val sortOptions = arrayOf("Default", "Name", "Recently Played")
        val currentIndex = currentSortOrder.ordinal

        AlertDialog.Builder(requireContext())
            .setTitle("Sort Stations")
            .setSingleChoiceItems(sortOptions, currentIndex) { dialog, which ->
                currentSortOrder = SortOrder.values()[which]
                PreferencesHelper.setSortOrder(requireContext(), currentSortOrder.name)
                updateSortButtonText()
                observeStations()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateSortButtonText() {
        sortButton.text = when (currentSortOrder) {
            SortOrder.DEFAULT -> "Default"
            SortOrder.NAME -> "Name"
            SortOrder.RECENTLY_PLAYED -> "Recent"
        }
    }

    private fun playStation(station: RadioStation) {
        viewModel.setCurrentStation(station)
        viewModel.setPlaying(true)

        // Update last played timestamp
        CoroutineScope(Dispatchers.IO).launch {
            repository.updateLastPlayedAt(station.id)
        }

        val proxyType = station.getProxyTypeEnum()
        val intent = Intent(requireContext(), RadioService::class.java).apply {
            action = RadioService.ACTION_PLAY
            putExtra("stream_url", station.streamUrl)
            putExtra("station_name", station.name)
            putExtra("proxy_host", if (station.useProxy) station.proxyHost else "")
            putExtra("proxy_port", station.proxyPort)
            putExtra("proxy_type", proxyType.name)
            putExtra("cover_art_uri", station.coverArtUri)
        }
        requireContext().startService(intent)
    }

    private fun showAddRadioDialog() {
        val dialog = AddEditRadioDialog.newInstance()
        dialog.show(parentFragmentManager, "AddEditRadioDialog")
    }

    private fun showStationMenu(station: RadioStation, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.station_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> {
                    val dialog = AddEditRadioDialog.newInstance(station)
                    dialog.show(parentFragmentManager, "AddEditRadioDialog")
                    true
                }
                R.id.action_delete -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.deleteStation(station)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}

// Updated Adapter with DiffUtil and stable IDs to prevent cover art duplication
class RadioStationAdapter(
    private val onStationClick: (RadioStation) -> Unit,
    private val onMenuClick: (RadioStation, View) -> Unit
) : RecyclerView.Adapter<RadioStationAdapter.ViewHolder>() {

    private var stations = listOf<RadioStation>()

    init {
        setHasStableIds(true)
    }

    fun submitList(newStations: List<RadioStation>) {
        val diffCallback = RadioStationDiffCallback(stations, newStations)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        stations = newStations
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long = stations[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_radio_station, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(stations[position])
    }

    override fun getItemCount() = stations.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverArt: ImageView = itemView.findViewById(R.id.coverArtImage)
        private val stationName: TextView = itemView.findViewById(R.id.stationNameText)
        private val genreText: TextView = itemView.findViewById(R.id.genreText)
        private val menuButton: MaterialButton = itemView.findViewById(R.id.menuButton)
        private var imageLoadDisposable: Disposable? = null

        fun bind(station: RadioStation) {
            stationName.text = station.name

            // Show proxy type indicator (I2P or Tor) and liked indicator
            val proxyIndicator = if (station.useProxy) {
                when (station.getProxyTypeEnum()) {
                    ProxyType.I2P -> " • I2P"
                    ProxyType.TOR -> " • Tor"
                    ProxyType.NONE -> ""
                }
            } else ""
            val likedIndicator = if (station.isLiked) " ♥" else ""
            genreText.text = "${station.genre}$proxyIndicator$likedIndicator"

            // Cancel any pending image load and clear the image first to prevent ghosting
            imageLoadDisposable?.dispose()
            coverArt.setImageResource(R.drawable.ic_radio)

            if (station.coverArtUri != null) {
                imageLoadDisposable = coverArt.load(station.coverArtUri) {
                    crossfade(true)
                    placeholder(R.drawable.ic_radio)
                    error(R.drawable.ic_radio)
                }
            }

            // Touch animation for press feedback
            itemView.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate()
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(100)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
                false
            }

            itemView.setOnClickListener {
                onStationClick(station)
            }

            menuButton.setOnClickListener {
                onMenuClick(station, it)
            }
        }
    }

    // DiffUtil callback for efficient list updates
    private class RadioStationDiffCallback(
        private val oldList: List<RadioStation>,
        private val newList: List<RadioStation>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old.name == new.name &&
                    old.genre == new.genre &&
                    old.coverArtUri == new.coverArtUri &&
                    old.useProxy == new.useProxy &&
                    old.proxyType == new.proxyType &&
                    old.isLiked == new.isLiked
        }
    }
}

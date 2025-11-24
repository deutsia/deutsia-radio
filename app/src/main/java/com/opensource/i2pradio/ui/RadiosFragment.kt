package com.opensource.i2pradio.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.request.Disposable
import com.opensource.i2pradio.util.loadSecure
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
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
    private lateinit var genreFilterButton: MaterialButton
    private lateinit var searchInput: TextInputEditText
    private lateinit var adapter: RadioStationAdapter
    private lateinit var repository: RadioRepository
    private val viewModel: RadioViewModel by activityViewModels()

    private var currentSortOrder = SortOrder.DEFAULT
    private var currentGenreFilter: String? = null // null means "All Genres"
    private var currentSearchQuery: String = ""
    private var currentStationsObserver: LiveData<List<RadioStation>>? = null

    // Cache for search filtering
    private var allStationsCache: List<RadioStation> = emptyList()

    // Predefined genres list (sorted alphabetically)
    private val allGenres = listOf(
        "All Genres", "Alternative", "Ambient", "Blues", "Christian", "Classical",
        "Comedy", "Country", "Dance", "EDM", "Electronic", "Folk",
        "Funk", "Gospel", "Hip Hop", "Indie", "Jazz", "K-Pop",
        "Latin", "Lo-Fi", "Metal", "News", "Oldies", "Pop", "Punk",
        "R&B", "Reggae", "Rock", "Soul", "Sports", "Talk", "World", "Other"
    )

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
        genreFilterButton = view.findViewById(R.id.genreFilterButton)
        searchInput = view.findViewById(R.id.searchInput)

        adapter = RadioStationAdapter(
            onStationClick = { station -> playStation(station) },
            onMenuClick = { station, anchor -> showStationMenu(station, anchor) },
            onLikeClick = { station -> toggleLike(station) }
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

        // Load saved genre filter
        currentGenreFilter = PreferencesHelper.getGenreFilter(requireContext())
        updateGenreFilterButtonText()

        // Setup search functionality
        setupSearchFunctionality()

        // Observe radio stations with current sort order and genre filter
        observeStations()

        fabAddRadio.setOnClickListener {
            showAddRadioDialog()
        }

        sortButton.setOnClickListener {
            showSortDialog()
        }

        genreFilterButton.setOnClickListener {
            showGenreFilterDialog()
        }

        view.findViewById<MaterialButton>(R.id.emptyStateAddButton).setOnClickListener {
            showAddRadioDialog()
        }

        return view
    }

    private fun setupSearchFunctionality() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                filterStations()
            }
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // Hide keyboard
                searchInput.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun observeStations() {
        // Remove old observer
        currentStationsObserver?.removeObservers(viewLifecycleOwner)

        // Get new LiveData based on sort order and genre filter
        currentStationsObserver = if (currentGenreFilter != null) {
            repository.getStationsByGenreSorted(currentGenreFilter!!, currentSortOrder)
        } else {
            repository.getStationsSorted(currentSortOrder)
        }

        currentStationsObserver?.observe(viewLifecycleOwner) { stations ->
            // Update cache for search filtering
            allStationsCache = stations
            filterStations()
        }
    }

    private fun filterStations() {
        val filteredStations = if (currentSearchQuery.isEmpty()) {
            allStationsCache
        } else {
            allStationsCache.filter { station ->
                station.name.contains(currentSearchQuery, ignoreCase = true) ||
                station.genre.contains(currentSearchQuery, ignoreCase = true)
            }
        }

        if (filteredStations.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateContainer.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateContainer.visibility = View.GONE
            adapter.submitList(filteredStations)
        }
    }

    private fun showSortDialog() {
        val sortOptions = arrayOf(
            getString(R.string.sort_default),
            getString(R.string.sort_name),
            getString(R.string.sort_recent),
            getString(R.string.sort_liked),
            getString(R.string.sort_genre)
        )
        val currentIndex = currentSortOrder.ordinal

        AlertDialog.Builder(requireContext())
            .setTitle("Sort Stations")
            .setSingleChoiceItems(sortOptions, currentIndex) { dialog, which ->
                currentSortOrder = SortOrder.entries[which]
                PreferencesHelper.setSortOrder(requireContext(), currentSortOrder.name)
                updateSortButtonText()
                observeStations()
                dialog.dismiss()
            }
            .show()
    }

    private fun showGenreFilterDialog() {
        // Build the genre list - combine predefined genres with any genres from database
        CoroutineScope(Dispatchers.Main).launch {
            val dbGenres = try {
                repository.getAllGenresSync()
            } catch (e: Exception) {
                emptyList()
            }

            // Merge predefined genres with database genres, keeping alphabetical order
            val combinedGenres = (allGenres + dbGenres)
                .distinct()
                .sortedWith(compareBy {
                    // "All Genres" first, "Other" last, rest alphabetically
                    when (it) {
                        "All Genres" -> "0$it"
                        "Other" -> "ZZZ$it"
                        else -> "A$it"
                    }
                })

            val currentIndex = if (currentGenreFilter == null) {
                0 // "All Genres"
            } else {
                combinedGenres.indexOf(currentGenreFilter).takeIf { it >= 0 } ?: 0
            }

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.filter_by_genre))
                .setSingleChoiceItems(combinedGenres.toTypedArray(), currentIndex) { dialog, which ->
                    val selectedGenre = combinedGenres[which]
                    currentGenreFilter = if (selectedGenre == "All Genres") null else selectedGenre
                    PreferencesHelper.setGenreFilter(requireContext(), currentGenreFilter)
                    updateGenreFilterButtonText()
                    // Clear search when changing genre filter
                    currentSearchQuery = ""
                    searchInput.setText("")
                    observeStations()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun updateSortButtonText() {
        sortButton.text = when (currentSortOrder) {
            SortOrder.DEFAULT -> getString(R.string.sort_default)
            SortOrder.NAME -> getString(R.string.sort_name)
            SortOrder.RECENTLY_PLAYED -> getString(R.string.sort_recent)
            SortOrder.LIKED -> getString(R.string.sort_liked)
            SortOrder.GENRE -> getString(R.string.sort_genre)
        }
    }

    private fun updateGenreFilterButtonText() {
        genreFilterButton.text = currentGenreFilter ?: getString(R.string.genre_all)
    }

    private fun playStation(station: RadioStation) {
        viewModel.setCurrentStation(station)
        viewModel.setBuffering(true)  // Show buffering state while connecting

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
        // Use startForegroundService for Android 8+ compatibility
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showAddRadioDialog() {
        val dialog = AddEditRadioDialog.newInstance()
        dialog.show(parentFragmentManager, "AddEditRadioDialog")
    }

    private fun toggleLike(station: RadioStation) {
        CoroutineScope(Dispatchers.IO).launch {
            repository.toggleLike(station.id)
            // Also update ViewModel if this is the currently playing station
            val updatedStation = repository.getStationById(station.id)
            CoroutineScope(Dispatchers.Main).launch {
                updatedStation?.let {
                    // Update current station's like state in ViewModel if it matches
                    if (viewModel.getCurrentStation()?.id == it.id) {
                        viewModel.updateCurrentStationLikeState(it.isLiked)
                    }
                }
            }
        }
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
    private val onMenuClick: (RadioStation, View) -> Unit,
    private val onLikeClick: (RadioStation) -> Unit
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
        private val likeButton: MaterialButton = itemView.findViewById(R.id.likeButton)
        private var imageLoadDisposable: Disposable? = null

        fun bind(station: RadioStation) {
            stationName.text = station.name

            // Show proxy type indicator (I2P or Tor)
            val proxyIndicator = if (station.useProxy) {
                when (station.getProxyTypeEnum()) {
                    ProxyType.I2P -> " • I2P"
                    ProxyType.TOR -> " • Tor"
                    ProxyType.NONE -> ""
                }
            } else ""
            genreText.text = "${station.genre}$proxyIndicator"

            // Update like button icon and color based on liked state
            updateLikeButton(station.isLiked)

            // Cancel any pending image load and clear the image first to prevent ghosting
            imageLoadDisposable?.dispose()
            coverArt.setImageResource(R.drawable.ic_radio)

            // Use loadSecure to route remote URLs through Tor when Force Tor is enabled
            if (station.coverArtUri != null) {
                imageLoadDisposable = coverArt.loadSecure(station.coverArtUri) {
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

            likeButton.setOnClickListener {
                onLikeClick(station)
            }
        }

        private fun updateLikeButton(isLiked: Boolean) {
            val iconRes = if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            likeButton.setIconResource(iconRes)
            if (isLiked) {
                likeButton.setIconTintResource(com.google.android.material.R.color.design_default_color_error)
            } else {
                likeButton.iconTint = android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        itemView, com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
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

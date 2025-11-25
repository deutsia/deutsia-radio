package com.opensource.i2pradio.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.request.Disposable
import com.opensource.i2pradio.util.loadSecure
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.RadioRepository
import com.opensource.i2pradio.data.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment() {
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

    // Selection mode
    private var actionMode: ActionMode? = null

    // Broadcast receiver for like state changes from other views
    private val likeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.BROADCAST_LIKE_STATE_CHANGED) {
                // Refresh the adapter to update like icons
                adapter.notifyDataSetChanged()
            }
        }
    }

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
        val view = inflater.inflate(R.layout.fragment_library, container, false)

        repository = RadioRepository(requireContext())

        recyclerView = view.findViewById(R.id.libraryRecyclerView)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        fabAddRadio = view.findViewById(R.id.fabAddRadio)
        sortButton = view.findViewById(R.id.sortButton)
        genreFilterButton = view.findViewById(R.id.genreFilterButton)
        searchInput = view.findViewById(R.id.searchInput)

        adapter = RadioStationAdapter(
            onStationClick = { station -> playStation(station) },
            onMenuClick = { station, anchor -> showStationMenu(station, anchor) },
            onLikeClick = { station -> toggleLike(station) },
            onLongPress = { station -> startSelectionMode(station) },
            onSelectionChanged = { updateActionModeTitle() }
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

        // Observe miniplayer visibility to adjust FAB margin dynamically
        viewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isVisible ->
            adjustFabMarginForMiniPlayer(isVisible)
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
        lifecycleScope.launch(Dispatchers.Main) {
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

            // Create custom dialog with search functionality
            val dialogView = LayoutInflater.from(requireContext()).inflate(
                android.R.layout.select_dialog_singlechoice, null
            )

            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.filter_by_genre))
                .setView(createGenreSearchView(combinedGenres, currentIndex))
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            dialog.show()
        }
    }

    private fun createGenreSearchView(genres: List<String>, selectedIndex: Int): View {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 0)
        }

        // Search input
        val searchInput = TextInputEditText(context).apply {
            hint = "Search genres..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(16, 16, 16, 16)
        }

        val searchLayout = com.google.android.material.textfield.TextInputLayout(context).apply {
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            isHintEnabled = true
            addView(searchInput)
        }

        container.addView(searchLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 16
        })

        // RecyclerView for genre list
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f
            }
        }

        var filteredGenres = genres.toList()
        val adapter = GenreAdapter(filteredGenres, selectedIndex) { selectedGenre ->
            currentGenreFilter = if (selectedGenre == "All Genres") null else selectedGenre
            PreferencesHelper.setGenreFilter(requireContext(), currentGenreFilter)
            updateGenreFilterButtonText()
            // Clear search when changing genre filter
            currentSearchQuery = ""
            this.searchInput.setText("")
            observeStations()
            // Dismiss the dialog
            (recyclerView.parent?.parent as? android.app.Dialog)?.dismiss()
        }
        recyclerView.adapter = adapter

        // Search functionality
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                filteredGenres = if (query.isEmpty()) {
                    genres
                } else {
                    genres.filter { it.contains(query, ignoreCase = true) }
                }
                adapter.updateGenres(filteredGenres)
            }
        })

        container.addView(recyclerView)
        return container
    }

    // Adapter for genre list with search
    private inner class GenreAdapter(
        private var genres: List<String>,
        private val selectedIndex: Int,
        private val onGenreSelected: (String) -> Unit
    ) : RecyclerView.Adapter<GenreAdapter.ViewHolder>() {

        private var selectedPosition = selectedIndex

        fun updateGenres(newGenres: List<String>) {
            genres = newGenres
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView? = view.findViewById(android.R.id.text1)
            val radioButton: android.widget.RadioButton? = view.findViewById(R.id.radio_button)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.item_genre_choice, parent, false
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // Add bounds check to prevent IndexOutOfBoundsException during concurrent modification
            if (position >= genres.size) return

            val genre = genres[position]
            holder.textView?.text = genre
            holder.radioButton?.isChecked = position == selectedPosition

            holder.itemView.setOnClickListener {
                selectedPosition = position
                notifyDataSetChanged()
                onGenreSelected(genre)
            }
        }

        override fun getItemCount() = genres.size
    }

    private fun updateSortButtonText() {
        sortButton.text = when (currentSortOrder) {
            SortOrder.DEFAULT -> getString(R.string.sort_default)
            SortOrder.NAME -> getString(R.string.sort_name)
            SortOrder.RECENTLY_PLAYED -> getString(R.string.sort_recent)
            SortOrder.LIKED -> getString(R.string.sort_liked)
            SortOrder.GENRE -> getString(R.string.sort_genre)
        }

        // Use hamburger icon for Default and Genre, sort icon for others
        sortButton.setIconResource(when (currentSortOrder) {
            SortOrder.DEFAULT, SortOrder.GENRE -> R.drawable.ic_menu
            else -> R.drawable.ic_sort
        })
    }

    private fun updateGenreFilterButtonText() {
        genreFilterButton.text = currentGenreFilter ?: getString(R.string.genre_all)
    }

    private fun playStation(station: RadioStation) {
        viewModel.setCurrentStation(station)
        viewModel.setBuffering(true)  // Show buffering state while connecting

        // Update last played timestamp
        lifecycleScope.launch(Dispatchers.IO) {
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
        lifecycleScope.launch(Dispatchers.IO) {
            repository.toggleLike(station.id)
            // Also update ViewModel if this is the currently playing station
            val updatedStation = repository.getStationById(station.id)
            withContext(Dispatchers.Main) {
                updatedStation?.let {
                    // Update current station's like state in ViewModel if it matches
                    if (viewModel.getCurrentStation()?.id == it.id) {
                        viewModel.updateCurrentStationLikeState(it.isLiked)
                    }

                    // Broadcast like state change to all views
                    val broadcastIntent = Intent(MainActivity.BROADCAST_LIKE_STATE_CHANGED).apply {
                        putExtra(MainActivity.EXTRA_IS_LIKED, it.isLiked)
                        putExtra(MainActivity.EXTRA_STATION_ID, it.id)
                        putExtra(MainActivity.EXTRA_RADIO_BROWSER_UUID, it.radioBrowserUuid)
                    }
                    LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(broadcastIntent)

                    // Show toast message for both like and unlike
                    if (it.isLiked) {
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
                    lifecycleScope.launch(Dispatchers.IO) {
                        repository.deleteStation(station)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * Adjusts the FAB bottom margin based on miniplayer visibility.
     * When miniplayer is visible, adds extra margin to prevent overlap.
     */
    private fun adjustFabMarginForMiniPlayer(isMiniPlayerVisible: Boolean) {
        val layoutParams = fabAddRadio.layoutParams as ViewGroup.MarginLayoutParams
        val density = resources.displayMetrics.density

        // Base margin: 24dp
        // Miniplayer height with margins: ~100dp
        val baseMargin = (24 * density).toInt()
        val miniPlayerHeight = (100 * density).toInt()

        layoutParams.bottomMargin = if (isMiniPlayerVisible) {
            baseMargin + miniPlayerHeight  // 124dp total when miniplayer is visible
        } else {
            baseMargin  // 24dp when miniplayer is hidden
        }

        fabAddRadio.layoutParams = layoutParams
    }

    /**
     * Starts selection mode when user long-presses a station
     */
    private fun startSelectionMode(station: RadioStation) {
        adapter.enterSelectionMode(station.id)
        actionMode = requireActivity().startActionMode(actionModeCallback)
        updateActionModeTitle()
    }

    /**
     * Updates the ActionMode title to show selection count
     */
    private fun updateActionModeTitle() {
        val count = adapter.getSelectionCount()
        actionMode?.title = "$count selected"
    }

    /**
     * ActionMode callback for selection mode
     */
    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.selection_menu, menu)
            // Hide FAB during selection mode
            fabAddRadio.hide()
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_select_all -> {
                    adapter.selectAll()
                    updateActionModeTitle()
                    true
                }
                R.id.action_delete -> {
                    showDeleteConfirmationDialog()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter.exitSelectionMode()
            actionMode = null
            // Show FAB again
            fabAddRadio.show()
        }
    }

    /**
     * Shows confirmation dialog before deleting selected stations
     */
    private fun showDeleteConfirmationDialog() {
        val selectedCount = adapter.getSelectionCount()
        if (selectedCount == 0) {
            actionMode?.finish()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Delete stations")
            .setMessage("Are you sure you want to delete $selectedCount station${if (selectedCount > 1) "s" else ""}?")
            .setPositiveButton("Delete") { _, _ ->
                deleteSelectedStations()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Deletes the selected stations
     */
    private fun deleteSelectedStations() {
        val selectedIds = adapter.getSelectedStationIds()
        lifecycleScope.launch(Dispatchers.IO) {
            repository.deleteStationsByIds(selectedIds)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    requireContext(),
                    "Deleted ${selectedIds.size} station${if (selectedIds.size > 1) "s" else ""}",
                    Toast.LENGTH_SHORT
                ).show()
                actionMode?.finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Register broadcast receiver for like state changes
        val filter = IntentFilter(MainActivity.BROADCAST_LIKE_STATE_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(likeStateReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        // Unregister broadcast receiver
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(likeStateReceiver)
    }
}

// Updated Adapter with DiffUtil, stable IDs, and selection mode support
class RadioStationAdapter(
    private val onStationClick: (RadioStation) -> Unit,
    private val onMenuClick: (RadioStation, View) -> Unit,
    private val onLikeClick: (RadioStation) -> Unit,
    private val onLongPress: (RadioStation) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RadioStationAdapter.ViewHolder>() {

    private var stations = listOf<RadioStation>()
    private val selectedStations = mutableSetOf<Long>()
    var isSelectionMode = false
        private set

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

    // Selection mode methods
    fun enterSelectionMode(initialStationId: Long) {
        isSelectionMode = true
        selectedStations.clear()
        selectedStations.add(initialStationId)
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedStations.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(stationId: Long) {
        if (selectedStations.contains(stationId)) {
            selectedStations.remove(stationId)
        } else {
            selectedStations.add(stationId)
        }
        notifyItemChanged(stations.indexOfFirst { it.id == stationId })
        onSelectionChanged()
    }

    fun selectAll() {
        selectedStations.clear()
        selectedStations.addAll(stations.map { it.id })
        notifyDataSetChanged()
    }

    fun getSelectedStationIds(): List<Long> = selectedStations.toList()

    fun getSelectionCount(): Int = selectedStations.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverArt: ImageView = itemView.findViewById(R.id.coverArtImage)
        private val stationName: TextView = itemView.findViewById(R.id.stationNameText)
        private val genreText: TextView = itemView.findViewById(R.id.genreText)
        private val menuButton: MaterialButton = itemView.findViewById(R.id.menuButton)
        private val likeButton: MaterialButton = itemView.findViewById(R.id.likeButton)
        private val selectionCheckBox: CheckBox = itemView.findViewById(R.id.selectionCheckBox)
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

            // Selection mode UI
            val isSelected = selectedStations.contains(station.id)
            if (isSelectionMode) {
                selectionCheckBox.visibility = View.VISIBLE
                selectionCheckBox.isChecked = isSelected
                menuButton.visibility = View.GONE
                likeButton.visibility = View.GONE

                // Apply highlighting to selected items
                if (isSelected) {
                    applySelectionHighlight()
                } else {
                    removeSelectionHighlight()
                }
            } else {
                selectionCheckBox.visibility = View.GONE
                menuButton.visibility = View.VISIBLE
                likeButton.visibility = View.VISIBLE
                removeSelectionHighlight()
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

            // Handle clicks based on mode
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(station.id)
                } else {
                    onStationClick(station)
                }
            }

            // Long press to enter selection mode
            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    onLongPress(station)
                    true
                } else {
                    false
                }
            }

            menuButton.setOnClickListener {
                onMenuClick(station, it)
            }

            likeButton.setOnClickListener {
                onLikeClick(station)
            }

            // Checkbox click also toggles selection
            selectionCheckBox.setOnClickListener {
                toggleSelection(station.id)
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

        private fun applySelectionHighlight() {
            val cardView = itemView as com.google.android.material.card.MaterialCardView
            val primaryColor = com.google.android.material.color.MaterialColors.getColor(
                itemView,
                com.google.android.material.R.attr.colorPrimary
            )
            val primaryContainerColor = com.google.android.material.color.MaterialColors.getColor(
                itemView,
                com.google.android.material.R.attr.colorPrimaryContainer
            )

            // Apply stroke for clear selection indicator
            cardView.strokeWidth = (2 * itemView.resources.displayMetrics.density).toInt() // 2dp stroke
            cardView.strokeColor = primaryColor

            // Apply subtle background tint using primaryContainer color
            cardView.setCardBackgroundColor(primaryContainerColor)
        }

        private fun removeSelectionHighlight() {
            val cardView = itemView as com.google.android.material.card.MaterialCardView
            val defaultBackgroundColor = com.google.android.material.color.MaterialColors.getColor(
                itemView,
                com.google.android.material.R.attr.colorSurfaceContainerLow
            )

            // Remove stroke
            cardView.strokeWidth = 0

            // Restore default background
            cardView.setCardBackgroundColor(defaultBackgroundColor)
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

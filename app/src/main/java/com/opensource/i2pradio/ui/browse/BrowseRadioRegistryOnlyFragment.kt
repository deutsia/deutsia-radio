package com.opensource.i2pradio.ui.browse

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
    private lateinit var btnBackToDiscovery: ImageButton
    private lateinit var resultsSearchInputLayout: MaterialCardView
    private lateinit var resultsSearchInput: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var categoryFilterChip: Chip
    private lateinit var genreFilterChip: Chip
    private lateinit var addFilterChip: Chip
    private lateinit var resultsCount: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var resultsRecyclerView: RecyclerView
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
    private var currentResultsTitle = ""

    // Search debounce
    private var searchDebounceRunnable: Runnable? = null
    private val searchDebounceDelay = 800L
    private var isSearchPending = false
    private var isManualSearchClear = false

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
        setupSearchAndFilters()
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
        btnBackToDiscovery = view.findViewById(R.id.btnBackToDiscovery)
        resultsSearchInputLayout = view.findViewById(R.id.resultsSearchInputLayout)
        resultsSearchInput = view.findViewById(R.id.resultsSearchInput)
        btnClearSearch = view.findViewById(R.id.btnClearSearch)
        categoryFilterChip = view.findViewById(R.id.categoryFilterChip)
        genreFilterChip = view.findViewById(R.id.genreFilterChip)
        addFilterChip = view.findViewById(R.id.addFilterChip)
        resultsCount = view.findViewById(R.id.resultsCount)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        resultsRecyclerView = view.findViewById(R.id.resultsRecyclerView)
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
            viewModel.refresh()
        }
    }

    private fun setupSearchAndFilters() {
        // Search input handling with debounce (same as BrowseStationsFragment)
        resultsSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Show/hide clear button based on text content
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE

                searchDebounceRunnable?.let { resultsSearchInput.removeCallbacks(it) }

                val query = s?.toString()?.trim() ?: ""
                // Show "Searching..." indicator when typing (2+ chars)
                if (query.length >= 2) {
                    isSearchPending = true
                    resultsCount.text = getString(R.string.searching)
                }

                searchDebounceRunnable = Runnable {
                    isSearchPending = false
                    if (query.length >= 2) {
                        currentResultsTitle = "\"$query\""
                        viewModel.search(query)
                        updateCategoryChip()
                    } else if (query.isEmpty() && !isManualSearchClear) {
                        // Reset to current mode when search is cleared
                        currentResultsTitle = when (currentStationType) {
                            StationType.TOR -> getString(R.string.browse_tor_stations)
                            StationType.I2P -> getString(R.string.browse_i2p_stations)
                        }
                        viewModel.search("")
                        updateCategoryChip()
                    }
                    isManualSearchClear = false
                }
                resultsSearchInput.postDelayed(searchDebounceRunnable!!, searchDebounceDelay)
            }
        })

        resultsSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // Cancel pending debounce when user presses Enter
                searchDebounceRunnable?.let { resultsSearchInput.removeCallbacks(it) }
                isSearchPending = false

                val query = resultsSearchInput.text?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    currentResultsTitle = "\"$query\""
                    viewModel.search(query)
                    updateCategoryChip()
                }
                hideKeyboard()
                true
            } else {
                false
            }
        }

        // Clear button click handler
        btnClearSearch.setOnClickListener {
            clearSearch()
            resultsSearchInput.requestFocus()
        }

        // Category chip - shows current category and allows switching
        categoryFilterChip.setOnClickListener {
            showCategoryMenu()
        }

        // Genre filter chip
        genreFilterChip.setOnClickListener {
            showGenreFilterDialog()
        }
        genreFilterChip.setOnCloseIconClickListener {
            viewModel.clearRegistryGenreFilter()
            genreFilterChip.visibility = View.GONE
        }

        // Add filter chip
        addFilterChip.setOnClickListener {
            showAddFilterMenu()
        }
    }

    private fun updateCategoryChip() {
        categoryFilterChip.text = currentResultsTitle.ifEmpty { getString(R.string.browse_all_stations) }
    }

    private fun updateResultsCount(count: Int) {
        resultsCount.text = getString(R.string.browse_results_format, count)
    }

    private fun showCategoryMenu() {
        val currentNetwork = when (viewModel.privacyStationMode.value) {
            PrivacyStationMode.TOR -> getString(R.string.browse_tor_stations)
            PrivacyStationMode.I2P -> getString(R.string.browse_i2p_stations)
            PrivacyStationMode.ALL_PRIVACY -> getString(R.string.browse_all_stations)
            else -> getString(R.string.browse_all_stations)
        }

        val categories = arrayOf(
            getString(R.string.browse_all_stations)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(currentNetwork)
            .setItems(categories) { _, which ->
                clearSearch()
                when (which) {
                    0 -> {
                        currentResultsTitle = getString(R.string.browse_all_stations)
                        updateCategoryChip()
                        viewModel.loadAllPrivacyStations()
                    }
                }
            }
            .show()
    }

    private fun showAddFilterMenu() {
        val filters = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // Only genre filter for privacy stations
        if (viewModel.selectedRegistryGenre.value == null) {
            filters.add(getString(R.string.filter_genre))
            actions.add { showGenreFilterDialog() }
        }

        if (filters.isEmpty()) {
            if (!com.opensource.i2pradio.ui.PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                Toast.makeText(requireContext(), getString(R.string.all_filters_applied), Toast.LENGTH_SHORT).show()
            }
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_filter)
            .setItems(filters.toTypedArray()) { _, which ->
                actions[which]()
            }
            .show()
    }

    private fun showGenreFilterDialog() {
        val genres = viewModel.registryGenres.value ?: return
        if (genres.isEmpty()) {
            if (!com.opensource.i2pradio.ui.PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                Toast.makeText(requireContext(), R.string.loading_genres, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val currentGenre = viewModel.selectedRegistryGenre.value
        var tempSelectedGenreIndex: Int? = if (currentGenre == null) {
            null
        } else {
            genres.indexOf(currentGenre).takeIf { it >= 0 }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_genre)
            .setView(createGenreSearchView(genres, tempSelectedGenreIndex) { selectedIndex ->
                tempSelectedGenreIndex = selectedIndex
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (tempSelectedGenreIndex == null) {
                    viewModel.clearRegistryGenreFilter()
                    genreFilterChip.visibility = View.GONE
                } else {
                    val selectedGenre = genres[tempSelectedGenreIndex!!]
                    viewModel.filterByRegistryGenre(selectedGenre)
                    genreFilterChip.text = selectedGenre
                    genreFilterChip.visibility = View.VISIBLE
                }
            }
            .create()

        dialog.show()
    }

    private fun createGenreSearchView(
        genres: List<String>,
        selectedIndex: Int?,
        onGenreSelected: (Int?) -> Unit
    ): View {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }

        val searchInput = TextInputEditText(context).apply {
            hint = getString(R.string.search_genres)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val searchLayout = TextInputLayout(context).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            isHintEnabled = true
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            setStartIconDrawable(R.drawable.ic_search)
            addView(searchInput)
        }

        container.addView(searchLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(24, 0, 24, 8)
        })

        val divider = View(context).apply {
            val dividerColor = com.google.android.material.color.MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOutlineVariant
            )
            setBackgroundColor(dividerColor)
        }
        container.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            1
        ))

        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            val maxHeight = (400 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxHeight
            )
            clipToPadding = false
            setPadding(0, 8, 0, 0)
        }

        var filteredGenres = genres.toList()
        val adapter = GenreAdapter(filteredGenres, selectedIndex, onGenreSelected)
        recyclerView.adapter = adapter

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

    private inner class GenreAdapter(
        private var genres: List<String>,
        private var selectedIndex: Int?,
        private val onGenreSelected: (Int?) -> Unit
    ) : RecyclerView.Adapter<GenreAdapter.ViewHolder>() {

        inner class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val textView = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(48, 32, 48, 32)
                textSize = 16f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurface
                ))
            }
            return ViewHolder(textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val genre = genres[position]
            holder.textView.text = genre

            val isSelected = selectedIndex != null && genres.indexOf(genre) == selectedIndex
            if (isSelected) {
                holder.textView.setBackgroundColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        holder.textView,
                        com.google.android.material.R.attr.colorSecondaryContainer
                    )
                )
            } else {
                holder.textView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            holder.textView.setOnClickListener {
                val oldIndex = selectedIndex
                selectedIndex = if (selectedIndex == position) null else position
                onGenreSelected(selectedIndex)

                if (oldIndex != null) notifyItemChanged(oldIndex)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = genres.size

        fun updateGenres(newGenres: List<String>) {
            genres = newGenres
            notifyDataSetChanged()
        }
    }

    private fun clearSearch() {
        searchDebounceRunnable?.let { resultsSearchInput.removeCallbacks(it) }
        isManualSearchClear = true
        resultsSearchInput.setText("")
        btnClearSearch.visibility = View.GONE
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(resultsSearchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(resultsSearchInput.windowToken, 0)
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

        // Clear search and filters
        clearSearch()
        genreFilterChip.visibility = View.GONE

        when (stationType) {
            StationType.TOR -> {
                currentResultsTitle = getString(R.string.browse_tor_stations)
                // Load from API with privacy mode set
                viewModel.loadApiTorStations()
            }
            StationType.I2P -> {
                currentResultsTitle = getString(R.string.browse_i2p_stations)
                // Load from API with privacy mode set
                viewModel.loadApiI2pStations()
            }
        }
        updateCategoryChip()
    }

    private fun switchToDiscoveryMode() {
        isInResultsMode = false
        resultsContainer.visibility = View.GONE
        discoveryContainer.visibility = View.VISIBLE

        // Reset privacy mode and filters
        viewModel.resetPrivacyMode()
        clearSearch()
        genreFilterChip.visibility = View.GONE
        currentResultsTitle = ""
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
        // Observe Privacy Radio stations for discovery carousels
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
        }

        // Observe stations for results mode (search/filter results)
        viewModel.stations.observe(viewLifecycleOwner) { browserStations ->
            if (isInResultsMode && viewModel.isInPrivacyMode()) {
                // Convert RadioBrowserStation back to RadioRegistryStation for the adapter
                val registryStations = browserStations.mapNotNull { station ->
                    // Create a RadioRegistryStation from the browser station
                    RadioRegistryStation(
                        id = station.stationuuid,
                        name = station.name,
                        streamUrl = station.urlResolved.takeIf { it.isNotEmpty() } ?: station.url,
                        homepage = station.homepage.takeIf { it.isNotEmpty() },
                        faviconUrl = station.favicon.takeIf { it.isNotEmpty() },
                        genre = station.tags.takeIf { it.isNotEmpty() },
                        codec = station.codec.takeIf { it.isNotEmpty() },
                        bitrate = station.bitrate.takeIf { it > 0 },
                        language = station.language.takeIf { it.isNotEmpty() },
                        network = if (station.url.contains(".onion")) "tor" else if (station.url.contains(".i2p")) "i2p" else "unknown",
                        country = station.country.takeIf { it.isNotEmpty() },
                        countryCode = station.countrycode.takeIf { it.isNotEmpty() },
                        isOnline = station.lastcheckok,
                        lastCheckTime = null,
                        status = "approved",
                        checkCount = 0,
                        checkOkCount = 0,
                        submittedAt = null,
                        createdAt = null,
                        updatedAt = null
                    )
                }
                resultsAdapter.submitList(registryStations)
                updateEmptyState(registryStations.isEmpty())
                updateResultsCount(registryStations.size)
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

        // Observe selected registry genre to update chip
        viewModel.selectedRegistryGenre.observe(viewLifecycleOwner) { genre ->
            if (genre != null) {
                genreFilterChip.text = genre
                genreFilterChip.visibility = View.VISIBLE
            } else {
                genreFilterChip.visibility = View.GONE
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
        lifecycleScope.launch {
            // Convert to RadioBrowserStation to use shared like status checking
            val browserStation = RadioBrowserStation.fromRegistryStation(station)
            val radioStation = repository.convertToRadioStationWithLikeStatus(browserStation)
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
                    // Use !wasLiked instead of querying database to avoid race condition
                    // (the database operation in viewModel.toggleLike is async)
                    radioViewModel.updateCurrentStationLikeState(!wasLiked)
                }
            }

            val broadcastIntent = Intent(MainActivity.BROADCAST_LIKE_STATE_CHANGED).apply {
                // Use !wasLiked to get the new state after toggle
                putExtra(MainActivity.EXTRA_IS_LIKED, !wasLiked)
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
        // Clean up search debounce
        searchDebounceRunnable?.let { resultsSearchInput.removeCallbacks(it) }
        // Clean up skeleton adapters
        (torStationsRecyclerView.adapter as? SkeletonCarouselAdapter)?.stopAllShimmers()
        (i2pStationsRecyclerView.adapter as? SkeletonCarouselAdapter)?.stopAllShimmers()
    }
}

package com.opensource.i2pradio.ui.browse

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.MotionEvent
import android.view.GestureDetector
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
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
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserResult
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserStation
import com.opensource.i2pradio.ui.RadioViewModel
import com.opensource.i2pradio.i2p.I2PManager
import com.opensource.i2pradio.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment for browsing and searching RadioBrowser stations.
 * Features a two-mode UI:
 * - Discovery mode: Visual browsing with genre chips and carousels
 * - Results mode: Search and filtered list view
 */
class BrowseStationsFragment : Fragment() {

    // Discovery mode views
    private lateinit var discoveryContainer: NestedScrollView
    private lateinit var discoverySearchBar: MaterialCardView
    private lateinit var genreChipsRow1: LinearLayout
    private lateinit var genreChipsRow2: LinearLayout
    private lateinit var genreSeeAll: TextView
    private lateinit var usaRecyclerView: RecyclerView
    private lateinit var usaSeeAll: TextView
    private lateinit var germanyRecyclerView: RecyclerView
    private lateinit var germanySeeAll: TextView
    private lateinit var spanishRecyclerView: RecyclerView
    private lateinit var spanishSeeAll: TextView
    private lateinit var frenchRecyclerView: RecyclerView
    private lateinit var frenchSeeAll: TextView
    private lateinit var trendingRecyclerView: RecyclerView
    private lateinit var trendingSeeAll: TextView
    private lateinit var topVotedPreviewRecyclerView: RecyclerView
    private lateinit var topVotedSeeAll: TextView
    private lateinit var popularRecyclerView: RecyclerView
    private lateinit var popularSeeAll: TextView
    private lateinit var newStationsRecyclerView: RecyclerView
    private lateinit var newStationsSeeAll: TextView

    // Results mode views
    private lateinit var resultsContainer: LinearLayout
    private lateinit var btnBackToDiscovery: ImageButton
    private lateinit var resultsSearchInputLayout: MaterialCardView
    private lateinit var resultsSearchInput: android.widget.EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var categoryFilterChip: Chip
    private lateinit var countryFilterChip: Chip
    private lateinit var genreFilterChip: Chip
    private lateinit var languageFilterChip: Chip
    private lateinit var addFilterChip: Chip
    private lateinit var resultsCount: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var stationsRecyclerView: RecyclerView

    // Shared views
    private lateinit var loadingContainer: FrameLayout
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var torWarningBanner: MaterialCardView

    // Adapters
    private lateinit var stationsAdapter: BrowseStationsAdapter
    private lateinit var usaAdapter: BrowseCarouselAdapter
    private lateinit var germanyAdapter: BrowseCarouselAdapter
    private lateinit var spanishAdapter: BrowseCarouselAdapter
    private lateinit var frenchAdapter: BrowseCarouselAdapter
    private lateinit var trendingAdapter: BrowseCarouselAdapter
    private lateinit var topVotedPreviewAdapter: BrowseCarouselAdapter
    private lateinit var popularAdapter: BrowseCarouselAdapter
    private lateinit var newStationsAdapter: BrowseCarouselAdapter

    // Skeleton adapters for loading state
    private val skeletonCarouselAdapters = mutableListOf<SkeletonCarouselAdapter>()
    private var skeletonListAdapter: SkeletonListAdapter? = null

    private val viewModel: BrowseViewModel by viewModels()
    private val radioViewModel: RadioViewModel by activityViewModels()
    private lateinit var repository: RadioBrowserRepository

    private var searchDebounceRunnable: Runnable? = null
    private val searchDebounceDelay = 500L
    private var isManualSearchClear = false
    private var isInResultsMode = false

    // Current results mode category (for display)
    private var currentResultsTitle = ""

    // Genre chips data
    private data class GenreChipData(val tag: String, val displayName: Int)
    private val genreChipsRow1Data = listOf(
        GenreChipData("pop", R.string.genre_pop),
        GenreChipData("rock", R.string.genre_rock),
        GenreChipData("jazz", R.string.genre_jazz),
        GenreChipData("classical", R.string.genre_classical),
        GenreChipData("hip hop", R.string.genre_hip_hop),
        GenreChipData("electronic", R.string.genre_electronic),
        GenreChipData("edm", R.string.genre_edm)
    )
    private val genreChipsRow2Data = listOf(
        GenreChipData("country", R.string.genre_country),
        GenreChipData("latin", R.string.genre_latin),
        GenreChipData("r&b", R.string.genre_r_and_b),
        GenreChipData("news", R.string.genre_news),
        GenreChipData("talk", R.string.genre_talk),
        GenreChipData("sports", R.string.genre_sports),
        GenreChipData("i2p", R.string.genre_i2p),
        GenreChipData("tor", R.string.genre_tor)
    )

    /**
     * Normalize genre name for matching purposes.
     * Maps known variants (e.g., "hip hop", "hip-hop", "hiphop") to a canonical form.
     * This must match the normalization in BrowseViewModel.normalizeGenreName().
     */
    private fun normalizeGenreName(name: String): String {
        val lower = name.lowercase().trim()
        return when {
            lower in listOf("hip hop", "hip-hop", "hiphop") -> "hiphop"
            lower in listOf("k-pop", "kpop") -> "kpop"
            lower in listOf("lo-fi", "lofi") -> "lofi"
            lower in listOf("r&b", "r and b", "rnb") -> "rnb"
            else -> lower
        }
    }

    // Broadcast receiver for like state changes from other views
    private val likeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.BROADCAST_LIKE_STATE_CHANGED) {
                viewModel.refreshLikedAndSavedUuids()
                // Also refresh carousel adapters
                refreshCarouselLikeStates()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_browse_stations, container, false)

        repository = RadioBrowserRepository(requireContext())

        findViews(view)
        setupDiscoveryMode()
        setupResultsMode()
        setupSharedViews()
        observeViewModel()
        observeDiscoveryData()

        return view
    }

    private fun findViews(view: View) {
        // Discovery mode views
        discoveryContainer = view.findViewById(R.id.discoveryContainer)
        discoverySearchBar = view.findViewById(R.id.discoverySearchBar)
        genreChipsRow1 = view.findViewById(R.id.genreChipsRow1)
        genreChipsRow2 = view.findViewById(R.id.genreChipsRow2)
        genreSeeAll = view.findViewById(R.id.genreSeeAll)
        usaRecyclerView = view.findViewById(R.id.usaRecyclerView)
        usaSeeAll = view.findViewById(R.id.usaSeeAll)
        germanyRecyclerView = view.findViewById(R.id.germanyRecyclerView)
        germanySeeAll = view.findViewById(R.id.germanySeeAll)
        spanishRecyclerView = view.findViewById(R.id.spanishRecyclerView)
        spanishSeeAll = view.findViewById(R.id.spanishSeeAll)
        frenchRecyclerView = view.findViewById(R.id.frenchRecyclerView)
        frenchSeeAll = view.findViewById(R.id.frenchSeeAll)
        trendingRecyclerView = view.findViewById(R.id.trendingRecyclerView)
        trendingSeeAll = view.findViewById(R.id.trendingSeeAll)
        topVotedPreviewRecyclerView = view.findViewById(R.id.topVotedPreviewRecyclerView)
        topVotedSeeAll = view.findViewById(R.id.topVotedSeeAll)
        popularRecyclerView = view.findViewById(R.id.popularRecyclerView)
        popularSeeAll = view.findViewById(R.id.popularSeeAll)
        newStationsRecyclerView = view.findViewById(R.id.newStationsRecyclerView)
        newStationsSeeAll = view.findViewById(R.id.newStationsSeeAll)

        // Results mode views
        resultsContainer = view.findViewById(R.id.resultsContainer)
        btnBackToDiscovery = view.findViewById(R.id.btnBackToDiscovery)
        resultsSearchInputLayout = view.findViewById(R.id.resultsSearchInputLayout)
        resultsSearchInput = view.findViewById(R.id.resultsSearchInput)
        btnClearSearch = view.findViewById(R.id.btnClearSearch)
        categoryFilterChip = view.findViewById(R.id.categoryFilterChip)
        countryFilterChip = view.findViewById(R.id.countryFilterChip)
        genreFilterChip = view.findViewById(R.id.genreFilterChip)
        languageFilterChip = view.findViewById(R.id.languageFilterChip)
        addFilterChip = view.findViewById(R.id.addFilterChip)
        resultsCount = view.findViewById(R.id.resultsCount)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        stationsRecyclerView = view.findViewById(R.id.stationsRecyclerView)

        // Shared views
        loadingContainer = view.findViewById(R.id.loadingContainer)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        torWarningBanner = view.findViewById(R.id.torWarningBanner)
    }

    private fun setupDiscoveryMode() {
        // Search bar tap -> switch to results mode with search focus
        discoverySearchBar.setOnClickListener {
            switchToResultsMode(focusSearch = true)
        }

        // Genre See All -> show full genre selection dialog
        genreSeeAll.setOnClickListener {
            showFullGenreDialog()
        }

        // Set up genre chips
        setupGenreChips()

        // Set up carousels
        setupCarousels()

        // Country/Language carousel See All buttons
        usaSeeAll.setOnClickListener {
            currentResultsTitle = getString(R.string.browse_popular_usa)
            switchToResultsMode()
            viewModel.loadByCountryCode("US")
        }

        germanySeeAll.setOnClickListener {
            currentResultsTitle = getString(R.string.browse_popular_germany)
            switchToResultsMode()
            viewModel.loadByCountryCode("DE")
        }

        spanishSeeAll.setOnClickListener {
            currentResultsTitle = getString(R.string.browse_spanish_radio)
            switchToResultsMode()
            viewModel.loadByLanguage("spanish")
        }

        frenchSeeAll.setOnClickListener {
            currentResultsTitle = getString(R.string.browse_french_radio)
            switchToResultsMode()
            viewModel.loadByLanguage("french")
        }

        // See All buttons
        trendingSeeAll.setOnClickListener {
            currentResultsTitle = getString(R.string.browse_trending)
            switchToResultsMode()
            viewModel.loadRandom() // Uses recently changed as "trending"
        }

        topVotedSeeAll.setOnClickListener {
            currentResultsTitle = getString(R.string.browse_top_voted)
            switchToResultsMode()
            viewModel.loadTopVoted()
        }

        popularSeeAll.setOnClickListener {
            currentResultsTitle = getString(R.string.browse_popular)
            switchToResultsMode()
            viewModel.loadTopClicked()
        }

        newStationsSeeAll.setOnClickListener {
            currentResultsTitle = getString(R.string.browse_new_stations)
            switchToResultsMode()
            viewModel.loadRandom() // Recently changed stations
        }
    }

    private fun setupGenreChips() {
        // Row 1
        genreChipsRow1Data.forEach { genreData ->
            val chip = createGenreChip(genreData)
            genreChipsRow1.addView(chip)
        }

        // Row 2
        genreChipsRow2Data.forEach { genreData ->
            val chip = createGenreChip(genreData)
            genreChipsRow2.addView(chip)
        }

        // Set up touch handling for the HorizontalScrollViews
        (genreChipsRow1.parent as? android.widget.HorizontalScrollView)?.let {
            setupGenreScrollTouchHandling(it)
        }
        (genreChipsRow2.parent as? android.widget.HorizontalScrollView)?.let {
            setupGenreScrollTouchHandling(it)
        }
    }

    private fun createGenreChip(genreData: GenreChipData): Chip {
        val chip = Chip(requireContext()).apply {
            text = getString(genreData.displayName)
            isCheckable = false
            isClickable = true
            setChipBackgroundColorResource(R.color.chip_background_selector)
            setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text_selector))
            chipStrokeWidth = 0f

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = resources.getDimensionPixelSize(R.dimen.chip_margin)
            layoutParams = params
        }

        chip.setOnClickListener {
            currentResultsTitle = getString(R.string.browse_all_stations)
            switchToResultsMode()

            // Special handling for I2P and Tor chips - load curated lists
            when (genreData.tag.lowercase()) {
                "i2p" -> {
                    viewModel.loadCuratedI2pStations()
                }
                "tor" -> {
                    viewModel.loadCuratedTorStations()
                }
                else -> {
                    // Normal genre chip handling
                    // Find the tag in loaded tags using normalized comparison
                    // This handles variants like "hip hop", "hip-hop", "hiphop" all matching the same tag
                    val tags = viewModel.tags.value
                    val normalizedChipTag = normalizeGenreName(genreData.tag)
                    val tagInfo = tags?.find { normalizeGenreName(it.name) == normalizedChipTag }

                    if (tagInfo != null) {
                        viewModel.addTagFilter(tagInfo)
                        viewModel.loadAllStations()
                    } else {
                        // Search by tag name directly if TagInfo not available
                        viewModel.search(genreData.tag)
                    }
                }
            }
        }

        return chip
    }

    /**
     * Set up touch handling for genre chip HorizontalScrollViews to work with ViewPager2.
     * When touching the scroll view, immediately claim the gesture for horizontal scrolling.
     * Only release to ViewPager2 if the swipe turns out to be clearly vertical.
     * This matches the approach used for carousels.
     */
    private fun setupGenreScrollTouchHandling(scrollView: android.widget.HorizontalScrollView) {
        var startX = 0f
        var startY = 0f
        val touchSlop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop

        scrollView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    // Immediately claim gesture - genre chips get priority
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - startX)
                    val dy = kotlin.math.abs(event.y - startY)
                    // Only release to ViewPager2 if swipe is clearly vertical
                    if (dy > touchSlop && dy > dx * 2) {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false // Don't consume the event, let HorizontalScrollView handle it
        }
    }

    /**
     * Set up touch handling for horizontal RecyclerViews to work with ViewPager2.
     * When touching a carousel, immediately claim the gesture for carousel scrolling.
     * Only release to ViewPager2 if the swipe turns out to be clearly vertical.
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
                        // Immediately claim gesture - carousel gets priority
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = kotlin.math.abs(e.x - startX)
                        val dy = kotlin.math.abs(e.y - startY)
                        // Only release to ViewPager2 if swipe is clearly vertical
                        if (dy > touchSlop && dy > dx * 2) {
                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return false // Don't consume the event
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun setupCarousels() {
        // Create real adapters first
        usaAdapter = BrowseCarouselAdapter(
            onStationClick = { station -> playStation(station) },
            onLikeClick = { station -> likeStation(station) },
            showRankBadge = false
        )
        germanyAdapter = BrowseCarouselAdapter(
            onStationClick = { station -> playStation(station) },
            onLikeClick = { station -> likeStation(station) },
            showRankBadge = false
        )
        spanishAdapter = BrowseCarouselAdapter(
            onStationClick = { station -> playStation(station) },
            onLikeClick = { station -> likeStation(station) },
            showRankBadge = false
        )
        frenchAdapter = BrowseCarouselAdapter(
            onStationClick = { station -> playStation(station) },
            onLikeClick = { station -> likeStation(station) },
            showRankBadge = false
        )
        trendingAdapter = BrowseCarouselAdapter(
            onStationClick = { station -> playStation(station) },
            onLikeClick = { station -> likeStation(station) },
            showRankBadge = true
        )
        topVotedPreviewAdapter = BrowseCarouselAdapter(
            onStationClick = { station -> playStation(station) },
            onLikeClick = { station -> likeStation(station) },
            showRankBadge = true
        )
        popularAdapter = BrowseCarouselAdapter(
            onStationClick = { station -> playStation(station) },
            onLikeClick = { station -> likeStation(station) },
            showRankBadge = false
        )
        newStationsAdapter = BrowseCarouselAdapter(
            onStationClick = { station -> playStation(station) },
            onLikeClick = { station -> likeStation(station) },
            showRankBadge = false
        )

        // Setup all carousels with skeleton adapters initially
        val carouselRecyclerViews = listOf(
            usaRecyclerView,
            germanyRecyclerView,
            spanishRecyclerView,
            frenchRecyclerView,
            trendingRecyclerView,
            topVotedPreviewRecyclerView,
            popularRecyclerView,
            newStationsRecyclerView
        )

        skeletonCarouselAdapters.clear()
        carouselRecyclerViews.forEach { recyclerView ->
            val skeletonAdapter = SkeletonCarouselAdapter(itemCount = 4)
            skeletonCarouselAdapters.add(skeletonAdapter)
            recyclerView.adapter = skeletonAdapter
            recyclerView.layoutManager = LinearLayoutManager(
                requireContext(), LinearLayoutManager.HORIZONTAL, false
            )
            setupCarouselTouchHandling(recyclerView)
        }
    }

    /**
     * Swap skeleton adapters with real data adapters for a specific carousel.
     */
    private fun swapToRealAdapter(recyclerView: RecyclerView, realAdapter: RecyclerView.Adapter<*>) {
        val currentAdapter = recyclerView.adapter
        if (currentAdapter is SkeletonCarouselAdapter) {
            currentAdapter.stopAllShimmers()
            skeletonCarouselAdapters.remove(currentAdapter)
        }
        recyclerView.adapter = realAdapter
    }

    private fun setupResultsMode() {
        // Back button
        btnBackToDiscovery.setOnClickListener {
            switchToDiscoveryMode()
        }

        // Search input
        setupResultsSearch()

        // Main stations list
        stationsAdapter = BrowseStationsAdapter(
            onStationClick = { station -> playStation(station) },
            onAddClick = { station -> saveStation(station) },
            onRemoveClick = { station -> removeStation(station) },
            onLikeClick = { station -> likeStation(station) }
        )
        stationsRecyclerView.adapter = stationsAdapter
        stationsRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Pagination
        stationsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                if (totalItemCount > 0 && lastVisibleItem >= totalItemCount - 5) {
                    viewModel.loadMore()
                }
            }
        })

        // Swipe refresh
        swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        // Filter chips
        setupFilterChips()
    }

    private fun setupResultsSearch() {
        resultsSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Show/hide clear button based on text content
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE

                searchDebounceRunnable?.let { resultsSearchInput.removeCallbacks(it) }
                searchDebounceRunnable = Runnable {
                    val query = s?.toString()?.trim() ?: ""
                    if (query.length >= 2) {
                        currentResultsTitle = "\"$query\""
                        viewModel.search(query)
                        updateCategoryChip()
                    } else if (query.isEmpty() && !isManualSearchClear) {
                        // Reset to all stations when search is cleared
                        currentResultsTitle = getString(R.string.browse_all_stations)
                        viewModel.loadAllStations()
                        updateCategoryChip()
                    }
                    isManualSearchClear = false
                }
                resultsSearchInput.postDelayed(searchDebounceRunnable!!, searchDebounceDelay)
            }
        })

        resultsSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
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
    }

    private fun setupFilterChips() {
        // Category chip shows current browse mode
        categoryFilterChip.setOnClickListener {
            showCategoryMenu()
        }

        // Country filter
        countryFilterChip.setOnClickListener {
            showCountryFilterDialog()
        }
        countryFilterChip.setOnCloseIconClickListener {
            viewModel.clearCountryFilter()
            countryFilterChip.visibility = View.GONE
        }

        // Genre filter
        genreFilterChip.setOnClickListener {
            showGenreFilterDialog()
        }
        genreFilterChip.setOnCloseIconClickListener {
            viewModel.clearTagFilter()
            genreFilterChip.visibility = View.GONE
        }

        // Language filter
        languageFilterChip.setOnClickListener {
            showLanguageFilterDialog()
        }
        languageFilterChip.setOnCloseIconClickListener {
            viewModel.clearLanguageFilter()
            languageFilterChip.visibility = View.GONE
        }

        // Add filter button
        addFilterChip.setOnClickListener {
            showAddFilterMenu()
        }
    }

    private fun setupSharedViews() {
        // Any shared view setup if needed
    }

    private fun observeViewModel() {
        viewModel.stations.observe(viewLifecycleOwner) { stations ->
            stationsAdapter.submitList(stations)
            updateEmptyState(stations.isEmpty())
            updateResultsCount(stations.size)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading && stationsAdapter.itemCount == 0 && isInResultsMode) {
                loadingContainer.visibility = View.VISIBLE
            } else {
                loadingContainer.visibility = View.GONE
            }
            swipeRefresh.isRefreshing = isLoading && stationsAdapter.itemCount > 0
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.showTorWarning.observe(viewLifecycleOwner) { showWarning ->
            torWarningBanner.visibility = if (showWarning) View.VISIBLE else View.GONE
        }

        viewModel.savedStationUuids.observe(viewLifecycleOwner) { uuids ->
            stationsAdapter.updateSavedUuids(uuids)
        }

        viewModel.likedStationUuids.observe(viewLifecycleOwner) { uuids ->
            stationsAdapter.updateLikedUuids(uuids)
            // Update carousel adapters too
            usaAdapter.updateLikedUuids(uuids)
            germanyAdapter.updateLikedUuids(uuids)
            spanishAdapter.updateLikedUuids(uuids)
            frenchAdapter.updateLikedUuids(uuids)
            trendingAdapter.updateLikedUuids(uuids)
            topVotedPreviewAdapter.updateLikedUuids(uuids)
            popularAdapter.updateLikedUuids(uuids)
            newStationsAdapter.updateLikedUuids(uuids)
        }

        viewModel.selectedCountry.observe(viewLifecycleOwner) { country ->
            if (country != null) {
                countryFilterChip.text = country.name
                countryFilterChip.visibility = View.VISIBLE
            } else {
                countryFilterChip.visibility = View.GONE
            }
        }

        viewModel.selectedTag.observe(viewLifecycleOwner) { tag ->
            if (tag != null) {
                genreFilterChip.text = translateGenreName(tag.name)
                genreFilterChip.visibility = View.VISIBLE
            } else {
                genreFilterChip.visibility = View.GONE
            }
        }

        viewModel.selectedLanguage.observe(viewLifecycleOwner) { language ->
            if (language != null) {
                languageFilterChip.text = language.name
                languageFilterChip.visibility = View.VISIBLE
            } else {
                languageFilterChip.visibility = View.GONE
            }
        }
    }

    private fun observeDiscoveryData() {
        viewModel.usaStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                swapToRealAdapter(usaRecyclerView, usaAdapter)
            }
            usaAdapter.submitList(stations)
        }

        viewModel.germanyStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                swapToRealAdapter(germanyRecyclerView, germanyAdapter)
            }
            germanyAdapter.submitList(stations)
        }

        viewModel.spanishStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                swapToRealAdapter(spanishRecyclerView, spanishAdapter)
            }
            spanishAdapter.submitList(stations)
        }

        viewModel.frenchStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                swapToRealAdapter(frenchRecyclerView, frenchAdapter)
            }
            frenchAdapter.submitList(stations)
        }

        viewModel.trendingStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                swapToRealAdapter(trendingRecyclerView, trendingAdapter)
            }
            trendingAdapter.submitList(stations)
        }

        viewModel.topVotedPreviewStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                swapToRealAdapter(topVotedPreviewRecyclerView, topVotedPreviewAdapter)
            }
            topVotedPreviewAdapter.submitList(stations)
        }

        viewModel.popularStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                swapToRealAdapter(popularRecyclerView, popularAdapter)
            }
            popularAdapter.submitList(stations)
        }

        viewModel.newStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isNotEmpty()) {
                swapToRealAdapter(newStationsRecyclerView, newStationsAdapter)
            }
            newStationsAdapter.submitList(stations)
        }
    }

    private fun refreshCarouselLikeStates() {
        val likedUuids = viewModel.likedStationUuids.value ?: emptySet()
        usaAdapter.updateLikedUuids(likedUuids)
        germanyAdapter.updateLikedUuids(likedUuids)
        spanishAdapter.updateLikedUuids(likedUuids)
        frenchAdapter.updateLikedUuids(likedUuids)
        trendingAdapter.updateLikedUuids(likedUuids)
        topVotedPreviewAdapter.updateLikedUuids(likedUuids)
        popularAdapter.updateLikedUuids(likedUuids)
        newStationsAdapter.updateLikedUuids(likedUuids)
    }

    private fun switchToResultsMode(focusSearch: Boolean = false) {
        isInResultsMode = true
        discoveryContainer.visibility = View.GONE
        resultsContainer.visibility = View.VISIBLE

        if (focusSearch) {
            resultsSearchInput.requestFocus()
            showKeyboard()
        }

        // If no category set, default to All Stations
        if (currentResultsTitle.isEmpty()) {
            currentResultsTitle = getString(R.string.browse_all_stations)
            viewModel.loadAllStations()
        }
        updateCategoryChip()
    }

    private fun switchToDiscoveryMode() {
        isInResultsMode = false
        resultsContainer.visibility = View.GONE
        discoveryContainer.visibility = View.VISIBLE

        // Clear search and filters
        clearSearch()
        viewModel.filterByCountry(null)
        viewModel.filterByTag(null)
        viewModel.filterByLanguage(null)
        currentResultsTitle = ""

        // Refresh discovery data (force refresh to get fresh content)
        viewModel.loadDiscoveryData(forceRefresh = true)
    }

    private fun updateCategoryChip() {
        categoryFilterChip.text = currentResultsTitle.ifEmpty { getString(R.string.browse_all_stations) }
    }

    private fun updateResultsCount(count: Int) {
        resultsCount.text = getString(R.string.browse_results_format, count)
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty && viewModel.isLoading.value != true && isInResultsMode) {
            emptyStateContainer.visibility = View.VISIBLE
            stationsRecyclerView.visibility = View.GONE
        } else {
            emptyStateContainer.visibility = View.GONE
            stationsRecyclerView.visibility = View.VISIBLE
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

    private fun showCategoryMenu() {
        val categories = arrayOf(
            getString(R.string.browse_all_stations),
            getString(R.string.browse_top_voted),
            getString(R.string.browse_popular),
            getString(R.string.browse_trending),
            getString(R.string.browse_history)
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.browse_top_voted)
            .setItems(categories) { _, which ->
                clearSearch()
                when (which) {
                    0 -> {
                        currentResultsTitle = getString(R.string.browse_all_stations)
                        viewModel.loadAllStations()
                    }
                    1 -> {
                        currentResultsTitle = getString(R.string.browse_top_voted)
                        viewModel.loadTopVoted()
                    }
                    2 -> {
                        currentResultsTitle = getString(R.string.browse_popular)
                        viewModel.loadTopClicked()
                    }
                    3 -> {
                        currentResultsTitle = getString(R.string.browse_trending)
                        viewModel.loadRandom()
                    }
                    4 -> {
                        currentResultsTitle = getString(R.string.browse_history)
                        viewModel.loadHistory()
                    }
                }
                updateCategoryChip()
            }
            .show()
    }

    /**
     * Show a full-screen genre dialog for discovery mode browsing.
     * Selecting a genre immediately switches to results mode filtered by that genre.
     */
    private fun showFullGenreDialog() {
        val tags = viewModel.tags.value ?: return
        if (tags.isEmpty()) {
            Toast.makeText(requireContext(), R.string.loading_genres, Toast.LENGTH_SHORT).show()
            return
        }

        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }

        val searchInput = TextInputEditText(context).apply {
            hint = getString(R.string.search_genres)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val searchLayout = com.google.android.material.textfield.TextInputLayout(context).apply {
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_FILLED
            isHintEnabled = true
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
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
            val maxHeight = (450 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxHeight
            )
            clipToPadding = false
            setPadding(0, 8, 0, 8)
        }

        var filteredTags = tags.toList()

        // Create the dialog first so we can dismiss it in the adapter
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.browse_by_genre)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Adapter that dismisses dialog and navigates to genre on click
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private var items = filteredTags

            fun updateItems(newItems: List<com.opensource.i2pradio.data.radiobrowser.TagInfo>) {
                items = newItems
                notifyDataSetChanged()
            }

            inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val textView: TextView? = view.findViewById(android.R.id.text1)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(
                    android.R.layout.simple_list_item_1, parent, false
                )
                return ViewHolder(view)
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val tag = items[position]
                val displayName = translateGenreName(tag.name)
                (holder as ViewHolder).textView?.text = "$displayName (${tag.stationCount})"

                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    // Navigate to results with this genre
                    currentResultsTitle = getString(R.string.browse_all_stations)
                    switchToResultsMode()
                    viewModel.addTagFilter(tag)
                    viewModel.loadAllStations()
                }
            }

            override fun getItemCount() = items.size
        }

        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                filteredTags = if (query.isEmpty()) {
                    tags
                } else {
                    tags.filter { tag ->
                        val englishName = tag.name
                        val translatedName = translateGenreName(tag.name)
                        englishName.contains(query, ignoreCase = true) ||
                        translatedName.contains(query, ignoreCase = true)
                    }
                }
                adapter.updateItems(filteredTags)
            }
        })

        container.addView(recyclerView)
        dialog.show()
    }

    private fun showAddFilterMenu() {
        val filters = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (viewModel.selectedCountry.value == null) {
            filters.add(getString(R.string.filter_country))
            actions.add { showCountryFilterDialog() }
        }
        if (viewModel.selectedTag.value == null) {
            filters.add(getString(R.string.filter_genre))
            actions.add { showGenreFilterDialog() }
        }
        if (viewModel.selectedLanguage.value == null) {
            filters.add(getString(R.string.filter_language))
            actions.add { showLanguageFilterDialog() }
        }

        if (filters.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.all_filters_applied), Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_filter)
            .setItems(filters.toTypedArray()) { _, which ->
                actions[which]()
            }
            .show()
    }

    /**
     * Translate common genre names from English (RadioBrowser API) to localized strings.
     */
    private fun translateGenreName(englishName: String): String {
        val normalized = englishName.trim().lowercase()

        return when (normalized) {
            "alternative" -> getString(R.string.genre_alternative)
            "ambient" -> getString(R.string.genre_ambient)
            "blues" -> getString(R.string.genre_blues)
            "christian" -> getString(R.string.genre_christian)
            "classical" -> getString(R.string.genre_classical)
            "comedy" -> getString(R.string.genre_comedy)
            "country" -> getString(R.string.genre_country)
            "dance" -> getString(R.string.genre_dance)
            "edm" -> getString(R.string.genre_edm)
            "electronic" -> getString(R.string.genre_electronic)
            "folk" -> getString(R.string.genre_folk)
            "funk" -> getString(R.string.genre_funk)
            "gospel" -> getString(R.string.genre_gospel)
            "hip hop", "hip-hop", "hiphop" -> getString(R.string.genre_hip_hop)
            "indie" -> getString(R.string.genre_indie)
            "jazz" -> getString(R.string.genre_jazz)
            "k-pop", "kpop" -> getString(R.string.genre_k_pop)
            "latin" -> getString(R.string.genre_latin)
            "lo-fi", "lofi" -> getString(R.string.genre_lo_fi)
            "metal" -> getString(R.string.genre_metal)
            "news" -> getString(R.string.genre_news)
            "oldies" -> getString(R.string.genre_oldies)
            "pop" -> getString(R.string.genre_pop)
            "punk" -> getString(R.string.genre_punk)
            "r&b", "r and b", "rnb" -> getString(R.string.genre_r_and_b)
            "reggae" -> getString(R.string.genre_reggae)
            "rock" -> getString(R.string.genre_rock)
            "soul" -> getString(R.string.genre_soul)
            "sports" -> getString(R.string.genre_sports)
            "talk" -> getString(R.string.genre_talk)
            "world" -> getString(R.string.genre_world)
            "other" -> getString(R.string.genre_other)
            else -> englishName
        }
    }

    private fun playStation(station: RadioBrowserStation) {
        val streamUrl = station.urlResolved ?: station.url
        val isTorStation = streamUrl.contains(".onion")
        val isI2PStation = streamUrl.contains(".i2p")

        // Check if Tor is available for .onion stations
        if (isTorStation) {
            if (!TorManager.isOrbotInstalled(requireContext())) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.toast_tor_not_installed),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            if (!TorManager.isConnected()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.toast_tor_not_running),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        // Check if I2P is available for .i2p stations
        if (isI2PStation) {
            lifecycleScope.launch(Dispatchers.IO) {
                val isI2PAvailable = I2PManager.checkProxyAvailabilitySync()
                withContext(Dispatchers.Main) {
                    if (!isI2PAvailable) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.toast_i2p_not_running),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        startPlayback(station)
                    }
                }
            }
            return
        }

        // For non-Tor/I2P stations, proceed immediately
        startPlayback(station)
    }

    private fun startPlayback(station: RadioBrowserStation) {
        lifecycleScope.launch {
            repository.addToBrowseHistory(station)
        }

        val radioStation = repository.convertToRadioStation(station)
        radioViewModel.setCurrentStation(radioStation)
        radioViewModel.setBuffering(true)

        val intent = Intent(requireContext(), RadioService::class.java).apply {
            action = RadioService.ACTION_PLAY
            putExtra("stream_url", radioStation.streamUrl)
            putExtra("station_name", radioStation.name)
            putExtra("proxy_host", "")
            putExtra("proxy_port", 0)
            putExtra("proxy_type", "NONE")
            putExtra("cover_art_uri", radioStation.coverArtUri)
            putExtra("custom_proxy_protocol", "HTTP")
            putExtra("proxy_username", "")
            putExtra("proxy_password", "")
            putExtra("proxy_auth_type", "NONE")
            putExtra("proxy_connection_timeout", 30)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun saveStation(station: RadioBrowserStation) {
        viewModel.saveStation(station)
        Toast.makeText(
            requireContext(),
            getString(R.string.station_saved, station.name),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun removeStation(station: RadioBrowserStation) {
        viewModel.removeStation(station)
        Toast.makeText(
            requireContext(),
            getString(R.string.station_removed, station.name),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun likeStation(station: RadioBrowserStation) {
        val wasLiked = viewModel.likedStationUuids.value?.contains(station.stationuuid) ?: false
        val wasSaved = viewModel.savedStationUuids.value?.contains(station.stationuuid) ?: false

        viewModel.toggleLike(station)

        lifecycleScope.launch {
            val updatedStation = repository.getStationInfoByUuid(station.stationuuid)

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

    private fun showCountryFilterDialog() {
        val countries = viewModel.countries.value ?: return
        if (countries.isEmpty()) {
            Toast.makeText(requireContext(), R.string.loading_countries, Toast.LENGTH_SHORT).show()
            return
        }

        val currentCountry = viewModel.selectedCountry.value
        var tempSelectedCountryIndex: Int? = if (currentCountry == null) {
            null
        } else {
            countries.indexOf(currentCountry).takeIf { it >= 0 }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_country)
            .setView(createCountrySearchView(countries, tempSelectedCountryIndex) { selectedIndex ->
                tempSelectedCountryIndex = selectedIndex
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (tempSelectedCountryIndex == null) {
                    viewModel.clearCountryFilter()
                } else {
                    // Use addCountryFilter to keep current category (intelligent filtering)
                    viewModel.addCountryFilter(countries[tempSelectedCountryIndex!!])
                }
            }
            .create()

        dialog.show()
    }

    private fun showGenreFilterDialog() {
        val tags = viewModel.tags.value ?: return
        if (tags.isEmpty()) {
            Toast.makeText(requireContext(), R.string.loading_genres, Toast.LENGTH_SHORT).show()
            return
        }

        val currentTag = viewModel.selectedTag.value
        var tempSelectedTagIndex: Int? = if (currentTag == null) {
            null
        } else {
            tags.indexOf(currentTag).takeIf { it >= 0 }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_genre)
            .setView(createTagSearchView(tags, tempSelectedTagIndex) { selectedIndex ->
                tempSelectedTagIndex = selectedIndex
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (tempSelectedTagIndex == null) {
                    viewModel.clearTagFilter()
                } else {
                    // Use addTagFilter to keep current category (intelligent filtering)
                    viewModel.addTagFilter(tags[tempSelectedTagIndex!!])
                }
            }
            .create()

        dialog.show()
    }

    private fun showLanguageFilterDialog() {
        val languages = viewModel.languages.value ?: return
        if (languages.isEmpty()) {
            Toast.makeText(requireContext(), R.string.loading_languages, Toast.LENGTH_SHORT).show()
            return
        }

        val currentLanguage = viewModel.selectedLanguage.value
        var tempSelectedLanguageIndex: Int? = if (currentLanguage == null) {
            null
        } else {
            languages.indexOf(currentLanguage).takeIf { it >= 0 }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_language)
            .setView(createLanguageSearchView(languages, tempSelectedLanguageIndex) { selectedIndex ->
                tempSelectedLanguageIndex = selectedIndex
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (tempSelectedLanguageIndex == null) {
                    viewModel.clearLanguageFilter()
                } else {
                    // Use addLanguageFilter to keep current category (intelligent filtering)
                    viewModel.addLanguageFilter(languages[tempSelectedLanguageIndex!!])
                }
            }
            .create()

        dialog.show()
    }

    private fun createCountrySearchView(
        countries: List<com.opensource.i2pradio.data.radiobrowser.CountryInfo>,
        selectedIndex: Int?,
        onCountrySelected: (Int?) -> Unit
    ): View {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }

        val searchInput = TextInputEditText(context).apply {
            hint = getString(R.string.search_countries)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val searchLayout = com.google.android.material.textfield.TextInputLayout(context).apply {
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_FILLED
            isHintEnabled = true
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
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

        var filteredCountries = countries.toList()
        val adapter = CountryAdapter(filteredCountries, selectedIndex, onCountrySelected)
        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                filteredCountries = if (query.isEmpty()) {
                    countries
                } else {
                    countries.filter { it.name.contains(query, ignoreCase = true) }
                }
                adapter.updateCountries(filteredCountries)
            }
        })

        container.addView(recyclerView)
        return container
    }

    private fun createTagSearchView(
        tags: List<com.opensource.i2pradio.data.radiobrowser.TagInfo>,
        selectedIndex: Int?,
        onTagSelected: (Int?) -> Unit
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

        val searchLayout = com.google.android.material.textfield.TextInputLayout(context).apply {
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_FILLED
            isHintEnabled = true
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
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

        var filteredTags = tags.toList()
        val adapter = TagAdapter(filteredTags, selectedIndex, onTagSelected)
        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                filteredTags = if (query.isEmpty()) {
                    tags
                } else {
                    tags.filter { tag ->
                        val englishName = tag.name
                        val translatedName = translateGenreName(tag.name)
                        englishName.contains(query, ignoreCase = true) ||
                        translatedName.contains(query, ignoreCase = true)
                    }
                }
                adapter.updateTags(filteredTags)
            }
        })

        container.addView(recyclerView)
        return container
    }

    private fun createLanguageSearchView(
        languages: List<com.opensource.i2pradio.data.radiobrowser.LanguageInfo>,
        selectedIndex: Int?,
        onLanguageSelected: (Int?) -> Unit
    ): View {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }

        val searchInput = TextInputEditText(context).apply {
            hint = getString(R.string.search_languages)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val searchLayout = com.google.android.material.textfield.TextInputLayout(context).apply {
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_FILLED
            isHintEnabled = true
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
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

        var filteredLanguages = languages.toList()
        val adapter = LanguageAdapter(filteredLanguages, selectedIndex, onLanguageSelected)
        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                filteredLanguages = if (query.isEmpty()) {
                    languages
                } else {
                    languages.filter { it.name.contains(query, ignoreCase = true) }
                }
                adapter.updateLanguages(filteredLanguages)
            }
        })

        container.addView(recyclerView)
        return container
    }

    // Inner adapter classes for filter dialogs

    private inner class CountryAdapter(
        private var countries: List<com.opensource.i2pradio.data.radiobrowser.CountryInfo>,
        private val initialSelectedIndex: Int?,
        private val onCountrySelected: (Int?) -> Unit
    ) : RecyclerView.Adapter<CountryAdapter.ViewHolder>() {

        private var selectedPosition: Int = -1
        private val originalCountries = countries

        init {
            selectedPosition = initialSelectedIndex ?: -1
        }

        fun updateCountries(newCountries: List<com.opensource.i2pradio.data.radiobrowser.CountryInfo>) {
            countries = newCountries
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
            if (position >= itemCount) return

            if (position == 0) {
                holder.textView?.text = getString(R.string.filter_all_countries)
                holder.radioButton?.isChecked = selectedPosition == -1

                holder.itemView.setOnClickListener {
                    selectedPosition = -1
                    notifyDataSetChanged()
                    onCountrySelected(null)
                }
            } else {
                val country = countries[position - 1]
                holder.textView?.text = "${country.name} (${country.stationCount})"

                val originalIndex = originalCountries.indexOf(country)
                holder.radioButton?.isChecked = selectedPosition == originalIndex

                holder.itemView.setOnClickListener {
                    selectedPosition = originalIndex
                    notifyDataSetChanged()
                    onCountrySelected(originalIndex)
                }
            }
        }

        override fun getItemCount() = countries.size + 1
    }

    private inner class TagAdapter(
        private var tags: List<com.opensource.i2pradio.data.radiobrowser.TagInfo>,
        private val initialSelectedIndex: Int?,
        private val onTagSelected: (Int?) -> Unit
    ) : RecyclerView.Adapter<TagAdapter.ViewHolder>() {

        private var selectedPosition: Int = -1
        private val originalTags = tags

        init {
            selectedPosition = initialSelectedIndex ?: -1
        }

        fun updateTags(newTags: List<com.opensource.i2pradio.data.radiobrowser.TagInfo>) {
            tags = newTags
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
            if (position >= itemCount) return

            if (position == 0) {
                holder.textView?.text = getString(R.string.filter_all_genres)
                holder.radioButton?.isChecked = selectedPosition == -1

                holder.itemView.setOnClickListener {
                    selectedPosition = -1
                    notifyDataSetChanged()
                    onTagSelected(null)
                }
            } else {
                val tag = tags[position - 1]
                val displayName = translateGenreName(tag.name)
                holder.textView?.text = "$displayName (${tag.stationCount})"

                val originalIndex = originalTags.indexOf(tag)
                holder.radioButton?.isChecked = selectedPosition == originalIndex

                holder.itemView.setOnClickListener {
                    selectedPosition = originalIndex
                    notifyDataSetChanged()
                    onTagSelected(originalIndex)
                }
            }
        }

        override fun getItemCount() = tags.size + 1
    }

    private inner class LanguageAdapter(
        private var languages: List<com.opensource.i2pradio.data.radiobrowser.LanguageInfo>,
        private val initialSelectedIndex: Int?,
        private val onLanguageSelected: (Int?) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.ViewHolder>() {

        private var selectedPosition: Int = -1
        private val originalLanguages = languages

        init {
            selectedPosition = initialSelectedIndex ?: -1
        }

        fun updateLanguages(newLanguages: List<com.opensource.i2pradio.data.radiobrowser.LanguageInfo>) {
            languages = newLanguages
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
            if (position >= itemCount) return

            if (position == 0) {
                holder.textView?.text = getString(R.string.filter_all_languages)
                holder.radioButton?.isChecked = selectedPosition == -1

                holder.itemView.setOnClickListener {
                    selectedPosition = -1
                    notifyDataSetChanged()
                    onLanguageSelected(null)
                }
            } else {
                val language = languages[position - 1]
                holder.textView?.text = "${language.name} (${language.stationCount})"

                val originalIndex = originalLanguages.indexOf(language)
                holder.radioButton?.isChecked = selectedPosition == originalIndex

                holder.itemView.setOnClickListener {
                    selectedPosition = originalIndex
                    notifyDataSetChanged()
                    onLanguageSelected(originalIndex)
                }
            }
        }

        override fun getItemCount() = languages.size + 1
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MainActivity.BROADCAST_LIKE_STATE_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(likeStateReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(likeStateReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchDebounceRunnable?.let { resultsSearchInput.removeCallbacks(it) }

        // Clean up skeleton adapters
        skeletonCarouselAdapters.forEach { it.stopAllShimmers() }
        skeletonCarouselAdapters.clear()
        skeletonListAdapter?.stopAllShimmers()
        skeletonListAdapter = null
    }
}

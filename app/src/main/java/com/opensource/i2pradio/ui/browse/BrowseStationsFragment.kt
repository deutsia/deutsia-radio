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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserRepository
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserStation
import com.opensource.i2pradio.ui.RadioViewModel
import kotlinx.coroutines.launch

/**
 * Fragment for browsing and searching RadioBrowser stations.
 */
class BrowseStationsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingContainer: FrameLayout
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var torWarningBanner: MaterialCardView
    private lateinit var searchInput: TextInputEditText
    private lateinit var chipTopVoted: Chip
    private lateinit var chipPopular: Chip
    private lateinit var chipRandom: Chip
    private lateinit var chipHistory: Chip
    private lateinit var countryFilterButton: MaterialButton
    private lateinit var genreFilterButton: MaterialButton
    private lateinit var languageFilterButton: MaterialButton
    private lateinit var adapter: BrowseStationsAdapter

    private val viewModel: BrowseViewModel by viewModels()
    private val radioViewModel: RadioViewModel by activityViewModels()
    private lateinit var repository: RadioBrowserRepository

    private var searchDebounceRunnable: Runnable? = null
    private val searchDebounceDelay = 500L
    private var isManualSearchClear = false

    // Broadcast receiver for like state changes from other views
    private val likeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.BROADCAST_LIKE_STATE_CHANGED) {
                // Refresh the liked UUIDs to update the UI
                viewModel.refreshLikedAndSavedUuids()
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

        // Find views
        recyclerView = view.findViewById(R.id.stationsRecyclerView)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        loadingContainer = view.findViewById(R.id.loadingContainer)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        torWarningBanner = view.findViewById(R.id.torWarningBanner)
        searchInput = view.findViewById(R.id.searchInput)
        chipTopVoted = view.findViewById(R.id.chipTopVoted)
        chipPopular = view.findViewById(R.id.chipPopular)
        chipRandom = view.findViewById(R.id.chipRandom)
        chipHistory = view.findViewById(R.id.chipHistory)
        countryFilterButton = view.findViewById(R.id.countryFilterButton)
        genreFilterButton = view.findViewById(R.id.genreFilterButton)
        languageFilterButton = view.findViewById(R.id.languageFilterButton)

        setupRecyclerView()
        setupSearch()
        setupChips()
        setupFilters()
        setupSwipeRefresh()
        observeViewModel()

        return view
    }

    private fun setupRecyclerView() {
        adapter = BrowseStationsAdapter(
            onStationClick = { station -> playStation(station) },
            onAddClick = { station -> saveStation(station) },
            onRemoveClick = { station -> removeStation(station) },
            onLikeClick = { station -> likeStation(station) }
        )
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Pagination - load more when scrolling near bottom
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
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
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Debounce search
                searchDebounceRunnable?.let { searchInput.removeCallbacks(it) }
                searchDebounceRunnable = Runnable {
                    val query = s?.toString()?.trim() ?: ""
                    if (query.length >= 2) {
                        viewModel.search(query)
                        clearChipSelection()
                    } else if (query.isEmpty() && !isManualSearchClear) {
                        // Reset to top voted when search is cleared (but not when manually cleared by chip clicks)
                        chipTopVoted.isChecked = true
                        clearOtherChips(chipTopVoted)
                        viewModel.loadTopVoted()
                    }
                    isManualSearchClear = false
                }
                searchInput.postDelayed(searchDebounceRunnable!!, searchDebounceDelay)
            }
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = searchInput.text?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    viewModel.search(query)
                    clearChipSelection()
                }
                searchInput.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun setupChips() {
        // Use click listeners instead of checked change listeners
        // This is more reliable with ChipGroup single selection
        chipTopVoted.setOnClickListener {
            if (!chipTopVoted.isChecked) {
                chipTopVoted.isChecked = true
            }
            clearOtherChips(chipTopVoted)
            clearSearch()
            viewModel.loadTopVoted()
        }

        chipPopular.setOnClickListener {
            if (!chipPopular.isChecked) {
                chipPopular.isChecked = true
            }
            clearOtherChips(chipPopular)
            clearSearch()
            viewModel.loadTopClicked()
        }

        chipRandom.setOnClickListener {
            if (!chipRandom.isChecked) {
                chipRandom.isChecked = true
            }
            clearOtherChips(chipRandom)
            clearSearch()
            viewModel.loadRandom()
        }

        chipHistory.setOnClickListener {
            if (!chipHistory.isChecked) {
                chipHistory.isChecked = true
            }
            clearOtherChips(chipHistory)
            clearSearch()
            viewModel.loadHistory()
        }
    }

    private fun clearOtherChips(except: Chip) {
        if (except != chipTopVoted) chipTopVoted.isChecked = false
        if (except != chipPopular) chipPopular.isChecked = false
        if (except != chipRandom) chipRandom.isChecked = false
        if (except != chipHistory) chipHistory.isChecked = false
    }

    private fun selectTopVotedChip() {
        chipTopVoted.isChecked = true
        clearOtherChips(chipTopVoted)
        clearSearch()
        viewModel.loadTopVoted()
    }

    private fun setupFilters() {
        countryFilterButton.setOnClickListener {
            showCountryFilterDialog()
        }

        genreFilterButton.setOnClickListener {
            showGenreFilterDialog()
        }

        languageFilterButton.setOnClickListener {
            showLanguageFilterDialog()
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }
    }

    private fun observeViewModel() {
        viewModel.stations.observe(viewLifecycleOwner) { stations ->
            adapter.submitList(stations)
            updateEmptyState(stations.isEmpty())
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading && adapter.itemCount == 0) {
                loadingContainer.visibility = View.VISIBLE
            } else {
                loadingContainer.visibility = View.GONE
            }
            swipeRefresh.isRefreshing = isLoading && adapter.itemCount > 0
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
            adapter.updateSavedUuids(uuids)
        }

        viewModel.likedStationUuids.observe(viewLifecycleOwner) { uuids ->
            adapter.updateLikedUuids(uuids)
        }

        viewModel.selectedCountry.observe(viewLifecycleOwner) { country ->
            countryFilterButton.text = country?.name ?: getString(R.string.filter_country)
        }

        viewModel.selectedTag.observe(viewLifecycleOwner) { tag ->
            genreFilterButton.text = tag?.name ?: getString(R.string.filter_genre)
        }

        viewModel.selectedLanguage.observe(viewLifecycleOwner) { language ->
            languageFilterButton.text = language?.name ?: getString(R.string.filter_language)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty && viewModel.isLoading.value != true) {
            emptyStateContainer.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyStateContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun clearChipSelection() {
        chipTopVoted.isChecked = false
        chipPopular.isChecked = false
        chipRandom.isChecked = false
        chipHistory.isChecked = false
    }

    private fun clearSearch() {
        searchDebounceRunnable?.let { searchInput.removeCallbacks(it) }
        isManualSearchClear = true
        searchInput.setText("")
    }

    private fun playStation(station: RadioBrowserStation) {
        // Add to browse history
        lifecycleScope.launch {
            repository.addToBrowseHistory(station)
        }

        // Convert to RadioStation and play
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
            // Custom proxy fields (default values for new RadioBrowser stations)
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
        // Check current like and saved state before toggling
        val wasLiked = viewModel.likedStationUuids.value?.contains(station.stationuuid) ?: false
        val wasSaved = viewModel.savedStationUuids.value?.contains(station.stationuuid) ?: false

        viewModel.toggleLike(station)

        // Show appropriate toast based on the action performed
        lifecycleScope.launch {
            val updatedStation = repository.getStationInfoByUuid(station.stationuuid)

            if (!wasLiked) {
                // Station was just liked/hearted
                if (!wasSaved) {
                    // Station wasn't in library before - now added
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.station_saved, station.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Station was already in library, just marked as favorite
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.station_added_to_favorites, station.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                // Station was just unliked/unhearted
                // Check if it was a quick toggle or a long-time favorite
                val stationAge = if (updatedStation != null) {
                    System.currentTimeMillis() - updatedStation.addedTimestamp
                } else {
                    0L
                }

                val fiveMinutesInMillis = 5 * 60 * 1000
                if (stationAge > fiveMinutesInMillis) {
                    // Was a favorite for more than 5 minutes
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.station_removed_from_favorites, station.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Quick toggle or recently added
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.station_removed, station.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // If this station is currently playing, update RadioViewModel to sync miniplayer/Now Playing
            radioViewModel.getCurrentStation()?.let { currentStation ->
                if (currentStation.radioBrowserUuid == station.stationuuid) {
                    radioViewModel.updateCurrentStationLikeState(updatedStation?.isLiked ?: false)
                }
            }

            // Broadcast like state change to all views
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

        // Variable to hold the temporary selection
        val currentCountry = viewModel.selectedCountry.value
        var tempSelectedCountryIndex: Int? = if (currentCountry == null) {
            null // "All Countries"
        } else {
            countries.indexOf(currentCountry).takeIf { it >= 0 }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_country)
            .setView(createCountrySearchView(countries, tempSelectedCountryIndex) { selectedIndex ->
                // Store the temporary selection but don't apply yet
                tempSelectedCountryIndex = selectedIndex
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Apply the selection when OK is clicked
                if (tempSelectedCountryIndex == null) {
                    viewModel.filterByCountry(null)
                    selectTopVotedChip()
                } else {
                    viewModel.filterByCountry(countries[tempSelectedCountryIndex!!])
                    clearChipSelection()
                    clearSearch()
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

        // Variable to hold the temporary selection
        val currentTag = viewModel.selectedTag.value
        var tempSelectedTagIndex: Int? = if (currentTag == null) {
            null // "All Genres"
        } else {
            tags.indexOf(currentTag).takeIf { it >= 0 }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_genre)
            .setView(createTagSearchView(tags, tempSelectedTagIndex) { selectedIndex ->
                // Store the temporary selection but don't apply yet
                tempSelectedTagIndex = selectedIndex
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Apply the selection when OK is clicked
                if (tempSelectedTagIndex == null) {
                    viewModel.filterByTag(null)
                    selectTopVotedChip()
                } else {
                    viewModel.filterByTag(tags[tempSelectedTagIndex!!])
                    clearChipSelection()
                    clearSearch()
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

        // Variable to hold the temporary selection
        val currentLanguage = viewModel.selectedLanguage.value
        var tempSelectedLanguageIndex: Int? = if (currentLanguage == null) {
            null // "All Languages"
        } else {
            languages.indexOf(currentLanguage).takeIf { it >= 0 }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_language)
            .setView(createLanguageSearchView(languages, tempSelectedLanguageIndex) { selectedIndex ->
                // Store the temporary selection but don't apply yet
                tempSelectedLanguageIndex = selectedIndex
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Apply the selection when OK is clicked
                if (tempSelectedLanguageIndex == null) {
                    viewModel.filterByLanguage(null)
                    selectTopVotedChip()
                } else {
                    viewModel.filterByLanguage(languages[tempSelectedLanguageIndex!!])
                    clearChipSelection()
                    clearSearch()
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

        // Search input with improved styling
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

        // Divider
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

        // RecyclerView for country list
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            // Set a max height for the dialog (approx 400dp)
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

        // Search functionality
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

        // Search input with improved styling
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

        // Divider
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

        // RecyclerView for tag list
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            // Set a max height for the dialog (approx 400dp)
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

        // Search functionality
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                filteredTags = if (query.isEmpty()) {
                    tags
                } else {
                    tags.filter { it.name.contains(query, ignoreCase = true) }
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

        // Search input with improved styling
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

        // Divider
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

        // RecyclerView for language list
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            // Set a max height for the dialog (approx 400dp)
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

        // Search functionality
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

    // Adapter for Country list with search
    private inner class CountryAdapter(
        private var countries: List<com.opensource.i2pradio.data.radiobrowser.CountryInfo>,
        private val initialSelectedIndex: Int?,
        private val onCountrySelected: (Int?) -> Unit
    ) : RecyclerView.Adapter<CountryAdapter.ViewHolder>() {

        private var selectedPosition: Int = -1 // -1 means "All Countries"
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
                // "All Countries" option
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

                // Find the index in the original list
                val originalIndex = originalCountries.indexOf(country)
                holder.radioButton?.isChecked = selectedPosition == originalIndex

                holder.itemView.setOnClickListener {
                    selectedPosition = originalIndex
                    notifyDataSetChanged()
                    onCountrySelected(originalIndex)
                }
            }
        }

        override fun getItemCount() = countries.size + 1 // +1 for "All Countries"
    }

    // Adapter for Tag list with search
    private inner class TagAdapter(
        private var tags: List<com.opensource.i2pradio.data.radiobrowser.TagInfo>,
        private val initialSelectedIndex: Int?,
        private val onTagSelected: (Int?) -> Unit
    ) : RecyclerView.Adapter<TagAdapter.ViewHolder>() {

        private var selectedPosition: Int = -1 // -1 means "All Genres"
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
                // "All Genres" option
                holder.textView?.text = getString(R.string.filter_all_genres)
                holder.radioButton?.isChecked = selectedPosition == -1

                holder.itemView.setOnClickListener {
                    selectedPosition = -1
                    notifyDataSetChanged()
                    onTagSelected(null)
                }
            } else {
                val tag = tags[position - 1]
                holder.textView?.text = "${tag.name} (${tag.stationCount})"

                // Find the index in the original list
                val originalIndex = originalTags.indexOf(tag)
                holder.radioButton?.isChecked = selectedPosition == originalIndex

                holder.itemView.setOnClickListener {
                    selectedPosition = originalIndex
                    notifyDataSetChanged()
                    onTagSelected(originalIndex)
                }
            }
        }

        override fun getItemCount() = tags.size + 1 // +1 for "All Genres"
    }

    // Adapter for Language list with search
    private inner class LanguageAdapter(
        private var languages: List<com.opensource.i2pradio.data.radiobrowser.LanguageInfo>,
        private val initialSelectedIndex: Int?,
        private val onLanguageSelected: (Int?) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.ViewHolder>() {

        private var selectedPosition: Int = -1 // -1 means "All Languages"
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
                // "All Languages" option
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

                // Find the index in the original list
                val originalIndex = originalLanguages.indexOf(language)
                holder.radioButton?.isChecked = selectedPosition == originalIndex

                holder.itemView.setOnClickListener {
                    selectedPosition = originalIndex
                    notifyDataSetChanged()
                    onLanguageSelected(originalIndex)
                }
            }
        }

        override fun getItemCount() = languages.size + 1 // +1 for "All Languages"
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

    override fun onDestroyView() {
        super.onDestroyView()
        searchDebounceRunnable?.let { searchInput.removeCallbacks(it) }
    }
}

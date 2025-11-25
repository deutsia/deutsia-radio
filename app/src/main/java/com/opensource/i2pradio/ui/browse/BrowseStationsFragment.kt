package com.opensource.i2pradio.ui.browse

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
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
    private lateinit var chipHistory: Chip
    private lateinit var countryFilterButton: MaterialButton
    private lateinit var genreFilterButton: MaterialButton
    private lateinit var adapter: BrowseStationsAdapter

    private val viewModel: BrowseViewModel by viewModels()
    private val radioViewModel: RadioViewModel by activityViewModels()
    private lateinit var repository: RadioBrowserRepository

    private var searchDebounceRunnable: Runnable? = null
    private val searchDebounceDelay = 500L
    private var isManualSearchClear = false

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
        chipHistory = view.findViewById(R.id.chipHistory)
        countryFilterButton = view.findViewById(R.id.countryFilterButton)
        genreFilterButton = view.findViewById(R.id.genreFilterButton)

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
        chipHistory.isChecked = false
    }

    private fun clearSearch() {
        searchDebounceRunnable?.let { searchInput.removeCallbacks(it) }
        isManualSearchClear = true
        searchInput.setText("")
    }

    private fun playStation(station: RadioBrowserStation) {
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
        viewModel.toggleLike(station)
        // Check the updated like state from the database and show toast
        lifecycleScope.launch {
            val updatedStation = repository.getStationInfoByUuid(station.stationuuid)
            updatedStation?.let {
                // Show toast message when station is liked
                if (it.isLiked) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.station_saved, station.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                // If this station is currently playing, update RadioViewModel to sync miniplayer/Now Playing
                radioViewModel.getCurrentStation()?.let { currentStation ->
                    if (currentStation.radioBrowserUuid == station.stationuuid) {
                        radioViewModel.updateCurrentStationLikeState(it.isLiked)
                    }
                }
            }
        }
    }

    private fun showCountryFilterDialog() {
        val countries = viewModel.countries.value ?: return
        if (countries.isEmpty()) {
            Toast.makeText(requireContext(), R.string.loading_countries, Toast.LENGTH_SHORT).show()
            return
        }

        val countryNames = listOf(getString(R.string.filter_all_countries)) +
                countries.map { "${it.name} (${it.stationCount})" }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_country)
            .setItems(countryNames.toTypedArray()) { _, which ->
                if (which == 0) {
                    viewModel.filterByCountry(null)
                    selectTopVotedChip()
                } else {
                    viewModel.filterByCountry(countries[which - 1])
                    clearChipSelection()
                    clearSearch()
                }
            }
            .show()
    }

    private fun showGenreFilterDialog() {
        val tags = viewModel.tags.value ?: return
        if (tags.isEmpty()) {
            Toast.makeText(requireContext(), R.string.loading_genres, Toast.LENGTH_SHORT).show()
            return
        }

        val tagNames = listOf(getString(R.string.filter_all_genres)) +
                tags.map { "${it.name} (${it.stationCount})" }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_genre)
            .setItems(tagNames.toTypedArray()) { _, which ->
                if (which == 0) {
                    viewModel.filterByTag(null)
                    selectTopVotedChip()
                } else {
                    viewModel.filterByTag(tags[which - 1])
                    clearChipSelection()
                    clearSearch()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchDebounceRunnable?.let { searchInput.removeCallbacks(it) }
    }
}

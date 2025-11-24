package com.opensource.i2pradio.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.opensource.i2pradio.data.radiobrowser.BrowseCategory
import com.opensource.i2pradio.data.radiobrowser.CountryInfo
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserRepository
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserResult
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserStation
import com.opensource.i2pradio.data.radiobrowser.TagInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewModel for the Browse Stations feature.
 * Manages UI state for searching and browsing RadioBrowser stations.
 */
class BrowseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RadioBrowserRepository(application)

    // Current browse category
    private val _currentCategory = MutableLiveData(BrowseCategory.TOP_VOTED)
    val currentCategory: LiveData<BrowseCategory> = _currentCategory

    // Station list
    private val _stations = MutableLiveData<List<RadioBrowserStation>>(emptyList())
    val stations: LiveData<List<RadioBrowserStation>> = _stations

    // Loading state
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Error message
    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    // Tor warning (when Force Tor enabled but not connected)
    private val _showTorWarning = MutableLiveData(false)
    val showTorWarning: LiveData<Boolean> = _showTorWarning

    // Search query
    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    // Selected country for filtering
    private val _selectedCountry = MutableLiveData<CountryInfo?>(null)
    val selectedCountry: LiveData<CountryInfo?> = _selectedCountry

    // Selected tag for filtering
    private val _selectedTag = MutableLiveData<TagInfo?>(null)
    val selectedTag: LiveData<TagInfo?> = _selectedTag

    // Countries list (for filter dropdown)
    private val _countries = MutableLiveData<List<CountryInfo>>(emptyList())
    val countries: LiveData<List<CountryInfo>> = _countries

    // Tags list (for filter dropdown)
    private val _tags = MutableLiveData<List<TagInfo>>(emptyList())
    val tags: LiveData<List<TagInfo>> = _tags

    // Pagination
    private var currentOffset = 0
    private val pageSize = 50
    private val _hasMoreResults = MutableLiveData(true)
    val hasMoreResults: LiveData<Boolean> = _hasMoreResults

    // Track saved station UUIDs for UI state
    private val _savedStationUuids = MutableLiveData<Set<String>>(emptySet())
    val savedStationUuids: LiveData<Set<String>> = _savedStationUuids

    // Current search/fetch job for cancellation
    private var currentJob: Job? = null

    init {
        // Load initial data
        loadTopVoted()
        loadCountries()
        loadTags()
    }

    /**
     * Load top voted stations
     */
    fun loadTopVoted() {
        _currentCategory.value = BrowseCategory.TOP_VOTED
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Load top clicked (popular) stations
     */
    fun loadTopClicked() {
        _currentCategory.value = BrowseCategory.TOP_CLICKED
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Load recently changed stations
     */
    fun loadRecentlyChanged() {
        _currentCategory.value = BrowseCategory.RECENTLY_CHANGED
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Search stations by name
     */
    fun search(query: String) {
        _searchQuery.value = query
        _currentCategory.value = BrowseCategory.SEARCH
        currentOffset = 0
        _stations.value = emptyList()
        if (query.isNotBlank()) {
            fetchStations()
        }
    }

    /**
     * Filter by country
     */
    fun filterByCountry(country: CountryInfo?) {
        _selectedCountry.value = country
        _currentCategory.value = BrowseCategory.BY_COUNTRY
        currentOffset = 0
        _stations.value = emptyList()
        if (country != null) {
            fetchStations()
        }
    }

    /**
     * Filter by tag/genre
     */
    fun filterByTag(tag: TagInfo?) {
        _selectedTag.value = tag
        _currentCategory.value = BrowseCategory.BY_TAG
        currentOffset = 0
        _stations.value = emptyList()
        if (tag != null) {
            fetchStations()
        }
    }

    /**
     * Load more results (pagination)
     */
    fun loadMore() {
        if (_isLoading.value == true || _hasMoreResults.value == false) return
        currentOffset += pageSize
        fetchStations(append = true)
    }

    /**
     * Refresh current view
     */
    fun refresh() {
        currentOffset = 0
        _stations.value = emptyList()
        _errorMessage.value = null
        fetchStations()
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Check if a station is already saved
     */
    suspend fun isStationSaved(uuid: String): Boolean {
        return repository.isStationSaved(uuid)
    }

    /**
     * Save a station to user's library
     */
    fun saveStation(station: RadioBrowserStation) {
        viewModelScope.launch {
            val id = repository.saveStation(station, asUserStation = true)
            if (id != null) {
                // Update saved UUIDs set
                val current = _savedStationUuids.value.orEmpty().toMutableSet()
                current.add(station.stationuuid)
                _savedStationUuids.value = current
            }
        }
    }

    /**
     * Mark a station as saved (for UI state)
     */
    fun markAsSaved(uuid: String) {
        val current = _savedStationUuids.value.orEmpty().toMutableSet()
        current.add(uuid)
        _savedStationUuids.value = current
    }

    /**
     * Load countries list
     */
    private fun loadCountries() {
        viewModelScope.launch {
            when (val result = repository.getCountries()) {
                is RadioBrowserResult.Success -> {
                    // Filter to countries with at least 10 stations
                    _countries.value = result.data.filter { it.stationCount >= 10 }
                }
                is RadioBrowserResult.Error -> {
                    // Silently fail - countries are optional
                }
                is RadioBrowserResult.Loading -> {}
            }
        }
    }

    /**
     * Load tags list
     */
    private fun loadTags() {
        viewModelScope.launch {
            when (val result = repository.getTags(100)) {
                is RadioBrowserResult.Success -> {
                    // Filter to tags with at least 50 stations
                    _tags.value = result.data.filter { it.stationCount >= 50 }
                }
                is RadioBrowserResult.Error -> {
                    // Silently fail - tags are optional
                }
                is RadioBrowserResult.Loading -> {}
            }
        }
    }

    /**
     * Fetch stations based on current category and filters
     */
    private fun fetchStations(append: Boolean = false) {
        // Check Tor status
        if (repository.isTorRequiredButNotConnected()) {
            _showTorWarning.value = true
            return
        }
        _showTorWarning.value = false

        // Cancel previous job
        currentJob?.cancel()

        currentJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val result: RadioBrowserResult<List<RadioBrowserStation>> = when (_currentCategory.value) {
                BrowseCategory.TOP_VOTED -> {
                    repository.getTopVoted(pageSize, currentOffset)
                }
                BrowseCategory.TOP_CLICKED -> {
                    repository.getTopClicked(pageSize, currentOffset)
                }
                BrowseCategory.RECENTLY_CHANGED -> {
                    repository.getRecentlyChanged(pageSize, currentOffset)
                }
                BrowseCategory.BY_COUNTRY -> {
                    val country = _selectedCountry.value
                    if (country != null) {
                        repository.getByCountryCode(country.iso3166_1, pageSize, currentOffset)
                    } else {
                        RadioBrowserResult.Error("No country selected")
                    }
                }
                BrowseCategory.BY_TAG -> {
                    val tag = _selectedTag.value
                    if (tag != null) {
                        repository.getByTag(tag.name, pageSize, currentOffset)
                    } else {
                        RadioBrowserResult.Error("No tag selected")
                    }
                }
                BrowseCategory.SEARCH -> {
                    val query = _searchQuery.value
                    if (!query.isNullOrBlank()) {
                        repository.searchByName(query, pageSize, currentOffset)
                    } else {
                        RadioBrowserResult.Error("Empty search query")
                    }
                }
                null -> RadioBrowserResult.Error("No category selected")
            }

            when (result) {
                is RadioBrowserResult.Success -> {
                    val newStations = result.data
                    if (append) {
                        val current = _stations.value.orEmpty()
                        _stations.value = current + newStations
                    } else {
                        _stations.value = newStations
                    }
                    _hasMoreResults.value = newStations.size >= pageSize

                    // Check which stations are already saved
                    checkSavedStatus(newStations)
                }
                is RadioBrowserResult.Error -> {
                    _errorMessage.value = result.message
                    _hasMoreResults.value = false
                }
                is RadioBrowserResult.Loading -> {
                    // Already handled by _isLoading
                }
            }

            _isLoading.value = false
        }
    }

    /**
     * Check saved status for a list of stations
     */
    private suspend fun checkSavedStatus(stations: List<RadioBrowserStation>) {
        val savedUuids = mutableSetOf<String>()
        savedUuids.addAll(_savedStationUuids.value.orEmpty())

        for (station in stations) {
            if (repository.isStationSaved(station.stationuuid)) {
                savedUuids.add(station.stationuuid)
            }
        }

        _savedStationUuids.value = savedUuids
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
    }
}

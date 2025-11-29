package com.opensource.i2pradio.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.opensource.i2pradio.data.radiobrowser.BrowseCategory
import com.opensource.i2pradio.data.radiobrowser.CountryInfo
import com.opensource.i2pradio.data.radiobrowser.LanguageInfo
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserRepository
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserResult
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserStation
import com.opensource.i2pradio.data.radiobrowser.TagInfo
import kotlinx.coroutines.Dispatchers
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

    // Selected language for filtering
    private val _selectedLanguage = MutableLiveData<LanguageInfo?>(null)
    val selectedLanguage: LiveData<LanguageInfo?> = _selectedLanguage

    // Countries list (for filter dropdown)
    private val _countries = MutableLiveData<List<CountryInfo>>(emptyList())
    val countries: LiveData<List<CountryInfo>> = _countries

    // Tags list (for filter dropdown)
    private val _tags = MutableLiveData<List<TagInfo>>(emptyList())
    val tags: LiveData<List<TagInfo>> = _tags

    // Languages list (for filter dropdown)
    private val _languages = MutableLiveData<List<LanguageInfo>>(emptyList())
    val languages: LiveData<List<LanguageInfo>> = _languages

    // Temporary memory of stations shown in Top Voted (to hide in Popular)
    private val topVotedStationUuids = mutableSetOf<String>()

    // Pagination
    private var currentOffset = 0
    private val pageSize = 50
    private val _hasMoreResults = MutableLiveData(true)
    val hasMoreResults: LiveData<Boolean> = _hasMoreResults

    // Track saved station UUIDs for UI state
    private val _savedStationUuids = MutableLiveData<Set<String>>(emptySet())
    val savedStationUuids: LiveData<Set<String>> = _savedStationUuids

    // Track liked station UUIDs for UI state
    private val _likedStationUuids = MutableLiveData<Set<String>>(emptySet())
    val likedStationUuids: LiveData<Set<String>> = _likedStationUuids

    // Current search/fetch job for cancellation
    private var currentJob: Job? = null

    init {
        // Load initial data
        loadTopVoted()
        loadCountries()
        loadTags()
        loadLanguages()
    }

    /**
     * Load top voted stations
     */
    fun loadTopVoted() {
        _currentCategory.value = BrowseCategory.TOP_VOTED
        currentOffset = 0
        _stations.value = emptyList()
        // Clear the temporary memory when switching tabs
        topVotedStationUuids.clear()
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
     * Load random stations (actually newest/recently changed stations)
     */
    fun loadRandom() {
        _currentCategory.value = BrowseCategory.RANDOM
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Load history (last 75 stations played from browse)
     */
    fun loadHistory() {
        _currentCategory.value = BrowseCategory.HISTORY
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
     * Filter by language
     */
    fun filterByLanguage(language: LanguageInfo?) {
        _selectedLanguage.value = language
        _currentCategory.value = BrowseCategory.BY_LANGUAGE
        currentOffset = 0
        _stations.value = emptyList()
        if (language != null) {
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
     * Like a station (saves it and marks it as liked)
     * Note: This is fire-and-forget. Use toggleLike() if you need synchronous behavior.
     */
    fun likeStation(station: RadioBrowserStation) {
        viewModelScope.launch {
            val id = repository.saveStationAsLiked(station)
            if (id != null) {
                // Update both saved and liked UUIDs sets
                val savedCurrent = _savedStationUuids.value.orEmpty().toMutableSet()
                savedCurrent.add(station.stationuuid)
                _savedStationUuids.value = savedCurrent

                val likedCurrent = _likedStationUuids.value.orEmpty().toMutableSet()
                likedCurrent.add(station.stationuuid)
                _likedStationUuids.value = likedCurrent
            }
        }
    }

    /**
     * Toggle like status for a station
     */
    fun toggleLike(station: RadioBrowserStation) {
        val uuid = station.stationuuid
        val isCurrentlyLiked = _likedStationUuids.value.orEmpty().contains(uuid)

        viewModelScope.launch {
            if (isCurrentlyLiked) {
                // Unlike: remove the station from library entirely
                val deleted = repository.deleteStationByUuid(uuid)
                if (deleted) {
                    // Update both saved and liked UUIDs sets
                    val savedCurrent = _savedStationUuids.value.orEmpty().toMutableSet()
                    savedCurrent.remove(uuid)
                    _savedStationUuids.value = savedCurrent

                    val likedCurrent = _likedStationUuids.value.orEmpty().toMutableSet()
                    likedCurrent.remove(uuid)
                    _likedStationUuids.value = likedCurrent
                }
            } else {
                // Like: save and mark as liked
                val id = repository.saveStationAsLiked(station)
                if (id != null) {
                    val savedCurrent = _savedStationUuids.value.orEmpty().toMutableSet()
                    savedCurrent.add(uuid)
                    _savedStationUuids.value = savedCurrent

                    val likedCurrent = _likedStationUuids.value.orEmpty().toMutableSet()
                    likedCurrent.add(uuid)
                    _likedStationUuids.value = likedCurrent
                }
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
     * Remove a station from user's library
     */
    fun removeStation(station: RadioBrowserStation) {
        viewModelScope.launch {
            val deleted = repository.deleteStationByUuid(station.stationuuid)
            if (deleted) {
                // Update saved and liked UUIDs sets
                val savedCurrent = _savedStationUuids.value.orEmpty().toMutableSet()
                savedCurrent.remove(station.stationuuid)
                _savedStationUuids.value = savedCurrent

                val likedCurrent = _likedStationUuids.value.orEmpty().toMutableSet()
                likedCurrent.remove(station.stationuuid)
                _likedStationUuids.value = likedCurrent
            }
        }
    }

    /**
     * Load countries list
     */
    private fun loadCountries() {
        viewModelScope.launch {
            when (val result = repository.getCountries()) {
                is RadioBrowserResult.Success -> {
                    // Sort alphabetically, show all countries regardless of station count
                    _countries.value = result.data.sortedBy { it.name }
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
            when (val result = repository.getTags(200)) {
                is RadioBrowserResult.Success -> {
                    // Filter to tags with at least 10 stations and sort alphabetically
                    _tags.value = result.data
                        .filter { it.stationCount >= 10 }
                        .sortedBy { it.name.lowercase() }
                }
                is RadioBrowserResult.Error -> {
                    // Silently fail - tags are optional
                }
                is RadioBrowserResult.Loading -> {}
            }
        }
    }

    /**
     * Load languages list
     */
    private fun loadLanguages() {
        viewModelScope.launch {
            when (val result = repository.getLanguages(200)) {
                is RadioBrowserResult.Success -> {
                    // Filter to languages with at least 10 stations and sort alphabetically
                    _languages.value = result.data
                        .filter { it.stationCount >= 10 }
                        .sortedBy { it.name }
                }
                is RadioBrowserResult.Error -> {
                    // Silently fail - languages are optional
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
                BrowseCategory.RANDOM -> {
                    repository.getRecentlyChanged(pageSize, currentOffset)
                }
                BrowseCategory.HISTORY -> {
                    // Show user's browse history (last 75 played from browse)
                    repository.getBrowseHistory()
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
                BrowseCategory.BY_LANGUAGE -> {
                    val language = _selectedLanguage.value
                    if (language != null) {
                        repository.getByLanguage(language.name, pageSize, currentOffset)
                    } else {
                        RadioBrowserResult.Error("No language selected")
                    }
                }
                BrowseCategory.SEARCH -> {
                    val query = _searchQuery.value
                    if (!query.isNullOrBlank()) {
                        // Intelligent multi-word search across multiple fields
                        performIntelligentSearch(query, pageSize, currentOffset)
                    } else {
                        RadioBrowserResult.Error("Empty search query")
                    }
                }
                null -> RadioBrowserResult.Error("No category selected")
            }

            when (result) {
                is RadioBrowserResult.Success -> {
                    var newStations = result.data

                    // Track Top Voted stations for hiding in Popular
                    if (_currentCategory.value == BrowseCategory.TOP_VOTED) {
                        newStations.forEach { topVotedStationUuids.add(it.stationuuid) }
                    }

                    // Filter out Top Voted stations from Popular
                    if (_currentCategory.value == BrowseCategory.TOP_CLICKED && topVotedStationUuids.isNotEmpty()) {
                        newStations = newStations.filter { it.stationuuid !in topVotedStationUuids }
                    }

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
     * Check saved and liked status for a list of stations
     * Uses batch query to avoid N+1 query problem
     */
    private suspend fun checkSavedStatus(stations: List<RadioBrowserStation>) {
        val savedUuids = mutableSetOf<String>()
        savedUuids.addAll(_savedStationUuids.value.orEmpty())

        val likedUuids = mutableSetOf<String>()
        likedUuids.addAll(_likedStationUuids.value.orEmpty())

        // Batch query: get all station info at once instead of one-by-one
        val uuids = stations.map { it.stationuuid }
        val stationInfoMap = repository.getStationInfoByUuids(uuids)

        for (station in stations) {
            val stationInfo = stationInfoMap[station.stationuuid]
            if (stationInfo != null) {
                savedUuids.add(station.stationuuid)
                if (stationInfo.isLiked) {
                    likedUuids.add(station.stationuuid)
                }
            }
        }

        _savedStationUuids.postValue(savedUuids)
        _likedStationUuids.postValue(likedUuids)
    }

    /**
     * Refresh liked and saved station UUIDs from the database.
     * Called when like state changes from other views to keep UI in sync.
     */
    fun refreshLikedAndSavedUuids() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentStations = _stations.value ?: emptyList()
            if (currentStations.isNotEmpty()) {
                checkSavedStatus(currentStations)
            }
        }
    }

    /**
     * OPTIMIZED intelligent multi-field search that supports:
     * - Multi-word queries (e.g., "BBC London", "Rock USA")
     * - Searching by genre/tag (e.g., "Jazz", "Classical")
     * - Searching by country (e.g., "Germany", "USA")
     * - Combined searches (e.g., "Rock Germany" finds rock stations from Germany)
     *
     * PERFORMANCE: Reduced from 9+ API calls to just 2-3 calls per search (78-89% reduction!)
     */
    private suspend fun performIntelligentSearch(
        query: String,
        limit: Int,
        offset: Int
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val trimmedQuery = query.trim()

        // For pagination (offset > 0), search by name only for consistency
        if (offset > 0) {
            return repository.searchStations(
                name = trimmedQuery,
                limit = limit,
                offset = offset
            )
        }

        // Split query into words for intelligent multi-field search
        val words = trimmedQuery.split("\\s+".toRegex())
            .filter { it.length >= 2 }

        // OPTIMIZATION: Reduced API calls by searching only for the complete phrase
        // and letting client-side filtering handle multi-word matches
        val allResults = mutableListOf<RadioBrowserStation>()

        // Primary search: Search by name (most comprehensive - covers station names)
        // RadioBrowser searches for the query as a substring in station names
        val nameResult = repository.searchStations(
            name = trimmedQuery,
            limit = limit,
            offset = 0
        )
        if (nameResult is RadioBrowserResult.Success) {
            allResults.addAll(nameResult.data)
        }

        // Secondary search: Search by tag/genre (for genre-based queries like "Rock" or "Jazz")
        // RadioBrowser searches for the query as a substring in tags/genres
        val tagResult = repository.searchStations(
            tag = trimmedQuery,
            limit = limit,
            offset = 0
        )
        if (tagResult is RadioBrowserResult.Success) {
            allResults.addAll(tagResult.data)
        }

        // Tertiary search: Search by country for single-word queries only
        // Skip for multi-word to reduce API calls - client-side filtering handles cross-field matches
        if (words.size == 1) {
            val countryResult = repository.searchStations(
                country = trimmedQuery,
                limit = limit,
                offset = 0
            )
            if (countryResult is RadioBrowserResult.Success) {
                allResults.addAll(countryResult.data)
            }
        }

        // If no results from any search, return error
        if (allResults.isEmpty()) {
            return RadioBrowserResult.Error("No stations found for: $trimmedQuery")
        }

        // OPTIMIZATION: Pre-normalize search terms once (not in the filter loop)
        val searchTerms = words.map { it.lowercase() }

        // For multi-word queries, filter results client-side to keep only stations
        // that match ALL words somewhere in their searchable fields (name, tags, country)
        val filteredResults = if (searchTerms.size > 1) {
            // Multi-word query: filter to stations that match all words somewhere
            allResults.filter { station ->
                // OPTIMIZATION: Build normalized searchable text once per station
                // (not 3 times with separate .lowercase() calls)
                val searchableText = buildString {
                    append(station.name.lowercase())
                    append(" ")
                    append(station.tags.lowercase())
                    append(" ")
                    append(station.country.lowercase())
                }
                // All words must appear somewhere in the station's searchable text
                searchTerms.all { term -> searchableText.contains(term) }
            }
        } else {
            allResults
        }

        // Deduplicate and limit results
        val deduplicated = filteredResults
            .distinctBy { it.stationuuid }
            .take(limit)

        return RadioBrowserResult.Success(deduplicated)
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
    }
}

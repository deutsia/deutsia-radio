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
import com.opensource.i2pradio.data.radioregistry.RadioRegistryRepository
import com.opensource.i2pradio.data.radioregistry.RadioRegistryResult
import com.opensource.i2pradio.data.radioregistry.RadioRegistryStation
import com.opensource.i2pradio.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * ViewModel for the Browse Stations feature.
 * Manages UI state for searching and browsing RadioBrowser stations.
 */
class BrowseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RadioBrowserRepository(application)
    private val registryRepository = RadioRegistryRepository(application)

    // Current browse category
    private val _currentCategory = MutableLiveData(BrowseCategory.ALL_STATIONS)
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

    // Discovery mode carousel data (cached to survive configuration changes)
    private val _usaStations = MutableLiveData<List<RadioBrowserStation>>(emptyList())
    val usaStations: LiveData<List<RadioBrowserStation>> = _usaStations

    private val _germanyStations = MutableLiveData<List<RadioBrowserStation>>(emptyList())
    val germanyStations: LiveData<List<RadioBrowserStation>> = _germanyStations

    private val _spanishStations = MutableLiveData<List<RadioBrowserStation>>(emptyList())
    val spanishStations: LiveData<List<RadioBrowserStation>> = _spanishStations

    private val _frenchStations = MutableLiveData<List<RadioBrowserStation>>(emptyList())
    val frenchStations: LiveData<List<RadioBrowserStation>> = _frenchStations

    private val _trendingStations = MutableLiveData<List<RadioBrowserStation>>(emptyList())
    val trendingStations: LiveData<List<RadioBrowserStation>> = _trendingStations

    private val _topVotedPreviewStations = MutableLiveData<List<RadioBrowserStation>>(emptyList())
    val topVotedPreviewStations: LiveData<List<RadioBrowserStation>> = _topVotedPreviewStations

    private val _popularStations = MutableLiveData<List<RadioBrowserStation>>(emptyList())
    val popularStations: LiveData<List<RadioBrowserStation>> = _popularStations

    private val _newStations = MutableLiveData<List<RadioBrowserStation>>(emptyList())
    val newStations: LiveData<List<RadioBrowserStation>> = _newStations

    // Privacy Radio (Radio Registry API) stations
    private val _privacyTorStations = MutableLiveData<List<RadioRegistryStation>>(emptyList())
    val privacyTorStations: LiveData<List<RadioRegistryStation>> = _privacyTorStations

    private val _privacyI2pStations = MutableLiveData<List<RadioRegistryStation>>(emptyList())
    val privacyI2pStations: LiveData<List<RadioRegistryStation>> = _privacyI2pStations

    // Privacy Radio loading state (independent of main discovery loading)
    private val _isPrivacyRadioLoading = MutableLiveData(true)
    val isPrivacyRadioLoading: LiveData<Boolean> = _isPrivacyRadioLoading

    // Privacy Radio error state
    private val _privacyRadioError = MutableLiveData<String?>(null)
    val privacyRadioError: LiveData<String?> = _privacyRadioError

    // Track if privacy radio data has been loaded
    private var privacyRadioDataLoaded = false

    // Track if privacy radio loading failed due to Tor not being ready (race condition)
    private var privacyRadioNeedsTorRetry = false

    // TorManager state listener for retrying privacy radio load when Tor connects
    private val torStateListener: (TorManager.TorState) -> Unit = { state ->
        if (state == TorManager.TorState.CONNECTED && privacyRadioNeedsTorRetry) {
            // Tor is now connected, retry loading privacy radio data
            android.util.Log.d("BrowseViewModel", "Tor connected, retrying privacy radio data load")
            privacyRadioNeedsTorRetry = false
            privacyRadioDataLoaded = false // Reset to allow reload
            loadPrivacyRadioData(forceRefresh = true)
        }
    }

    // Track if discovery data has been loaded
    private var discoveryDataLoaded = false

    // Discovery loading state
    private val _isDiscoveryLoading = MutableLiveData(true)
    val isDiscoveryLoading: LiveData<Boolean> = _isDiscoveryLoading

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
        // Register TorManager listener to retry privacy radio loading when Tor connects
        // This handles the race condition where the ViewModel loads before Tor is ready
        TorManager.addStateListener(torStateListener, notifyImmediately = false)

        // Load initial data
        loadAllStations()
        loadCountries()
        loadTags()
        loadLanguages()
        loadDiscoveryData()
        loadPrivacyRadioData()
    }

    /**
     * Load all discovery mode carousel data.
     * This is cached in the ViewModel to survive configuration changes.
     */
    fun loadDiscoveryData(forceRefresh: Boolean = false) {
        if (discoveryDataLoaded && !forceRefresh) return
        discoveryDataLoaded = true
        _isDiscoveryLoading.value = true

        viewModelScope.launch {
            // Load USA stations
            when (val result = repository.getByCountryCode("US", 10, 0)) {
                is RadioBrowserResult.Success -> _usaStations.value = result.data
                else -> {}
            }

            // Load Germany stations
            when (val result = repository.getByCountryCode("DE", 10, 0)) {
                is RadioBrowserResult.Success -> _germanyStations.value = result.data
                else -> {}
            }

            // Load Spanish stations
            when (val result = repository.getByLanguage("spanish", 10, 0)) {
                is RadioBrowserResult.Success -> _spanishStations.value = result.data
                else -> {}
            }

            // Load French stations
            when (val result = repository.getByLanguage("french", 10, 0)) {
                is RadioBrowserResult.Success -> _frenchStations.value = result.data
                else -> {}
            }

            // Load trending (recently changed)
            when (val result = repository.getRecentlyChanged(10, 0)) {
                is RadioBrowserResult.Success -> _trendingStations.value = result.data
                else -> {}
            }

            // Load top voted preview
            when (val result = repository.getTopVoted(10, 0)) {
                is RadioBrowserResult.Success -> _topVotedPreviewStations.value = result.data
                else -> {}
            }

            // Load popular
            when (val result = repository.getTopClicked(10, 0)) {
                is RadioBrowserResult.Success -> _popularStations.value = result.data
                else -> {}
            }

            // Load new stations (recently changed, different offset to get different results than trending)
            when (val result = repository.getRecentlyChanged(10, 10)) {
                is RadioBrowserResult.Success -> _newStations.value = result.data
                else -> {}
            }

            _isDiscoveryLoading.value = false
        }
    }

    /**
     * Load privacy radio stations from the Radio Registry API.
     * Loads both Tor and I2P stations for the Privacy Radio section.
     * API calls are made in parallel to reduce total loading time.
     *
     * Handles the race condition where this is called before TorManager has
     * completed its connection check. If loading fails because Tor is required
     * but not connected, sets a flag to retry when Tor connects.
     */
    fun loadPrivacyRadioData(forceRefresh: Boolean = false) {
        if (privacyRadioDataLoaded && !forceRefresh) return
        privacyRadioDataLoaded = true
        _isPrivacyRadioLoading.value = true
        _privacyRadioError.value = null

        // Check if Tor is required but not yet connected (race condition)
        // If so, mark for retry when Tor connects
        if (registryRepository.isTorRequiredButNotConnected()) {
            android.util.Log.d("BrowseViewModel", "Tor required but not connected yet, will retry when Tor connects")
            privacyRadioNeedsTorRetry = true
            // Don't fail immediately - Tor might connect soon
            // Keep loading state so skeleton shows while waiting
        }

        // Launch parallel requests for Tor and I2P stations
        // Each updates its LiveData as soon as data arrives, not waiting for the other
        viewModelScope.launch {
            var torFailed = false
            var i2pFailed = false
            var torError: String? = null
            var i2pError: String? = null

            // Launch Tor stations fetch
            val torJob = async {
                try {
                    when (val result = registryRepository.getTorStations(limit = 20)) {
                        is RadioRegistryResult.Success -> {
                            _privacyTorStations.postValue(result.data.take(10))
                        }
                        is RadioRegistryResult.Error -> {
                            torFailed = true
                            torError = result.message
                            // Check if this is a Tor connection issue
                            if (result.message?.contains("Tor") == true ||
                                result.message?.contains("proxy") == true) {
                                privacyRadioNeedsTorRetry = true
                            }
                        }
                        is RadioRegistryResult.Loading -> {}
                    }
                } catch (e: Exception) {
                    torFailed = true
                    torError = e.message
                }
            }

            // Launch I2P stations fetch in parallel
            val i2pJob = async {
                try {
                    when (val result = registryRepository.getI2pStations(limit = 20)) {
                        is RadioRegistryResult.Success -> {
                            _privacyI2pStations.postValue(result.data.take(10))
                        }
                        is RadioRegistryResult.Error -> {
                            i2pFailed = true
                            i2pError = result.message
                            // Check if this is a Tor connection issue
                            if (result.message?.contains("Tor") == true ||
                                result.message?.contains("proxy") == true) {
                                privacyRadioNeedsTorRetry = true
                            }
                        }
                        is RadioRegistryResult.Loading -> {}
                    }
                } catch (e: Exception) {
                    i2pFailed = true
                    i2pError = e.message
                }
            }

            // Wait for both to complete before setting loading to false
            torJob.await()
            i2pJob.await()

            // Set error if both failed
            if (torFailed && i2pFailed) {
                _privacyRadioError.postValue(torError ?: i2pError)
            } else if (torFailed) {
                _privacyRadioError.postValue(torError)
            } else if (i2pFailed) {
                _privacyRadioError.postValue(i2pError)
            }

            _isPrivacyRadioLoading.postValue(false)
        }
    }

    /**
     * Load all Tor stations from the API for results mode
     */
    fun loadApiTorStations() {
        _currentCategory.value = BrowseCategory.ALL_STATIONS
        currentOffset = 0
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = registryRepository.getTorStations(limit = 100)) {
                    is RadioRegistryResult.Success -> {
                        val browserStations = result.data.map { RadioBrowserStation.fromRegistryStation(it) }
                        _stations.postValue(browserStations)
                        _hasMoreResults.postValue(false)
                        _isLoading.postValue(false)
                    }
                    is RadioRegistryResult.Error -> {
                        // Fall back to curated stations on error
                        loadCuratedTorStations()
                    }
                    is RadioRegistryResult.Loading -> {}
                }
            } catch (e: Exception) {
                _errorMessage.postValue("Failed to load Tor stations: ${e.message}")
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * Load all I2P stations from the API for results mode
     */
    fun loadApiI2pStations() {
        _currentCategory.value = BrowseCategory.ALL_STATIONS
        currentOffset = 0
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = registryRepository.getI2pStations(limit = 100)) {
                    is RadioRegistryResult.Success -> {
                        val browserStations = result.data.map { RadioBrowserStation.fromRegistryStation(it) }
                        _stations.postValue(browserStations)
                        _hasMoreResults.postValue(false)
                        _isLoading.postValue(false)
                    }
                    is RadioRegistryResult.Error -> {
                        // Fall back to curated stations on error
                        loadCuratedI2pStations()
                    }
                    is RadioRegistryResult.Loading -> {}
                }
            } catch (e: Exception) {
                _errorMessage.postValue("Failed to load I2P stations: ${e.message}")
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * Convert a RadioRegistryStation to a playable RadioStation
     */
    fun getPlayableStation(station: RadioRegistryStation) = station.toRadioStation()

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
     * Load all stations based on current filter (no ranking applied).
     * This is useful when filters like "Jazz" return few results with ranked categories
     * because it fetches ALL stations matching the filter directly from the API.
     */
    fun loadAllStations() {
        _currentCategory.value = BrowseCategory.ALL_STATIONS
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Load stations by country code (e.g., "US", "DE", "GB")
     */
    fun loadByCountryCode(countryCode: String) {
        // Find or create a CountryInfo for this country code
        val existingCountry = _countries.value?.find { it.iso3166_1 == countryCode }
        if (existingCountry != null) {
            filterByCountry(existingCountry)
        } else {
            // Create a temporary CountryInfo if not found in list
            val tempCountry = CountryInfo(name = countryCode, iso3166_1 = countryCode, stationCount = 0)
            filterByCountry(tempCountry)
        }
    }

    /**
     * Load stations by language (e.g., "english", "spanish")
     */
    fun loadByLanguage(language: String) {
        // Find or create a LanguageInfo for this language
        val existingLanguage = _languages.value?.find { it.name.equals(language, ignoreCase = true) }
        if (existingLanguage != null) {
            filterByLanguage(existingLanguage)
        } else {
            // Create a temporary LanguageInfo if not found in list
            val tempLanguage = LanguageInfo(name = language, stationCount = 0)
            filterByLanguage(tempLanguage)
        }
    }

    /**
     * Search stations by name
     * This now preserves active filters (tag, country, language) and combines them with search
     */
    fun search(query: String) {
        _searchQuery.value = query
        _currentCategory.value = BrowseCategory.SEARCH
        currentOffset = 0
        _stations.value = emptyList()
        if (query.isNotBlank()) {
            fetchStations()
        } else if (hasActiveFilters()) {
            // If search is cleared but filters are active, show filtered results
            restoreFilteredView()
        }
    }

    /**
     * Load curated I2P stations from bundled JSON
     */
    fun loadCuratedI2pStations() {
        _currentCategory.postValue(BrowseCategory.ALL_STATIONS)
        currentOffset = 0
        _isLoading.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val i2pStations = com.opensource.i2pradio.data.DefaultStations.getI2pStations(getApplication())
                val browserStations = i2pStations.map { RadioBrowserStation.fromRadioStation(it) }
                _stations.postValue(browserStations)
                _hasMoreResults.postValue(false) // Disable pagination for curated list
                _isLoading.postValue(false)
            } catch (e: Exception) {
                _errorMessage.postValue("Failed to load I2P stations: ${e.message}")
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * Load curated Tor stations from bundled JSON
     */
    fun loadCuratedTorStations() {
        _currentCategory.postValue(BrowseCategory.ALL_STATIONS)
        currentOffset = 0
        _isLoading.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val torStations = com.opensource.i2pradio.data.DefaultStations.getTorStations(getApplication())
                val browserStations = torStations.map { RadioBrowserStation.fromRadioStation(it) }
                _stations.postValue(browserStations)
                _hasMoreResults.postValue(false) // Disable pagination for curated list
                _isLoading.postValue(false)
            } catch (e: Exception) {
                _errorMessage.postValue("Failed to load Tor stations: ${e.message}")
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * Check if any filters (tag, country, or language) are currently active
     */
    private fun hasActiveFilters(): Boolean {
        return _selectedTag.value != null ||
               _selectedCountry.value != null ||
               _selectedLanguage.value != null
    }

    /**
     * Restore the filtered view when search is cleared but filters remain active
     */
    private fun restoreFilteredView() {
        when {
            _selectedTag.value != null -> {
                _currentCategory.value = BrowseCategory.BY_TAG
                fetchStations()
            }
            _selectedCountry.value != null -> {
                _currentCategory.value = BrowseCategory.BY_COUNTRY
                fetchStations()
            }
            _selectedLanguage.value != null -> {
                _currentCategory.value = BrowseCategory.BY_LANGUAGE
                fetchStations()
            }
        }
    }

    /**
     * Filter by country (sets country as the primary category)
     */
    fun filterByCountry(country: CountryInfo?) {
        _selectedCountry.value = country
        if (country != null) {
            _currentCategory.value = BrowseCategory.BY_COUNTRY
        }
        currentOffset = 0
        _stations.value = emptyList()
        if (country != null) {
            fetchStations()
        }
    }

    /**
     * Filter by tag/genre (sets tag as the primary category)
     */
    fun filterByTag(tag: TagInfo?) {
        _selectedTag.value = tag
        if (tag != null) {
            _currentCategory.value = BrowseCategory.BY_TAG
        }
        currentOffset = 0
        _stations.value = emptyList()
        if (tag != null) {
            fetchStations()
        }
    }

    /**
     * Filter by language (sets language as the primary category)
     */
    fun filterByLanguage(language: LanguageInfo?) {
        _selectedLanguage.value = language
        if (language != null) {
            _currentCategory.value = BrowseCategory.BY_LANGUAGE
        }
        currentOffset = 0
        _stations.value = emptyList()
        if (language != null) {
            fetchStations()
        }
    }

    /**
     * Add a country filter without changing the current category.
     * Used for intelligent filtering where filters stack on top of the current view.
     */
    fun addCountryFilter(country: CountryInfo?) {
        _selectedCountry.value = country
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Add a tag/genre filter without changing the current category.
     * Used for intelligent filtering where filters stack on top of the current view.
     */
    fun addTagFilter(tag: TagInfo?) {
        _selectedTag.value = tag
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Add a language filter without changing the current category.
     * Used for intelligent filtering where filters stack on top of the current view.
     */
    fun addLanguageFilter(language: LanguageInfo?) {
        _selectedLanguage.value = language
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Clear a specific filter and re-fetch results.
     * Used when removing a filter chip - keeps current category but removes the filter.
     */
    fun clearCountryFilter() {
        _selectedCountry.value = null
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Clear tag filter and re-fetch results.
     */
    fun clearTagFilter() {
        _selectedTag.value = null
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
    }

    /**
     * Clear language filter and re-fetch results.
     */
    fun clearLanguageFilter() {
        _selectedLanguage.value = null
        currentOffset = 0
        _stations.value = emptyList()
        fetchStations()
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

        // Check current UI state (not database state) for immediate feedback
        val isCurrentlyLiked = _likedStationUuids.value?.contains(uuid) == true

        // IMMEDIATELY update UI state (optimistic update)
        val savedCurrent = _savedStationUuids.value.orEmpty().toMutableSet()
        val likedCurrent = _likedStationUuids.value.orEmpty().toMutableSet()

        if (isCurrentlyLiked) {
            // Optimistically remove from UI
            savedCurrent.remove(uuid)
            likedCurrent.remove(uuid)
        } else {
            // Optimistically add to UI
            savedCurrent.add(uuid)
            likedCurrent.add(uuid)
        }

        _savedStationUuids.value = savedCurrent
        _likedStationUuids.value = likedCurrent

        // Then perform database operations in background
        viewModelScope.launch {
            if (isCurrentlyLiked) {
                // Unlike: remove the station from library entirely
                val deleted = repository.deleteStationByUuid(uuid)
                if (!deleted) {
                    // Revert optimistic update if database operation failed
                    val revertSaved = _savedStationUuids.value.orEmpty().toMutableSet()
                    val revertLiked = _likedStationUuids.value.orEmpty().toMutableSet()
                    revertSaved.add(uuid)
                    revertLiked.add(uuid)
                    _savedStationUuids.postValue(revertSaved)
                    _likedStationUuids.postValue(revertLiked)
                }
            } else {
                // Like: save and mark as liked
                val id = repository.saveStationAsLiked(station)
                if (id == null) {
                    // Revert optimistic update if database operation failed
                    val revertSaved = _savedStationUuids.value.orEmpty().toMutableSet()
                    val revertLiked = _likedStationUuids.value.orEmpty().toMutableSet()
                    revertSaved.remove(uuid)
                    revertLiked.remove(uuid)
                    _savedStationUuids.postValue(revertSaved)
                    _likedStationUuids.postValue(revertLiked)
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
     * Normalize genre name for deduplication purposes.
     * Maps known variants (e.g., "hip hop", "hip-hop", "hiphop") to a canonical form.
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

    /**
     * Load tags list
     */
    private fun loadTags() {
        viewModelScope.launch {
            when (val result = repository.getTags(200)) {
                is RadioBrowserResult.Success -> {
                    // Filter to tags with at least 10 stations, consolidate variants, and sort alphabetically
                    // Group by normalized name and keep the variant with the highest station count
                    _tags.value = result.data
                        .filter { it.stationCount >= 10 }
                        .groupBy { normalizeGenreName(it.name) }
                        .map { (_, variants) ->
                            // Keep the variant with the highest station count (most reliable for API)
                            variants.maxByOrNull { it.stationCount }!!
                        }
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
                BrowseCategory.ALL_STATIONS -> {
                    // Use searchStations with combined filters to properly support
                    // multiple active filters (e.g., tag + country + language)
                    val tag = _selectedTag.value
                    val country = _selectedCountry.value
                    val language = _selectedLanguage.value
                    repository.searchStations(
                        name = null,
                        tag = tag?.name,
                        countrycode = country?.iso3166_1,
                        language = language?.name,
                        limit = pageSize,
                        offset = currentOffset
                    )
                }
                null -> RadioBrowserResult.Error("No category selected")
            }

            when (result) {
                is RadioBrowserResult.Success -> {
                    var newStations = result.data

                    // Store original API response size BEFORE any filtering
                    // This is critical for pagination - we need to know if the API
                    // returned a full page, not how many survived filtering
                    val apiResponseSize = result.data.size

                    // Track Top Voted stations for hiding in Popular
                    if (_currentCategory.value == BrowseCategory.TOP_VOTED) {
                        newStations.forEach { topVotedStationUuids.add(it.stationuuid) }
                    }

                    // Filter out Top Voted stations from Popular
                    if (_currentCategory.value == BrowseCategory.TOP_CLICKED && topVotedStationUuids.isNotEmpty()) {
                        newStations = newStations.filter { it.stationuuid !in topVotedStationUuids }
                    }

                    // INTELLIGENT FILTERING: Apply active filters to results from ANY category
                    // This ensures filters like "rock" work with "History", "Top Voted", etc.
                    newStations = applyActiveFilters(newStations)

                    if (append) {
                        val current = _stations.value.orEmpty()
                        _stations.value = current + newStations
                    } else {
                        _stations.value = newStations
                    }
                    // Use API response size (before filtering) to determine if more results exist
                    // This fixes the bug where pagination stopped early because filtered results
                    // were fewer than pageSize even though more stations exist in the API
                    _hasMoreResults.value = apiResponseSize >= pageSize

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
     *
     * This method properly syncs the UI state with the database:
     * - For stations IN the database: adds them to saved/liked sets
     * - For stations NOT in the database: removes them from saved/liked sets
     *
     * This fixes the bug where after add+delete, the UI would still show
     * the station as added because the old code only added, never removed.
     */
    private suspend fun checkSavedStatus(stations: List<RadioBrowserStation>) {
        val savedUuids = _savedStationUuids.value.orEmpty().toMutableSet()
        val likedUuids = _likedStationUuids.value.orEmpty().toMutableSet()

        // Batch query: get all station info at once instead of one-by-one
        val uuids = stations.map { it.stationuuid }
        val stationInfoMap = repository.getStationInfoByUuids(uuids)

        // For each station in the displayed list, sync its status with the database
        for (station in stations) {
            val uuid = station.stationuuid
            val stationInfo = stationInfoMap[uuid]
            if (stationInfo != null) {
                // Station is in database - mark as saved
                savedUuids.add(uuid)
                // Update liked status based on database state
                if (stationInfo.isLiked) {
                    likedUuids.add(uuid)
                } else {
                    likedUuids.remove(uuid)
                }
            } else {
                // Station is NOT in database - remove from both sets
                // This is critical for the add->delete->add cycle to work correctly
                savedUuids.remove(uuid)
                likedUuids.remove(uuid)
            }
        }

        _savedStationUuids.postValue(savedUuids)
        _likedStationUuids.postValue(likedUuids)
    }

    /**
     * Refresh liked and saved station UUIDs from the database.
     * Called when like state changes from other views to keep UI in sync,
     * or when the fragment resumes to catch changes made in other screens (e.g., library).
     *
     * This checks ALL displayed stations including:
     * - Results list stations
     * - All carousel stations (USA, Germany, Spanish, French, Trending, Top Voted, Popular, New)
     */
    fun refreshLikedAndSavedUuids() {
        viewModelScope.launch(Dispatchers.IO) {
            // Collect all displayed stations from results and all carousels
            val allDisplayedStations = mutableListOf<RadioBrowserStation>()

            // Results list
            _stations.value?.let { allDisplayedStations.addAll(it) }

            // All carousel stations
            _usaStations.value?.let { allDisplayedStations.addAll(it) }
            _germanyStations.value?.let { allDisplayedStations.addAll(it) }
            _spanishStations.value?.let { allDisplayedStations.addAll(it) }
            _frenchStations.value?.let { allDisplayedStations.addAll(it) }
            _trendingStations.value?.let { allDisplayedStations.addAll(it) }
            _topVotedPreviewStations.value?.let { allDisplayedStations.addAll(it) }
            _popularStations.value?.let { allDisplayedStations.addAll(it) }
            _newStations.value?.let { allDisplayedStations.addAll(it) }

            if (allDisplayedStations.isNotEmpty()) {
                // Deduplicate by UUID before checking (same station may appear in multiple carousels)
                val uniqueStations = allDisplayedStations.distinctBy { it.stationuuid }
                checkSavedStatus(uniqueStations)
            }
        }
    }

    /**
     * Apply active filters (tag, country, language) to a list of stations.
     * This enables intelligent filtering where filters work across ALL categories:
     * - "Rock" filter + "History" = only history stations with rock tag
     * - "Germany" filter + "Top Voted" = only top voted stations from Germany
     * - Multiple filters stack: "Rock" + "Germany" = rock stations from Germany
     *
     * Filters are skipped when the current category is the same as the filter type
     * to avoid redundant filtering (e.g., don't re-filter by tag when in BY_TAG mode).
     */
    private fun applyActiveFilters(stations: List<RadioBrowserStation>): List<RadioBrowserStation> {
        var filtered = stations
        val currentCat = _currentCategory.value

        // Apply tag filter (unless we're already in BY_TAG mode)
        val activeTag = _selectedTag.value
        if (activeTag != null && currentCat != BrowseCategory.BY_TAG) {
            val tagName = activeTag.name.lowercase()
            filtered = filtered.filter { station ->
                station.tags.lowercase().contains(tagName)
            }
        }

        // Apply country filter (unless we're already in BY_COUNTRY mode)
        val activeCountry = _selectedCountry.value
        if (activeCountry != null && currentCat != BrowseCategory.BY_COUNTRY) {
            val countryCode = activeCountry.iso3166_1.lowercase()
            val countryName = activeCountry.name.lowercase()
            filtered = filtered.filter { station ->
                station.countrycode.lowercase() == countryCode ||
                station.country.lowercase().contains(countryName)
            }
        }

        // Apply language filter (unless we're already in BY_LANGUAGE mode)
        val activeLanguage = _selectedLanguage.value
        if (activeLanguage != null && currentCat != BrowseCategory.BY_LANGUAGE) {
            val langName = activeLanguage.name.lowercase()
            filtered = filtered.filter { station ->
                station.language.lowercase().contains(langName)
            }
        }

        return filtered
    }

    /**
     * OPTIMIZED intelligent multi-field search that supports:
     * - Multi-word queries (e.g., "BBC London", "Rock USA")
     * - Searching by genre/tag (e.g., "Jazz", "Classical")
     * - Searching by country (e.g., "Germany", "USA")
     * - Combined searches (e.g., "Rock Germany" finds rock stations from Germany)
     * - COMBINED WITH ACTIVE FILTERS: If a tag/country/language filter is active,
     *   search results are filtered to only include stations matching both the search
     *   query AND the active filter criteria
     *
     * PERFORMANCE: Reduced from 9+ API calls to just 2-3 calls per search (78-89% reduction!)
     */
    private suspend fun performIntelligentSearch(
        query: String,
        limit: Int,
        offset: Int
    ): RadioBrowserResult<List<RadioBrowserStation>> {
        val trimmedQuery = query.trim()

        // Get active filters to combine with search
        val activeTag = _selectedTag.value
        val activeCountry = _selectedCountry.value
        val activeLanguage = _selectedLanguage.value

        // For pagination (offset > 0), search by name only for consistency
        // Language filter is applied by applyActiveFilters in fetchStations
        if (offset > 0) {
            return repository.searchStations(
                name = trimmedQuery,
                tag = activeTag?.name,  // Include tag filter if active
                countrycode = activeCountry?.iso3166_1,  // Include country filter if active
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
        // Include active tag filter in API call when available
        val nameResult = repository.searchStations(
            name = trimmedQuery,
            tag = activeTag?.name,  // Combine with active tag filter
            countrycode = activeCountry?.iso3166_1,  // Combine with active country filter
            limit = limit,
            offset = 0
        )
        if (nameResult is RadioBrowserResult.Success) {
            allResults.addAll(nameResult.data)
        }

        // Secondary search: Search by tag/genre (for genre-based queries like "Rock" or "Jazz")
        // Only do this if no tag filter is already active (otherwise redundant)
        if (activeTag == null) {
            val tagResult = repository.searchStations(
                tag = trimmedQuery,
                countrycode = activeCountry?.iso3166_1,  // Still apply country filter
                limit = limit,
                offset = 0
            )
            if (tagResult is RadioBrowserResult.Success) {
                allResults.addAll(tagResult.data)
            }
        }

        // Tertiary search: Search by country for single-word queries only
        // Skip if country filter is already active (would be redundant)
        if (words.size == 1 && activeCountry == null) {
            val countryResult = repository.searchStations(
                country = trimmedQuery,
                tag = activeTag?.name,  // Still apply tag filter
                limit = limit,
                offset = 0
            )
            if (countryResult is RadioBrowserResult.Success) {
                allResults.addAll(countryResult.data)
            }
        }

        // If no results from any search, return error
        if (allResults.isEmpty()) {
            val filterInfo = buildString {
                append(trimmedQuery)
                activeTag?.let { append(" in genre '${it.name}'") }
                activeCountry?.let { append(" in country '${it.name}'") }
                activeLanguage?.let { append(" in language '${it.name}'") }
            }
            return RadioBrowserResult.Error("No stations found for: $filterInfo")
        }

        // OPTIMIZATION: Pre-normalize search terms once (not in the filter loop)
        val searchTerms = words.map { it.lowercase() }

        // For multi-word queries, filter results client-side to keep only stations
        // that match ALL words somewhere in their searchable fields (name, tags, country)
        var filteredResults = if (searchTerms.size > 1) {
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

        // Note: Language filter is applied by applyActiveFilters in fetchStations
        // to ensure apiResponseSize is captured before filtering for pagination

        // Deduplicate and limit results
        val deduplicated = filteredResults
            .distinctBy { it.stationuuid }
            .take(limit)

        return RadioBrowserResult.Success(deduplicated)
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        // Remove TorManager listener to prevent memory leaks
        TorManager.removeStateListener(torStateListener)
    }
}

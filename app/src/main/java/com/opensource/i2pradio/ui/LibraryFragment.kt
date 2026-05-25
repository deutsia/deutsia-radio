package com.opensource.i2pradio.ui

import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.google.android.material.checkbox.MaterialCheckBox
import android.widget.ImageView
import android.widget.Toast
import android.widget.LinearLayout
import android.graphics.drawable.ColorDrawable
import android.widget.PopupWindow
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.request.Disposable
import com.opensource.i2pradio.util.loadSecure
import com.opensource.i2pradio.util.loadSecurePrivacy
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.opensource.i2pradio.MainActivity
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.RadioStationPasswordHelper
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

    // Drag-to-reorder (Custom order). While a drag is in progress we hold off on
    // re-submitting the list from LiveData so the database write doesn't fight the gesture.
    private var isDraggingStation = false
    private val reorderTouchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = adapter.isReorderable()
            override fun isItemViewSwipeEnabled() = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val dragFlags = if (adapter.isReorderable()) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN
                } else {
                    0
                }
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDraggingStation = true
                    viewHolder?.itemView?.alpha = 0.85f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                if (isDraggingStation) {
                    isDraggingStation = false
                    persistCustomOrder()
                }
            }
        })
    }

    private fun persistCustomOrder() {
        val ordered = adapter.currentStations()
        allStationsCache = ordered
        val ids = ordered.map { it.id }
        lifecycleScope.launch {
            repository.setStationOrder(ids)
        }
    }

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
    private fun getAllGenres(): List<String> {
        return listOf(
            getString(R.string.genre_all),
            getString(R.string.genre_alternative),
            getString(R.string.genre_ambient),
            getString(R.string.genre_blues),
            getString(R.string.genre_christian),
            getString(R.string.genre_classical),
            getString(R.string.genre_comedy),
            getString(R.string.genre_country),
            getString(R.string.genre_dance),
            getString(R.string.genre_edm),
            getString(R.string.genre_electronic),
            getString(R.string.genre_folk),
            getString(R.string.genre_funk),
            getString(R.string.genre_gospel),
            getString(R.string.genre_hip_hop),
            getString(R.string.genre_indie),
            getString(R.string.genre_jazz),
            getString(R.string.genre_k_pop),
            getString(R.string.genre_latin),
            getString(R.string.genre_lo_fi),
            getString(R.string.genre_metal),
            getString(R.string.genre_news),
            getString(R.string.genre_oldies),
            getString(R.string.genre_pop),
            getString(R.string.genre_punk),
            getString(R.string.genre_r_and_b),
            getString(R.string.genre_reggae),
            getString(R.string.genre_rock),
            getString(R.string.genre_soul),
            getString(R.string.genre_sports),
            getString(R.string.genre_talk),
            getString(R.string.genre_world),
            getString(R.string.genre_other)
        )
    }

    /**
     * Translate common genre names from English (database/API) to localized strings.
     * Returns the translated name if available, otherwise returns the original name.
     */
    private fun translateGenreName(englishName: String): String {
        // Normalize the genre name for matching (lowercase, trim)
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
            else -> englishName // Return original if no translation available
        }
    }

    /**
     * Get the English equivalent of a localized genre name for database queries.
     * This checks if the genre name matches any translated string and returns the English version.
     */
    private fun getEnglishGenreName(localizedName: String): String {
        return when (localizedName) {
            getString(R.string.genre_alternative) -> "Alternative"
            getString(R.string.genre_ambient) -> "Ambient"
            getString(R.string.genre_blues) -> "Blues"
            getString(R.string.genre_christian) -> "Christian"
            getString(R.string.genre_classical) -> "Classical"
            getString(R.string.genre_comedy) -> "Comedy"
            getString(R.string.genre_country) -> "Country"
            getString(R.string.genre_dance) -> "Dance"
            getString(R.string.genre_edm) -> "EDM"
            getString(R.string.genre_electronic) -> "Electronic"
            getString(R.string.genre_folk) -> "Folk"
            getString(R.string.genre_funk) -> "Funk"
            getString(R.string.genre_gospel) -> "Gospel"
            getString(R.string.genre_hip_hop) -> "Hip Hop"
            getString(R.string.genre_indie) -> "Indie"
            getString(R.string.genre_jazz) -> "Jazz"
            getString(R.string.genre_k_pop) -> "K-Pop"
            getString(R.string.genre_latin) -> "Latin"
            getString(R.string.genre_lo_fi) -> "Lo-Fi"
            getString(R.string.genre_metal) -> "Metal"
            getString(R.string.genre_news) -> "News"
            getString(R.string.genre_oldies) -> "Oldies"
            getString(R.string.genre_pop) -> "Pop"
            getString(R.string.genre_punk) -> "Punk"
            getString(R.string.genre_r_and_b) -> "R&B"
            getString(R.string.genre_reggae) -> "Reggae"
            getString(R.string.genre_rock) -> "Rock"
            getString(R.string.genre_soul) -> "Soul"
            getString(R.string.genre_sports) -> "Sports"
            getString(R.string.genre_talk) -> "Talk"
            getString(R.string.genre_world) -> "World"
            getString(R.string.genre_other) -> "Other"
            else -> localizedName // Return as-is if not a known translated genre
        }
    }

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
            onSelectionChanged = { updateActionModeTitle() },
            onStartDrag = { viewHolder -> reorderTouchHelper.startDrag(viewHolder) }
        )
        recyclerView.adapter = adapter
        reorderTouchHelper.attachToRecyclerView(recyclerView)

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
        // currentGenreFilter is always in English for database queries
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
        // Don't clobber the list mid-drag; persistCustomOrder() refreshes it on drop.
        if (isDraggingStation) return

        // Drag-to-reorder is offered only for the full, unfiltered Custom-order list.
        val reorderable = currentSortOrder == SortOrder.CUSTOM &&
            currentGenreFilter == null &&
            currentSearchQuery.isEmpty()

        val filteredStations = if (currentSearchQuery.isEmpty()) {
            allStationsCache
        } else {
            // Intelligent multi-word search across multiple fields
            val searchTerms = currentSearchQuery.split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .map { it.lowercase() }

            allStationsCache.filter { station ->
                // Create a searchable text combining all relevant fields
                val searchableText = buildString {
                    append(station.name.lowercase())
                    append(" ")
                    append(station.genre.lowercase())
                    append(" ")
                    append(station.country.lowercase())
                    append(" ")
                    append(station.countryCode.lowercase())
                }

                // Check if all search terms match somewhere in the searchable text
                // This allows multi-word queries like "BBC London" or "Jazz Germany"
                searchTerms.all { term ->
                    searchableText.contains(term)
                }
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

        // Toggle handles AFTER submitting the list. setReorderEnabled triggers a
        // full rebind when the flag changes, which must be the last update so the
        // grip handles actually appear (DiffUtil move-dispatch alone won't rebind them).
        adapter.setReorderEnabled(reorderable)
    }

    private fun showSortDialog() {
        val sortOptions = arrayOf(
            getString(R.string.sort_default),
            getString(R.string.sort_name),
            getString(R.string.sort_recent),
            getString(R.string.sort_liked),
            getString(R.string.sort_genre),
            getString(R.string.sort_custom)
        )
        val currentIndex = currentSortOrder.ordinal

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_sort_stations))
            .setSingleChoiceItems(sortOptions, currentIndex) { dialog, which ->
                currentSortOrder = SortOrder.entries[which]
                PreferencesHelper.setSortOrder(requireContext(), currentSortOrder.name)
                updateSortButtonText()
                observeStations()
                dialog.dismiss()
                if (currentSortOrder == SortOrder.CUSTOM &&
                    !PreferencesHelper.isCustomOrderHintShown(requireContext())
                ) {
                    PreferencesHelper.setCustomOrderHintShown(requireContext(), true)
                    showCustomOrderHintDialog()
                }
            }
            .show()
    }

    private fun showCustomOrderHintDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.custom_order_hint_title))
            .setMessage(getString(R.string.custom_order_hint_message))
            .setPositiveButton(getString(R.string.button_got_it), null)
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

            // Translate database genres from English to localized strings
            val translatedDbGenres = dbGenres.map { translateGenreName(it) }

            // Merge predefined genres with translated database genres, keeping alphabetical order
            val allGenresText = getString(R.string.genre_all)
            val otherGenreText = getString(R.string.genre_other)
            val combinedGenres = (getAllGenres() + translatedDbGenres)
                .distinct()
                .sortedWith(compareBy {
                    // "All Genres" first, "Other" last, rest alphabetically
                    when (it) {
                        allGenresText -> "0$it"
                        otherGenreText -> "ZZZ$it"
                        else -> "A$it"
                    }
                })

            val currentIndex = if (currentGenreFilter == null) {
                0 // "All Genres"
            } else {
                // currentGenreFilter is in English, so translate it to find in combinedGenres
                val translatedFilter = translateGenreName(currentGenreFilter!!)
                combinedGenres.indexOf(translatedFilter).takeIf { it >= 0 } ?: 0
            }

            // Variable to hold the temporary selection (translated for UI display)
            var tempSelectedGenre: String? = if (currentGenreFilter != null) {
                translateGenreName(currentGenreFilter!!)
            } else {
                null
            }

            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.filter_by_genre))
                .setView(createGenreSearchView(combinedGenres, currentIndex) { selectedGenre ->
                    // Store the temporary selection but don't apply yet
                    tempSelectedGenre = selectedGenre
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(getString(R.string.manage_genres)) { _, _ ->
                    showManageGenresDialog()
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // Apply the selection when OK is clicked
                    // Store English genre name for language portability
                    currentGenreFilter = if (tempSelectedGenre == allGenresText || tempSelectedGenre == null) {
                        null
                    } else {
                        getEnglishGenreName(tempSelectedGenre)
                    }
                    PreferencesHelper.setGenreFilter(requireContext(), currentGenreFilter)
                    updateGenreFilterButtonText()
                    // Clear search when changing genre filter
                    currentSearchQuery = ""
                    searchInput.setText("")
                    observeStations()
                }
                .create()

            dialog.show()
        }
    }

    private fun showManageGenresDialog() {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_manage_genres, null)
        val recycler = view.findViewById<MaxHeightRecyclerView>(R.id.manageGenresRecycler)
        recycler.maxHeightPx = (360 * resources.displayMetrics.density).toInt()
        val emptyView = view.findViewById<TextView>(R.id.manageGenresEmpty)
        val titleView = view.findViewById<TextView>(R.id.manageGenresTitle)
        val selectAllButton = view.findViewById<MaterialButton>(R.id.selectAllButton)
        val deleteSelectedButton = view.findViewById<MaterialButton>(R.id.deleteSelectedButton)
        val closeButton = view.findViewById<MaterialButton>(R.id.closeButton)

        recycler.layoutManager = LinearLayoutManager(context)

        var reload: () -> Unit = {}
        lateinit var adapter: ManageGenreAdapter

        fun updateChrome() {
            if (adapter.selectionMode) {
                titleView.text = getString(R.string.manage_genres_selected_count, adapter.selectedCount())
                selectAllButton.visibility = View.VISIBLE
                deleteSelectedButton.visibility = View.VISIBLE
                closeButton.text = getString(R.string.button_cancel)
            } else {
                titleView.text = getString(R.string.manage_genres)
                selectAllButton.visibility = View.GONE
                deleteSelectedButton.visibility = View.GONE
                closeButton.text = getString(R.string.button_close)
            }
        }

        adapter = ManageGenreAdapter(
            onRename = { genre -> showRenameGenreDialog(genre) { reload() } },
            onDelete = { genre -> confirmDeleteGenre(genre) { reload() } },
            onSelectionChanged = { updateChrome() }
        )
        recycler.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .create()

        reload = {
            lifecycleScope.launch(Dispatchers.Main) {
                val dbGenres = try {
                    repository.getAllGenresSync()
                } catch (e: Exception) {
                    emptyList()
                }
                // Only user-managed genres are deletable; "Other" is the fallback sink.
                val manageable = dbGenres.filter { it.isNotBlank() && it != "Other" }.sorted()
                if (!isAdded) return@launch
                adapter.setGenres(manageable)
                val isEmpty = manageable.isEmpty()
                emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
                recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
                updateChrome()
            }
        }

        closeButton.setOnClickListener {
            if (adapter.selectionMode) adapter.exitSelectionMode() else dialog.dismiss()
        }
        selectAllButton.setOnClickListener { adapter.selectAll() }
        deleteSelectedButton.setOnClickListener {
            val selected = adapter.selectedGenres()
            if (selected.isNotEmpty()) {
                confirmDeleteGenres(selected) {
                    adapter.exitSelectionMode()
                    reload()
                }
            }
        }

        updateChrome()
        reload()
        dialog.show()
    }

    private fun confirmDeleteGenre(genre: String, onComplete: (() -> Unit)? = null) {
        lifecycleScope.launch(Dispatchers.Main) {
            val count = try {
                repository.countStationsByGenre(genre)
            } catch (e: Exception) {
                0
            }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.genre_delete_confirm_title))
                .setMessage(getString(R.string.genre_delete_confirm_message, genre, count))
                .setPositiveButton(getString(R.string.genre_action_delete)) { _, _ ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        repository.renameGenre(genre, "Other")
                        if (!isAdded) return@launch
                        // Reset the filter if we just deleted the genre being filtered on
                        if (currentGenreFilter == genre) {
                            currentGenreFilter = null
                            PreferencesHelper.setGenreFilter(requireContext(), null)
                            updateGenreFilterButtonText()
                        }
                        observeStations()
                        if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.genre_deleted_toast, genre),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        onComplete?.invoke()
                    }
                }
                .setNegativeButton(getString(R.string.button_cancel), null)
                .show()
        }
    }

    private fun confirmDeleteGenres(genres: List<String>, onComplete: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.genres_delete_selected_title))
            .setMessage(getString(R.string.genres_delete_selected_message, genres.size))
            .setPositiveButton(getString(R.string.genre_action_delete)) { _, _ ->
                lifecycleScope.launch(Dispatchers.Main) {
                    for (genre in genres) {
                        repository.renameGenre(genre, "Other")
                    }
                    if (!isAdded) return@launch
                    if (currentGenreFilter != null && genres.contains(currentGenreFilter)) {
                        currentGenreFilter = null
                        PreferencesHelper.setGenreFilter(requireContext(), null)
                        updateGenreFilterButtonText()
                    }
                    observeStations()
                    if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.genres_deleted_toast, genres.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    onComplete()
                }
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
            .show()
    }

    private fun showRenameGenreDialog(oldGenre: String, onComplete: (() -> Unit)? = null) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val input = TextInputEditText(context).apply {
            setText(oldGenre)
            setSelection(text?.length ?: 0)
        }
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.genre_rename_title))
            .setView(wrapper)
            .setPositiveButton(getString(R.string.button_save)) { _, _ ->
                val newGenre = input.text?.toString()?.trim().orEmpty()
                if (newGenre.isEmpty() || newGenre == oldGenre) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.Main) {
                    repository.renameGenre(oldGenre, newGenre)
                    if (!isAdded) return@launch
                    if (currentGenreFilter == oldGenre) {
                        currentGenreFilter = newGenre
                        PreferencesHelper.setGenreFilter(requireContext(), newGenre)
                        updateGenreFilterButtonText()
                    }
                    observeStations()
                    if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.genre_renamed_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    onComplete?.invoke()
                }
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
            .show()
    }

    // Adapter for the Manage genres dialog: per-row rename/delete, plus long-press
    // multi-select for mass delete (mirrors the library's selection behaviour).
    private inner class ManageGenreAdapter(
        private val onRename: (String) -> Unit,
        private val onDelete: (String) -> Unit,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<ManageGenreAdapter.ViewHolder>() {

        private val genres = mutableListOf<String>()
        private val selected = mutableSetOf<String>()
        var selectionMode = false
            private set

        fun setGenres(newGenres: List<String>) {
            genres.clear()
            genres.addAll(newGenres)
            selected.retainAll(genres.toSet())
            if (selected.isEmpty()) selectionMode = false
            notifyDataSetChanged()
        }

        fun selectedGenres(): List<String> = selected.toList()

        fun selectedCount(): Int = selected.size

        fun enterSelectionMode(genre: String) {
            selectionMode = true
            selected.clear()
            selected.add(genre)
            notifyDataSetChanged()
            onSelectionChanged()
        }

        fun exitSelectionMode() {
            selectionMode = false
            selected.clear()
            notifyDataSetChanged()
            onSelectionChanged()
        }

        fun selectAll() {
            selected.clear()
            selected.addAll(genres)
            notifyDataSetChanged()
            onSelectionChanged()
        }

        private fun toggle(genre: String) {
            if (!selected.add(genre)) selected.remove(genre)
            if (selected.isEmpty()) {
                exitSelectionMode()
            } else {
                notifyDataSetChanged()
                onSelectionChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_manage_genre, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = genres.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            if (position >= genres.size) return
            holder.bind(genres[position])
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.genreName)
            private val checkBox: MaterialCheckBox = itemView.findViewById(R.id.genreCheckBox)
            private val renameButton: MaterialButton = itemView.findViewById(R.id.renameButton)
            private val deleteButton: MaterialButton = itemView.findViewById(R.id.deleteButton)

            fun bind(genre: String) {
                name.text = genre
                if (selectionMode) {
                    checkBox.visibility = View.VISIBLE
                    checkBox.isChecked = selected.contains(genre)
                    renameButton.visibility = View.GONE
                    deleteButton.visibility = View.GONE
                } else {
                    checkBox.visibility = View.GONE
                    renameButton.visibility = View.VISIBLE
                    deleteButton.visibility = View.VISIBLE
                }
                itemView.setOnClickListener {
                    if (selectionMode) toggle(genre)
                }
                itemView.setOnLongClickListener {
                    if (selectionMode) toggle(genre) else enterSelectionMode(genre)
                    true
                }
                renameButton.setOnClickListener { onRename(genre) }
                deleteButton.setOnClickListener { onDelete(genre) }
            }
        }
    }

    private fun createGenreSearchView(genres: List<String>, selectedIndex: Int, onGenreSelected: (String) -> Unit): View {
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

        // RecyclerView for genre list
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

        var filteredGenres = genres.toList()
        val adapter = GenreAdapter(filteredGenres, selectedIndex) { selectedGenre ->
            // Just update the selection, don't apply or dismiss
            onGenreSelected(selectedGenre)
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
            SortOrder.CUSTOM -> getString(R.string.sort_custom)
        }

        // Use hamburger icon for Default and Genre, sort icon for others
        sortButton.setIconResource(when (currentSortOrder) {
            SortOrder.DEFAULT, SortOrder.GENRE -> R.drawable.ic_menu
            else -> R.drawable.ic_sort
        })
    }

    private fun updateGenreFilterButtonText() {
        // Display translated genre name on button
        genreFilterButton.text = if (currentGenreFilter != null) {
            translateGenreName(currentGenreFilter!!)
        } else {
            getString(R.string.genre_all)
        }
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
            // Custom proxy fields - decrypt password before passing to service
            putExtra("custom_proxy_protocol", station.customProxyProtocol)
            putExtra("proxy_username", station.proxyUsername)
            putExtra("proxy_password", RadioStationPasswordHelper.getDecryptedPassword(requireContext(), station))
            putExtra("proxy_auth_type", station.proxyAuthType)
            putExtra("proxy_connection_timeout", station.proxyConnectionTimeout)
            putExtra("hls_hint", station.hlsHint)
            putExtra("codec_hint", station.codecHint)
            putExtra(RadioService.EXTRA_STATION_ID, station.id)
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
                    // Check by radioBrowserUuid first (for global radios that may have id=0 when playing from Browse),
                    // then fall back to ID comparison
                    val currentStation = viewModel.getCurrentStation()
                    val isCurrentStation = if (!currentStation?.radioBrowserUuid.isNullOrEmpty() && !it.radioBrowserUuid.isNullOrEmpty()) {
                        currentStation.radioBrowserUuid == it.radioBrowserUuid
                    } else {
                        currentStation?.id == it.id
                    }
                    if (isCurrentStation) {
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
                    if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
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
    }

    private fun showStationMenu(station: RadioStation, anchor: View) {
        val popupView = LayoutInflater.from(requireContext())
            .inflate(R.layout.popup_station_menu, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.elevation = 8f
        popupWindow.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        popupView.findViewById<View>(R.id.menuItemEdit).setOnClickListener {
            popupWindow.dismiss()
            val dialog = AddEditRadioDialog.newInstance(station)
            dialog.show(parentFragmentManager, "AddEditRadioDialog")
        }

        // Move up/down are only meaningful in Custom order, and only for the full,
        // unfiltered list (manual order isn't well-defined inside a genre/search subset).
        val canReorder = currentSortOrder == SortOrder.CUSTOM &&
            currentGenreFilter == null &&
            currentSearchQuery.isEmpty()
        val moveUpItem = popupView.findViewById<View>(R.id.menuItemMoveUp)
        val moveDownItem = popupView.findViewById<View>(R.id.menuItemMoveDown)
        moveUpItem.visibility = if (canReorder) View.VISIBLE else View.GONE
        moveDownItem.visibility = if (canReorder) View.VISIBLE else View.GONE
        moveUpItem.setOnClickListener {
            popupWindow.dismiss()
            moveStation(station, -1)
        }
        moveDownItem.setOnClickListener {
            popupWindow.dismiss()
            moveStation(station, 1)
        }

        popupView.findViewById<View>(R.id.menuItemDelete).setOnClickListener {
            popupWindow.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                repository.deleteStation(station)
            }
        }

        popupWindow.showAsDropDown(anchor, 0, -anchor.height)
    }

    // Swap a station with its neighbour in the custom order and persist the result.
    // Operates on the full, unfiltered library (guaranteed by the canReorder gate).
    private fun moveStation(station: RadioStation, direction: Int) {
        val list = allStationsCache.toMutableList()
        val i = list.indexOfFirst { it.id == station.id }
        if (i < 0) return
        val j = i + direction
        if (j < 0 || j >= list.size) return
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        val orderedIds = list.map { it.id }
        lifecycleScope.launch {
            repository.setStationOrder(orderedIds)
            // The CUSTOM LiveData re-emits in the new order and the list animates.
        }
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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_delete_stations))
            .setMessage(getString(if (selectedCount > 1) R.string.delete_stations_message_plural else R.string.delete_stations_message_single, selectedCount))
            .setPositiveButton(getString(R.string.button_delete)) { _, _ ->
                deleteSelectedStations()
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
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
                if (!PreferencesHelper.isToastMessagesDisabled(requireContext())) {
                    Toast.makeText(
                        requireContext(),
                        getString(if (selectedIds.size > 1) R.string.deleted_stations_message_plural else R.string.deleted_stations_message_single, selectedIds.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
    private val onSelectionChanged: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : RecyclerView.Adapter<RadioStationAdapter.ViewHolder>() {

    private var stations = listOf<RadioStation>()
    private val selectedStations = mutableSetOf<Long>()
    var isSelectionMode = false
        private set
    private var reorderEnabled = false

    init {
        setHasStableIds(true)
    }

    fun submitList(newStations: List<RadioStation>) {
        val diffCallback = RadioStationDiffCallback(stations, newStations)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        stations = newStations
        diffResult.dispatchUpdatesTo(this)
    }

    // Drag-to-reorder support (Custom order only). Show grip handles when enabled,
    // and expose the live list so the fragment can persist the order on drop.
    fun setReorderEnabled(enabled: Boolean) {
        if (reorderEnabled != enabled) {
            reorderEnabled = enabled
            notifyDataSetChanged()
        }
    }

    fun isReorderable(): Boolean = reorderEnabled && !isSelectionMode

    fun currentStations(): List<RadioStation> = stations

    fun moveItem(from: Int, to: Int) {
        if (from !in stations.indices || to !in stations.indices) return
        val list = stations.toMutableList()
        list.add(to, list.removeAt(from))
        stations = list
        notifyItemMoved(from, to)
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
        private val selectionCheckBox: MaterialCheckBox = itemView.findViewById(R.id.selectionCheckBox)
        private val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
        private var imageLoadDisposable: Disposable? = null

        fun bind(station: RadioStation) {
            stationName.text = station.name
            stationName.isSelected = true // drive marquee scrolling for long names

            // Show proxy type indicator (I2P or Tor only - Custom proxy is indicated by top-right icon)
            val proxyIndicator = if (station.useProxy) {
                when (station.getProxyTypeEnum()) {
                    ProxyType.I2P -> " ${itemView.context.getString(R.string.proxy_label_i2p)}"
                    ProxyType.TOR -> " ${itemView.context.getString(R.string.proxy_label_tor)}"
                    ProxyType.CUSTOM -> ""
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
            // For privacy stations (Tor/I2P), use loadSecurePrivacy to route through Tor when available
            if (station.coverArtUri != null) {
                val isPrivacyStation = station.getProxyTypeEnum().let {
                    it == ProxyType.TOR || it == ProxyType.I2P
                }
                val imageLoadBuilder: coil.request.ImageRequest.Builder.() -> Unit = {
                    crossfade(true)
                    placeholder(R.drawable.ic_radio)
                    error(R.drawable.ic_radio)
                }
                imageLoadDisposable = if (isPrivacyStation) {
                    coverArt.loadSecurePrivacy(station.coverArtUri, imageLoadBuilder)
                } else {
                    coverArt.loadSecure(station.coverArtUri, imageLoadBuilder)
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

            // Drag handle: visible only when reordering (Custom order, not selecting).
            // Touching the handle starts the drag via the ItemTouchHelper.
            if (isReorderable()) {
                dragHandle.visibility = View.VISIBLE
                dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag(this@ViewHolder)
                    }
                    false
                }
            } else {
                dragHandle.visibility = View.GONE
                dragHandle.setOnTouchListener(null)
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

            // Long press enters multi-select — except in Custom order, where a
            // long press starts a drag-to-reorder instead (handled by ItemTouchHelper).
            itemView.setOnLongClickListener {
                if (!isSelectionMode && !reorderEnabled) {
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

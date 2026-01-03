package com.opensource.i2pradio.ui.browse

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.request.Disposable
import com.google.android.material.button.MaterialButton
import com.opensource.i2pradio.R
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserStation
import com.opensource.i2pradio.util.loadSecure

/**
 * RecyclerView adapter for displaying RadioBrowser search/browse results.
 */
class BrowseStationsAdapter(
    private val onStationClick: (RadioBrowserStation) -> Unit,
    private val onAddClick: (RadioBrowserStation) -> Unit,
    private val onRemoveClick: (RadioBrowserStation) -> Unit,
    private val onLikeClick: (RadioBrowserStation) -> Unit
) : RecyclerView.Adapter<BrowseStationsAdapter.ViewHolder>() {

    private var stations = listOf<RadioBrowserStation>()
    private var savedUuids = setOf<String>()
    private var likedUuids = setOf<String>()

    init {
        setHasStableIds(true)
    }

    fun submitList(newStations: List<RadioBrowserStation>) {
        val diffCallback = BrowseStationDiffCallback(stations, newStations)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        stations = newStations
        diffResult.dispatchUpdatesTo(this)
    }

    fun updateSavedUuids(uuids: Set<String>) {
        val oldSavedUuids = savedUuids
        savedUuids = uuids

        // Find items that changed saved status and notify
        stations.forEachIndexed { index, station ->
            val wasSaved = oldSavedUuids.contains(station.stationuuid)
            val isSaved = uuids.contains(station.stationuuid)
            if (wasSaved != isSaved) {
                notifyItemChanged(index, PAYLOAD_SAVED_STATUS)
            }
        }
    }

    fun updateLikedUuids(uuids: Set<String>) {
        val oldLikedUuids = likedUuids
        likedUuids = uuids

        // Find items that changed liked status and notify
        stations.forEachIndexed { index, station ->
            val wasLiked = oldLikedUuids.contains(station.stationuuid)
            val isLiked = uuids.contains(station.stationuuid)
            if (wasLiked != isLiked) {
                notifyItemChanged(index, PAYLOAD_LIKED_STATUS)
            }
        }
    }

    override fun getItemId(position: Int): Long {
        return stations[position].stationuuid.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browse_station, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]
        holder.bind(
            station,
            savedUuids.contains(station.stationuuid),
            likedUuids.contains(station.stationuuid)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val station = stations[position]
        if (payloads.contains(PAYLOAD_SAVED_STATUS)) {
            holder.updateSavedStatus(savedUuids.contains(station.stationuuid))
        }
        if (payloads.contains(PAYLOAD_LIKED_STATUS)) {
            holder.updateLikedStatus(likedUuids.contains(station.stationuuid))
        }
    }

    override fun getItemCount() = stations.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val stationIcon: ImageView = itemView.findViewById(R.id.stationIcon)
        private val stationName: TextView = itemView.findViewById(R.id.stationName)
        private val stationInfo: TextView = itemView.findViewById(R.id.stationInfo)
        private val qualityInfo: TextView = itemView.findViewById(R.id.qualityInfo)
        private val likeButton: MaterialButton = itemView.findViewById(R.id.likeButton)
        private val actionButton: MaterialButton = itemView.findViewById(R.id.actionButton)
        private var imageLoadDisposable: Disposable? = null

        fun bind(station: RadioBrowserStation, isSaved: Boolean, isLiked: Boolean) {
            stationName.text = station.name

            // Build info string: genre (with network indicator) + country/status
            val isPrivacyStation = station.proxyType.uppercase() in listOf("TOR", "I2P")
            val genreWithNetwork = station.getGenreWithNetwork()
            val baseText = if (genreWithNetwork.isNotEmpty() && genreWithNetwork != "Other") {
                "$genreWithNetwork • "
            } else {
                ""
            }

            if (isPrivacyStation) {
                // For privacy stations, show Online/Offline status with color
                val statusText = if (station.lastcheckok) "Online" else "Offline"
                val statusColor = if (station.lastcheckok) {
                    Color.parseColor("#4CAF50")  // Material Green
                } else {
                    Color.parseColor("#F44336")  // Material Red
                }
                val spannable = SpannableStringBuilder(baseText).apply {
                    val statusStart = length
                    append(statusText)
                    setSpan(
                        ForegroundColorSpan(statusColor),
                        statusStart,
                        statusStart + statusText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                stationInfo.text = spannable
            } else {
                // For regular stations, show country
                val infoParts = mutableListOf<String>()
                if (genreWithNetwork.isNotEmpty() && genreWithNetwork != "Other") {
                    infoParts.add(genreWithNetwork)
                }
                if (station.country.isNotEmpty()) {
                    infoParts.add(station.country)
                }
                stationInfo.text = infoParts.joinToString(" • ")
            }

            // Quality info
            val quality = station.getQualityInfo()
            if (quality.isNotEmpty()) {
                qualityInfo.text = quality
                qualityInfo.visibility = View.VISIBLE
            } else {
                qualityInfo.visibility = View.GONE
            }

            // Load favicon using secure loader (respects Tor settings)
            imageLoadDisposable?.dispose()
            stationIcon.setImageResource(R.drawable.ic_radio)
            if (station.favicon.isNotEmpty()) {
                imageLoadDisposable = stationIcon.loadSecure(station.favicon) {
                    crossfade(true)
                    placeholder(R.drawable.ic_radio)
                    error(R.drawable.ic_radio)
                }
            }

            // Update saved and liked status
            updateSavedStatus(isSaved)
            updateLikedStatus(isLiked)

            // Like button click listener
            likeButton.setOnClickListener {
                onLikeClick(station)
            }

            // Touch feedback animation
            itemView.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.animate()
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(100)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
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

            // Click listeners
            itemView.setOnClickListener {
                onStationClick(station)
            }

            actionButton.setOnClickListener {
                if (savedUuids.contains(station.stationuuid)) {
                    onRemoveClick(station)
                } else {
                    onAddClick(station)
                }
            }
        }

        fun updateSavedStatus(isSaved: Boolean) {
            actionButton.setIconResource(if (isSaved) R.drawable.ic_check else R.drawable.ic_add)
            actionButton.isEnabled = true
            actionButton.alpha = 1f
        }

        fun updateLikedStatus(isLiked: Boolean) {
            if (isLiked) {
                likeButton.setIconResource(R.drawable.ic_favorite)
                likeButton.setIconTintResource(R.color.color_favorite)
            } else {
                likeButton.setIconResource(R.drawable.ic_favorite_border)
                // Reset to default theme color
                val typedValue = android.util.TypedValue()
                itemView.context.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    typedValue,
                    true
                )
                if (typedValue.resourceId != 0) {
                    likeButton.setIconTintResource(typedValue.resourceId)
                } else {
                    likeButton.iconTint = android.content.res.ColorStateList.valueOf(typedValue.data)
                }
            }
        }
    }

    private class BrowseStationDiffCallback(
        private val oldList: List<RadioBrowserStation>,
        private val newList: List<RadioBrowserStation>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].stationuuid == newList[newItemPosition].stationuuid
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old.name == new.name &&
                    old.favicon == new.favicon &&
                    old.country == new.country &&
                    old.bitrate == new.bitrate &&
                    old.codec == new.codec
        }
    }

    companion object {
        private const val PAYLOAD_SAVED_STATUS = "saved_status"
        private const val PAYLOAD_LIKED_STATUS = "liked_status"
    }
}

package com.opensource.i2pradio.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.request.Disposable
import com.opensource.i2pradio.R
import com.opensource.i2pradio.data.radiobrowser.RadioBrowserStation
import com.opensource.i2pradio.util.loadSecure

/**
 * Adapter for horizontal carousel of stations (Trending, Popular sections).
 */
class BrowseCarouselAdapter(
    private val onStationClick: (RadioBrowserStation) -> Unit,
    private val onLikeClick: (RadioBrowserStation) -> Unit,
    private val showRankBadge: Boolean = false
) : ListAdapter<RadioBrowserStation, BrowseCarouselAdapter.ViewHolder>(DiffCallback()) {

    private var likedUuids: Set<String> = emptySet()

    fun updateLikedUuids(uuids: Set<String>) {
        val oldLikedUuids = likedUuids
        likedUuids = uuids

        // Find items that changed liked status and notify with payload for efficient update
        currentList.forEachIndexed { index, station ->
            val wasLiked = oldLikedUuids.contains(station.stationuuid)
            val isLiked = uuids.contains(station.stationuuid)
            if (wasLiked != isLiked) {
                notifyItemChanged(index, PAYLOAD_LIKED_STATUS)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browse_station_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position + 1)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        // Handle payload-based partial update for liked status
        val station = getItem(position)
        if (payloads.contains(PAYLOAD_LIKED_STATUS)) {
            holder.updateLikedStatus(likedUuids.contains(station.stationuuid))
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val stationImage: ImageView = itemView.findViewById(R.id.stationImage)
        private val stationName: TextView = itemView.findViewById(R.id.stationName)
        private val stationInfo: TextView = itemView.findViewById(R.id.stationInfo)
        private val rankBadge: TextView = itemView.findViewById(R.id.rankBadge)
        private val btnLike: ImageButton = itemView.findViewById(R.id.btnLike)
        private var imageLoadDisposable: Disposable? = null

        fun bind(station: RadioBrowserStation, rank: Int) {
            stationName.text = station.name
            stationInfo.text = buildString {
                val genreWithNetwork = station.getGenreWithNetwork()
                if (genreWithNetwork.isNotEmpty() && genreWithNetwork != "Other") {
                    append(genreWithNetwork)
                }
                if (station.country.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(station.country)
                }
            }.ifEmpty { station.country.ifEmpty { "Radio" } }

            // Load station image using secure loader (respects Tor settings)
            imageLoadDisposable?.dispose()
            stationImage.setImageResource(R.drawable.ic_radio)
            if (station.favicon.isNotEmpty()) {
                imageLoadDisposable = stationImage.loadSecure(station.favicon) {
                    crossfade(true)
                    placeholder(R.drawable.ic_radio)
                    error(R.drawable.ic_radio)
                }
            }

            // Rank badge
            if (showRankBadge) {
                rankBadge.visibility = View.VISIBLE
                rankBadge.text = rank.toString()
            } else {
                rankBadge.visibility = View.GONE
            }

            // Like state
            updateLikedStatus(likedUuids.contains(station.stationuuid))

            // Click listeners
            itemView.setOnClickListener { onStationClick(station) }
            btnLike.setOnClickListener { onLikeClick(station) }
        }

        fun updateLikedStatus(isLiked: Boolean) {
            btnLike.setImageResource(
                if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            )
            // Animate the tint color change
            if (isLiked) {
                btnLike.imageTintList = android.content.res.ColorStateList.valueOf(
                    itemView.context.getColor(R.color.color_favorite)
                )
                // Pulse animation for visual feedback
                btnLike.animate()
                    .scaleX(1.3f)
                    .scaleY(1.3f)
                    .setDuration(100)
                    .withEndAction {
                        btnLike.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
            } else {
                btnLike.imageTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.WHITE
                )
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RadioBrowserStation>() {
        override fun areItemsTheSame(oldItem: RadioBrowserStation, newItem: RadioBrowserStation): Boolean {
            return oldItem.stationuuid == newItem.stationuuid
        }

        override fun areContentsTheSame(oldItem: RadioBrowserStation, newItem: RadioBrowserStation): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val PAYLOAD_LIKED_STATUS = "liked_status"
    }
}

package com.opensource.i2pradio.ui.browse

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import com.opensource.i2pradio.data.radioregistry.RadioRegistryStation
import com.opensource.i2pradio.util.loadSecurePrivacy

/**
 * Adapter for horizontal carousel of Privacy Radio stations (Tor/I2P from Radio Registry API).
 */
class PrivacyRadioCarouselAdapter(
    private val onStationClick: (RadioRegistryStation) -> Unit,
    private val onLikeClick: (RadioRegistryStation) -> Unit,
    private val showRankBadge: Boolean = false
) : ListAdapter<RadioRegistryStation, PrivacyRadioCarouselAdapter.ViewHolder>(DiffCallback()) {

    private var likedUuids: Set<String> = emptySet()

    // Cover art loading is disabled initially to ensure basic station data renders first
    // This prevents Tor-proxied image loading from blocking the carousel display
    private var coverArtLoadingEnabled = false

    /**
     * Enable cover art loading. Call this after the carousel has been populated and rendered.
     * This allows cover art to load asynchronously over Tor without blocking the initial render.
     */
    fun enableCoverArtLoading() {
        if (!coverArtLoadingEnabled) {
            coverArtLoadingEnabled = true
            // Trigger rebind of all visible items to start loading cover art
            notifyItemRangeChanged(0, itemCount, PAYLOAD_LOAD_COVER_ART)
        }
    }

    fun updateLikedUuids(uuids: Set<String>) {
        val oldLikedUuids = likedUuids
        likedUuids = uuids

        currentList.forEachIndexed { index, station ->
            val uuid = "registry_${station.id}"
            val wasLiked = oldLikedUuids.contains(uuid)
            val isLiked = uuids.contains(uuid)
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
        val station = getItem(position)
        if (payloads.contains(PAYLOAD_LIKED_STATUS)) {
            val uuid = "registry_${station.id}"
            holder.updateLikedStatus(likedUuids.contains(uuid))
        }
        if (payloads.contains(PAYLOAD_LOAD_COVER_ART)) {
            holder.loadCoverArt(station.faviconUrl)
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val stationImage: ImageView = itemView.findViewById(R.id.stationImage)
        private val stationName: TextView = itemView.findViewById(R.id.stationName)
        private val stationInfo: TextView = itemView.findViewById(R.id.stationInfo)
        private val rankBadge: TextView = itemView.findViewById(R.id.rankBadge)
        private val btnLike: ImageButton = itemView.findViewById(R.id.btnLike)
        private var imageLoadDisposable: Disposable? = null
        private var pendingImageLoadRunnable: Runnable? = null
        private val handler = Handler(Looper.getMainLooper())

        fun bind(station: RadioRegistryStation, rank: Int) {
            stationName.text = station.name
            // Build station info with "Online" in green
            val genreWithNetwork = station.getGenreWithNetwork()
            val baseText = if (genreWithNetwork.isNotEmpty() && genreWithNetwork != "Other") {
                "$genreWithNetwork • "
            } else {
                "${station.getNetworkIndicator() ?: "Privacy Radio"} • "
            }
            val onlineText = "Online"
            val spannable = SpannableStringBuilder(baseText).apply {
                val onlineStart = length
                append(onlineText)
                setSpan(
                    ForegroundColorSpan(Color.parseColor("#4CAF50")),  // Material Green
                    onlineStart,
                    onlineStart + onlineText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            stationInfo.text = spannable

            // Cancel any pending image load from view recycling
            pendingImageLoadRunnable?.let { handler.removeCallbacks(it) }
            imageLoadDisposable?.dispose()

            // Set default icon immediately so basic station info is visible right away
            // Cover art loading over Tor is deferred to avoid blocking carousel rendering
            stationImage.setImageResource(R.drawable.ic_radio)

            // Only load cover art after the adapter has been attached and rendered
            // This prevents Tor-proxied image loading from interfering with initial render
            if (!station.faviconUrl.isNullOrEmpty() && coverArtLoadingEnabled) {
                val faviconUrl = station.faviconUrl
                pendingImageLoadRunnable = Runnable {
                    // Check if view is still attached and cover art loading is still enabled
                    if (stationImage.isAttachedToWindow && coverArtLoadingEnabled) {
                        imageLoadDisposable = stationImage.loadSecurePrivacy(faviconUrl) {
                            crossfade(true)
                            placeholder(R.drawable.ic_radio)
                            error(R.drawable.ic_radio)
                        }
                    }
                }
                // Delay cover art loading significantly to ensure carousel is fully rendered first
                handler.postDelayed(pendingImageLoadRunnable!!, COVER_ART_LOAD_DELAY_MS)
            }

            // Rank badge
            if (showRankBadge) {
                rankBadge.visibility = View.VISIBLE
                rankBadge.text = rank.toString()
            } else {
                rankBadge.visibility = View.GONE
            }

            // Like state
            val uuid = "registry_${station.id}"
            updateLikedStatus(likedUuids.contains(uuid))

            // Online indicator - change image tint if offline
            if (!station.isOnline) {
                stationImage.alpha = 0.5f
            } else {
                stationImage.alpha = 1.0f
            }

            // Click listeners
            itemView.setOnClickListener { onStationClick(station) }
            btnLike.setOnClickListener { onLikeClick(station) }
        }

        fun updateLikedStatus(isLiked: Boolean) {
            btnLike.setImageResource(
                if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            )
        }

        /**
         * Load cover art for this station. Called when cover art loading is enabled
         * after the carousel has been rendered.
         */
        fun loadCoverArt(faviconUrl: String?) {
            if (!faviconUrl.isNullOrEmpty() && coverArtLoadingEnabled) {
                pendingImageLoadRunnable?.let { handler.removeCallbacks(it) }
                imageLoadDisposable?.dispose()

                pendingImageLoadRunnable = Runnable {
                    if (stationImage.isAttachedToWindow) {
                        imageLoadDisposable = stationImage.loadSecurePrivacy(faviconUrl) {
                            crossfade(true)
                            placeholder(R.drawable.ic_radio)
                            error(R.drawable.ic_radio)
                        }
                    }
                }
                handler.postDelayed(pendingImageLoadRunnable!!, COVER_ART_LOAD_DELAY_MS)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RadioRegistryStation>() {
        override fun areItemsTheSame(oldItem: RadioRegistryStation, newItem: RadioRegistryStation): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: RadioRegistryStation, newItem: RadioRegistryStation): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val PAYLOAD_LIKED_STATUS = "liked_status"
        private const val PAYLOAD_LOAD_COVER_ART = "load_cover_art"
        // Delay cover art loading to ensure basic station data renders first
        // This prevents cover art loading over Tor from blocking the carousel display
        private const val COVER_ART_LOAD_DELAY_MS = 150L
    }
}

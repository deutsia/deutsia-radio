package com.opensource.i2pradio.ui.browse

import android.graphics.Color
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
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val stationImage: ImageView = itemView.findViewById(R.id.stationImage)
        private val stationName: TextView = itemView.findViewById(R.id.stationName)
        private val stationInfo: TextView = itemView.findViewById(R.id.stationInfo)
        private val rankBadge: TextView = itemView.findViewById(R.id.rankBadge)
        private val btnLike: ImageButton = itemView.findViewById(R.id.btnLike)
        private var imageLoadDisposable: Disposable? = null

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

            // Load station image using secure loader
            imageLoadDisposable?.dispose()
            stationImage.setImageResource(R.drawable.ic_radio)
            if (!station.faviconUrl.isNullOrEmpty()) {
                imageLoadDisposable = stationImage.loadSecurePrivacy(station.faviconUrl) {
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
    }
}

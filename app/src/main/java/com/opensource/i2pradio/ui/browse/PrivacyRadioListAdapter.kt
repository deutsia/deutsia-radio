package com.opensource.i2pradio.ui.browse

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.request.Disposable
import com.google.android.material.button.MaterialButton
import com.opensource.i2pradio.R
import com.opensource.i2pradio.data.radioregistry.RadioRegistryStation
import com.opensource.i2pradio.util.loadSecurePrivacy

/**
 * Adapter for list view of Privacy Radio stations (Tor/I2P from Radio Registry API).
 * Used in the "See All" results view.
 */
class PrivacyRadioListAdapter(
    private val onStationClick: (RadioRegistryStation) -> Unit,
    private val onLikeClick: (RadioRegistryStation) -> Unit,
    private val onAddClick: ((RadioRegistryStation) -> Unit)? = null,
    private val onRemoveClick: ((RadioRegistryStation) -> Unit)? = null
) : ListAdapter<RadioRegistryStation, PrivacyRadioListAdapter.ViewHolder>(DiffCallback()) {

    private var likedUuids: Set<String> = emptySet()
    private var savedUuids: Set<String> = emptySet()

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

    fun updateSavedUuids(uuids: Set<String>) {
        val oldSavedUuids = savedUuids
        savedUuids = uuids

        currentList.forEachIndexed { index, station ->
            val uuid = "registry_${station.id}"
            val wasSaved = oldSavedUuids.contains(uuid)
            val isSaved = uuids.contains(uuid)
            if (wasSaved != isSaved) {
                notifyItemChanged(index, PAYLOAD_SAVED_STATUS)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browse_station, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val station = getItem(position)
        val uuid = "registry_${station.id}"
        if (payloads.contains(PAYLOAD_LIKED_STATUS)) {
            holder.updateLikedStatus(likedUuids.contains(uuid))
        }
        if (payloads.contains(PAYLOAD_SAVED_STATUS)) {
            holder.updateSavedStatus(savedUuids.contains(uuid))
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val stationIcon: ImageView = itemView.findViewById(R.id.stationIcon)
        private val stationName: TextView = itemView.findViewById(R.id.stationName)
        private val stationInfo: TextView = itemView.findViewById(R.id.stationInfo)
        private val qualityInfo: TextView = itemView.findViewById(R.id.qualityInfo)
        private val likeButton: MaterialButton = itemView.findViewById(R.id.likeButton)
        private val actionButton: MaterialButton = itemView.findViewById(R.id.actionButton)
        private var imageLoadDisposable: Disposable? = null

        fun bind(station: RadioRegistryStation) {
            stationName.text = station.name

            // Build station info with "Online" in green
            val genreWithNetwork = station.getGenreWithNetwork()
            val baseText = if (genreWithNetwork.isNotEmpty() && genreWithNetwork != "Other") {
                "$genreWithNetwork • "
            } else {
                "${station.getNetworkIndicator() ?: "Privacy Radio"} • "
            }
            val statusText = if (station.isOnline) "Online" else "Offline"
            val statusColor = if (station.isOnline) "#4CAF50" else "#F44336"
            val spannable = SpannableStringBuilder(baseText).apply {
                val statusStart = length
                append(statusText)
                setSpan(
                    ForegroundColorSpan(Color.parseColor(statusColor)),
                    statusStart,
                    statusStart + statusText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            stationInfo.text = spannable

            // Hide quality info for privacy radio stations (no bitrate data)
            qualityInfo.visibility = View.GONE

            // Show action button for add/remove from library
            val uuid = "registry_${station.id}"
            val isSaved = savedUuids.contains(uuid)
            if (onAddClick != null || onRemoveClick != null) {
                actionButton.visibility = View.VISIBLE
                updateSavedStatus(isSaved)
                actionButton.setOnClickListener {
                    if (savedUuids.contains(uuid)) {
                        onRemoveClick?.invoke(station)
                    } else {
                        onAddClick?.invoke(station)
                    }
                }
            } else {
                actionButton.visibility = View.GONE
            }

            // Load station image using secure loader
            imageLoadDisposable?.dispose()
            stationIcon.setImageResource(R.drawable.ic_radio)
            if (!station.faviconUrl.isNullOrEmpty()) {
                imageLoadDisposable = stationIcon.loadSecurePrivacy(station.faviconUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_radio)
                    error(R.drawable.ic_radio)
                }
            }

            // Like state
            updateLikedStatus(likedUuids.contains(uuid))

            // Online indicator - change image alpha if offline
            stationIcon.alpha = if (station.isOnline) 1.0f else 0.5f

            // Touch feedback animation
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
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
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
            itemView.setOnClickListener { onStationClick(station) }
            likeButton.setOnClickListener { onLikeClick(station) }
        }

        fun updateLikedStatus(isLiked: Boolean) {
            likeButton.setIconResource(
                if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            )
            // Animate the tint color change
            if (isLiked) {
                likeButton.setIconTintResource(R.color.color_favorite)
                // Pulse animation for visual feedback
                likeButton.animate()
                    .scaleX(1.3f)
                    .scaleY(1.3f)
                    .setDuration(100)
                    .withEndAction {
                        likeButton.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
            } else {
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

        fun updateSavedStatus(isSaved: Boolean) {
            actionButton.setIconResource(if (isSaved) R.drawable.ic_check else R.drawable.ic_add)
            actionButton.isEnabled = true
            actionButton.alpha = 1f
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
        private const val PAYLOAD_SAVED_STATUS = "saved_status"
    }
}

package com.opensource.i2pradio.ui.browse

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.opensource.i2pradio.R

/**
 * Adapter that displays skeleton placeholder cards with shimmer animation.
 * Used while carousel data is loading.
 */
class SkeletonCarouselAdapter(
    private val itemCount: Int = 4
) : RecyclerView.Adapter<SkeletonCarouselAdapter.ViewHolder>() {

    private val shimmerAnimators = mutableListOf<ValueAnimator>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browse_station_card_skeleton, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.startShimmer(position)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.stopShimmer()
    }

    override fun getItemCount(): Int = itemCount

    fun stopAllShimmers() {
        shimmerAnimators.forEach { it.cancel() }
        shimmerAnimators.clear()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val shimmerOverlay: View = itemView.findViewById(R.id.shimmerOverlay)
        private var animator: ValueAnimator? = null

        fun startShimmer(position: Int) {
            val parent = itemView.parent as? ViewGroup ?: return
            val itemWidth = itemView.width.takeIf { it > 0 } ?: 150.dpToPx(itemView.context)
            val shimmerWidth = shimmerOverlay.width.takeIf { it > 0 } ?: 80.dpToPx(itemView.context)

            animator?.cancel()
            animator = ValueAnimator.ofFloat(-shimmerWidth.toFloat(), itemWidth.toFloat()).apply {
                duration = 1000L
                startDelay = (position * 100L) // Staggered animation
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { animation ->
                    shimmerOverlay.translationX = animation.animatedValue as Float
                }
                start()
            }
            animator?.let { shimmerAnimators.add(it) }
        }

        fun stopShimmer() {
            animator?.cancel()
            animator?.let { shimmerAnimators.remove(it) }
            animator = null
        }

        private fun Int.dpToPx(context: android.content.Context): Int {
            return (this * context.resources.displayMetrics.density).toInt()
        }
    }
}

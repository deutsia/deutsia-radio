package com.opensource.i2pradio.ui

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView that wraps its content height but never grows past [maxHeightPx].
 * Once content exceeds the cap it scrolls internally, which lets us drop a list
 * inside a dialog without it running off the screen (and without wasting space
 * when there are only a few items).
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val spec = if (maxHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightSpec
        }
        super.onMeasure(widthSpec, spec)
    }
}

package com.opensource.i2pradio.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.opensource.i2pradio.R

/**
 * A FrameLayout that applies a shimmer animation effect to its contents.
 * Used for skeleton loading screens.
 */
class ShimmerFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val shimmerPaint = Paint()
    private var shimmerAnimator: ValueAnimator? = null
    private var shimmerTranslate: Float = 0f
    private val shimmerMatrix = Matrix()
    private var shimmerGradient: LinearGradient? = null

    private var shimmerWidth = 0
    private var shimmerColor = 0x33FFFFFF // Semi-transparent white
    private var shimmerDuration = 1200L
    private var isShimmerStarted = false

    init {
        setWillNotDraw(false)
        shimmerPaint.isAntiAlias = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            shimmerWidth = w / 3
            createShimmerGradient()
        }
    }

    private fun createShimmerGradient() {
        shimmerGradient = LinearGradient(
            0f, 0f, shimmerWidth.toFloat(), 0f,
            intArrayOf(
                0x00FFFFFF,
                shimmerColor,
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shimmerPaint.shader = shimmerGradient
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (isShimmerStarted && shimmerGradient != null && width > 0) {
            shimmerMatrix.reset()
            shimmerMatrix.setTranslate(shimmerTranslate, 0f)
            shimmerGradient?.setLocalMatrix(shimmerMatrix)

            canvas.save()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shimmerPaint)
            canvas.restore()
        }
    }

    fun startShimmer() {
        if (isShimmerStarted) return
        isShimmerStarted = true

        shimmerAnimator?.cancel()
        shimmerAnimator = ValueAnimator.ofFloat(-shimmerWidth.toFloat(), width.toFloat() + shimmerWidth).apply {
            duration = shimmerDuration
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { animation ->
                shimmerTranslate = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopShimmer() {
        isShimmerStarted = false
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        invalidate()
    }

    fun isShimmerStarted(): Boolean = isShimmerStarted

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShimmerStarted) {
            startShimmer()
        }
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }
}

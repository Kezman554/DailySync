package com.personalmorningalarm.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import kotlin.math.min

/**
 * The alarm screens' rings, two ways:
 *  - [setProgress]: one arc from the top on a track (Stage 1 shake, timers). The arc
 *    is accent while running and turns positive once [setComplete] says so.
 *  - [setSegments]: the circle split into one arc per checkpoint, done ones in the
 *    positive colour, the rest in surface 2 (Stage 2).
 * Round caps. The 210dp rings use a 14dp stroke on the card-surface track; the
 * 56dp Continue timer uses [makeSmall] (4dp on surface 2). Colours come from the
 * theme so light mode follows.
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var stroke = resources.displayMetrics.density * STROKE_DP

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    private val accent = MaterialColors.getColor(this, MaterialR.attr.colorPrimary)
    private var track = MaterialColors.getColor(this, MaterialR.attr.colorSurface)
    private val positive = MaterialColors.getColor(this, R.attr.colorPositive)
    private val pending = MaterialColors.getColor(this, R.attr.colorSurface2)

    private var segments = 0
    private var done = 0
    private var progress = 0f
    private var complete = false

    /** The small Continue-timer ring: a 4dp stroke on a surface-2 track. */
    fun makeSmall() {
        stroke = resources.displayMetrics.density * SMALL_STROKE_DP
        paint.strokeWidth = stroke
        track = pending
        invalidate()
    }

    /** Continuous mode: [fraction] of the circle filled, 0..1, in accent. */
    fun setProgress(fraction: Float) {
        segments = 0
        complete = false
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    /** Continuous mode, finished: the whole ring in the positive colour. */
    fun setComplete() {
        segments = 0
        complete = true
        progress = 1f
        invalidate()
    }

    /** Segmented mode: [total] arcs, the first [completed] of them done. */
    fun setSegments(total: Int, completed: Int) {
        segments = total.coerceAtLeast(1)
        done = completed.coerceIn(0, segments)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
        val half = stroke / 2f
        val left = paddingLeft + (width - paddingLeft - paddingRight - size) / 2f
        val top = paddingTop + (height - paddingTop - paddingBottom - size) / 2f
        oval.set(left + half, top + half, left + size - half, top + size - half)

        if (segments == 0) {
            paint.color = track
            canvas.drawArc(oval, 0f, 360f, false, paint)
            if (progress > 0f) {
                paint.color = if (complete) positive else accent
                canvas.drawArc(oval, START, 360f * progress, false, paint)
            }
            return
        }

        // Equal arcs with a gap between neighbours. Round caps reach half the stroke
        // past each end, so the gap in degrees allows for both caps.
        val radius = oval.width() / 2f
        val capDegrees = Math.toDegrees((stroke / radius).toDouble()).toFloat()
        val slot = 360f / segments
        val sweep = if (segments == 1) 360f else (slot - capDegrees - GAP_DEGREES).coerceAtLeast(1f)
        for (i in 0 until segments) {
            paint.color = if (i < done) positive else pending
            val start = START + i * slot + (if (segments == 1) 0f else (capDegrees + GAP_DEGREES) / 2f)
            canvas.drawArc(oval, start, sweep, false, paint)
        }
    }

    private companion object {
        const val STROKE_DP = 14f
        const val SMALL_STROKE_DP = 4f
        const val START = -90f
        const val GAP_DEGREES = 4f
    }
}

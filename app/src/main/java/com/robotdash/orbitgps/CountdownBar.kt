package com.robotdash.orbitgps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Thin vertical bar beside the junction picture that fills from the bottom as the
 * turn gets closer (0 = just appeared, 1 = at the turn).
 */
class CountdownBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var fraction: Float = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c == field) return
            field = c
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2FD0FF.toInt() }
    private val r = RectF()

    /**
     * The bar takes its height from the picture beside it and must never ask for more.
     * (A plain View asks for all the space it's offered, which in 1.5 stretched the
     * junction panel and the blue banner down the whole screen.)
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            else -> (8 * resources.displayMetrics.density).toInt()
        }
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (h <= 0f) return
        val rad = w / 2
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, rad, rad, track)
        if (fraction > 0f) {
            r.set(0f, (1 - fraction) * h, w, h)
            c.drawRoundRect(r, rad, rad, fill)
        }
    }
}

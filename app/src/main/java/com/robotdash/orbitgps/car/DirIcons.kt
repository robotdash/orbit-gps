package com.robotdash.orbitgps.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import com.robotdash.orbitgps.NavCore
import com.robotdash.orbitgps.nav.Pt
import kotlin.math.abs
import kotlin.math.roundToInt

/** Garmin-style arrows for car list rows: which way a place is from the way you're heading. */
object DirIcons {
    private val cache = HashMap<Int, CarIcon>()

    fun icon(p: Pt): CarIcon? {
        val dir = NavCore.directionTo(p) ?: return null
        val step = ((dir / 22.5).roundToInt() * 22.5).let { if (it <= -180) it + 360 else it }   // 16 directions
        val key = step.roundToInt()
        return cache.getOrPut(key) { build(key.toFloat(), abs(step) > 100) }
    }

    private fun build(deg: Float, behind: Boolean): CarIcon {
        val s = 96
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.rotate(deg, s / 2f, s / 2f)
        val path = Path().apply { moveTo(48f, 8f); lineTo(80f, 84f); lineTo(48f, 66f); lineTo(16f, 84f); close() }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (behind) 0xFF7FA9B8.toInt() else 0xFF2FD0FF.toInt() })
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }
}

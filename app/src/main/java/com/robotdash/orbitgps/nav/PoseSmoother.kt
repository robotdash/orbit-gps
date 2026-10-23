package com.robotdash.orbitgps.nav

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Turns once-a-second GPS fixes into smooth, continuous movement for the screen.
 *
 * Between fixes the car's position is predicted from its speed and heading. When
 * it's on a route, the prediction slides along the route line itself, so the arrow
 * follows curves in the road instead of cutting corners. When a new fix arrives
 * that disagrees a little with the prediction, the difference is blended away over
 * about half a second instead of jumping.
 *
 * Pure logic (no Android) so it can be tested frame by frame.
 */
class PoseSmoother {

    data class Pose(val lat: Double, val lon: Double, val bearing: Double, val speed: Double)

    private class Basis(
        val pt: Pt, val bearing: Double, val speed: Double, val time: Long,
        val route: Route?, val along: Double?,
    )

    private var basis: Basis? = null
    // Blend-away correction (metres east/north, degrees) captured when a fix arrives.
    private var offE = 0.0
    private var offN = 0.0
    private var offB = 0.0
    private var offTime = 0L

    /** Longest we keep predicting forward without a fix (GPS dropout). */
    private val maxPredictMs = 1600L
    /** How quickly a correction fades (ms). */
    private val blendMs = 450.0

    val hasFix: Boolean get() = basis != null

    /**
     * A new GPS fix. [along] = metres along [route] where the fix sits, when the car
     * is on that route (null when off route / not navigating).
     */
    fun onFix(p: Pt, bearing: Double, speedMs: Double, timeMs: Long, route: Route? = null, along: Double? = null) {
        val before = if (basis != null) at(timeMs) else null
        basis = Basis(p, bearing, speedMs.coerceAtLeast(0.0), timeMs, route, along)
        offTime = timeMs
        if (before == null) { offE = 0.0; offN = 0.0; offB = 0.0; return }
        val now = rawAt(timeMs)
        val (e, n) = metres(now.lat, now.lon, before.lat, before.lon)
        // A big disagreement (GPS jump, reroute, long gap): go straight there.
        if (e * e + n * n > 60.0 * 60.0) { offE = 0.0; offN = 0.0; offB = 0.0; return }
        offE = e; offN = n
        offB = Geo.normalize(before.bearing - now.bearing)
    }

    /** Where to draw the car at [timeMs]. */
    fun at(timeMs: Long): Pose {
        val raw = rawAt(timeMs)
        val k = exp(-(timeMs - offTime).coerceAtLeast(0) / blendMs)
        if (k < 0.01) return raw
        val (lat, lon) = offset(raw.lat, raw.lon, offE * k, offN * k)
        return raw.copy(lat = lat, lon = lon, bearing = (raw.bearing + offB * k + 360) % 360)
    }

    /** Prediction from the latest fix alone, before blending. */
    private fun rawAt(timeMs: Long): Pose {
        val b = basis ?: return Pose(0.0, 0.0, 0.0, 0.0)
        val dt = (timeMs - b.time).coerceIn(0, maxPredictMs) / 1000.0
        val travel = b.speed * dt
        val route = b.route
        if (route != null && b.along != null && b.speed > 0.5) {
            val a = (b.along + travel).coerceAtMost(route.length)
            val p = pointAt(route, a)
            // Heading = direction of the road just ahead, so the arrow turns with the curve.
            val ahead = pointAt(route, (a + 15).coerceAtMost(route.length))
            val behind = pointAt(route, (a - 5).coerceAtLeast(0.0))
            val brg = if (Geo.distance(behind, ahead) > 1) Geo.bearing(behind, ahead) else b.bearing
            return Pose(p.lat, p.lon, brg, b.speed)
        }
        val rad = Math.toRadians(b.bearing)
        val (lat, lon) = offset(b.pt.lat, b.pt.lon, travel * sin(rad), travel * cos(rad))
        return Pose(lat, lon, b.bearing, b.speed)
    }

    companion object {
        fun pointAt(r: Route, d: Double): Pt {
            val c = r.cumulative
            if (d <= 0) return r.points.first()
            if (d >= c.last()) return r.points.last()
            // Binary search for the segment containing d.
            var lo = 0; var hi = c.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (c[mid] < d) lo = mid else hi = mid }
            val span = c[hi] - c[lo]
            val t = if (span <= 0) 0.0 else (d - c[lo]) / span
            return Geo.lerp(r.points[lo], r.points[hi], t)
        }

        private const val M_PER_DEG_LAT = 111_320.0

        /** Metres (east, north) from (lat0, lon0) to (lat1, lon1). */
        fun metres(lat0: Double, lon0: Double, lat1: Double, lon1: Double): Pair<Double, Double> {
            val e = (lon1 - lon0) * M_PER_DEG_LAT * cos(Math.toRadians(lat0))
            val n = (lat1 - lat0) * M_PER_DEG_LAT
            return e to n
        }

        fun offset(lat: Double, lon: Double, east: Double, north: Double): Pair<Double, Double> =
            (lat + north / M_PER_DEG_LAT) to (lon + east / (M_PER_DEG_LAT * cos(Math.toRadians(lat))))
    }
}

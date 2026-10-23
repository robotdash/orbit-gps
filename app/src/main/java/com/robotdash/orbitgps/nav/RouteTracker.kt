package com.robotdash.orbitgps.nav

/**
 * Follows the car along a [Route].
 *
 * On each GPS fix it finds where you are on the route line (searching forward from
 * the last known spot, so a road that loops back near itself doesn't confuse it),
 * how far you are from the next announced turn, and whether you've left the route.
 */
class RouteTracker(val route: Route) {

    data class Progress(
        /** Your position snapped onto the route line. */
        val snapped: Pt,
        /** Direction of the route at that point, degrees. */
        val routeBearing: Double,
        /** Metres from the route line. */
        val offset: Double,
        /** Metres travelled along the route. */
        val along: Double,
        /** Index into [Route.announced] of the next manoeuvre, or -1 at the end. */
        val nextIndex: Int,
        val distanceToNext: Double,
        val remaining: Double,
        val offRoute: Boolean,
        val arrived: Boolean,
    )

    private var segIndex = 0          // segment we were last on
    private var offCount = 0
    private var lastAlong = 0.0

    /** How far we search ahead/behind for the current segment. */
    private val lookAheadMetres = 600.0

    fun update(p: Pt, heading: Double, speedMs: Double, accuracy: Double): Progress {
        val pts = route.points
        val cum = route.cumulative

        // Search segments from a bit behind the last one to a bit ahead.
        var best = segIndex; var bestD = Double.MAX_VALUE; var bestT = 0.0
        var i = segIndex
        while (i > 0 && cum[segIndex] - cum[i] < 50) i--
        // Look ahead by distance from where we last were (long straight highway
        // segments can be kilometres between points), and always check the next few.
        while (i < pts.size - 1 && (cum[i] < lastAlong + lookAheadMetres || i <= segIndex + 3)) {
            val (d, t) = distanceToSegment(p, pts[i], pts[i + 1])
            // Prefer segments heading our way when moving.
            val segBearing = Geo.bearing(pts[i], pts[i + 1])
            val penalty = if (speedMs > 3 && Geo.angleDiff(segBearing, heading) > 100) 30.0 else 0.0
            if (d + penalty < bestD) { bestD = d + penalty; best = i; bestT = t }
            i++
        }
        // Lost completely (e.g. after a long GPS gap)? Search the whole route.
        if (bestD > 200) {
            for (j in 0 until pts.size - 1) {
                val (d, t) = distanceToSegment(p, pts[j], pts[j + 1])
                if (d < bestD) { bestD = d; best = j; bestT = t }
            }
        }
        segIndex = best
        val a = pts[best]; val b = pts[best + 1]
        val snapped = Geo.lerp(a, b, bestT)
        val offset = Geo.distance(p, snapped)
        val along = (cum[best] + (cum[best + 1] - cum[best]) * bestT).coerceAtLeast(lastAlong - 30)
        lastAlong = along
        val routeBearing = Geo.bearing(a, b)

        val ann = route.announced
        var nextIndex = ann.indexOfFirst { it.distanceFromStart > along + 3 }
        val distanceToNext = if (nextIndex >= 0) ann[nextIndex].distanceFromStart - along else 0.0
        val remaining = (route.length - along).coerceAtLeast(0.0)

        // Off route: well away from the line for 3 fixes in a row, or driving the wrong way.
        val limit = 35.0 + accuracy.coerceAtMost(40.0)
        val wrongWay = speedMs > 4 && Geo.angleDiff(routeBearing, heading) > 120
        offCount = if (offset > limit || wrongWay) offCount + 1 else 0
        val offRoute = offCount >= 3

        val arrived = remaining < 25 || (nextIndex < 0 && remaining < 60)
        if (arrived) nextIndex = -1
        return Progress(snapped, routeBearing, offset, along, nextIndex, distanceToNext, remaining, offRoute, arrived)
    }

    /** Distance from p to segment a-b (metres) and how far along it (0..1). */
    private fun distanceToSegment(p: Pt, a: Pt, b: Pt): Pair<Double, Double> {
        val lat0 = Math.toRadians(p.lat)
        val kx = 111_320.0 * Math.cos(lat0); val ky = 110_540.0
        val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx; val y = ay + t * dy
        return Math.sqrt(x * x + y * y) to t
    }
}

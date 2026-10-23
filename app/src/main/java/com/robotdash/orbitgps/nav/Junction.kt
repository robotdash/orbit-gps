package com.robotdash.orbitgps.nav

import com.robotdash.orbitgps.voice.ManeuverType
import com.robotdash.orbitgps.voice.Side
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Which Garmin-style junction picture to draw for a step. */
enum class JunctionKind { HIGHWAY_EXIT, FORK, LANE_TURN, INTERSECTION, ROUNDABOUT }

/** One lane from the router: its arrows ("left", "straight", …) and whether our route can use it. */
data class LaneInfo(val indications: List<String>, val valid: Boolean)

/** Green sign text from OSRM `destinations` / `exits`. */
data class Sign(val refs: List<String>, val names: List<String>, val exit: String?) {
    fun isEmpty() = refs.isEmpty() && names.isEmpty() && exit == null

    companion object {
        /**
         * "YH 16, BC 37: Prince Rupert, Kitimat" → refs [YH 16, BC 37], names [Prince Rupert, Kitimat].
         * Without a colon, entries that look like route numbers become refs and the rest names.
         */
        fun parse(destinations: String, exits: String?): Sign? {
            val d = destinations.trim()
            fun split(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val refs: List<String>
            val names: List<String>
            if (d.contains(':')) {
                val (r, n) = split(d.substringBefore(':')).partition { looksLikeRef(it) }
                refs = r
                names = (split(d.substringAfter(':')) + n).distinct()
            } else {
                val (r, n) = split(d).partition { looksLikeRef(it) }
                refs = r
                names = n
            }
            val exit = exits?.trim()?.removePrefix("EXIT")?.removePrefix("Exit")?.trim()?.takeIf { it.isNotEmpty() }
            return Sign(refs.take(2), names.take(2), exit).takeUnless { it.isEmpty() }
        }

        private fun looksLikeRef(s: String) =
            Regex("""^(?:[A-Z]{1,4}\s?)?\d{1,3}[A-Z]?(?:\s+(North|South|East|West|N|S|E|W))?$""").matches(s)
    }
}

/** An intersection on the route: every road's bearing, which one we come in on and leave by. */
data class Crossing(
    val pointIndex: Int,
    val bearings: List<Int>,
    val inIdx: Int,
    val outIdx: Int,
    val entry: List<Boolean>,
)

/** A roundabout exit, as an angle around the circle (0 = where we enter, at the bottom). */
data class RoundaboutExit(val angle: Double, val counted: Boolean)

data class RoundaboutInfo(
    val exits: List<RoundaboutExit>,
    /** Index into [exits] of the one we take. */
    val takeIndex: Int,
    /** Exit number the router announced, if any. */
    val exitNumber: Int?,
    /** True when traffic goes clockwise (left-hand traffic). */
    val leftHand: Boolean,
)

data class Junction(
    val kind: JunctionKind,
    val side: Side?,
    val lanes: List<LaneInfo>?,
    val sign: Sign?,
    val street: String,
    val roundabout: RoundaboutInfo? = null,
)

/** Builds [Junction]s from the router's step data. */
object Junctions {

    /** Lanes at the start of a step (≥ 2 lanes), from its first intersection. */
    fun lanes(inters: JSONArray?): List<LaneInfo>? {
        val arr = inters?.optJSONObject(0)?.optJSONArray("lanes") ?: return null
        val out = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            val ind = l.optJSONArray("indications")
            LaneInfo((0 until (ind?.length() ?: 0)).map { ind!!.getString(it) }, l.optBoolean("valid", false))
        }
        return out.takeIf { it.size >= 2 }
    }

    fun crossing(x: JSONObject, pointIndex: Int): Crossing? {
        val b = x.optJSONArray("bearings") ?: return null
        val entry = x.optJSONArray("entry")
        val bearings = (0 until b.length()).map { b.getInt(it) }
        return Crossing(
            pointIndex, bearings, x.optInt("in", -1), x.optInt("out", -1),
            bearings.indices.map { entry?.optBoolean(it, true) ?: true },
        )
    }

    /**
     * Lay out a roundabout's exits around a circle fitted to the route's path through it.
     * Travel direction comes from the path itself (Valhalla's driving_side says "left" in
     * Canada). Returns null if the shape doesn't look like a roundabout.
     */
    fun roundabout(step: JSONObject, exitStep: JSONObject?, exitNumber: Int?, ringPts: List<Pt>): RoundaboutInfo? {
        if (ringPts.size < 3) return null
        val inters = step.optJSONArray("intersections") ?: return null
        val exitX = exitStep?.optJSONArray("intersections")?.optJSONObject(0) ?: return null

        fun loc(o: JSONObject): Pt? = o.optJSONArray("location")?.let { Pt(it.getDouble(1), it.getDouble(0)) }
        val entryPt = loc(inters.getJSONObject(0)) ?: ringPts.first()
        val exitPt = loc(exitX) ?: ringPts.last()

        // Which way round do we go? Sum of turning along the path: + = clockwise.
        var turn = 0.0
        for (i in 2 until ringPts.size) {
            val a = ringPts[i - 2]; val b = ringPts[i - 1]; val c = ringPts[i]
            if (Geo.distance(a, b) > 0.5 && Geo.distance(b, c) > 0.5) {
                turn += Geo.normalize(Geo.bearing(b, c) - Geo.bearing(a, b))
            }
        }
        val clockwise = turn > 0
        val centre = fitCentre(ringPts) ?: return null
        val entryBearing = Geo.bearing(centre, entryPt)

        /** Angle around the circle with our entry at 180 (the bottom of the picture). */
        fun place(p: Pt) = Geo.normalize(Geo.bearing(centre, p) - entryBearing + 180)
        /** How far along the direction of travel an exit at angle [a] is. */
        fun travel(a: Double): Double {
            val t = if (clockwise) 180 + a else 180 - a
            return ((t % 360) + 360) % 360
        }

        val found = mutableListOf<RoundaboutExit>()
        for (k in 1 until inters.length()) {
            val x = inters.getJSONObject(k)
            val bearings = x.optJSONArray("bearings") ?: continue
            val entry = x.optJSONArray("entry")
            val inI = x.optInt("in", -1)
            val outI = x.optInt("out", -1)
            val p = loc(x) ?: continue
            var counted = false
            var any = false
            for (i in 0 until bearings.length()) {
                if (i == inI || i == outI) continue
                if (entry == null || entry.optBoolean(i, true)) counted = true
                any = true
            }
            if (any) found += RoundaboutExit(place(p), counted)
        }
        found += RoundaboutExit(place(exitPt), true)
        val ours = found.last()

        // Sort along the direction of travel, drop anything at our own entry, merge near-duplicates.
        val merged = mutableListOf<RoundaboutExit>()
        for (e in found.sortedBy { travel(it.angle) }) {
            val isOurs = e === ours
            if (!isOurs && abs(Geo.normalize(e.angle - 180)) < 25) continue
            val last = merged.lastOrNull()
            if (last != null && abs(Geo.normalize(e.angle - last.angle)) < 25) {
                merged[merged.size - 1] = if (isOurs) e else last.copy(counted = last.counted || e.counted)
            } else {
                merged += e
            }
        }
        val take = merged.indexOfFirst { abs(Geo.normalize(it.angle - ours.angle)) < 1e-6 }
        if (take < 0 || merged.size > 10) return null
        return RoundaboutInfo(merged, take, exitNumber, clockwise)
    }

    /** Least-squares circle (Kasa fit) through the points; null unless the radius is 4–250 m. */
    private fun fitCentre(pts: List<Pt>): Pt? {
        val o = pts[0]
        val mx = cos(Math.toRadians(o.lat)) * 111_320.0
        val xs = pts.map { (it.lon - o.lon) * mx }
        val ys = pts.map { (it.lat - o.lat) * 110_540.0 }
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        for (i in pts.indices) {
            val x = xs[i]; val y = ys[i]; val z = x * x + y * y
            sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y
            sxz += x * z; syz += y * z; sz += z
        }
        val m = arrayOf(
            doubleArrayOf(sxx, sxy, sx, -sxz),
            doubleArrayOf(sxy, syy, sy, -syz),
            doubleArrayOf(sx, sy, pts.size.toDouble(), -sz),
        )
        // Gauss–Jordan with partial pivoting.
        for (col in 0 until 3) {
            val piv = (col until 3).maxBy { abs(m[it][col]) }
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            if (abs(m[col][col]) < 1e-9) return null
            for (r in 0 until 3) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (c in col until 4) m[r][c] -= m[col][c] * f
            }
        }
        val a = m[0][3] / m[0][0]
        val b = m[1][3] / m[1][1]
        val cx = -a / 2
        val cy = -b / 2
        val r = sqrt(xs.indices.map { (xs[it] - cx) * (xs[it] - cx) + (ys[it] - cy) * (ys[it] - cy) }.average())
        if (r < 4 || r > 250) return null
        return Pt(o.lat + cy / 110_540.0, o.lon + cx / mx)
    }

    /** Number of clearly different directions at a crossing (roads within [mergeDeg] count once). */
    fun distinctDirections(c: Crossing, mergeDeg: Double = 22.0): Int {
        val s = c.bearings.map { it.toDouble() }.sorted()
        if (s.isEmpty()) return 0
        var n = 1
        for (i in 1 until s.size) if (s[i] - s[i - 1] > mergeDeg) n++
        if (n > 1 && 360 - s.last() + s.first() <= mergeDeg) n--
        return n
    }

    /** Decide which picture (if any) a step gets. */
    fun choose(
        type: ManeuverType,
        side: Side?,
        lanes: List<LaneInfo>?,
        sign: Sign?,
        street: String,
        crossing: Crossing?,
        nextGap: Double?,
        nextIsArrive: Boolean,
        roundabout: RoundaboutInfo?,
    ): Junction? {
        // Lanes only help when some can be used and some can't.
        val useful = lanes?.takeIf { l -> l.size >= 2 && l.any { it.valid } && l.any { !it.valid } }
        return when (type) {
            ManeuverType.ROUNDABOUT -> roundabout?.let { Junction(JunctionKind.ROUNDABOUT, side, null, null, street, it) }
            ManeuverType.EXIT -> Junction(JunctionKind.HIGHWAY_EXIT, side ?: Side.RIGHT, useful, sign, street)
            ManeuverType.KEEP, ManeuverType.STAY -> when {
                useful != null || sign != null -> Junction(JunctionKind.FORK, side ?: Side.RIGHT, useful, sign, street)
                crossing != null -> Junction(JunctionKind.INTERSECTION, side, null, null, street)
                else -> null
            }
            ManeuverType.ARRIVE, ManeuverType.FERRY, ManeuverType.MERGE,
            ManeuverType.MERGE_HIGHWAY, ManeuverType.UTURN -> null
            else -> when {
                useful != null && useful.size >= 2 -> Junction(JunctionKind.LANE_TURN, side, useful, sign, street)
                crossing != null && unusual(type, crossing, nextGap, nextIsArrive) ->
                    Junction(JunctionKind.INTERSECTION, side, null, null, street)
                else -> null
            }
        }
    }

    /**
     * Worth a top-down picture: turn-then-turn within 150 m, a sharp turn, 5+ roads, or
     * another road leaving within 35° of ours (easy to take the wrong one).
     */
    private fun unusual(type: ManeuverType, c: Crossing, nextGap: Double?, nextIsArrive: Boolean): Boolean {
        if (nextGap != null && nextGap < 150 && !nextIsArrive) return true
        if (type == ManeuverType.SHARP) return true
        if (distinctDirections(c) >= 5) return true
        if (c.outIdx in c.bearings.indices) {
            val out = c.bearings[c.outIdx]
            for (i in c.bearings.indices) {
                if (i != c.outIdx && i != c.inIdx && c.entry[i] && Geo.angleDiff(c.bearings[i].toDouble(), out.toDouble()) < 35) return true
            }
        }
        return false
    }
}

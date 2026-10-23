package com.robotdash.orbitgps.nav

import com.robotdash.orbitgps.voice.Lanes
import com.robotdash.orbitgps.voice.Maneuver
import com.robotdash.orbitgps.voice.ManeuverType
import com.robotdash.orbitgps.voice.Side
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** A point on the earth. */
data class Pt(val lat: Double, val lon: Double)

/**
 * One instruction along the route.
 * [maneuver] is null for steps Orbit doesn't announce (departing, "continue straight",
 * leaving a roundabout); the next announced step covers them.
 */
data class NavStep(
    val maneuver: Maneuver?,
    val instruction: String,
    val streetName: String,
    /** Index into [Route.points] where this manoeuvre happens. */
    val pointIndex: Int,
    /** Metres from the start of the route to this manoeuvre. */
    val distanceFromStart: Double,
    val icon: TurnIcon,
    /** What the junction picture should show for this step, if anything. */
    val junction: Junction? = null,
)

enum class TurnIcon { STRAIGHT, TURN, SLIGHT, SHARP, UTURN, FORK, ROUNDABOUT, MERGE, EXIT, ARRIVE, FERRY }

/**
 * "Take the second right" counting. Off for now: route data can't yet tell a real
 * side street from a driveway or parking-lot entrance, and a wrong count is worse
 * than a plain "turn right".
 */
const val COUNT_TURNS = false

class Route(
    val points: List<Pt>,
    /** Metres from start to each point. */
    val cumulative: DoubleArray,
    val steps: List<NavStep>,
    val durationSec: Double,
    /** Every intersection along the route (for junction pictures). */
    val crossings: List<Crossing> = emptyList(),
) {
    val length: Double get() = cumulative.last()

    /** Steps that get spoken / shown, in order. */
    val announced: List<NavStep> = steps.filter { it.maneuver != null }

    companion object {
        /** Parse a Valhalla route returned in OSRM format. */
        fun fromOsrmJson(json: String): Route {
            val root = JSONObject(json)
            val r = root.getJSONArray("routes").getJSONObject(0)
            // Build the full-detail route line from each step's own geometry (the
            // route-level geometry is a simplified overview). Remember where each step starts.
            val points = ArrayList<Pt>()
            val stepStart = ArrayList<Int>()
            val rawSteps = ArrayList<JSONObject>()
            val lastLegStep = ArrayList<Boolean>()
            val legs = r.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val js = legs.getJSONObject(l).getJSONArray("steps")
                for (s in 0 until js.length()) {
                    val step = js.getJSONObject(s)
                    val g = Polyline.decode(step.optString("geometry", ""), 6)
                    val start = if (points.isEmpty()) 0 else points.size - 1
                    stepStart += start
                    rawSteps += step
                    lastLegStep += (l == legs.length() - 1)
                    val skipFirst = points.isNotEmpty() && g.isNotEmpty() && Geo.distance(points.last(), g.first()) < 0.5
                    points.addAll(if (skipFirst) g.drop(1) else g)
                }
            }
            if (points.size < 2) points.addAll(Polyline.decode(r.getString("geometry"), 6))
            val cum = DoubleArray(points.size)
            for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.distance(points[i - 1], points[i])

            val parsed = rawSteps.mapIndexed { i, step ->
                val ns = parseStep(step, rawSteps.getOrNull(i - 1), stepStart.getOrNull(i - 1), stepStart[i].coerceAtMost(points.size - 1), cum)
                // An arrival before the last leg is a stop along the way.
                if (!lastLegStep[i] && ns.maneuver?.type == ManeuverType.ARRIVE)
                    ns.copy(maneuver = ns.maneuver.copy(waypoint = true)) else ns
            }

            // Intersections, placed on the nearest route point (searching forward within each step).
            val crossings = ArrayList<Crossing>()
            val firstCrossing = arrayOfNulls<Crossing>(rawSteps.size)
            rawSteps.forEachIndexed { i, step ->
                val xs = step.optJSONArray("intersections") ?: return@forEachIndexed
                val start = stepStart[i].coerceAtMost(points.size - 1)
                val end = (stepStart.getOrNull(i + 1) ?: (points.size - 1)).coerceIn(start, points.size - 1)
                var cursor = start
                for (k in 0 until xs.length()) {
                    val x = xs.getJSONObject(k)
                    val loc = x.optJSONArray("location") ?: continue
                    val p = Pt(loc.getDouble(1), loc.getDouble(0))
                    var best = cursor
                    var bestD = Double.MAX_VALUE
                    for (j in cursor..end) {
                        val d = Geo.distance(points[j], p)
                        if (d < bestD) { bestD = d; best = j }
                    }
                    cursor = best
                    val c = Junctions.crossing(x, if (k == 0) start else cursor) ?: continue
                    crossings += c
                    if (k == 0) firstCrossing[i] = c
                }
            }

            // Junction pictures for announced steps (not right at the start).
            val announcedIdx = parsed.indices.filter { parsed[it].maneuver != null }
            val steps = parsed.mapIndexed { i, ns ->
                val m = ns.maneuver
                if (m == null || ns.distanceFromStart < 60) return@mapIndexed ns
                val st = rawSteps[i]
                val next = announcedIdx.firstOrNull { it > i }?.let { parsed[it] }
                val dest = st.optString("destinations", "").takeUnless { it.isBlank() }
                val exits = st.optString("exits", "").takeUnless { it.isBlank() }
                val sign = dest?.let { Sign.parse(it, exits) } ?: exits?.let { Sign.parse("", it) }
                val ring = if (m.type == ManeuverType.ROUNDABOUT) {
                    val from = stepStart[i].coerceAtMost(points.size - 1)
                    val to = (stepStart.getOrNull(i + 1) ?: (points.size - 1)) + 1
                    Junctions.roundabout(st, rawSteps.getOrNull(i + 1), m.exitNumber,
                        points.subList(from, to.coerceIn(from + 1, points.size)))
                } else null
                val road = ns.streetName.ifBlank { st.optString("ref", "") }
                val j = runCatching {
                    Junctions.choose(
                        m.type, m.side, Junctions.lanes(st.optJSONArray("intersections")), sign, road,
                        firstCrossing[i], next?.let { it.distanceFromStart - ns.distanceFromStart },
                        next?.maneuver?.type == ManeuverType.ARRIVE, ring,
                    )
                }.getOrNull()
                if (j != null) ns.copy(junction = j) else ns
            }
            return Route(points, cum, steps, r.optDouble("duration", 0.0), crossings)
        }

        private fun parseStep(step: JSONObject, prev: JSONObject?, prevStart: Int?, idx: Int, cum: DoubleArray): NavStep {
            val m = step.getJSONObject("maneuver")
            val type = m.getString("type")
            val mod = m.optString("modifier", "")
            val name = step.optString("name", "")
            val inters = step.optJSONArray("intersections")
            val side = when {
                mod.contains("right") -> Side.RIGHT
                mod.contains("left") -> Side.LEFT
                else -> null
            }
            val lanes = lanesAt(inters)
            val mode = step.optString("mode", "driving")

            var icon = TurnIcon.STRAIGHT
            val man: Maneuver? = when {
                mode == "ferry" && type != "arrive" -> { icon = TurnIcon.FERRY; Maneuver(ManeuverType.FERRY) }
                type == "depart" -> null
                type == "arrive" -> { icon = TurnIcon.ARRIVE; Maneuver(ManeuverType.ARRIVE, side) }
                type == "roundabout" || type == "rotary" || type == "roundabout turn" -> {
                    icon = TurnIcon.ROUNDABOUT
                    Maneuver(ManeuverType.ROUNDABOUT, side, exitNumber = m.optInt("exit", 0).takeIf { it > 0 })
                }
                type == "exit roundabout" || type == "exit rotary" -> null
                type == "fork" -> { icon = TurnIcon.FORK; Maneuver(ManeuverType.KEEP, side, lanes = lanes) }
                type == "off ramp" -> { icon = TurnIcon.EXIT; Maneuver(ManeuverType.EXIT, side, lanes = lanes) }
                type == "on ramp" -> {
                    icon = TurnIcon.SLIGHT
                    if (mod.contains("slight") || mod == "straight" || side == null)
                        Maneuver(ManeuverType.MERGE_HIGHWAY, side)
                    else Maneuver(ManeuverType.RAMP, side, lanes = lanes)
                }
                type == "merge" -> { icon = TurnIcon.MERGE; Maneuver(if (side != null) ManeuverType.MERGE else ManeuverType.MERGE_HIGHWAY, side) }
                type == "end of road" && side != null -> { icon = TurnIcon.TURN; Maneuver(ManeuverType.END_OF_ROAD, side, lanes = lanes) }
                mod == "uturn" -> { icon = TurnIcon.UTURN; Maneuver(ManeuverType.UTURN) }
                // "continue"/"new name" going straight: nothing to say
                mod == "straight" || mod.isEmpty() -> null
                (type == "continue" || type == "new name") && mod.startsWith("slight") -> {
                    // road bends / splits slightly: "keep right" is the useful prompt
                    icon = TurnIcon.FORK; Maneuver(ManeuverType.STAY, side, lanes = lanes)
                }
                mod.startsWith("slight") -> { icon = TurnIcon.SLIGHT; Maneuver(ManeuverType.BEAR, side, lanes = lanes) }
                mod.startsWith("sharp") -> { icon = TurnIcon.SHARP; Maneuver(ManeuverType.SHARP, side, lanes = lanes) }
                side != null -> {
                    icon = TurnIcon.TURN
                    Maneuver(ManeuverType.TURN, side,
                        turnCount = if (COUNT_TURNS) turnCount(prev, prevStart, side, cum, idx) else null, lanes = lanes)
                }
                else -> null
            }
            return NavStep(man, m.optString("instruction", ""), name, idx, cum[idx.coerceIn(0, cum.size - 1)], icon)
        }

        /** Lanes at the manoeuvre point, left to right. */
        private fun lanesAt(inters: JSONArray?): Lanes? {
            val first = inters?.optJSONObject(0) ?: return null
            val arr = first.optJSONArray("lanes") ?: return null
            val valid = (0 until arr.length()).map { arr.getJSONObject(it).optBoolean("valid", false) }
            return if (valid.size >= 2) Lanes(valid) else null
        }

        /**
         * "Take the second right": counts roads branching off on the same side shortly
         * before this turn. Conservative — only used when those roads are close (within
         * 150 m) so the count is easy to follow; otherwise just "turn right".
         */
        private fun turnCount(prev: JSONObject?, prevStart: Int?, side: Side, cum: DoubleArray, turnIdx: Int): Int? {
            val inters = prev?.optJSONArray("intersections") ?: return null
            prevStart ?: return null
            val turnAt = cum[turnIdx.coerceIn(0, cum.size - 1)]
            // Intersection positions: distance from the previous step's start, measured on
            // the previous step's own line.
            val prevGeom = Polyline.decode(prev.optString("geometry", ""), 6)
            var count = 0
            // skip index 0: that's where the previous manoeuvre itself happened
            for (i in 1 until inters.length()) {
                val x = inters.getJSONObject(i)
                val locA = x.optJSONArray("location") ?: continue
                val at = Pt(locA.getDouble(1), locA.getDouble(0))
                val k = prevGeom.indices.minByOrNull { Geo.distance(prevGeom[it], at) } ?: continue
                val gi = (prevStart + k).coerceAtMost(cum.size - 1)
                val before = turnAt - cum[gi]
                if (before <= 5 || before > 150) continue
                val bearings = x.optJSONArray("bearings") ?: continue
                val inIdx = x.optInt("in", -1)
                val outIdx = x.optInt("out", -1)
                if (inIdx < 0 || outIdx < 0) continue
                val heading = bearings.getInt(outIdx).toDouble()
                // Count every road you'd see on that side (even one-ways you can't enter),
                // because that's how a driver counts "the second right".
                for (b in 0 until bearings.length()) {
                    if (b == inIdx || b == outIdx) continue
                    val rel = Geo.normalize(bearings.getInt(b) - heading)
                    val sameSide = if (side == Side.RIGHT) rel in 30.0..150.0 else rel in -150.0..-30.0
                    if (sameSide) { count++; break }
                }
            }
            return if (count in 1..3) count + 1 else null
        }

    }
}

object Geo {
    private const val R = 6_371_000.0
    fun distance(a: Pt, b: Pt): Double {
        val lat = Math.toRadians((a.lat + b.lat) / 2)
        val x = Math.toRadians(b.lon - a.lon) * cos(lat)
        val y = Math.toRadians(b.lat - a.lat)
        return sqrt(x * x + y * y) * R
    }

    /** Initial bearing a→b in degrees 0..360. */
    fun bearing(a: Pt, b: Pt): Double {
        val lat = Math.toRadians((a.lat + b.lat) / 2)
        val x = Math.toRadians(b.lon - a.lon) * cos(lat)
        val y = Math.toRadians(b.lat - a.lat)
        return (Math.toDegrees(atan2(x, y)) + 360) % 360
    }

    /** Angle to -180..180. */
    fun normalize(deg: Double): Double {
        var d = deg % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    fun angleDiff(a: Double, b: Double) = abs(normalize(a - b))

    /** Point a fraction t of the way from a to b. */
    fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}

object Polyline {
    fun decode(s: String, precision: Int): List<Pt> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<Pt>()
        var i = 0; var lat = 0L; var lon = 0L
        while (i < s.length) {
            var shift = 0; var result = 0L; var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
            shift = 0; result = 0L
            do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
            out += Pt(lat / factor, lon / factor)
        }
        return out
    }
}

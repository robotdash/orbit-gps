package com.robotdash.orbitgps.voice

/**
 * Garmin-style voice guidance.
 *
 * Turns "what's the next manoeuvre and how far away is it" into a list of voice
 * clip ids (files in assets/voice/<id>.ogg), e.g.
 *   ["in_500_m", "use_right_2_lanes", "to_turn_right"]
 *
 * Each manoeuvre is announced up to three times:
 *   EARLY   – a heads-up well before the turn ("In 2 kilometres, keep right.")
 *   PREPARE – get ready, with lane guidance ("In 300 metres, use the right two lanes to turn right.")
 *   ACTION  – at the turn ("Now turn right, then take the second left.")
 * Distances scale with speed so highway exits get much earlier warnings.
 *
 * The engine knows nothing about maps or routing; the navigation layer feeds it
 * [Maneuver]s built from the route.
 */

enum class Side { LEFT, RIGHT }

enum class ManeuverType {
    TURN,          // ordinary turn
    BEAR,          // slight turn
    SHARP,         // sharp turn
    KEEP,          // fork / keep left-right
    STAY,          // stay left/right to remain on the road (lanes peel off the other side)
    EXIT,          // leave highway via exit
    RAMP,          // take an on-ramp
    MERGE,         // merge left/right
    MERGE_HIGHWAY, // merge onto the highway
    ROUNDABOUT,    // roundabout; use [Maneuver.exitNumber]
    UTURN,
    STRAIGHT,      // straight through an intersection
    END_OF_ROAD,   // T-junction: at the end of the road turn left/right
    FERRY,
    ARRIVE,
}

/**
 * Lanes at the manoeuvre, listed left to right; true = lane is good for the route.
 * Example: 4 lanes, right two good -> [false, false, true, true]
 */
data class Lanes(val valid: List<Boolean>)

data class Maneuver(
    val type: ManeuverType,
    val side: Side? = null,
    /** "Take the second right" — count of the right/left turn, if the router says so. */
    val turnCount: Int? = null,
    /** Roundabout exit number (1-5). */
    val exitNumber: Int? = null,
    val lanes: Lanes? = null,
    /** Highway exit/keep where we want to remain on the highway. */
    val staysOnHighway: Boolean = false,
    /** An ARRIVE that is a stop along the way, not the final destination. */
    val waypoint: Boolean = false,
)

enum class Stage { EARLY, PREPARE, ACTION }

/** Distances (metres) at which each stage is spoken, for one speed band. */
data class StageDistances(val early: Double, val prepare: Double, val action: Double)

object PromptEngine {

    /** Distance clips we have recordings for, in metres. */
    private val DISTANCE_CLIPS = listOf(
        3000 to "in_3_km", 2000 to "in_2_km", 1500 to "in_1_5_km", 1000 to "in_1_km",
        800 to "in_800_m", 600 to "in_600_m", 500 to "in_500_m", 400 to "in_400_m",
        300 to "in_300_m", 200 to "in_200_m", 150 to "in_150_m", 100 to "in_100_m",
        50 to "in_50_m",
    )

    /**
     * Recorded "Continue on this road for N km" distances (keep in step with
     * CONTINUE_KM in tools/make_clips.py).
     */
    val CONTINUE_KM = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 12.0, 15.0,
        20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 120.0, 150.0, 200.0)

    /** How close the following manoeuvre must be to say "then …". */
    fun thenThreshold(speedKmh: Double): Double = when {
        speedKmh < 60 -> 150.0
        speedKmh < 90 -> 250.0
        else -> 400.0
    }

    /** 1.0 normal; 1.3 = "earlier warnings" setting. Scales the early and prepare prompts. */
    @Volatile var warningScale: Double = 1.0

    /** Announcement distances by speed band. Highway gets ~2 km of warning. */
    fun stageDistances(speedKmh: Double): StageDistances = baseDistances(speedKmh).let {
        it.copy(early = it.early * warningScale, prepare = it.prepare * warningScale)
    }

    private fun baseDistances(speedKmh: Double): StageDistances = when {
        speedKmh < 40 -> StageDistances(early = 300.0, prepare = 100.0, action = 25.0)
        speedKmh < 60 -> StageDistances(early = 500.0, prepare = 200.0, action = 40.0)
        speedKmh < 90 -> StageDistances(early = 1000.0, prepare = 400.0, action = 80.0)
        else -> StageDistances(early = 2000.0, prepare = 800.0, action = 200.0)
    }

    /** Nearest recorded distance phrase, e.g. 780 m -> "in_800_m". */
    fun distanceClip(metres: Double): String =
        DISTANCE_CLIPS.minBy { kotlin.math.abs(it.first - metres) }.second

    /**
     * "Continue on this road for N km", for long stretches after a turn, using the
     * recorded distance nearest the real one (1.6 km → "one and a half", 3.9 km → "four").
     * Null under about 1 km. Very long stretches say "Follow the road" instead of a
     * number that would be badly off.
     */
    fun continueClip(metres: Double): String? {
        val km = metres / 1000.0
        if (km < 0.95) return null
        if (km > 230.0) return "follow_road" // 15% past the longest recording
        // Nearest; on an exact tie, the shorter distance.
        val n = CONTINUE_KM.minWith(compareBy<Double> { kotlin.math.abs(it - km) }.thenBy { it })
        val id = if (n == Math.floor(n)) n.toInt().toString() else n.toString().replace('.', '_')
        return "continue_for_${id}_km"
    }

    // ---------------------------------------------------------------------

    /** The clip(s) describing the manoeuvre itself ("turn right", "take the second left"). */
    fun actionClips(m: Maneuver): List<String> {
        val s = m.side?.name?.lowercase() ?: "right"
        return when (m.type) {
            ManeuverType.TURN -> when (val n = m.turnCount) {
                null, 0 -> listOf("turn_$s")
                1 -> listOf("next_$s")
                in 2..4 -> listOf("take_${n}_$s")
                else -> listOf("turn_$s")
            }
            ManeuverType.BEAR -> listOf("bear_$s")
            ManeuverType.SHARP -> listOf("sharp_$s")
            ManeuverType.KEEP ->
                if (m.staysOnHighway) listOf("keep_$s", "to_stay_on_highway") else listOf("fork_$s")
            ManeuverType.STAY -> listOf("stay_$s")
            ManeuverType.EXIT -> listOf(if (m.side != null) "exit_$s" else "take_exit")
            ManeuverType.RAMP -> listOf("ramp_$s")
            ManeuverType.MERGE -> listOf("merge_$s")
            ManeuverType.MERGE_HIGHWAY -> listOf("merge_highway")
            ManeuverType.ROUNDABOUT -> {
                val e = m.exitNumber
                if (e != null && e in 1..5) listOf("roundabout_exit_$e") else listOf("enter_roundabout")
            }
            ManeuverType.UTURN -> listOf("u_turn")
            ManeuverType.STRAIGHT -> listOf("straight_through")
            ManeuverType.END_OF_ROAD -> listOf("end_of_road_$s")
            ManeuverType.FERRY -> listOf("board_ferry")
            ManeuverType.ARRIVE -> listOf(
                when (m.side) {
                    Side.LEFT -> "dest_ahead_left"
                    Side.RIGHT -> "dest_ahead_right"
                    null -> "dest_ahead"
                }
            )
        }
    }

    /**
     * Lane phrase, e.g. "use the right two lanes", or null when lanes don't help
     * (no data, or every lane is fine).
     */
    fun laneClip(lanes: Lanes?): String? {
        val v = lanes?.valid ?: return null
        if (v.isEmpty() || v.all { it } || v.none { it }) return null
        val n = v.size
        val first = v.indexOfFirst { it }
        val last = v.indexOfLast { it }
        val count = last - first + 1
        // Only describe a single contiguous block of good lanes.
        if (v.subList(first, last + 1).any { !it }) return null
        return when {
            last == n - 1 && count == 1 -> "use_right_lane"
            last == n - 1 && count == 2 -> "use_right_2_lanes"
            first == 0 && count == 1 -> "use_left_lane"
            first == 0 && count == 2 -> "use_left_2_lanes"
            first > 0 && last < n - 1 -> if (count == 1) "use_middle_lane" else "use_middle_lanes"
            else -> null
        }
    }

    /** "to turn right" style tail used after a lane phrase. */
    private fun laneTail(m: Maneuver): List<String>? {
        val s = m.side?.name?.lowercase()
        return when (m.type) {
            ManeuverType.TURN, ManeuverType.END_OF_ROAD ->
                if (s != null && (m.turnCount ?: 0) <= 1) listOf("to_turn_$s") else null
            ManeuverType.EXIT -> listOf("to_take_exit")
            ManeuverType.KEEP, ManeuverType.STAY ->
                if (m.staysOnHighway) listOf("to_stay_on_highway") else null
            else -> null
        }
    }

    /**
     * Build the full sentence for one stage.
     *
     * @param distance metres to this manoeuvre right now
     * @param next     the manoeuvre after this one, if any
     * @param gapToNext metres between this manoeuvre and [next]
     */
    fun build(
        stage: Stage,
        m: Maneuver,
        distance: Double,
        speedKmh: Double,
        next: Maneuver? = null,
        gapToNext: Double? = null,
    ): List<String> {
        val out = mutableListOf<String>()

        // Lead-in
        if (stage == Stage.ACTION) {
            if (m.type != ManeuverType.ARRIVE) out += "now"
        } else {
            out += distanceClip(distance)
        }

        // Arrival has its own wording at the final stage.
        if (m.type == ManeuverType.ARRIVE && stage == Stage.ACTION) {
            out += if (m.waypoint) "stop_reached" else when (m.side) {
                Side.LEFT -> "dest_on_left"
                Side.RIGHT -> "dest_on_right"
                null -> "arriving"
            }
            return out
        }

        // Lanes are given on PREPARE (and on EARLY for highway speeds, like Garmin).
        val lane = laneClip(m.lanes)
        val tail = laneTail(m)
        val giveLanes = lane != null && tail != null &&
            (stage == Stage.PREPARE || (stage == Stage.EARLY && speedKmh >= 90))
        if (giveLanes) {
            out += lane!!
            out += tail!!
        } else {
            out += actionClips(m)
        }

        // "…then take the second left" when the next manoeuvre comes quickly.
        if (stage != Stage.EARLY && next != null && gapToNext != null &&
            gapToNext <= thenThreshold(speedKmh) && next.type != ManeuverType.ARRIVE
        ) {
            out += "then"
            out += actionClips(next)
        } else if (stage == Stage.ACTION && next?.type == ManeuverType.ARRIVE &&
            gapToNext != null && gapToNext <= thenThreshold(speedKmh)
        ) {
            out += "then"
            out += actionClips(next) // "…then, your destination is ahead, on the left."
        }
        return out
    }
}

/**
 * Tracks which stages have been spoken for the current manoeuvre and decides
 * when to speak. Call [update] on every GPS fix.
 */
class GuidanceTracker(private val speak: (List<String>) -> Unit) {

    private var currentId: Int = -1
    private val spoken = mutableSetOf<Stage>()
    private var distanceAtStart = 0.0
    /** Manoeuvre already mentioned as "…then turn left" in the previous prompt. */
    private var preAnnouncedId: Int? = null

    /**
     * @param maneuverId changes whenever the route moves on to a new manoeuvre
     * @param distance   metres to the manoeuvre
     */
    fun update(
        maneuverId: Int,
        m: Maneuver,
        distance: Double,
        speedKmh: Double,
        next: Maneuver? = null,
        gapToNext: Double? = null,
    ) {
        if (maneuverId != currentId) {
            currentId = maneuverId
            spoken.clear()
            distanceAtStart = distance
            // Already told the driver "…then turn left": just give the final "now" prompt.
            if (preAnnouncedId == maneuverId) { spoken += Stage.EARLY; spoken += Stage.PREPARE }
            preAnnouncedId = null
            // Straight after a turn: on a long stretch, say how long to keep going.
            if (maneuverId > 0) PromptEngine.continueClip(distance)?.let {
                if (distance > PromptEngine.stageDistances(speedKmh).early * 1.5) speak(listOf(it))
            }
        }
        val d = PromptEngine.stageDistances(speedKmh)
        val stage = when {
            distance <= d.action -> Stage.ACTION
            distance <= d.prepare -> Stage.PREPARE
            distance <= d.early -> Stage.EARLY
            else -> null
        } ?: return
        if (stage in spoken) return
        // Skip the early warning when it would come too close to the prepare prompt
        // (short road segment, or already near) — PREPARE covers it.
        if (stage == Stage.EARLY && (distanceAtStart <= d.prepare + 150 || distance <= d.prepare + 150)) return
        spoken += stage
        // Once a later stage is said, earlier ones never fire.
        if (stage == Stage.PREPARE) spoken += Stage.EARLY
        if (stage == Stage.ACTION) { spoken += Stage.EARLY; spoken += Stage.PREPARE }
        val sentence = PromptEngine.build(stage, m, distance, speedKmh, next, gapToNext)
        if ("then" in sentence) preAnnouncedId = maneuverId + 1
        speak(sentence)
    }
}

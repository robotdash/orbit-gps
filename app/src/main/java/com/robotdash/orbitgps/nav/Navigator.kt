package com.robotdash.orbitgps.nav

import com.robotdash.orbitgps.voice.GuidanceTracker
import com.robotdash.orbitgps.voice.ManeuverType
import com.robotdash.orbitgps.voice.PromptEngine
import com.robotdash.orbitgps.voice.Side

/**
 * Runs one navigation session: feed it GPS fixes, it speaks prompts, tells the
 * screen what to show, and asks for a new route when you leave this one.
 *
 * Pure logic (no Android), so the whole drive can be simulated in unit tests.
 */
class Navigator(
    route: Route,
    private val speak: (List<String>) -> Unit,
    /** Called when off route; the app computes a new route from [Pt] with heading. */
    private val onOffRoute: (Pt, Double) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var route: Route = route
        private set
    private var tracker = RouteTracker(route)
    private val guidance = GuidanceTracker { speak(it) }
    private var generation = 0
    private var startedSpeaking = false
    private var rerouteAt = 0L
    /** Recalculations in a row without getting back onto a route. */
    private var rerouteTries = 0
    var arrived = false
        private set
    var lastProgress: RouteTracker.Progress? = null
        private set

    /** Swap in a fresh route after rerouting. */
    fun replaceRoute(newRoute: Route, announce: Boolean = true) {
        route = newRoute
        tracker = RouteTracker(newRoute)
        generation++
        rerouteAt = clock()   // give the new route a moment before judging "off route"
        // Only announce the first new route of an off-route spell, not every retry.
        if (announce && rerouteTries <= 1) speak(listOf("route_recalculated"))
    }

    companion object {
        /** About 7 km/h. */
        const val MOVING_MS = 2.0
    }

    fun update(p: Pt, heading: Double, speedMs: Double, accuracy: Double): RouteTracker.Progress? {
        if (arrived) return lastProgress
        val prog = tracker.update(p, heading, speedMs, accuracy)
        lastProgress = prog
        val kmh = speedMs * 3.6

        if (!startedSpeaking) {
            startedSpeaking = true
            speak(listOf(if (prog.offset > 60) "drive_to_route" else "route_start"))
        }

        if (prog.arrived) {
            arrived = true
            val side = route.announced.lastOrNull()?.maneuver?.takeIf { it.type == ManeuverType.ARRIVE }?.side
            speak(listOf(when (side) { Side.LEFT -> "dest_on_left"; Side.RIGHT -> "dest_on_right"; null -> "arrived" }))
            return prog
        }

        if (prog.offRoute) {
            // Stopped or creeping off the road (a gas station, a parking lot): wait until
            // the car is moving again instead of recalculating over and over.
            if (speedMs < MOVING_MS) return prog
            val now = clock()
            // 8 s, then 16, 32, 60 s if new routes still don't pick the car up.
            val wait = minOf(8_000L shl (rerouteTries - 1).coerceIn(0, 3), 60_000L)
            if (rerouteTries == 0 || now - rerouteAt > wait) {
                rerouteAt = now
                if (rerouteTries == 0) speak(listOf("recalculating"))
                rerouteTries++
                onOffRoute(p, heading)
            }
            return prog
        }
        rerouteTries = 0

        val ann = route.announced
        val i = prog.nextIndex
        if (i < 0) return prog
        val step = ann[i]
        val m = step.maneuver ?: return prog
        // The final "destination is on your left" is handled by arrival above.
        if (m.type == ManeuverType.ARRIVE && !m.waypoint &&
            prog.distanceToNext <= PromptEngine.stageDistances(kmh).action + 20) return prog
        val next = ann.getOrNull(i + 1)
        val gap = next?.let { it.distanceFromStart - step.distanceFromStart }
        guidance.update(generation * 10_000 + i, m, prog.distanceToNext, kmh, next?.maneuver, gap)
        return prog
    }
}

package com.robotdash.orbitgps

import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.PoseSmoother
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.Route
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class PoseSmootherTest {
    private val route = Route.fromOsrmJson(File("src/test/resources/route_terrace_town.json").readText())

    /** Real-world-ish GPS: fixes every 0.9–1.15 s, a few metres of wobble. */
    private fun run(kmh: Double, onRoute: Boolean): List<Pair<Long, PoseSmoother.Pose>> {
        val rnd = Random(7)
        val v = kmh / 3.6
        val s = PoseSmoother()
        val frames = mutableListOf<Pair<Long, PoseSmoother.Pose>>()
        var t = 0L
        var nextFix = 0L
        val end = ((route.length - 40) / v * 1000).toLong()
        while (t < end) {
            if (t >= nextFix) {
                val trueAlong = v * t / 1000.0
                val p = PoseSmoother.pointAt(route, trueAlong)
                val noisy = Pt(p.lat + rnd.nextDouble(-3.0, 3.0) / 111_320, p.lon + rnd.nextDouble(-3.0, 3.0) / 64_000)
                // On route the app snaps to the line and knows "along" (with a little error).
                if (onRoute) s.onFix(p, 0.0, v, t, route, trueAlong + rnd.nextDouble(-2.0, 2.0))
                else {
                    val ahead = PoseSmoother.pointAt(route, trueAlong + 10)
                    s.onFix(noisy, Geo.bearing(p, ahead), v, t)
                }
                nextFix = t + rnd.nextLong(900, 1150)
            }
            frames += t to s.at(t)
            t += 16
        }
        return frames
    }

    private fun check(name: String, frames: List<Pair<Long, PoseSmoother.Pose>>, kmh: Double) {
        val steps = frames.zipWithNext { (_, a), (_, b) -> Geo.distance(Pt(a.lat, a.lon), Pt(b.lat, b.lon)) }
        val expected = kmh / 3.6 * 0.016
        val maxStep = steps.drop(70).maxOrNull()!!
        val turns = frames.zipWithNext { (_, a), (_, b) -> Geo.angleDiff(a.bearing, b.bearing) }.drop(70).maxOrNull()!!
        println("$name: per-frame step expected ${"%.2f".format(expected)} m, max ${"%.2f".format(maxStep)} m; max turn per frame ${"%.1f".format(turns)}°")
        // A raw 1 Hz update would jump ${kmh/3.6} m at once; smooth movement never jumps more than a few frames' worth.
        assertTrue("$name jumped ${maxStep} m in one frame", maxStep < expected * 4 + 0.3)
    }

    @Test fun smoothOnRoute40() = check("on route 40 km/h", run(40.0, true), 40.0)
    @Test fun smoothOnRoute90() = check("on route 90 km/h", run(90.0, true), 90.0)
    @Test fun smoothOffRoute50() = check("off route 50 km/h, GPS wobble", run(50.0, false), 50.0)

    @Test fun stoppedStaysPut() {
        val s = PoseSmoother()
        val p = Pt(54.5163, -128.6035)
        s.onFix(p, 90.0, 0.0, 0)
        s.onFix(p, 90.0, 0.0, 1000)
        val a = s.at(1500); val b = s.at(2500)
        assertTrue(Geo.distance(Pt(a.lat, a.lon), Pt(b.lat, b.lon)) < 0.01)
    }

    @Test fun bigJumpGoesStraightThere() {
        val s = PoseSmoother()
        s.onFix(Pt(54.5163, -128.6035), 0.0, 10.0, 0)
        val far = Pt(54.5263, -128.6035) // ~1.1 km away (reroute / GPS gap)
        s.onFix(far, 0.0, 10.0, 1000)
        val now = s.at(1000)
        assertTrue(Geo.distance(Pt(now.lat, now.lon), far) < 1.0)
    }
}

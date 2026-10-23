package com.robotdash.orbitgps

import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.Navigator
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NavigatorTest {

    private fun load(name: String) = Route.fromOsrmJson(File("src/test/resources/$name").readText())
    private val clips = File("src/main/assets/voice").listFiles()!!.map { it.nameWithoutExtension }.toSet()

    /** Drive along the route at [kmh], one GPS fix per second; returns what was said (with metres along). */
    private fun drive(route: Route, kmh: Double, nav: (Route, MutableList<Pair<Double, List<String>>>) -> Navigator? = { _, _ -> null }): List<Pair<Double, List<String>>> {
        val said = mutableListOf<Pair<Double, List<String>>>()
        var along = 0.0
        val n = nav(route, said) ?: run {
            var cur = 0.0
            Navigator(route, { said += cur to it }, { _, _ -> }).also { navi ->
                val step = kmh / 3.6
                while (along <= route.length + 5 && !navi.arrived) {
                    val (p, b) = pointAt(route, along)
                    cur = along
                    navi.update(p, b, step, 5.0)
                    along += step
                }
            }
        }
        return said
    }

    private fun pointAt(route: Route, d: Double): Pair<Pt, Double> {
        val c = route.cumulative
        val found = c.indexOfFirst { it >= d }
        val i = when { found < 0 -> c.size - 1; found == 0 -> 1; else -> found }
        val a = route.points[i - 1]; val b = route.points[i]
        val t = if (c[i] == c[i - 1]) 0.0 else ((d - c[i - 1]) / (c[i] - c[i - 1])).coerceIn(0.0, 1.0)
        return Geo.lerp(a, b, t) to Geo.bearing(a, b)
    }

    private fun print(title: String, said: List<Pair<Double, List<String>>>) {
        println("== $title")
        said.forEach { (d, s) -> println("  %6.0f m: %s".format(d, s.joinToString(" "))) }
    }

    @Test fun townDrive() {
        val r = load("route_terrace_town.json")
        println("steps: " + r.steps.map { "${it.icon}/${it.maneuver?.type}/${it.maneuver?.side}/${it.maneuver?.turnCount}" })
        val said = drive(r, 40.0)
        print("Terrace town @40", said)
        assertEquals("route_start", said.first().second.first())
        assertEquals("dest_on_left", said.last().second.last())
        assertTrue(said.any { it.second.contains("now") })
        val missing = said.flatMap { it.second }.toSet() - clips
        assertTrue("missing clips $missing", missing.isEmpty())
    }

    @Test fun highwayDrive() {
        val r = load("route_terrace_kitimat.json")
        val said = drive(r, 90.0)
        print("Terrace -> Kitimat @90", said)
        assertTrue(said.size >= 4)
        // A highway turn gets its early warning about a kilometre out.
        assertTrue(said.any { it.second.first() == "in_1_km" || it.second.first() == "in_800_m" })
        val missing = said.flatMap { it.second }.toSet() - clips
        assertTrue("missing clips $missing", missing.isEmpty())
    }

    @Test fun lanesRoute() {
        val r = load("route_pg_lanes.json")
        val said = drive(r, 70.0)
        print("Prince George @70", said)
        val missing = said.flatMap { it.second }.toSet() - clips
        assertTrue("missing clips $missing", missing.isEmpty())
    }

    @Test fun stopAlongTheWay() {
        val r = load("route_with_stop.json")
        val waypoints = r.announced.filter { it.maneuver?.waypoint == true }
        assertEquals(1, waypoints.size)
        val said = drive(r, 40.0)
        print("Terrace with a stop @40", said)
        val flat = said.map { it.second }
        // Orbit announces reaching the stop, keeps going, then arrives at the destination.
        val stopAt = flat.indexOfFirst { "stop_reached" in it }
        assertTrue("stop_reached never said", stopAt > 0)
        assertTrue("guidance should continue after the stop", flat.drop(stopAt + 1).any { it.firstOrNull() == "now" })
        assertEquals("dest_on_left", flat.last().last())
        val missing = flat.flatten().toSet() - clips
        assertTrue("missing clips $missing", missing.isEmpty())
    }

    @Test fun offRouteTriggersReroute() {
        val r = load("route_terrace_kitimat.json")
        var rerouted = 0
        val said = mutableListOf<List<String>>()
        var t = 0L
        val nav = Navigator(r, { said += it }, { _, _ -> rerouted++ }, clock = { t })
        // Drive 1 km on route, then veer 200 m off to the side.
        var d = 0.0
        while (d < 1000) { val (p, b) = pointAt(r, d); nav.update(p, b, 20.0, 5.0); d += 20; t += 1000 }
        val (p, b) = pointAt(r, 1000.0)
        repeat(5) { k -> nav.update(Pt(p.lat + 0.002 * (k + 1), p.lon), b, 20.0, 5.0); t += 1000 }
        assertEquals(1, rerouted)
        assertTrue(said.any { it == listOf("recalculating") })
    }

    /** Oct 18 drive: parked off the road (gas station), the app recalculated over and over. */
    @Test fun parkedOffRouteWaitsInsteadOfRecalculating() {
        val r = load("route_terrace_kitimat.json")
        var rerouted = 0
        val said = mutableListOf<List<String>>()
        var t = 0L
        val nav = Navigator(r, { said += it }, { _, _ -> rerouted++ }, clock = { t })
        val (p, b) = pointAt(r, 1000.0)
        var d = 0.0
        while (d < 1000) { val (q, bb) = pointAt(r, d); nav.update(q, bb, 20.0, 5.0); d += 20; t += 1000 }
        // Pull 150 m off the road (sideways) and sit there for 2 minutes at walking pace or less.
        repeat(120) { nav.update(side(p, b, 150.0), b, 0.5, 8.0); t += 1000 }
        assertEquals(0, rerouted)
        assertTrue(said.none { it == listOf("recalculating") })
        // Drive off, still away from the route: now it recalculates once.
        repeat(5) { nav.update(side(p, b, 150.0 + it * 20), b + 90, 10.0, 8.0); t += 1000 }
        assertEquals(1, rerouted)
    }

    /** If new routes keep missing the car, back off (8, 16, 32, 60 s) and only say "recalculating" once. */
    @Test fun repeatedRecalculationBacksOff() {
        val r = load("route_terrace_kitimat.json")
        val times = mutableListOf<Long>()
        val said = mutableListOf<List<String>>()
        var t = 0L
        val nav = Navigator(r, { said += it }, { _, _ -> times += t }, clock = { t })
        val (p, b) = pointAt(r, 1000.0)
        var d = 0.0
        while (d < 1000) { val (q, bb) = pointAt(r, d); nav.update(q, bb, 20.0, 5.0); d += 20; t += 1000 }
        repeat(180) { nav.update(side(p, b, 300.0), b, 15.0, 5.0); t += 1000 }
        val gaps = times.zipWithNext { a, c -> (c - a) / 1000 }
        assertTrue("gaps $gaps", gaps.take(4).zip(listOf(9L, 17L, 33L, 61L)).all { (g, want) -> g in want - 1..want })
        assertEquals(1, said.count { it == listOf("recalculating") })
    }

    /** [m] metres to the right of a road heading [bearing] at [p]. */
    private fun side(p: Pt, bearing: Double, m: Double): Pt {
        val r = Math.toRadians(bearing + 90)
        return Pt(p.lat + m * Math.cos(r) / 110_540.0, p.lon + m * Math.sin(r) / (111_320.0 * Math.cos(Math.toRadians(p.lat))))
    }
}

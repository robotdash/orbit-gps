package com.robotdash.orbitgps

import android.location.Location
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.NavStep
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.Route
import com.robotdash.orbitgps.nav.TurnIcon
import com.robotdash.orbitgps.voice.Maneuver
import com.robotdash.orbitgps.voice.ManeuverType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Oct 18: during a demo drive (phone at home), "Go here instead" for a fuel stop
 * switched to real GPS, so the car jumped to the house and kept recalculating.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DemoDriveTest {

    private val home = Pt(54.4400, -128.4800)          // where the phone really is
    private val start = Pt(54.5163, -128.6035)          // demo route
    private val end = Pt(54.5163, -128.5200)
    private val fuel = Pt(54.5400, -128.5600)
    private val stopPt = Pt(54.5000, -128.5500)

    /** Straight-line stand-in for the router: start → stops → destination. */
    private fun fakeRoute(from: Pt, via: List<Pt>, to: Pt): Route {
        val corners = listOf(from) + via + to
        val pts = ArrayList<Pt>()
        val arriveAt = ArrayList<Int>()
        for (k in 1 until corners.size) {
            val a = corners[k - 1]; val b = corners[k]
            val n = (Geo.distance(a, b) / 20).toInt().coerceAtLeast(1)
            for (i in (if (k == 1) 0 else 1)..n) pts += Geo.lerp(a, b, i.toDouble() / n)
            arriveAt += pts.size - 1
        }
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + Geo.distance(pts[i - 1], pts[i])
        val steps = listOf(NavStep(null, "Head out", "", 0, 0.0, TurnIcon.STRAIGHT)) +
            arriveAt.mapIndexed { k, i ->
                val last = k == arriveAt.size - 1
                NavStep(Maneuver(ManeuverType.ARRIVE, waypoint = !last), "Arrive", "", i, cum[i], TurnIcon.ARRIVE)
            }
        return Route(pts, cum, steps, cum.last() / 15)
    }

    private fun fix(p: Pt) = Location("gps").apply {
        latitude = p.lat; longitude = p.lon; bearing = 0f; speed = 0.5f; accuracy = 8f; time = System.currentTimeMillis()
    }

    private fun seconds(s: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(s))

    private fun carAt(): Pt = NavCore.lastShown!!.let { Pt(it.latitude, it.longitude) }

    @Before fun setUp() {
        NavCore.init(ApplicationProvider.getApplicationContext())
        NavCore.stop()
        NavCore.routeForTest = { from, via, to -> fakeRoute(from, via, to) }
    }

    @After fun tearDown() { NavCore.stop(); NavCore.routeForTest = null }

    @Test fun goInsteadKeepsTheDemoGoing() {
        NavCore.setPreviewForTest(fakeRoute(start, emptyList(), end), end, "Work")
        NavCore.start(simulate = true)
        seconds(20)
        NavCore.gpsChipFixForTest(fix(home))             // the phone's real GPS: ignored in a demo
        val before = carAt()
        assertTrue("demo car should be near the route, not at home", Geo.distance(before, home) > 5_000)

        NavCore.goInstead(fuel, "Fuel stop")
        NavCore.gpsChipFixForTest(fix(home))             // arrives while the new route is planned
        seconds(1)
        assertEquals(NavCore.Mode.NAVIGATING, NavCore.mode)
        assertTrue("still a demo drive", NavCore.simulating)
        // The new route starts where the demo car was, and the car keeps moving along it.
        val newRoute = NavCore.navigator!!.route
        assertTrue(Geo.distance(newRoute.points.first(), before) < 30)
        seconds(10)
        NavCore.gpsChipFixForTest(fix(home))
        val after = carAt()
        assertTrue("car moved on (${Geo.distance(before, after)} m)", Geo.distance(before, after) > 50)
        assertTrue("car is on the new route", NavCore.progress!!.offset < 5 && !NavCore.progress!!.offRoute)
        assertTrue(Geo.distance(after, home) > 5_000)
    }

    @Test fun addStopInDemoDoesNotJumpAhead() {
        NavCore.setPreviewForTest(fakeRoute(start, emptyList(), end), end, "Work")
        NavCore.start(simulate = true)
        seconds(40)
        val before = carAt()
        var ok = false
        NavCore.addStop(stopPt, "Stop") { ok = it }
        seconds(2)
        assertTrue(ok)
        val after = carAt()
        // Two demo ticks at most 27 m/s each: no leap down the new route.
        assertTrue("jumped ${Geo.distance(before, after)} m", Geo.distance(before, after) < 80)
        assertTrue(!NavCore.progress!!.offRoute)
    }

    /**
     * Oct 21: drove past a stop and was told to make a U-turn many km down the road.
     * Driving away from a stop we came close to now skips it (no route back to it)
     * and offers "Go back"; the default is to carry on to the destination.
     */
    @Test fun drivingPastAStopCarriesOnAndOffersGoBack() {
        val a = Pt(54.5163, -128.7000); val b = Pt(54.5163, -128.5000)      // a road running east
        val shop = Pt(54.5190, -128.6000)                                      // 300 m north of it
        val asked = ArrayList<List<Pt>>()
        val backToShopFrom = ArrayList<Pt>()   // routes planned back to the shop after passing it
        NavCore.routeForTest = { from, via, to ->
            asked += via
            if (via.isNotEmpty() && from.lon > shop.lon + 0.001) backToShopFrom += from
            fakeRoute(from, via, to)
        }
        NavCore.setPreviewForTest(fakeRoute(a, emptyList(), b), b, "Work")
        NavCore.start(simulate = false)
        fun drive(lon: Double) = NavCore.gpsChipFixForTest(Location("gps").apply {
            latitude = 54.5163; longitude = lon; bearing = 90f; speed = 22f; accuracy = 6f; time = System.currentTimeMillis()
        })
        drive(-128.6900); seconds(1)
        var ok = false
        NavCore.addStop(shop, "Shop") { ok = it }
        seconds(2); assertTrue(ok)
        assertEquals(1, NavCore.stops.size)
        // Keep driving east along the road, past the shop and on.
        var lon = -128.6880
        while (lon < -128.5700) { drive(lon); seconds(1); lon += 0.0006 }
        assertEquals("stop skipped", 0, NavCore.stops.size)
        assertTrue("planned a U-turn back to the shop from $backToShopFrom", backToShopFrom.isEmpty())
        assertEquals("Go back offered", "Shop", NavCore.passedStop?.name)
        assertTrue("last route asked for goes straight on, not back to the shop", asked.last().isEmpty())
        val r = NavCore.navigator!!.route
        assertTrue("route ends at the destination", Geo.distance(r.points.last(), b) < 30)
        // Changed our mind: go back (now planning back to it is what we asked for).
        backToShopFrom.clear()
        NavCore.goBackToPassedStop(); seconds(1)
        assertEquals(1, NavCore.stops.size)
        assertEquals(listOf(shop), asked.last())
        // Or carry on: the offer goes away on its own.
        NavCore.removeStop(0); seconds(1)
        assertEquals(0, NavCore.stops.size)
    }
}

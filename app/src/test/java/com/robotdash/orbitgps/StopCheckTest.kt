package com.robotdash.orbitgps

import com.robotdash.orbitgps.NavCore.StopCheck
import org.junit.Assert.assertEquals
import org.junit.Test

class StopCheckTest {
    private fun check(closest: Double, now: Double, speed: Double = 20.0, to: Double = 180.0, heading: Double = 0.0) =
        NavCore.passedStopCheck(closest, now, speed, to, heading)

    @Test fun drovePastTheStripMall() = assertEquals(StopCheck.PASSED, check(closest = 120.0, now = 600.0))
    @Test fun stoppedThereThenLeft() = assertEquals(StopCheck.VISITED, check(closest = 25.0, now = 400.0))
    @Test fun stillApproaching() = assertEquals(StopCheck.NONE, check(closest = 120.0, now = 600.0, to = 10.0))
    @Test fun neverCameClose() = assertEquals(StopCheck.NONE, check(closest = 2500.0, now = 3000.0))
    @Test fun tooSoonToTell() = assertEquals(StopCheck.NONE, check(closest = 120.0, now = 250.0))
    @Test fun parkedOrCrawling() = assertEquals(StopCheck.NONE, check(closest = 120.0, now = 600.0, speed = 1.0))
}

class DirectionTest {
    @Test fun aheadAndBehind() {
        val here = com.robotdash.orbitgps.nav.Pt(54.5, -128.6)
        val north = com.robotdash.orbitgps.nav.Pt(54.6, -128.6)
        val b = com.robotdash.orbitgps.nav.Geo.bearing(here, north)
        // heading north: place to the north is ahead (≈0), heading south: behind (≈±180)
        assertEquals(0.0, com.robotdash.orbitgps.nav.Geo.normalize(b - 0.0), 1.0)
        assertEquals(180.0, kotlin.math.abs(com.robotdash.orbitgps.nav.Geo.normalize(b - 180.0)), 1.0)
    }
}

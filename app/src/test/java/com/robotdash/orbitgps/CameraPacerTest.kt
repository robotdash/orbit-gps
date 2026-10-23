package com.robotdash.orbitgps

import com.robotdash.orbitgps.nav.CameraPacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Oct 19: taps on the search bar / ☰ took seconds while the map camera moved every frame. */
class CameraPacerTest {

    @Test fun atMostThirtyMovesASecondAndWaitsForTheMapToDraw() {
        val p = CameraPacer()
        var moves = 0
        var t = 0L
        // 60 Hz frames for one second; the map finishes drawing 20 ms after each move.
        var drawnAt = Long.MAX_VALUE
        while (t < 1000) {
            if (t >= drawnAt) { p.rendered(); drawnAt = Long.MAX_VALUE }
            if (p.canMove(t)) { p.moved(t); moves++; drawnAt = t + 20 }
            t += 16
        }
        assertTrue("moves=$moves", moves in 25..33)
    }

    @Test fun slowMapMeansFewerMovesNotABacklog() {
        val p = CameraPacer()
        var moves = 0
        var t = 0L
        var drawnAt = Long.MAX_VALUE
        while (t < 1000) {
            if (t >= drawnAt) { p.rendered(); drawnAt = Long.MAX_VALUE }
            if (p.canMove(t)) { p.moved(t); moves++; drawnAt = t + 120 } // phone needs 120 ms per frame
            t += 16
        }
        assertTrue("moves=$moves", moves in 7..9)
    }

    @Test fun fingerOnScreenHoldsTheCamera() {
        val p = CameraPacer()
        assertTrue(p.canMove(1000))
        p.touchDown()
        assertFalse(p.canMove(1100))
        assertFalse(p.canMove(5000))
        p.touchUp(5000)
        assertFalse(p.canMove(5100))
        assertTrue(p.canMove(5300))
    }

    @Test fun neverWaitsForeverForAFrame() {
        val p = CameraPacer()
        p.moved(0)
        assertFalse(p.canMove(100))
        assertTrue(p.canMove(260))
        assertEquals(true, p.canMove(1000))
    }
}

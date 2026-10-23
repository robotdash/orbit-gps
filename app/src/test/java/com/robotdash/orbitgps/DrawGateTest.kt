package com.robotdash.orbitgps

import com.robotdash.orbitgps.car.DrawGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Oct 19, first launch in the car: "Orbit GPS isn't responding". Android Auto sends
 * several surface callbacks at once; each queued its own map draw, and the second
 * started the map snapshotter while it was still busy (it throws).
 */
class DrawGateTest {
    private val queue = ArrayDeque<Runnable>()
    private fun runQueued() { while (queue.isNotEmpty()) queue.removeFirst().run() }

    @Test fun burstAtLaunchStartsOneSnapshotAtATime() {
        var started = 0
        var inFlight = 0
        var maxInFlight = 0
        lateinit var gate: DrawGate
        gate = DrawGate({ queue.addLast(it) }) { started++; inFlight++; maxInFlight = maxOf(maxInFlight, inFlight) }
        // Surface available, stable area, visible area, state changed, first GPS fix.
        repeat(5) { gate.request() }
        runQueued()
        assertEquals(1, started)
        // More requests while the snapshot is being made.
        repeat(3) { gate.request() }
        runQueued()
        assertEquals(1, started)
        // Snapshot comes back: exactly one follow-up draw for everything asked meanwhile.
        inFlight--; gate.done(); runQueued()
        assertEquals(2, started)
        inFlight--; gate.done(); runQueued()
        assertEquals(2, started)
        assertEquals(1, maxInFlight)
        assertFalse(gate.running)
    }
}

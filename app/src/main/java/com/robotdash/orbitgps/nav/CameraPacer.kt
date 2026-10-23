package com.robotdash.orbitgps.nav

/**
 * Decides when the follow camera may move, so the map never crowds out taps.
 *
 * Moving MapLibre's camera every screen frame (up to 60 a second) queued up more map
 * redraws than a phone can finish on a tilted map, and taps on the search bar or menu
 * waited seconds behind them (1.9 and earlier). Now the camera moves at most [maxFps]
 * times a second, waits for the previous move to be drawn, and holds still while a
 * finger is on the screen.
 */
class CameraPacer(private val maxFps: Int = 30) {
    private var lastMoveMs = -1_000_000L
    private var awaitingRender = false
    private var fingerDown = false
    private var holdUntilMs = 0L

    /** If the map never reports a drawn frame (e.g. hidden), don't wait forever. */
    private val renderTimeoutMs = 250L
    /** Keep still a little after the finger lifts, so the tap's own work runs first. */
    private val afterTouchMs = 250L

    fun canMove(nowMs: Long): Boolean {
        if (fingerDown || nowMs < holdUntilMs) return false
        val since = nowMs - lastMoveMs
        // A little slack so a 60 Hz screen moves every 2nd frame (and 120 Hz every 4th).
        if (since < 1000L / maxFps - 8) return false
        if (awaitingRender && since < renderTimeoutMs) return false
        return true
    }

    fun moved(nowMs: Long) { lastMoveMs = nowMs; awaitingRender = true }
    fun rendered() { awaitingRender = false }
    fun touchDown() { fingerDown = true }
    fun touchUp(nowMs: Long) { fingerDown = false; holdUntilMs = nowMs + afterTouchMs }
}

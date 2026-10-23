package com.robotdash.orbitgps.car

/**
 * At most one draw queued or running. Requests during that time fold into a single
 * follow-up draw once [done] is called. [post] schedules work on the main thread.
 */
class DrawGate(private val post: (Runnable) -> Unit, private val draw: () -> Unit) {
    private var queued = false
    var running = false; private set
    private var again = false

    fun request() {
        if (queued || running) { again = true; return }
        queued = true
        post(Runnable {
            queued = false
            running = true
            try { draw() } catch (t: Throwable) { running = false; throw t }
        })
    }

    /** The draw that [request] started has finished (successfully or not). */
    fun done() {
        running = false
        if (again) { again = false; request() }
    }
}

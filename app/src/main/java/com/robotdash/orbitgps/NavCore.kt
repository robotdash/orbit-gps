package com.robotdash.orbitgps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.Navigator
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.Route
import com.robotdash.orbitgps.nav.RouteService
import com.robotdash.orbitgps.nav.RouteTracker
import com.robotdash.orbitgps.search.SavedPlaces
import com.robotdash.orbitgps.search.SearchDb
import com.robotdash.orbitgps.voice.PromptEngine
import com.robotdash.orbitgps.voice.VoicePlayer

/**
 * The navigation engine, shared by the phone screen and Android Auto.
 *
 * Owns GPS, routing, search, saved places, voice and the current trip. Screens
 * call its actions (preview, start, stop) and listen for changes; they never
 * own navigation themselves, so guidance carries on whichever screen is showing
 * (phone, car, or none with the phone locked).
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object NavCore {

    enum class Mode { IDLE, PREVIEW, NAVIGATING }

    interface Listener {
        /** A GPS fix (or demo-drive step). [shown] is the arrow position, snapped to the road when on route. */
        fun onFix(raw: Location, shown: Location) {}
        /** Mode, route, preview or destination changed. */
        fun onStateChanged() {}
    }

    lateinit var app: Context; private set
    lateinit var voice: VoicePlayer; private set
    lateinit var routes: RouteService; private set
    lateinit var search: SearchDb; private set
    lateinit var saved: SavedPlaces; private set
    lateinit var settings: Settings; private set
    private lateinit var gps: GpsTracker
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<Listener>()
    private var initialised = false

    // ---- state (read by screens)
    var mode = Mode.IDLE; private set
    var lastFix: Location? = null; private set
    var lastShown: Location? = null; private set
    var destination: Pt? = null; private set
    var destinationName = ""; private set
    var destinationDetail = ""; private set
    var previewRoute: Route? = null; private set
    var previewError: String? = null; private set
    var navigator: Navigator? = null; private set
    var progress: RouteTracker.Progress? = null; private set
    var simulating = false; private set

    /** The route being shown: the active one while navigating, else the preview. */
    val currentRoute: Route? get() = navigator?.route ?: previewRoute

    val gpsEnabled: Boolean get() = gps.isGpsEnabled
    val secondsSinceFix: Long get() = gps.secondsSinceFix

    fun init(ctx: Context) {
        if (initialised) return
        initialised = true
        app = ctx.applicationContext
        settings = Settings(app)
        voice = VoicePlayer(app)
        routes = RouteService(app).also { it.warmUp() }
        search = SearchDb(app)
        saved = SavedPlaces(app)
        gps = GpsTracker(app) { fromGpsChip(it) }
        applySettings()
    }

    fun applySettings() {
        voice.muted = !settings.voiceOn
        voice.volume = settings.volume / 100f
        voice.phoneSpeaker = settings.phoneSpeaker
        PromptEngine.warningScale = if (settings.earlyWarnings) 1.3 else 1.0
        routes.options = RouteService.Options(settings.avoidHighways, settings.avoidFerries, settings.avoidUnpaved)
    }

    /** New region files were imported: reload routing and search. */
    fun reloadRegion() {
        routes.close()
        search.close()
        routes = RouteService(app).also { it.warmUp() }
        search = SearchDb(app)
        applySettings()
    }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it.onStateChanged() }

    /** A display choice changed (e.g. 3D view): screens redraw. */
    fun notifyStateChanged() = changed()

    // ---------------------------------------------------------------- GPS holders

    private val holders = mutableSetOf<String>()

    /**
     * Something needs GPS ("screen", "car", "nav"). GPS runs while anyone holds it,
     * so it switches off when the app isn't in use. Needs location permission.
     */
    fun holdGps(who: String) {
        holders += who
        if (hasLocationPermission()) gps.start()
    }

    fun releaseGps(who: String) {
        holders -= who
        if (holders.isEmpty()) gps.stop()
    }

    fun hasLocationPermission() = androidx.core.content.ContextCompat.checkSelfPermission(
        app, android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Permission was just granted: start GPS for whoever is waiting. */
    fun permissionGranted() { if (holders.isNotEmpty()) gps.start() }

    // ---------------------------------------------------------------- actions

    /**
     * True while a demo drive is switching to a new destination: real GPS fixes are
     * ignored so the car doesn't jump to where the phone really is.
     */
    private var demoPending = false

    /** Plan a route to [dest] and show it for confirmation. [from] overrides the GPS position (demo). */
    fun preview(dest: Pt, name: String, detail: String = "", from: Location? = null) {
        if (mode == Mode.NAVIGATING) stop()
        destination = dest; destinationName = name; destinationDetail = detail
        previewRoute = null; previewError = null
        mode = Mode.PREVIEW
        changed()
        val f = from ?: lastFix
        if (f == null) { previewError = app.getString(R.string.need_fix); changed(); return }
        if (routeForTest == null && !routes.available) { previewError = app.getString(R.string.routing_missing); changed(); return }
        val start = Pt(f.latitude, f.longitude)
        val heading = if (f.speed > 2f) f.bearing.toDouble() else null
        plan(start, heading, dest) { result ->
            main.post {
                if (mode != Mode.PREVIEW || destination != dest) return@post
                result.onSuccess { previewRoute = it }.onFailure { previewError = it.message }
                changed()
            }
        }
    }

    fun cancelPreview() {
        demoPending = false
        if (mode != Mode.PREVIEW) return
        mode = Mode.IDLE
        previewRoute = null; previewError = null; destination = null
        changed()
    }

    /** Start guidance on the previewed route ([simulate] = demo drive). */
    fun start(simulate: Boolean = false) {
        demoPending = false
        val route = previewRoute ?: return
        val dest = destination ?: return
        stops = emptyList(); droppedOnThisRoute = 0
        saved.addRecent(SavedPlaces.Saved(destinationName, destinationDetail, dest))
        arrivalScheduled = false
        lastNotif = ""
        navigator = Navigator(route, { voice.speak(it) }, { p, h -> reroute(p, h, dest) })
        progress = null
        simulating = simulate
        mode = Mode.NAVIGATING
        holdGps("nav")
        NavigationService.start(app, app.getString(R.string.navigating_to, destinationName))
        changed()
        if (simulate) {
            simAlong = 0.0
            simSpeed = (route.length / route.durationSec.coerceAtLeast(1.0)).coerceIn(8.0, 27.0)
            main.post(simTick)
        } else {
            lastFix?.let { handleFix(it) }
        }
    }

    /** Test hook: put a ready-made route into preview (unit tests only). */
    @androidx.annotation.VisibleForTesting
    fun setPreviewForTest(route: Route, dest: Pt, name: String) {
        destination = dest; destinationName = name; previewRoute = route; previewError = null
        mode = Mode.PREVIEW; changed()
    }

    /** Test hook: plan routes with this instead of the on-phone router (unit tests only). */
    @androidx.annotation.VisibleForTesting
    var routeForTest: ((from: Pt, via: List<Pt>, to: Pt) -> Route)? = null

    private fun plan(from: Pt, heading: Double?, to: Pt, done: (Result<Route>) -> Unit) = plan(from, heading, emptyList(), to, done)

    private fun plan(from: Pt, heading: Double?, via: List<Pt>, to: Pt, done: (Result<Route>) -> Unit) {
        val fake = routeForTest ?: return routes.route(from, heading, via, to, done)
        done(runCatching { fake(from, via, to) })
    }

    /** Real GPS fixes are ignored during a demo drive (and while one switches destination). */
    private fun fromGpsChip(loc: Location) { if (!simulating && !demoPending) handleFix(loc) }

    /** Test hook: a fix arriving from the real GPS chip, with the demo-drive filter applied. */
    @androidx.annotation.VisibleForTesting
    fun gpsChipFixForTest(loc: Location) = fromGpsChip(loc)

    /** Test hook: feed a GPS fix as if it came from the GPS chip. */
    @androidx.annotation.VisibleForTesting
    fun fixForTest(loc: Location) = handleFix(loc)

    /** Navigate straight to a place (preview + start once the route is ready). Used by Android Auto. */
    fun navigateTo(dest: Pt, name: String, detail: String = "", demo: Boolean = false, from: Location? = null) {
        preview(dest, name, detail, from)
        val l = object : Listener {
            override fun onStateChanged() {
                if (mode != Mode.PREVIEW || destination != dest) { demoPending = false; removeListener(this); return }
                if (previewRoute != null) { removeListener(this); start(demo) }
                else if (previewError != null) { demoPending = false; removeListener(this) }
            }
        }
        addListener(l)
    }

    fun stop() {
        demoPending = false
        stops = emptyList(); droppedOnThisRoute = 0; stopError = null
        passedStop = null; main.removeCallbacks(clearPassed)
        main.removeCallbacks(simTick)
        NavigationService.stop(app)
        simulating = false
        navigator = null
        progress = null
        previewRoute = null; destination = null
        mode = Mode.IDLE
        releaseGps("nav")
        changed()
    }

    // ---------------------------------------------------------------- stops along the way

    data class Stop(val pt: Pt, val name: String)

    /** Stops still ahead on this trip, in order. */
    var stops: List<Stop> = emptyList(); private set
    var stopError: String? = null; private set

    /** The stop a route step arrives at (for the turn list / banner). */
    fun stopName(step: com.robotdash.orbitgps.nav.NavStep): String? {
        val route = currentRoute ?: return null
        val waypoints = route.announced.filter { it.maneuver?.waypoint == true }
        val i = waypoints.indexOf(step)
        if (i < 0) return null
        // Stops already passed on this route were dropped from the list.
        return stops.getOrNull(i - droppedOnThisRoute)?.name
    }

    /** While navigating: add a stop before the destination and reroute through it. */
    fun addStop(pt: Pt, name: String, done: (Boolean) -> Unit = {}) {
        val dest = destination ?: return done(false)
        val f = lastShown ?: lastFix ?: return done(false)
        val newStops = stops + Stop(pt, name)
        plan(Pt(f.latitude, f.longitude), f.bearing.toDouble().takeIf { f.speed > 2f }, newStops.map { it.pt }, dest) { result ->
            main.post {
                val nav = navigator ?: return@post done(false)
                result.onSuccess { r ->
                    stops = newStops; stopError = null; droppedOnThisRoute = 0
                    nav.replaceRoute(r)
                    if (simulating) simAlong = 0.0 // the new route starts where the demo car is
                    changed(); done(true)
                }.onFailure { stopError = it.message; changed(); done(false) }
            }
        }
    }

    /** While navigating: drop the trip and go to this place instead. */
    fun goInstead(pt: Pt, name: String, detail: String = "") {
        // In a demo drive, keep driving the demo from where the demo car is
        // (in 1.7 and earlier this switched to real GPS, i.e. wherever the phone was).
        val demo = simulating
        val here = if (demo) lastShown ?: lastFix else null
        stop()
        demoPending = demo
        navigateTo(pt, name, detail, demo, here)
    }

    /** Forget stops we've driven past (their arrival step is behind us). */
    private fun dropPassedStops(prog: RouteTracker.Progress) {
        if (stops.isEmpty()) return
        val route = navigator?.route ?: return
        val waypoints = route.announced.filter { it.maneuver?.waypoint == true }
        val passed = waypoints.count { it.distanceFromStart < prog.along - 30 }
        if (passed > 0 && passed <= stops.size) {
            // Route steps stay on this route, so track how many we've already dropped.
            val already = droppedOnThisRoute
            if (passed > already) {
                stops = stops.drop(passed - already)
                droppedOnThisRoute = passed
            }
        }
    }
    private var droppedOnThisRoute = 0

    private fun reroute(p: Pt, heading: Double, dest: Pt) {
        // A stopped car's heading is just the last one it had; don't let it steer the start.
        val h = heading.takeIf { (lastFix?.speed ?: 0f) > 2f }
        // Left the route while driving away from the next stop (drove past it): don't plan a
        // U-turn back to it somewhere down the road. Carry on to the destination and ask.
        stops.firstOrNull()?.let { s ->
            if (h != null && closestFor == s && closestToStop <= PASSED_NEAR_M &&
                Geo.angleDiff(Geo.bearing(p, s.pt), h) >= 100) {
                removeStopAt(0, if (closestToStop < 60) Reason.VISITED else Reason.PASSED)
                return
            }
        }
        val asked = stops
        plan(p, h, asked.map { it.pt }, dest) { result ->
            main.post {
                val nav = navigator ?: return@post
                // Stops changed while this was being worked out (skipped, removed): a newer route is coming.
                if (stops != asked) return@post
                result.onSuccess { r ->
                    droppedOnThisRoute = 0; nav.replaceRoute(r)
                    if (simulating) simAlong = 0.0
                    changed()
                }
            }
        }
    }

    /** A stop we just drove past without stopping; the driver can still choose to go back. */
    var passedStop: Stop? = null; private set
    private val clearPassed = Runnable { if (passedStop != null) { passedStop = null; changed() } }
    private var closestFor: Stop? = null
    private var closestToStop = Double.MAX_VALUE

    /**
     * Driving past the next stop: if we came close and are now heading away from it,
     * don't make the driver turn around for it. Skip it, keep going, and offer
     * "go back" for a little while. Reaching it (within ~60 m) counts as visited.
     */
    private fun checkPassedStop(loc: Location) {
        if (mode != Mode.NAVIGATING) return
        val s = stops.firstOrNull() ?: return
        if (closestFor != s) { closestFor = s; closestToStop = Double.MAX_VALUE }
        val here = Pt(loc.latitude, loc.longitude)
        val d = Geo.distance(here, s.pt)
        if (d < closestToStop) closestToStop = d
        when (passedStopCheck(closestToStop, d, loc.speed.toDouble(), Geo.bearing(here, s.pt), loc.bearing.toDouble())) {
            StopCheck.VISITED -> removeStopAt(0, Reason.VISITED)
            StopCheck.PASSED -> removeStopAt(0, Reason.PASSED)
            StopCheck.NONE -> {}
        }
    }

    enum class StopCheck { NONE, VISITED, PASSED }

    /**
     * Have we left the next stop behind? [closest] is how near we got (m), [now] how far it is,
     * [bearingTo] the direction to it and [heading] the way we're driving.
     */
    fun passedStopCheck(closest: Double, now: Double, speedMps: Double, bearingTo: Double, heading: Double): StopCheck {
        if (closest > PASSED_NEAR_M || now < closest + 250 || speedMps < 4) return StopCheck.NONE
        if (Geo.angleDiff(bearingTo, heading) < 100) return StopCheck.NONE      // still ahead of us
        return if (closest < 60) StopCheck.VISITED else StopCheck.PASSED
    }

    private enum class Reason { PASSED, REMOVED, VISITED }

    /** Came at least this close to a stop (m) and now driving away from it = drove past it. */
    const val PASSED_NEAR_M = 1500.0
    /** How long "Go back to your stop?" stays on offer. */
    const val GO_BACK_OFFER_MS = 90_000L

    /** Remove a stop from the trip (the driver changed their mind) and reroute. */
    fun removeStop(index: Int) = removeStopAt(index, Reason.REMOVED)

    private fun removeStopAt(index: Int, why: Reason) {
        val s = stops.getOrNull(index) ?: return
        stops = stops.filterIndexed { i, _ -> i != index }
        closestFor = null
        if (why == Reason.PASSED) {
            passedStop = s
            main.removeCallbacks(clearPassed); main.postDelayed(clearPassed, GO_BACK_OFFER_MS)
        }
        when (why) {
            Reason.PASSED -> voice.speak(listOf("stop_passed"))
            Reason.REMOVED -> voice.speak(listOf("stop_removed"))
            Reason.VISITED -> {}
        }
        rerouteFromHere()
    }

    /** Changed our mind about skipping: put the passed stop back first and head there. */
    /** Answered "continue": forget the passed stop for good. */
    fun dismissPassedStop() {
        if (passedStop == null) return
        passedStop = null; main.removeCallbacks(clearPassed); changed()
    }

    fun goBackToPassedStop() {
        val s = passedStop ?: return
        passedStop = null; main.removeCallbacks(clearPassed)
        stops = listOf(s) + stops
        closestFor = null
        voice.speak(listOf("stop_going_back"))
        rerouteFromHere()
    }

    private fun rerouteFromHere() {
        val dest = destination ?: return
        val f = lastShown ?: lastFix ?: return
        val asked = stops
        changed()
        plan(Pt(f.latitude, f.longitude), f.bearing.toDouble().takeIf { f.speed > 2f }, asked.map { it.pt }, dest) { result ->
            main.post {
                val nav = navigator ?: return@post
                if (stops != asked) return@post
                result.onSuccess { r -> droppedOnThisRoute = 0; nav.replaceRoute(r, announce = false); changed() }
                    .onFailure { stopError = it.message; changed() }
            }
        }
    }

    // ---------------------------------------------------------------- fixes

    private var arrivalScheduled = false
    private var lastNotif = ""

    /** The way you were last driving (kept while stopped), for "ahead or behind" arrows. */
    var lastHeading: Double? = null; private set

    /** Direction to [p] relative to the way you're heading (0 = dead ahead, ±180 = behind). Null without a fix. */
    fun directionTo(p: Pt): Double? {
        val f = lastShown ?: lastFix ?: return null
        val b = Geo.bearing(Pt(f.latitude, f.longitude), p)
        return Geo.normalize(b - (lastHeading ?: 0.0))
    }

    private fun handleFix(loc: Location) {
        lastFix = loc
        if (loc.hasBearing() && loc.speed > 1.5f) lastHeading = loc.bearing.toDouble()
        var shown = loc
        checkPassedStop(loc)
        navigator?.let { nav ->
            val prog = nav.update(Pt(loc.latitude, loc.longitude), loc.bearing.toDouble(), loc.speed.toDouble(), loc.accuracy.toDouble())
            progress = prog
            if (prog != null) dropPassedStops(prog)
            if (prog != null && !prog.offRoute && prog.offset < 25) {
                // Snap the arrow onto the road — steadier, like a car GPS.
                shown = Location(loc).apply {
                    latitude = prog.snapped.lat; longitude = prog.snapped.lon
                    if (loc.speed > 1.5f) bearing = prog.routeBearing.toFloat()
                }
            }
            if (prog != null) updateNotification(nav, prog)
            if (nav.arrived && !arrivalScheduled) {
                arrivalScheduled = true
                main.postDelayed({ if (navigator === nav) stop() }, 8000)
            }
        }
        lastShown = shown
        listeners.toList().forEach { it.onFix(loc, shown) }
    }

    private fun updateNotification(nav: Navigator, prog: RouteTracker.Progress) {
        val step = nav.route.announced.getOrNull(prog.nextIndex)
        val text = when {
            nav.arrived || step == null -> app.getString(R.string.arrived_toast)
            prog.offRoute -> app.getString(if ((lastFix?.speed ?: 0f) < com.robotdash.orbitgps.nav.Navigator.MOVING_MS) R.string.drive_to_route else R.string.recalculating)
            else -> "${Format.distance(prog.distanceToNext)} · ${step.instruction.trimEnd('.')}"
        }
        if (text != lastNotif) { lastNotif = text; NavigationService.update(app, text) }
    }

    // ---------------------------------------------------------------- demo drive

    private var simAlong = 0.0
    private var simSpeed = 15.0

    private val simTick = object : Runnable {
        override fun run() {
            val nav = navigator ?: return
            if (nav.arrived || !simulating) return
            val r = nav.route
            simAlong = (simAlong + simSpeed).coerceAtMost(r.length)
            val (p, b) = pointAlong(r, simAlong)
            handleFix(Location("demo").apply {
                latitude = p.lat; longitude = p.lon; bearing = b.toFloat(); speed = simSpeed.toFloat()
                accuracy = 5f; time = System.currentTimeMillis()
            })
            main.postDelayed(this, 1000)
        }
    }

    fun pointAlong(r: Route, d: Double): Pair<Pt, Double> {
        val c = r.cumulative
        var i = c.indexOfFirst { it >= d }
        if (i <= 0) i = if (i == 0) 1 else c.size - 1
        val a = r.points[i - 1]; val b = r.points[i]
        val t = if (c[i] == c[i - 1]) 0.0 else ((d - c[i - 1]) / (c[i] - c[i - 1])).coerceIn(0.0, 1.0)
        return Geo.lerp(a, b, t) to Geo.bearing(a, b)
    }

    /** Seconds left, scaled from the route's own estimate. */
    fun secondsLeft(): Double {
        val nav = navigator ?: return 0.0
        val p = progress ?: return nav.route.durationSec
        return nav.route.durationSec * (p.remaining / nav.route.length.coerceAtLeast(1.0))
    }
}

/** Shared text formatting for phone and car. */
object Format {
    fun distance(m: Double): String = when {
        m < 1000 -> "${((m / 10).toInt() * 10).coerceAtLeast(0)} m"
        m < 10_000 -> String.format("%.1f km", m / 1000)
        else -> "${(m / 1000).toInt()} km"
    }

    fun duration(sec: Double): String {
        val mins = Math.round(sec / 60).toInt()
        return if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"
    }
}

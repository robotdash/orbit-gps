package com.robotdash.orbitgps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.NavStep
import com.robotdash.orbitgps.nav.PoseSmoother
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.Route
import com.robotdash.orbitgps.nav.RouteTracker
import com.robotdash.orbitgps.nav.TurnIcon
import com.robotdash.orbitgps.search.Categories
import com.robotdash.orbitgps.search.Category
import com.robotdash.orbitgps.search.SavedPlaces
import com.robotdash.orbitgps.voice.Lanes
import com.robotdash.orbitgps.voice.Maneuver
import com.robotdash.orbitgps.voice.ManeuverType
import com.robotdash.orbitgps.voice.PromptEngine
import com.robotdash.orbitgps.voice.Side
import com.robotdash.orbitgps.voice.Stage
import com.robotdash.orbitgps.voice.VoicePlayer
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Date
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Orbit GPS phone screen. Navigation itself lives in [NavCore] (shared with
 * Android Auto); this screen shows it and sends the user's taps to it.
 */
class MainActivity : AppCompatActivity(), NavCore.Listener {

    companion object {
        /** Street level: the destination and the roads around it. */
        const val DEST_ZOOM = 15.0
        const val ONLINE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val ONLINE_STYLE_NIGHT_URL = "https://tiles.openfreemap.org/styles/dark"
        const val KOFI_URL = "https://ko-fi.com/dashorbit"
        val START = LatLng(54.5163, -128.6035) // Terrace
        private const val RESUME_FOLLOW_MS = 15_000L
        private const val ROUTE_SRC = "orbit-route"
        private const val DEST_SRC = "orbit-dest"
        private const val RESULTS_SRC = "orbit-results"
        private const val RESULTS_PIN = "orbit-results-pin"
        private const val RESULTS_NUM = "orbit-results-num"
        /** Results drawn on the map (the nearest ones; the list has the rest). */
        private const val MAP_RESULTS = 25
    }

    private val core = NavCore
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // views
    private lateinit var speedText: TextView
    private lateinit var statusText: TextView
    private lateinit var recenterBtn: FloatingActionButton
    private lateinit var orientBtn: FloatingActionButton
    private lateinit var searchBar: View
    private lateinit var searchPanel: View
    private lateinit var searchInput: EditText
    private lateinit var searchEmpty: TextView
    private lateinit var searchList: ListView
    private lateinit var previewCard: View
    private lateinit var previewTitle: TextView
    private lateinit var previewInfo: TextView
    private lateinit var navBanner: View
    private lateinit var turnIcon: ImageView
    private lateinit var turnDistance: TextView
    private lateinit var turnText: TextView
    private lateinit var laneRow: LinearLayout
    private lateinit var thenText: TextView
    private lateinit var junctionPanel: View
    private lateinit var junctionImage: ImageView
    private lateinit var junctionBar: CountdownBar
    /** Which junction picture is showing (route, step, night, size), so it's only drawn once. */
    private var junctionKey: String? = null
    private lateinit var navBottom: View
    private lateinit var etaText: TextView
    private lateinit var remainingText: TextView
    private lateinit var navSpeed: TextView
    private lateinit var speedBox: View
    private lateinit var fabColumn: View
    private lateinit var muteBtn: FloatingActionButton

    // view state
    private var headingUp = true
    private var following = true
    private var zoom = 16.5
    private val smoother = PoseSmoother()
    private val pacer = com.robotdash.orbitgps.nav.CameraPacer()
    private lateinit var carArrow: ImageView
    private var lastFrameMs = 0L
    private var lastCam: PoseSmoother.Pose? = null
    private var frameLoopOn = false
    private var onScreen = false
    private var nightShown: Boolean? = null
    private var styleShown: OfflineMaps.MapStyle? = null
    private var shownMode: NavCore.Mode? = null
    private var shownRoute: Route? = null
    private var lastSearchText = ""
    private val resumeFollow = Runnable { follow() }

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) core.permissionGranted()
        else statusText.text = getString(R.string.need_location)
    }

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindViews()

        mapView = findViewById(R.id.mapView)
        mapView.addOnDidFinishRenderingFrameListener { _, _, _ -> pacer.rendered() }
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            val start = core.lastShown?.let { LatLng(it.latitude, it.longitude) } ?: START
            m.cameraPosition = CameraPosition.Builder().target(start).zoom(11.0).build()
            m.uiSettings.isCompassEnabled = false
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) pauseFollow()
            }
            m.addOnMapClickListener { ll ->
                if (mapRows.isEmpty()) return@addOnMapClickListener false
                val pt = m.projection.toScreenLocation(ll)
                val r = 24 * resources.displayMetrics.density
                val hit = m.queryRenderedFeatures(android.graphics.RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r), RESULTS_PIN, RESULTS_NUM)
                val i = hit.firstNotNullOfOrNull { f -> f.getNumberProperty("i")?.toInt() } ?: return@addOnMapClickListener false
                mapRows.getOrNull(i)?.let { showPinCard(it) }
                true
            }
            m.addOnMapLongClickListener { ll ->
                val pt = Pt(ll.latitude, ll.longitude)
                val choices = mutableListOf<Pair<BigMenu.Choice, () -> Unit>>()
                if (core.mode != NavCore.Mode.NAVIGATING)
                    choices += BigMenu.Choice(getString(R.string.go_here), "🚗") to { previewTo(pt, getString(R.string.dropped_pin)) }
                choices += BigMenu.Choice(getString(R.string.report_here), "📍", getString(R.string.report_here_detail)) to { reportSpot(pt) }
                BigMenu.show(this, getString(R.string.dropped_pin), choices.map { it.first }) { choices[it].second() }
                true
            }
            loadStyle()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    mapRows.isNotEmpty() -> { clearResultPins(); follow() }
                    searchPanel.visibility == View.VISIBLE && showingCategory -> showBrowse()
                    searchPanel.visibility == View.VISIBLE && searchInput.text.isNotEmpty() -> searchInput.setText("")
                    searchPanel.visibility == View.VISIBLE -> { val wasPicking = pickFor != null; closeSearch()
                        if (wasPicking && reopenSavedAfterPick) { reopenSavedAfterPick = false; showSavedPlaces() } }
                    core.mode == NavCore.Mode.PREVIEW -> core.cancelPreview()
                    core.mode == NavCore.Mode.NAVIGATING -> confirmStop()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        core.addListener(this)
        refreshQuickChips()
        io.execute { core.search.warmUp() }
        if (!core.hasLocationPermission()) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        render()
        main.post { handleGeo(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGeo(intent)
    }

    /** "Navigate to …" from the Assistant, or a map link from another app (geo:…). */
    private fun handleGeo(i: Intent?) {
        val uri = i?.data?.toString() ?: return
        val g = com.robotdash.orbitgps.car.GeoQuery.parse(uri) ?: return
        i.data = null   // handle once
        if (g.query.isBlank()) { g.pt?.let { previewTo(it, getString(R.string.dropped_pin)) }; return }
        openSearch()
        searchInput.setText(g.query)
        searchInput.setSelection(g.query.length)
    }

    private fun bindViews() {
        findViewById<View>(R.id.resultsMapBtn).setOnClickListener { showResultsOnMap() }
        findViewById<View>(R.id.mapResultsList).setOnClickListener {
            val cat = lastCategory
            clearResultPins()
            if (cat != null) { openSearch(); showCategory(cat) }
        }
        findViewById<View>(R.id.mapResultsClose).setOnClickListener { clearResultPins(); follow() }
        speedText = findViewById(R.id.speed)
        statusText = findViewById(R.id.status)
        recenterBtn = findViewById(R.id.recenter)
        orientBtn = findViewById(R.id.orient)
        searchBar = findViewById(R.id.searchBar)
        searchPanel = findViewById(R.id.searchPanel)
        searchInput = findViewById(R.id.searchInput)
        searchEmpty = findViewById(R.id.searchEmpty)
        searchList = findViewById(R.id.searchResults)
        previewCard = findViewById(R.id.previewCard)
        previewTitle = findViewById(R.id.previewTitle)
        previewInfo = findViewById(R.id.previewInfo)
        navBanner = findViewById(R.id.navBanner)
        turnIcon = findViewById(R.id.turnIcon)
        turnDistance = findViewById(R.id.turnDistance)
        turnText = findViewById(R.id.turnText)
        laneRow = findViewById(R.id.laneRow)
        junctionPanel = findViewById(R.id.junctionPanel)
        junctionImage = findViewById(R.id.junctionImage)
        junctionBar = findViewById(R.id.junctionBar)
        thenText = findViewById(R.id.thenText)
        junctionPanel = findViewById(R.id.junctionPanel)
        junctionImage = findViewById(R.id.junctionImage)
        junctionBar = findViewById(R.id.junctionBar)
        navBottom = findViewById(R.id.navBottom)
        etaText = findViewById(R.id.eta)
        remainingText = findViewById(R.id.remaining)
        navSpeed = findViewById(R.id.navSpeed)
        speedBox = findViewById(R.id.speedBox)
        carArrow = findViewById(R.id.carArrow)
        fabColumn = findViewById(R.id.fabColumn)
        muteBtn = findViewById(R.id.muteBtn)
        muteBtn.setOnClickListener {
            core.settings.voiceOn = !core.settings.voiceOn
            core.applySettings()
            if (!core.settings.voiceOn) core.voice.stop()
            updateMuteIcon()
            Toast.makeText(this, if (core.settings.voiceOn) R.string.voice_unmuted else R.string.voice_muted, Toast.LENGTH_SHORT).show()
        }
        findViewById<FloatingActionButton>(R.id.nearbyBtn).setOnClickListener { openSearch() }
        navBanner.setOnClickListener { showTurnList() }
        previewTitle.setOnClickListener { showTurnList() }
        previewInfo.setOnClickListener { showTurnList() }
        // Keep the buttons above the bottom bar while navigating.
        navBottom.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeButtons() }
        previewCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeButtons() }

        recenterBtn.setOnClickListener { follow() }
        orientBtn.setOnClickListener {
            headingUp = !headingUp
            orientBtn.setImageResource(if (headingUp) R.drawable.ic_heading_up else R.drawable.ic_north_up)
            Toast.makeText(this, if (headingUp) R.string.heading_up else R.string.north_up, Toast.LENGTH_SHORT).show()
            follow()
        }
        findViewById<View>(R.id.menuBtn).setOnClickListener { showMainMenu() }
        findViewById<View>(R.id.searchClear).setOnClickListener { searchInput.setText("") }
        findViewById<View>(R.id.manageSavedBtn).setOnClickListener { closeSearch(); showSavedPlaces() }
        searchBar.setOnClickListener { openSearch() }
        findViewById<View>(R.id.searchBack).setOnClickListener { closeSearch() }
        findViewById<Button>(R.id.goBtn).setOnClickListener { startNav(false) }
        findViewById<Button>(R.id.simBtn).setOnClickListener { startNav(true) }
        findViewById<Button>(R.id.cancelBtn).setOnClickListener { core.cancelPreview() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { saveDestination() }
        findViewById<Button>(R.id.stopBtn).setOnClickListener { confirmStop() }
        findViewById<Button>(R.id.homeBtn).setOnClickListener { goToSlot(SavedPlaces.Slot.HOME) }
        findViewById<Button>(R.id.workBtn).setOnClickListener { goToSlot(SavedPlaces.Slot.WORK) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                findViewById<View>(R.id.searchClear).visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                // Wait for a short pause in typing before searching (keeps typing snappy).
                main.removeCallbacks(pendingSearch)
                pendingText = text
                if (text.isBlank()) runSearch(text) else main.postDelayed(pendingSearch, 180)
            }
        })
        searchList.setOnItemClickListener { parent, _, pos, _ ->
            val r = parent.getItemAtPosition(pos) as Row
            if (r.header) return@setOnItemClickListener
            if (r.spotId != null) { showSpotCard(r); return@setOnItemClickListener }
            choosePlace(r.pt!!, r.name, r.detail)
        }
    }

    // ================================================================ NavCore → screen

    override fun onStateChanged() = render()

    override fun onFix(raw: Location, shown: Location) {
        val kmh = (raw.speed * 3.6f).roundToInt()
        speedText.text = getString(R.string.speed_kmh, kmh)
        navSpeed.text = getString(R.string.speed_kmh, kmh)
        if (core.mode == NavCore.Mode.NAVIGATING) updateBanner()
        // Feed the smoother; the frame loop draws the arrow and moves the map.
        val prog = core.progress
        val onRoute = core.mode == NavCore.Mode.NAVIGATING && prog != null && !prog.offRoute && prog.offset < 25
        smoother.onFix(Pt(shown.latitude, shown.longitude), shown.bearing.toDouble(), raw.speed.toDouble(), nowMs(),
            if (onRoute) core.navigator?.route else null, if (onRoute) prog!!.along else null)
        startFrameLoop()
    }

    /** Show the panels for the current mode, and the route line. */
    private fun render() {
        offerGoBack()
        val mode = core.mode
        val modeChanged = mode != shownMode
        shownMode = mode
        searchBar.visibility = if (mode == NavCore.Mode.IDLE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.quickChips).visibility = if (mode == NavCore.Mode.IDLE && mapRows.isEmpty()) View.VISIBLE else View.GONE
        if (mode == NavCore.Mode.PREVIEW && mapRows.isNotEmpty()) clearResultPins()
        previewCard.visibility = if (mode == NavCore.Mode.PREVIEW) View.VISIBLE else View.GONE
        navBanner.visibility = if (mode == NavCore.Mode.NAVIGATING) View.VISIBLE else View.GONE
        if (mode != NavCore.Mode.NAVIGATING) hideJunction()
        navBottom.visibility = if (mode == NavCore.Mode.NAVIGATING) View.VISIBLE else View.GONE
        speedBox.visibility = if (mode == NavCore.Mode.NAVIGATING) View.GONE else View.VISIBLE
        // While navigating: only the buttons a driver needs (find nearby, mute, orientation, recenter).
        val nav = mode == NavCore.Mode.NAVIGATING
        for (id in listOf(R.id.nearbyBtn, R.id.muteBtn))
            findViewById<View>(id).visibility = if (nav) View.VISIBLE else View.GONE
        updateMuteIcon()
        placeButtons()

        if (mode == NavCore.Mode.PREVIEW) {
            previewTitle.text = core.destinationName
            val r = core.previewRoute
            previewInfo.text = when {
                r != null -> "${Format.distance(r.length)} · ${Format.duration(r.durationSec)} · " +
                    getString(R.string.arrive_at, timeAt(r.durationSec)) + "\n" + getString(R.string.turns_tap_hint)
                core.previewError != null -> core.previewError
                else -> getString(R.string.routing)
            }
            findViewById<View>(R.id.goBtn).isEnabled = r != null
            findViewById<View>(R.id.simBtn).isEnabled = r != null
        }
        val route = core.currentRoute
        if (route !== shownRoute || modeChanged) {
            shownRoute = route
            showRouteOnMap(route, core.destination)
        }
        // Picked a place: zoom straight to it (once per place), not to the whole trip.
        val dest = core.destination
        if (mode == NavCore.Mode.PREVIEW && dest != null && (dest != focusedDest || modeChanged)) {
            focusedDest = dest
            focusDestination(dest)
        }
        if (mode != NavCore.Mode.PREVIEW) focusedDest = null
        if (modeChanged && mode != NavCore.Mode.PREVIEW) follow()
        if (mode == NavCore.Mode.NAVIGATING) {
            headingUp = true
            orientBtn.setImageResource(R.drawable.ic_heading_up)
            updateBanner()
        }
    }

    // ================================================================ map

    private fun wantNight(): Boolean = when (core.settings.theme) {
        Settings.Theme.DAY -> false
        Settings.Theme.NIGHT -> true
        Settings.Theme.AUTO -> {
            val f = core.lastFix
            Sun.isNight(f?.latitude ?: START.latitude, f?.longitude ?: START.longitude)
        }
    }

    private fun loadStyle(force: Boolean = false) {
        val m = map ?: return
        val night = wantNight()
        val mapStyle = core.settings.mapStyle
        if (night == nightShown && mapStyle == styleShown && !force) return
        nightShown = night
        styleShown = mapStyle
        val style = if (OfflineMaps.hasOfflineMap(this))
            Style.Builder().fromJson(OfflineMaps.styleJson(this, night, mapStyle))
        else Style.Builder().fromUri(if (night) ONLINE_STYLE_NIGHT_URL else ONLINE_STYLE_URL)
        m.setStyle(style) { s -> onStyleReady(s) }
    }

    private fun onStyleReady(style: Style) {
        addRouteLayers(style)
        showRouteOnMap(core.currentRoute, core.destination)
        setResultPins(mapRows)
        lastCam = null
        startFrameLoop()
    }

    private fun addRouteLayers(style: Style) {
        style.addSource(GeoJsonSource(ROUTE_SRC))
        style.addSource(GeoJsonSource(DEST_SRC))
        val casing = LineLayer("orbit-route-casing", ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#0D47A1"), PropertyFactory.lineWidth(13f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val line = LineLayer("orbit-route-line", ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#2F8CFF"), PropertyFactory.lineWidth(8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val dest = CircleLayer("orbit-dest", DEST_SRC).withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#E53935"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
        )
        val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
        if (firstLabel != null) { style.addLayerBelow(casing, firstLabel); style.addLayerBelow(line, firstLabel) }
        else { style.addLayer(casing); style.addLayer(line) }
        style.addLayer(dest)
        // Category results as numbered pins (same order as the list).
        style.addSource(GeoJsonSource(RESULTS_SRC))
        style.addLayer(CircleLayer(RESULTS_PIN, RESULTS_SRC).withProperties(
            PropertyFactory.circleRadius(14f), PropertyFactory.circleColor("#00897B"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f),
        ))
        style.addLayer(SymbolLayer(RESULTS_NUM, RESULTS_SRC).withProperties(
            PropertyFactory.textField(org.maplibre.android.style.expressions.Expression.get("n")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")), PropertyFactory.textSize(13f),
            PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
        ))
    }

    private fun showRouteOnMap(route: Route?, dest: Pt?) {
        val style = map?.style ?: return
        if (!style.isFullyLoaded) return
        (style.getSource(ROUTE_SRC) as? GeoJsonSource)?.setGeoJson(GeoJson.line(route))
        (style.getSource(DEST_SRC) as? GeoJsonSource)?.setGeoJson(GeoJson.point(dest))
    }

    private fun zoomFor(kmh: Float): Double = when {
        kmh < 30 -> 17.0
        kmh < 60 -> 16.3
        kmh < 90 -> 15.5
        else -> 14.8
    }

    // ---------------------------------------------------------------- smooth motion

    private fun nowMs() = System.nanoTime() / 1_000_000

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!onScreen) { frameLoopOn = false; return }
            drawFrame(frameTimeNanos / 1_000_000)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFrameLoop() {
        if (frameLoopOn || !onScreen) return
        frameLoopOn = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Every screen frame: predict where the car is, move the map and the arrow together. */
    private fun drawFrame(ms: Long) {
        val m = map ?: return
        if (!smoother.hasFix) { carArrow.visibility = View.GONE; return }
        // The search screen covers the map: don't spend the phone's effort redrawing it.
        if (searchPanel.visibility == View.VISIBLE) { lastFrameMs = ms; return }
        val dt = if (lastFrameMs == 0L) 16L else (ms - lastFrameMs).coerceIn(1, 200)
        lastFrameMs = ms
        val pose = smoother.at(ms)
        if (following) {
            // Zoom eases toward the speed-based target over ~1.5 s.
            val target = zoomFor((pose.speed * 3.6).toFloat())
            zoom += (target - zoom) * (1 - Math.exp(-dt / 1500.0))
            val last = lastCam
            val moved = last == null || Math.abs(last.lat - pose.lat) > 1e-7 || Math.abs(last.lon - pose.lon) > 1e-7 ||
                Geo.angleDiff(last.bearing, pose.bearing) > 0.05 || Math.abs(target - zoom) > 0.002
            if (moved) {
                // Only when the last move has been drawn and no finger is down (keeps taps instant).
                if (!pacer.canMove(ms)) return
                setCamera(pose); lastCam = pose; pacer.moved(ms)
            }
        }
        if (pacer.canMove(ms) || !following) placeArrow(m, pose)
    }

    /** Any finger on the screen pauses the follow camera, so the tap is handled straight away. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> pacer.touchDown()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                pacer.touchUp(android.os.SystemClock.uptimeMillis())
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setCamera(pose: PoseSmoother.Pose) {
        val m = map ?: return
        val h = mapView.height.takeIf { it > 0 } ?: return
        // With a junction picture showing, keep the car lower so the road ahead stays visible.
        val topPad = if (headingUp) h * (if (junctionPanel.visibility == View.VISIBLE) 0.52 else 0.40) else 0.0
        val pos = CameraPosition.Builder()
            .target(LatLng(pose.lat, pose.lon))
            .bearing(if (headingUp) pose.bearing else 0.0)
            .tilt(if (headingUp) 50.0 else 0.0)
            .zoom(zoom)
            .padding(0.0, topPad, 0.0, 0.0)
            .build()
        m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    private fun placeArrow(m: MapLibreMap, pose: PoseSmoother.Pose) {
        val p = m.projection.toScreenLocation(LatLng(pose.lat, pose.lon))
        val w = carArrow.width.takeIf { it > 0 } ?: return run { carArrow.visibility = View.VISIBLE }
        val onMap = p.x > -w && p.y > -w && p.x < mapView.width + w && p.y < mapView.height + w
        carArrow.visibility = if (onMap) View.VISIBLE else View.GONE
        carArrow.translationX = p.x - w / 2f
        carArrow.translationY = p.y - carArrow.height / 2f
        carArrow.rotation = (pose.bearing - m.cameraPosition.bearing).toFloat()
        // Lean the arrow back to match the tilted map.
        carArrow.rotationX = m.cameraPosition.tilt.toFloat() * 0.6f
    }

    private fun pauseFollow() {
        following = false
        recenterBtn.visibility = View.VISIBLE
        main.removeCallbacks(resumeFollow)
        if (core.mode != NavCore.Mode.PREVIEW) main.postDelayed(resumeFollow, RESUME_FOLLOW_MS)
    }

    private fun follow() {
        main.removeCallbacks(resumeFollow)
        following = true
        recenterBtn.visibility = View.GONE
        lastCam = null
        startFrameLoop()
    }

    private var focusedDest: Pt? = null

    /**
     * Centre [p] at street level in the clear part of the map: below the search bar and
     * above the place card (measured after the card has been laid out, so it can't hide it).
     */
    private fun focusDestination(p: Pt) {
        val m = map ?: return
        following = false
        recenterBtn.visibility = View.VISIBLE
        previewCard.post {
            val d = resources.displayMetrics.density
            val top = (findViewById<View>(R.id.searchBar).bottom + 24 * d).toDouble()
            val cardTop = if (previewCard.height > 0) previewCard.top else mapView.height / 2
            val bottom = ((mapView.height - cardTop) + 24 * d).toDouble().coerceAtLeast(0.0)
            m.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(DEST_ZOOM).bearing(0.0).tilt(0.0)
                    .padding(0.0, top, 0.0, bottom).build()), 800)
        }
    }

    // status line + theme check, once a second
    private var themeCheckAt = 0L
    private val statusTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - themeCheckAt > 60_000) { themeCheckAt = now; loadStyle() }
            val fix = core.lastFix
            statusText.text = when {
                core.simulating -> getString(R.string.simulate)
                !core.hasLocationPermission() -> getString(R.string.need_location)
                !core.gpsEnabled -> getString(R.string.gps_off)
                fix == null || core.secondsSinceFix > 5 -> getString(R.string.waiting_gps)
                else -> getString(R.string.accuracy_m, fix.accuracy.roundToInt()) +
                    if (OfflineMaps.hasOfflineMap(this@MainActivity)) " · " + getString(R.string.offline) else ""
            }
            if (!core.simulating && (fix == null || core.secondsSinceFix > 5)) speedText.text = getString(R.string.speed_placeholder)
            main.postDelayed(this, 1000)
        }
    }

    // ================================================================ search + places menu

    /** Set while choosing a place to save (Home / Work / a favourite) instead of driving there. */
    private enum class PickFor { HOME, WORK, FAVOURITE }
    private var pickFor: PickFor? = null
    private var showingCategory = false
    private var pendingText = ""
    private val pendingSearch = Runnable { runSearch(pendingText) }
    private var reopenSavedAfterPick = false

    private fun openSearch(pick: PickFor? = null) {
        if (!core.search.available) {
            AlertDialog.Builder(this).setTitle(R.string.where_to).setMessage(R.string.search_missing)
                .setPositiveButton(R.string.choose_file) { _, _ -> pickFiles.launch(arrayOf("*/*")) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        pickFor = pick
        searchPanel.visibility = View.VISIBLE
        searchInput.setText("")
        searchInput.hint = getString(when (pick) {
            PickFor.HOME -> R.string.search_hint_home
            PickFor.WORK -> R.string.search_hint_work
            PickFor.FAVOURITE -> R.string.search_hint_save
            null -> R.string.search_hint
        })
        buildCategoryGrid()
        buildShortcutSlots()
        showBrowse()
        if (pick == PickFor.HOME || pick == PickFor.WORK) {
            // Setting Home/Work: an address is what's wanted, so bring up the keyboard.
            searchInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearch() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchPanel.visibility = View.GONE
        main.removeCallbacks(pendingSearch)
        lastCam = null
        pickFor = null
        showingCategory = false
    }

    private fun here(): Pt? = core.lastFix?.let { Pt(it.latitude, it.longitude) }
        ?: map?.cameraPosition?.target?.let { Pt(it.latitude, it.longitude) }

    /** Browse menu (empty box) vs. a list of results. */
    private fun setResultsMode(on: Boolean) {
        findViewById<View>(R.id.browseScroll).visibility = if (on) View.GONE else View.VISIBLE
        searchList.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) {
            findViewById<View>(R.id.resultsHeaderRow).visibility = View.GONE
            searchEmpty.visibility = View.GONE
        }
    }

    /** The "Where to?" menu: Home/Work, place types, saved and recent places. */
    private fun showBrowse() {
        showingCategory = false
        setResultsMode(false)
        val picking = pickFor != null
        findViewById<View>(R.id.quickRow).visibility = View.GONE   // Home/Work are tiles now
        for (id in listOf(R.id.shortcutSlots, R.id.shortcutsHint)) findViewById<View>(id).visibility = if (picking) View.GONE else View.VISIBLE
        (findViewById<View>(R.id.shortcutsHint).parent as ViewGroup).let { col ->
            // the "Your shortcuts" header just above the hint
            col.getChildAt(col.indexOfChild(findViewById(R.id.shortcutsHint)) - 1)?.visibility = if (picking) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.homeBtn).text =
            getString(if (core.saved.get(SavedPlaces.Slot.HOME) != null) R.string.home else R.string.set_home_short)
        findViewById<Button>(R.id.workBtn).text =
            getString(if (core.saved.get(SavedPlaces.Slot.WORK) != null) R.string.work else R.string.set_work_short)
        fillSavedList(picking)
        fillRecentList()
    }

    private fun placeRow(container: LinearLayout, name: String, detail: String, pt: Pt?,
                         onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
        val v = layoutInflater.inflate(R.layout.item_place, container, false)
        v.findViewById<TextView>(R.id.placeName).text = name
        v.findViewById<TextView>(R.id.placeDetail).apply {
            text = detail; visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        }
        val near = here()
        v.findViewById<TextView>(R.id.placeDist).text =
            if (pt != null && near != null) Format.distance(Geo.distance(near, pt)) else ""
        v.isClickable = true
        v.isFocusable = true
        v.setBackgroundResource(android.R.drawable.list_selector_background)
        v.setOnClickListener { onClick() }
        onLongClick?.let { lc -> v.setOnLongClickListener { lc(); true } }
        container.addView(v)
    }

    private fun fillSavedList(picking: Boolean) {
        val list = findViewById<LinearLayout>(R.id.savedList)
        list.removeAllViews()
        val header = (list.parent as ViewGroup).let { p -> p.getChildAt(p.indexOfChild(list) - 1) }
        header.visibility = if (picking) View.GONE else View.VISIBLE
        if (picking) return
        val home = core.saved.get(SavedPlaces.Slot.HOME)
        val work = core.saved.get(SavedPlaces.Slot.WORK)
        home?.let { h -> placeRow(list, getString(R.string.home), h.detail, h.pt, { choosePlace(h.pt, getString(R.string.home_plain), h.detail) }) }
        work?.let { w -> placeRow(list, getString(R.string.work), w.detail, w.pt, { choosePlace(w.pt, getString(R.string.work_plain), w.detail) }) }
        core.saved.favourites().forEach { f ->
            placeRow(list, "⭐  ${f.name}", f.detail, f.pt, { choosePlace(f.pt, f.name, f.detail) }, { favouriteOptions(f) })
        }
        if (list.childCount == 0) {
            list.addView(TextView(this).apply {
                text = getString(R.string.saved_empty)
                setTextColor(ContextCompat.getColor(context, R.color.panel_subtext))
                textSize = 15f
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 3, pad, pad / 2)
            })
        }
    }

    private fun fillRecentList() {
        val list = findViewById<LinearLayout>(R.id.recentList)
        list.removeAllViews()
        val rec = core.saved.recents()
        findViewById<View>(R.id.recentHeader).visibility = if (rec.isEmpty()) View.GONE else View.VISIBLE
        rec.take(8).forEach { r -> placeRow(list, r.name, r.detail, r.pt, { choosePlace(r.pt, r.name, r.detail) }) }
    }

    /** The grid of place types (Gas, Food, Coffee…). */
    /** A tile you can put in your shortcuts: Home, Work, Favourites or a place type. */
    private class Tile(val key: String, val emoji: String, val label: String, val open: () -> Unit)

    private fun tile(key: String): Tile? = when (key) {
        Settings.HOME -> Tile(key, "🏠", getString(if (core.saved.get(SavedPlaces.Slot.HOME) != null) R.string.tile_home else R.string.tile_set_home)) { closeSearch(); goToSlot(SavedPlaces.Slot.HOME) }
        Settings.WORK -> Tile(key, "💼", getString(if (core.saved.get(SavedPlaces.Slot.WORK) != null) R.string.tile_work else R.string.tile_set_work)) { closeSearch(); goToSlot(SavedPlaces.Slot.WORK) }
        Settings.FAVOURITES -> Tile(key, "⭐", getString(R.string.tile_favourites)) { closeSearch(); showFavourites() }
        else -> Categories.all.firstOrNull { it.label == key }?.let { c -> Tile(key, c.emoji, c.label) { openSearch(); showCategory(c) } }
    }

    /** The 6 shortcut slots (2 rows of 3) above the tiles; drop a tile on one to replace it. */
    private fun buildShortcutSlots() {
        val box = findViewById<LinearLayout>(R.id.shortcutSlots)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        var row: LinearLayout? = null
        core.settings.shortcutSlots().forEachIndexed { i, key ->
            if (i % 3 == 0) row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { box.addView(it) }
            val t = tile(key) ?: return@forEachIndexed
            val v = layoutInflater.inflate(R.layout.item_category, row, false)
            v.findViewById<TextView>(R.id.catEmoji).text = t.emoji
            v.findViewById<TextView>(R.id.catLabel).text = t.label
            fun outline(hot: Boolean) {
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * d; setColor(if (hot) 0xFF1F4E6B.toInt() else 0xFF1B2A4A.toInt())
                    setStroke(((if (hot) 4 else 2) * d).toInt(), 0xFF2FD0FF.toInt())
                }
            }
            outline(false)
            v.layoutParams = LinearLayout.LayoutParams(0, (92 * d).toInt(), 1f).apply {
                val m = (4 * d).toInt(); setMargins(m, m, m, m)
            }
            v.setOnClickListener { t.open() }
            v.setOnDragListener { _, e ->
                when (e.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> e.clipDescription?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> { outline(true); true }
                    android.view.DragEvent.ACTION_DRAG_EXITED -> { outline(false); true }
                    android.view.DragEvent.ACTION_DROP -> {
                        e.clipData?.getItemAt(0)?.text?.toString()?.let { setShortcut(i, it) }
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> { outline(false); true }
                    else -> true
                }
            }
            row!!.addView(v)
        }
        // Let go somewhere else (e.g. the slots were scrolled away): ask which one to replace.
        findViewById<View>(R.id.categoryGrid).setOnDragListener { _, e ->
            if (e.action == android.view.DragEvent.ACTION_DRAG_STARTED) true
            else {
                if (e.action == android.view.DragEvent.ACTION_DRAG_ENDED && !e.result) (e.localState as? String)?.let { askShortcutSlot(it) }
                true
            }
        }
    }

    private fun askShortcutSlot(key: String) {
        val t = tile(key) ?: return
        val now = core.settings.shortcutSlots()
        if (key in now) return
        BigMenu.show(this, getString(R.string.shortcut_which, "${t.emoji} ${t.label}"),
            now.mapNotNull { tile(it) }.map { BigMenu.Choice(getString(R.string.shortcut_slot, it.label), it.emoji) }) { i ->
            setShortcut(i, key)
        }
    }

    private fun setShortcut(slot: Int, label: String) {
        core.settings.pinShortcut(slot, label)
        buildShortcutSlots()
        refreshQuickChips()
        tile(label)?.let { Toast.makeText(this, getString(R.string.shortcut_set, "${it.emoji} ${it.label}"), Toast.LENGTH_SHORT).show() }
    }

    private fun toggleRvMode() {
        val on = !core.settings.rvMode
        core.settings.rvMode = on
        refreshQuickChips()
        Toast.makeText(this, if (on) R.string.rv_now_on else R.string.rv_now_off, Toast.LENGTH_LONG).show()
    }

    private fun buildCategoryGrid() {
        val grid = findViewById<android.widget.GridLayout>(R.id.categoryGrid)
        grid.removeAllViews()
        val keys = (if (pickFor == null) listOf(Settings.HOME, Settings.WORK, Settings.FAVOURITES) else emptyList()) +
            Categories.visible(core.settings.rvMode).map { it.label }
        keys.mapNotNull { tile(it) }.forEach { t ->
            val v = layoutInflater.inflate(R.layout.item_category, grid, false)
            v.findViewById<TextView>(R.id.catEmoji).text = t.emoji
            v.findViewById<TextView>(R.id.catLabel).text = t.label
            v.layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = (92 * resources.displayMetrics.density).toInt()
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                val m = (4 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, m)
            }
            v.setOnClickListener {
                // Home/Work/Favourites act straight away; place types list what's nearby.
                if (t.key.startsWith("@")) t.open() else Categories.all.firstOrNull { it.label == t.key }?.let { showCategory(it) }
            }
            v.setOnLongClickListener { tv ->
                tv.startDragAndDrop(android.content.ClipData.newPlainText("tile", t.key), View.DragShadowBuilder(tv), t.key, 0)
            }
            grid.addView(v)
        }
    }


    private fun runSearch(text: String) {
        lastSearchText = text
        if (text.isBlank()) { if (!showingCategory) showBrowse(); return }
        showingCategory = false
        val near = here()
        io.execute {
            val results = core.search.search(text, near)
            runOnUiThread {
                if (text != lastSearchText) return@runOnUiThread
                setResultsMode(true)
                findViewById<View>(R.id.resultsHeaderRow).visibility = View.GONE
                searchList.adapter = RowAdapter(results.map { Row(it.name, it.subtitle, it.pt) }, near)
                searchEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                searchEmpty.text = getString(R.string.no_results)
            }
        }
    }

    private data class Row(
        val name: String, val detail: String, val pt: Pt?,
        val header: Boolean = false, val saved: SavedPlaces.Saved? = null,
        /** Shown instead of straight-line distance (e.g. "12 km ahead"). */
        val distText: String? = null,
        /** A spot reported by Orbit users (spots.orbitgps.ca): can be voted on. */
        val spotId: Long? = null,
    )

    private inner class RowAdapter(items: List<Row>, val near: Pt?) :
        ArrayAdapter<Row>(this, R.layout.item_place, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_place, parent, false)
            val r = getItem(position)!!
            val name = v.findViewById<TextView>(R.id.placeName)
            name.text = r.name
            name.textSize = if (r.header) 14f else 18f
            name.setTextColor(if (r.header) ContextCompat.getColor(context, R.color.accent) else 0xFFFFFFFF.toInt())
            val detail = v.findViewById<TextView>(R.id.placeDetail)
            detail.text = r.detail
            detail.visibility = if (r.detail.isBlank()) View.GONE else View.VISIBLE
            v.findViewById<TextView>(R.id.placeDist).text = r.distText
                ?: if (r.pt != null && near != null) Format.distance(Geo.distance(near, r.pt)) else ""
            val arrow = v.findViewById<ImageView>(R.id.placeArrow)
            val dir = if (!r.header && r.pt != null) core.directionTo(r.pt) else null
            if (dir == null) arrow.visibility = if (r.header) View.GONE else View.INVISIBLE
            else {
                arrow.visibility = View.VISIBLE
                arrow.rotation = dir.toFloat()
                // Places behind you are dimmed a little.
                arrow.alpha = if (kotlin.math.abs(dir) > 100) 0.55f else 1f
                arrow.contentDescription = getString(if (kotlin.math.abs(dir) > 100) R.string.dir_behind else R.string.dir_ahead)
            }
            return v
        }
        override fun isEnabled(position: Int) = !getItem(position)!!.header
    }

    // ================================================================ reported spots

    /** Report a spot at [pt]: pick what it is, then a note and a few ticks. */
    private fun reportSpot(pt: Pt) {
        val types = com.robotdash.orbitgps.spots.Spots.Type.entries
        // The nearest address takes a moment with all of BC: look it up while they choose.
        val where = java.util.concurrent.FutureTask {
            core.search.nearestAddress(pt) ?: String.format(java.util.Locale.CANADA, "%.5f, %.5f", pt.lat, pt.lon)
        }
        io.execute(where)
        BigMenu.show(this, getString(R.string.report_title), types.map { BigMenu.Choice(it.label, it.emoji) }) { i ->
            io.execute {
                val w = runCatching { where.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                    .getOrDefault(String.format(java.util.Locale.CANADA, "%.5f, %.5f", pt.lat, pt.lon))
                runOnUiThread { reportDetails(pt, types[i], w) }
            }
        }
    }

    private fun reportDetails(pt: Pt, type: com.robotdash.orbitgps.spots.Spots.Type, where: String) {
        val flags = com.robotdash.orbitgps.spots.Spots.Flag.entries
        val note = android.widget.EditText(this).apply {
            hint = getString(R.string.report_note_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            filters = arrayOf(android.text.InputFilter.LengthFilter(300))
        }
        BigMenu.checklist(this, "${type.emoji} ${type.label}", flags.map { BigMenu.Check(it.label) },
            message = getString(R.string.report_where, where) + "\n" + getString(R.string.report_privacy),
            actionLabel = { getString(R.string.report_send) }, input = note) { ticks ->
            val chosen = flags.filterIndexed { i, _ -> ticks[i] }.toSet()
            com.robotdash.orbitgps.spots.Spots.report(this, type, pt, note.text.toString(), chosen)
            sendSpots(announce = true)
        }
    }

    /** Send waiting reports and votes (if there's signal) and fetch newly approved spots. */
    private fun sendSpots(announce: Boolean = false) {
        io.execute {
            val sent = runCatching { com.robotdash.orbitgps.spots.Spots.flush(this) }.getOrDefault(0)
            val waiting = com.robotdash.orbitgps.spots.Spots.waiting(this)
            runCatching { com.robotdash.orbitgps.spots.Spots.refresh(this) }
            if (announce) runOnUiThread {
                Toast.makeText(this, if (sent > 0 && waiting == 0) R.string.report_sent else R.string.report_queued, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** A spot reported by Orbit users: go there, or say whether it's still good. */
    private fun showSpotCard(r: Row, number: Int = 0) {
        val id = r.spotId ?: return
        val dist = r.distText ?: here()?.let { h -> r.pt?.let { Format.distance(Geo.distance(h, it)) } }
        val voted = com.robotdash.orbitgps.spots.Spots.votedOn(this, id)
        val choices = mutableListOf(BigMenu.Choice(getString(R.string.go_here), "🚗"))
        if (!voted) {
            choices += BigMenu.Choice(getString(R.string.spot_still_good), "👍", getString(R.string.spot_still_good_detail))
            choices += BigMenu.Choice(getString(R.string.spot_gone), "👎", getString(R.string.spot_gone_detail))
        }
        val title = (if (number > 0) "$number.  " else "") + r.name
        BigMenu.show(this, title, choices, message = listOfNotNull(dist, r.detail.ifBlank { null }, getString(R.string.spot_reported_by_users))
            .joinToString("\n"), cancelLabel = getString(R.string.close)) { i ->
            when (i) {
                0 -> r.pt?.let { clearResultPins(); choosePlace(it, r.name, r.detail) }
                else -> {
                    com.robotdash.orbitgps.spots.Spots.vote(this, id, good = i == 1)
                    sendSpots()
                    Toast.makeText(this, R.string.spot_vote_thanks, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Send queued reports the moment the phone gets signal again. */
    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (com.robotdash.orbitgps.spots.Spots.waiting(this@MainActivity) > 0) sendSpots()
        }
    }

    private var lastCategory: Category? = null
    /** The category results currently in the list. */
    private var categoryRows: List<Row> = emptyList()
    /** The results showing as pins on the map (empty = none). */
    private var mapRows: List<Row> = emptyList()

    /** List → Map: close the list and show the results as numbered pins. */
    private fun showResultsOnMap() {
        val cat = lastCategory ?: return
        val rows = categoryRows.filter { it.pt != null }.take(MAP_RESULTS)
        if (rows.isEmpty()) return
        closeSearch()
        mapRows = rows
        setResultPins(rows)
        val bar = findViewById<View>(R.id.mapResultsBar)
        findViewById<TextView>(R.id.mapResultsTitle).text = getString(R.string.map_results_title, cat.emoji, cat.label)
        // Below the turn banner while navigating, else where the shortcut chips are.
        (bar.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin =
            if (core.mode == NavCore.Mode.NAVIGATING) navBanner.bottom + (8 * resources.displayMetrics.density).toInt()
            else (102 * resources.displayMetrics.density).toInt()
        bar.visibility = View.VISIBLE
        findViewById<View>(R.id.quickChips).visibility = View.GONE
        fitResults(rows)
    }

    private fun setResultPins(rows: List<Row>) {
        val style = map?.style ?: return
        if (!style.isFullyLoaded) return
        val feats = rows.mapIndexedNotNull { i, r -> r.pt?.let {
            """{"type":"Feature","properties":{"i":$i,"n":"${i + 1}"},"geometry":{"type":"Point","coordinates":[${it.lon},${it.lat}]}}"""
        } }
        (style.getSource(RESULTS_SRC) as? GeoJsonSource)?.setGeoJson("""{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}""")
    }

    /**
     * North up and flat, you in the middle, zoomed out just enough to show the nearest few.
     * (Oct 22: framing the 8 nearest put the middle of them, e.g. a lake, on screen instead of
     * you, and kept the map's heading-up rotation and tilt.)
     */
    private fun fitResults(rows: List<Row>) {
        val m = map ?: return
        following = false
        recenterBtn.visibility = View.VISIBLE
        val me = here()
        val b = org.maplibre.android.geometry.LatLngBounds.Builder()
        val near = rows.mapNotNull { it.pt }.take(5)
        if (me != null) {
            b.include(LatLng(me.lat, me.lon))
            // Each pin and its mirror image through you: keeps you centred.
            near.forEach { p -> b.include(LatLng(p.lat, p.lon)); b.include(LatLng(2 * me.lat - p.lat, 2 * me.lon - p.lon)) }
        } else near.forEach { b.include(LatLng(it.lat, it.lon)) }
        val bar = findViewById<View>(R.id.mapResultsBar)
        bar.post {
            val d = resources.displayMetrics.density
            val top = bar.bottom + (40 * d).toInt()
            val bottom = (if (core.mode == NavCore.Mode.NAVIGATING) navBottom.height else 0) + (60 * d).toInt()
            val side = (40 * d).toInt()
            val bounds = runCatching { b.build() }.getOrNull()
            val update = if (bounds == null || near.size <= 1 && me == null)
                (near.firstOrNull() ?: me)?.let {
                    CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(it.lat, it.lon))
                        .zoom(DEST_ZOOM).bearing(0.0).tilt(0.0).build())
                }
            else CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0, side, top, side, bottom)
            update?.let { m.animateCamera(it, 800) }
        }
    }

    /** Tapped a pin: what it is, and go there. */
    private fun showPinCard(r: Row) {
        if (r.spotId != null) { showSpotCard(r, mapRows.indexOf(r) + 1); return }
        val i = mapRows.indexOf(r)
        val dist = r.distText ?: here()?.let { h -> r.pt?.let { Format.distance(Geo.distance(h, it)) } }
        val msg = listOfNotNull(dist, r.detail.ifBlank { null }).joinToString(" · ")
        BigMenu.show(this, "${i + 1}.  ${r.name}", listOf(BigMenu.Choice(
            getString(R.string.go_here), "🚗")),
            message = msg.ifBlank { null }, cancelLabel = getString(R.string.close)) {
            val pt = r.pt ?: return@show
            clearResultPins()
            choosePlace(pt, r.name, r.detail)
        }
    }

    private fun clearResultPins() {
        if (mapRows.isEmpty()) return
        mapRows = emptyList()
        setResultPins(emptyList())
        findViewById<View>(R.id.mapResultsBar).visibility = View.GONE
        if (core.mode == NavCore.Mode.IDLE) findViewById<View>(R.id.quickChips).visibility = View.VISIBLE
    }

    private fun showCategory(cat: Category) {
        val near = here() ?: return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
        showingCategory = true
        // While navigating, look along the rest of the route.
        val ahead = if (core.mode == NavCore.Mode.NAVIGATING) {
            val r = core.navigator?.route
            val along = core.progress?.along ?: 0.0
            r?.points?.filterIndexed { i, _ -> r.cumulative[i] >= along }
        } else null
        lastSearchText = "\u0000cat:${cat.label}"
        val token = lastSearchText
        setResultsMode(true)
        findViewById<TextView>(R.id.resultsHeader).text = "${cat.emoji} ${cat.label}"
        findViewById<View>(R.id.resultsHeaderRow).visibility = View.VISIBLE
        lastCategory = cat
        categoryRows = emptyList()
        searchList.adapter = RowAdapter(emptyList(), near)
        findViewById<View>(R.id.resultsMapBtn).visibility = View.GONE
        io.execute {
            // Camping-type places include spots people reported: get the latest list first
            // (quick, and only if it's been 15 minutes).
            val spotsCat = com.robotdash.orbitgps.spots.Spots.typesFor(cat.label).isNotEmpty()
            if (spotsCat) runCatching { com.robotdash.orbitgps.spots.Spots.refresh(this, maxAgeMs = 15 * 60_000L) }
            val found = core.search.nearby(cat, near, ahead, extra = com.robotdash.orbitgps.spots.Spots.places(this, cat.label))
            runOnUiThread {
                if (lastSearchText != token) return@runOnUiThread
                val alongRoute = found.firstOrNull()?.offRoute != null
                findViewById<TextView>(R.id.resultsHeader).text =
                    getString(if (alongRoute) R.string.cat_along else R.string.cat_nearest, cat.emoji, cat.label)
                categoryRows = found.map { f ->
                    val dist = if (f.offRoute != null) getString(R.string.km_ahead, Format.distance(f.distance))
                        else Format.distance(f.distance)
                    val sub = listOfNotNull(f.place.detail.takeIf { it.isNotBlank() },
                        f.offRoute?.takeIf { it > 150 }?.let { getString(R.string.off_route, Format.distance(it)) })
                        .joinToString(" · ")
                    Row(f.place.name, sub, f.place.pt, distText = dist, spotId = com.robotdash.orbitgps.spots.Spots.idOf(f.place))
                }
                searchList.adapter = RowAdapter(categoryRows, near)
                // Only reported spots (no map data, e.g. Overnight parking): say how to add one.
                searchEmpty.text = getString(if (cat.match.isEmpty()) R.string.cat_none_reported else R.string.cat_none)
                searchEmpty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
                findViewById<View>(R.id.resultsMapBtn).visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------- picking a place

    private fun slotLabel(slot: SavedPlaces.Slot) =
        getString(if (slot == SavedPlaces.Slot.HOME) R.string.home_plain else R.string.work_plain)

    /** Home / Work: drive there, or if not set yet, set it now. */
    private fun goToSlot(slot: SavedPlaces.Slot) {
        val s = core.saved.get(slot) ?: return askSetSlot(slot)
        choosePlace(s.pt, slotLabel(slot), s.detail)
    }

    /** A place was picked: save it (when choosing one to save), plan a route, or mid-trip ask stop / go. */
    private fun choosePlace(pt: Pt, name: String, detail: String) {
        pickFor?.let { savePicked(it, pt, name, detail); return }
        closeSearch()
        if (core.mode != NavCore.Mode.NAVIGATING) { previewTo(pt, name, detail); return }
        BigMenu.show(this, getString(R.string.stop_or_go_title, name), listOf(
            BigMenu.Choice(getString(R.string.add_stop), "➕", getString(R.string.add_stop_detail, core.destinationName)),
            BigMenu.Choice(getString(R.string.go_instead), "🏁", getString(R.string.go_instead_detail)),
        ), message = detail.ifBlank { null }) { which ->
            if (which == 0) core.addStop(pt, name) { ok ->
                Toast.makeText(this,
                    if (ok) getString(R.string.stop_added, name) else getString(R.string.stop_failed, core.stopError ?: ""),
                    Toast.LENGTH_LONG).show()
            } else core.goInstead(pt, name, detail)
        }
    }

    private fun savePicked(target: PickFor, pt: Pt, name: String, detail: String) {
        val label = listOf(name, detail).filter { it.isNotBlank() }.joinToString(", ")
        when (target) {
            PickFor.HOME -> core.saved.set(SavedPlaces.Slot.HOME, SavedPlaces.Saved(getString(R.string.home_plain), label, pt))
            PickFor.WORK -> core.saved.set(SavedPlaces.Slot.WORK, SavedPlaces.Saved(getString(R.string.work_plain), label, pt))
            PickFor.FAVOURITE -> core.saved.addFavourite(SavedPlaces.Saved(name, detail, pt))
        }
        Toast.makeText(this, getString(when (target) {
            PickFor.HOME -> R.string.home_saved; PickFor.WORK -> R.string.work_saved; PickFor.FAVOURITE -> R.string.fav_saved
        }, name), Toast.LENGTH_LONG).show()
        closeSearch()
        refreshQuickChips()
        if (reopenSavedAfterPick) { reopenSavedAfterPick = false; showSavedPlaces() }
    }

    /** "Set Home": current location or search for the address. */
    private fun askSetSlot(slot: SavedPlaces.Slot) {
        val target = if (slot == SavedPlaces.Slot.HOME) PickFor.HOME else PickFor.WORK
        BigMenu.show(this, getString(if (slot == SavedPlaces.Slot.HOME) R.string.set_home_title else R.string.set_work_title),
            listOf(BigMenu.Choice(getString(R.string.use_current_location)), BigMenu.Choice(getString(R.string.search_address))),
            onCancel = { reopenSavedAfterPick = false },
        ) { which -> if (which == 0) saveCurrentLocation(target) else openSearch(target) }
    }

    /** Save where you are now as Home, Work or a named favourite. */
    private fun saveCurrentLocation(target: PickFor) {
        val f = core.lastFix ?: run { Toast.makeText(this, R.string.need_fix, Toast.LENGTH_LONG).show(); return }
        val pt = Pt(f.latitude, f.longitude)
        io.execute {
            val addr = core.search.nearestAddress(pt) ?: ""
            runOnUiThread {
                if (target != PickFor.FAVOURITE) {
                    pickFor = target
                    savePicked(target, pt, addr.ifBlank { getString(R.string.my_location) }, "")
                    return@runOnUiThread
                }
                askName(addr.ifBlank { getString(R.string.my_location) }) { name ->
                    pickFor = PickFor.FAVOURITE
                    savePicked(PickFor.FAVOURITE, pt, name, addr.takeIf { it != name } ?: "")
                }
            }
        }
    }

    private fun askName(suggested: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(suggested); setSelection(text.length); inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this).setTitle(R.string.name_this_place).setView(box)
            .setPositiveButton(R.string.save_plain) { _, _ -> done(input.text.toString().trim().ifBlank { suggested }) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun favouriteOptions(f: SavedPlaces.Saved) {
        BigMenu.show(this, f.name, listOf(
            BigMenu.Choice(getString(R.string.drive_here), "🚗"),
            BigMenu.Choice(getString(R.string.rename), "✏️"),
            BigMenu.Choice(getString(R.string.remove), "🗑️", danger = true),
        ), message = f.detail.ifBlank { null }) { which ->
            when (which) {
                0 -> choosePlace(f.pt, f.name, f.detail)
                1 -> askName(f.name) { n -> core.saved.renameFavourite(f, n); refreshSaved() }
                2 -> { core.saved.removeFavourite(f); refreshSaved() }
            }
        }
    }

    private fun refreshSaved() {
        if (searchPanel.visibility == View.VISIBLE && !showingCategory) showBrowse()
        refreshQuickChips()
    }

    /** Saved places screen: Home, Work, favourites, and ways to add them. */
    private fun showSavedPlaces() {
        val d = resources.displayMetrics.density
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        lateinit var dialog: AlertDialog
        fun slotRow(slot: SavedPlaces.Slot) {
            val s = core.saved.get(slot)
            val title = getString(if (slot == SavedPlaces.Slot.HOME) R.string.home else R.string.work)
            placeRow(column, title, s?.detail?.ifBlank { null } ?: getString(if (s == null) R.string.not_set else R.string.saved),
                s?.pt, {
                    dialog.dismiss()
                    if (s == null) { reopenSavedAfterPick = true; askSetSlot(slot) }
                    else BigMenu.show(this, title, listOf(
                        BigMenu.Choice(getString(R.string.drive_here), "🚗"),
                        BigMenu.Choice(getString(R.string.change), "✏️"),
                        BigMenu.Choice(getString(R.string.remove), "🗑️", danger = true),
                    ), message = s.detail.ifBlank { null }) { w ->
                        when (w) {
                            0 -> choosePlace(s.pt, slotLabel(slot), s.detail)
                            1 -> { reopenSavedAfterPick = true; askSetSlot(slot) }
                            2 -> { core.saved.clear(slot); refreshQuickChips(); showSavedPlaces() }
                        }
                    }
                })
        }
        slotRow(SavedPlaces.Slot.HOME)
        slotRow(SavedPlaces.Slot.WORK)
        core.saved.favourites().forEach { f ->
            placeRow(column, "⭐  ${f.name}", f.detail, f.pt, { dialog.dismiss(); favouriteOptions(f) })
        }
        fun addButton(text: Int, action: () -> Unit) {
            column.addView(Button(this, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TonalButton).apply {
                setText(text)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), 0) }
                setOnClickListener { dialog.dismiss(); action() }
            })
        }
        addButton(R.string.save_current_location) { reopenSavedAfterPick = true; saveCurrentLocation(PickFor.FAVOURITE) }
        addButton(R.string.find_place_to_save) { reopenSavedAfterPick = true; openSearch(PickFor.FAVOURITE) }
        val scroll = ScrollView(this).apply { addView(column) }
        dialog = AlertDialog.Builder(this).setTitle(R.string.saved_places_title).setView(scroll)
            .setPositiveButton(R.string.done, null).create()
        dialog.show()
    }

    /** ⭐ Favourites button: saved places as big tiles, nearest first; tap one to go there. */
    private fun showFavourites() {
        val here = core.lastShown ?: core.lastFix
        val favs = core.saved.favourites().let { list ->
            if (here == null) list
            else list.sortedBy { Geo.distance(Pt(here.latitude, here.longitude), it.pt) }
        }
        val tiles = favs.map { f ->
            val away = here?.let { Format.distance(Geo.distance(Pt(it.latitude, it.longitude), f.pt)) }
            BigMenu.Choice(f.name, "⭐", listOfNotNull(away, f.detail.ifBlank { null }).joinToString(" · "))
        } + BigMenu.Choice(getString(R.string.fav_manage), "✏️", getString(R.string.fav_manage_detail))
        BigMenu.show(this, getString(R.string.favourites_title), tiles,
            message = if (favs.isEmpty()) getString(R.string.fav_none) else null,
            cancelLabel = getString(R.string.close)) { i ->
            if (i < favs.size) favs[i].let { choosePlace(it.pt, it.name, it.detail) } else showSavedPlaces()
        }
    }

    /** One-tap buttons under "Where to?": Home, Work, Gas, Food, Coffee, Rest areas, More. */
    private fun refreshQuickChips() {
        val row = findViewById<LinearLayout>(R.id.quickChipRow)
        row.removeAllViews()
        val d = resources.displayMetrics.density
        fun chip(text: String, onClick: () -> Unit) {
            row.addView(TextView(this).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.chip_bg)
                setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (8 * d).toInt() }
                setOnClickListener { onClick() }
            })
        }

        // Your 3 shortcuts (Where to? → Your shortcuts), plus Free camping in RV mode.
        val keys = core.settings.shortcutSlots().toMutableList()
        if (core.settings.rvMode && "Free camping" !in keys) keys += "Free camping"
        for (t in keys.mapNotNull { tile(it) }) chip("${t.emoji} ${t.label}") { t.open() }
        chip(getString(R.string.more_places)) { openSearch() }
    }

    /** ☰ menu. */
    private fun showMainMenu() {
        val entries = buildList<Pair<String, () -> Unit>> {
            if (core.mode == NavCore.Mode.NAVIGATING && core.stops.isNotEmpty())
                add(getString(R.string.menu_stops, core.stops.size) to { showStops() })
            add(getString(R.string.menu_saved) to { showSavedPlaces() })
            add(getString(R.string.menu_find) to { openSearch() })
            add(getString(R.string.menu_style) to { chooseMapStyle() })
            add(getString(R.string.menu_maps) to { regionMenu() })
            add(getString(if (core.settings.rvMode) R.string.menu_rv_on else R.string.menu_rv_off) to { toggleRvMode() })
            add(getString(R.string.menu_report) to { here()?.let { reportSpot(it) } })
            add(getString(R.string.menu_voice) to { voiceCheck() })
            add(getString(R.string.menu_settings) to { showSettings() })
            add(getString(R.string.menu_about) to { showAbout() })
        }
        BigMenu.show(this, getString(R.string.app_name), entries.map { BigMenu.Choice(it.first) },
            cancelLabel = getString(R.string.close)) { which -> entries[which].second() }
    }

    /** Stops on this trip: tap one to remove it (plans change). */
    private fun showStops() {
        val stops = core.stops
        if (stops.isEmpty()) return
        BigMenu.show(this, getString(R.string.stops_title),
            stops.mapIndexed { i, s -> BigMenu.Choice("${i + 1}.  ${s.name}", detail = getString(R.string.stop_remove)) }) { which ->
            val s = stops[which]
            BigMenu.show(this, s.name, listOf(BigMenu.Choice(getString(R.string.stop_remove), "✕", danger = true)),
                message = getString(R.string.stop_remove_q)) {
                val i = core.stops.indexOf(s)
                if (i >= 0) core.removeStop(i)
            }
        }
    }

    /** After driving past a stop: ask whether to go back to it or carry on (carrying on is the default). */
    private var shownPassed: NavCore.Stop? = null
    private var goBackDialog: AlertDialog? = null
    private fun offerGoBack() {
        val s = core.passedStop
        if (s == null) { goBackDialog?.let { runCatching { it.dismiss() } }; goBackDialog = null; return }
        if (s == shownPassed) return
        shownPassed = s
        goBackDialog = BigMenu.show(this, getString(R.string.stop_passed_title, s.name), listOf(
            BigMenu.Choice(getString(R.string.stop_go_back_to, s.name), "↩", getString(R.string.stop_go_back_detail)),
            BigMenu.Choice(getString(R.string.stop_continue), "🏁", getString(R.string.stop_continue_detail)),
        ), message = getString(R.string.stop_passed_ask), cancelLabel = null) { which ->
            goBackDialog = null
            if (which == 0) core.goBackToPassedStop() else core.dismissPassedStop()
        }
    }

    private fun chooseMapStyle() {
        val entries = OfflineMaps.MapStyle.entries
        val icons = mapOf(OfflineMaps.MapStyle.STANDARD to "🗺️", OfflineMaps.MapStyle.TOPO to "⛰️", OfflineMaps.MapStyle.GREY to "◐")
        BigMenu.show(this, getString(R.string.map_style), entries.map {
            BigMenu.Choice(getString(styleLabel(it)), icons[it] ?: "", selected = it == core.settings.mapStyle)
        }) { i -> setMapStyle(entries[i]) }
    }

    private fun styleLabel(st: OfflineMaps.MapStyle) = when (st) {
        OfflineMaps.MapStyle.STANDARD -> R.string.style_standard
        OfflineMaps.MapStyle.TOPO -> R.string.style_topo
        OfflineMaps.MapStyle.GREY -> R.string.style_grey
    }

    private fun setMapStyle(st: OfflineMaps.MapStyle) {
        core.settings.mapStyle = st
        if (OfflineMaps.hasOfflineMap(this) && OfflineMaps.effectiveStyle(this, st) != st) {
            AlertDialog.Builder(this).setTitle(R.string.map_style).setMessage(R.string.topo_needs_terrain)
                .setPositiveButton(R.string.choose_file) { _, _ -> pickFiles.launch(arrayOf("*/*")) }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        } else if (!OfflineMaps.hasOfflineMap(this) && st != OfflineMaps.MapStyle.STANDARD) {
            Toast.makeText(this, R.string.style_needs_offline, Toast.LENGTH_LONG).show()
        }
        loadStyle(force = true)
    }

    private fun saveDestination() {
        val dest = core.destination ?: return
        val item = SavedPlaces.Saved(core.destinationName, core.destinationDetail, dest)
        BigMenu.show(this, getString(R.string.save_as), listOf(
            BigMenu.Choice(getString(R.string.set_home), "🏠"),
            BigMenu.Choice(getString(R.string.set_work), "💼"),
            BigMenu.Choice(getString(R.string.add_fav), "⭐"),
        ), message = listOf(item.name, item.detail).filter { it.isNotBlank() }.joinToString(", ")) { which ->
                when (which) {
                    0 -> core.saved.set(SavedPlaces.Slot.HOME, item.copy(name = getString(R.string.home_plain),
                        detail = listOf(item.name, item.detail).filter { it.isNotBlank() }.joinToString(", ")))
                    1 -> core.saved.set(SavedPlaces.Slot.WORK, item.copy(name = getString(R.string.work_plain),
                        detail = listOf(item.name, item.detail).filter { it.isNotBlank() }.joinToString(", ")))
                    else -> { askName(item.name) { n -> core.saved.addFavourite(item.copy(name = n)); refreshQuickChips() }; return@show }
                }
                refreshQuickChips()
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
    }

    // ================================================================ route + navigation

    private fun previewTo(dest: Pt, name: String, detail: String = "") {
        if (!core.routes.available) {
            AlertDialog.Builder(this).setTitle(R.string.where_to).setMessage(R.string.routing_missing)
                .setPositiveButton(R.string.choose_file) { _, _ -> pickFiles.launch(arrayOf("*/*")) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        core.preview(dest, name, detail)
    }

    private fun startNav(simulate: Boolean) {
        askNotifications()
        warnIfVoiceUnheard()
        core.start(simulate)
    }

    private fun confirmStop() {
        BigMenu.show(this, getString(R.string.stop_question), listOf(
            BigMenu.Choice(getString(R.string.stop_navigation), "⏹", danger = true),
        ), cancelLabel = getString(R.string.keep_going)) { core.stop() }
    }

    private fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun updateBanner() {
        val nav = core.navigator ?: return
        val prog = core.progress
        val ann = nav.route.announced
        val step = prog?.let { ann.getOrNull(it.nextIndex) }
        if (nav.arrived || (prog != null && step == null)) {
            turnIcon.setImageResource(R.drawable.turn_arrive); turnIcon.scaleX = 1f
            turnDistance.text = getString(R.string.arrived_toast)
            turnText.text = core.destinationName
            laneRow.visibility = View.GONE; thenText.visibility = View.GONE
            hideJunction()
        } else if (prog == null || step == null) {
            turnIcon.setImageResource(R.drawable.turn_straight); turnIcon.scaleX = 1f
            turnDistance.text = ""
            turnText.text = getString(R.string.navigating_to, core.destinationName)
            hideJunction()
        } else {
            setTurnIcon(turnIcon, step)
            turnDistance.text = Format.distance(prog.distanceToNext)
            turnText.text = if (step.maneuver?.waypoint == true) getString(R.string.your_stop, core.stopName(step) ?: "")
                else step.instruction.trimEnd('.')
            showLanes(step.maneuver?.lanes)
            // The junction picture shows the lanes itself.
            if (showJunction(nav.route, step, prog)) laneRow.visibility = View.GONE
            val next = ann.getOrNull(prog.nextIndex + 1)
            val gap = next?.let { it.distanceFromStart - step.distanceFromStart }
            if (next != null && gap != null && gap < 250 && next.maneuver?.type != ManeuverType.ARRIVE) {
                thenText.text = getString(R.string.then_short, next.instruction.trimEnd('.'))
                thenText.visibility = View.VISIBLE
            } else thenText.visibility = View.GONE
            if (prog.offRoute) {
                // Parked off the road: no point saying "recalculating".
                val stopped = (core.lastFix?.speed ?: 0f) < com.robotdash.orbitgps.nav.Navigator.MOVING_MS
                turnText.text = getString(if (stopped) R.string.drive_to_route else R.string.recalculating)
                hideJunction()
            }
        }
        if (prog == null || step == null) hideJunction()
        val secs = core.secondsLeft()
        etaText.text = getString(R.string.arrive_at, timeAt(secs))
        remainingText.text = "${Format.distance(prog?.remaining ?: nav.route.length)} · ${Format.duration(secs)}"
    }

    /** Show (or keep showing) the junction picture for [step] once it's close. Returns true if shown. */
    private fun showJunction(route: Route, step: NavStep, prog: RouteTracker.Progress): Boolean {
        val j = step.junction
        if (j == null || !core.settings.junctionPictures || prog.offRoute) { hideJunction(); return false }
        val showAt = JunctionRenderer.showDistance(j, core.lastFix?.speed ?: 0f)
        if (prog.distanceToNext > showAt) { hideJunction(); return false }
        val night = nightShown ?: false
        val w = navBanner.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = minOf((w * 0.62f).toInt(), (resources.displayMetrics.heightPixels * 0.38f).toInt())
        val key = "${System.identityHashCode(route)}:${step.pointIndex}:$night:$w:$h"
        if (key != junctionKey) {
            val bmp = runCatching { JunctionRenderer.render(route, step, w, h, night) }.getOrNull()
            if (bmp == null) { hideJunction(); return false }
            junctionImage.setImageBitmap(bmp)
            junctionKey = key
        }
        junctionBar.fraction = (1 - prog.distanceToNext / showAt).toFloat()
        if (junctionPanel.visibility != View.VISIBLE) {
            junctionPanel.visibility = View.VISIBLE
            lastCam = null
        }
        return true
    }

    private fun hideJunction() {
        if (junctionPanel.visibility == View.GONE) return
        junctionPanel.visibility = View.GONE
        lastCam = null
        junctionKey = null
        junctionImage.setImageDrawable(null)
    }

    /** The list of turns: everything ahead while navigating, or the whole route in preview. */
    private fun showTurnList() {
        val route = core.currentRoute ?: return
        val navigating = core.mode == NavCore.Mode.NAVIGATING
        val along = if (navigating) core.progress?.along ?: 0.0 else 0.0
        val steps = route.announced.filter { it.distanceFromStart > along + 3 }
        if (steps.isEmpty()) return
        val v = layoutInflater.inflate(R.layout.dialog_turns, null)
        v.findViewById<TextView>(R.id.turnsTitle).text =
            if (navigating) getString(R.string.turns_title) else getString(R.string.turns_title_preview, core.destinationName)
        val remaining = route.length - along
        val secs = if (navigating) core.secondsLeft() else route.durationSec
        v.findViewById<TextView>(R.id.turnsSummary).text =
            getString(R.string.turns_summary, Format.distance(remaining), Format.duration(secs), steps.size)
        v.findViewById<ListView>(R.id.turnsList).adapter = object : ArrayAdapter<NavStep>(this, R.layout.item_turn, steps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_turn, parent, false)
                val st = getItem(position)!!
                setTurnIcon(row.findViewById(R.id.turnRowIcon), st)
                val waypoint = st.maneuver?.waypoint == true
                row.findViewById<TextView>(R.id.turnRowText).text =
                    if (waypoint) getString(R.string.your_stop, core.stopName(st) ?: "") else st.instruction.trimEnd('.')
                // How far you then drive before the next turn.
                val next = steps.getOrNull(position + 1)
                val stretch = next?.let { it.distanceFromStart - st.distanceFromStart }
                row.findViewById<TextView>(R.id.turnRowSub).text = when {
                    stretch == null -> ""
                    st.streetName.isNotBlank() -> getString(R.string.turn_then_for, Format.distance(stretch), st.streetName)
                    else -> getString(R.string.turn_then_plain, Format.distance(stretch))
                }
                row.findViewById<TextView>(R.id.turnRowDist).text = Format.distance(st.distanceFromStart - along)
                return row
            }
        }
        AlertDialog.Builder(this).setView(v).setPositiveButton(android.R.string.ok, null)
            .apply { if (navigating && core.stops.isNotEmpty()) setNeutralButton(getString(R.string.stops_button, core.stops.size)) { _, _ -> showStops() } }
            .show()
    }

    private fun updateMuteIcon() {
        muteBtn.setImageResource(if (core.settings.voiceOn) R.drawable.ic_voice else R.drawable.ic_voice_off)
    }

    /** Sit the button column just above whichever bottom panel is showing. */
    private fun placeButtons() {
        val d = resources.displayMetrics.density
        val below = when {
            navBottom.visibility == View.VISIBLE -> navBottom.height
            previewCard.visibility == View.VISIBLE -> previewCard.height + (12 * d).toInt()
            else -> 0
        }
        val lp = fabColumn.layoutParams as android.widget.FrameLayout.LayoutParams
        val want = below + (if (below > 0) 16 * d else 40 * d).toInt()
        if (lp.bottomMargin != want) { lp.bottomMargin = want; fabColumn.layoutParams = lp }
    }

    private fun setTurnIcon(view: ImageView, step: NavStep) {
        view.setImageResource(TurnIcons.drawable(step.icon))
        view.scaleX = if (TurnIcons.mirrored(step)) -1f else 1f
    }

    private fun showLanes(lanes: Lanes?) {
        laneRow.removeAllViews()
        val v = lanes?.valid
        if (v == null || v.size < 2 || v.all { it }) { laneRow.visibility = View.GONE; return }
        val d = resources.displayMetrics.density
        v.forEach { ok ->
            laneRow.addView(View(this).apply {
                setBackgroundResource(if (ok) R.drawable.lane_on else R.drawable.lane_off)
                layoutParams = LinearLayout.LayoutParams((22 * d).toInt(), (30 * d).toInt()).apply {
                    marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt()
                }
            })
        }
        laneRow.visibility = View.VISIBLE
    }

    private fun timeAt(secFromNow: Double): String =
        DateFormat.getTimeFormat(this).format(Date(System.currentTimeMillis() + (secFromNow * 1000).toLong()))

    // ================================================================ settings + about

    private fun showSettings() {
        val s = core.settings
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val swVoice = v.findViewById<MaterialSwitch>(R.id.swVoice)
        val sbVolume = v.findViewById<SeekBar>(R.id.sbVolume)
        val swEarly = v.findViewById<MaterialSwitch>(R.id.swEarly)
        val swJunctions = v.findViewById<MaterialSwitch>(R.id.swJunctions)
        val rgStyle = v.findViewById<RadioGroup>(R.id.rgStyle)
        val swSpeaker = v.findViewById<MaterialSwitch>(R.id.swSpeaker)
        val swRv = v.findViewById<MaterialSwitch>(R.id.swRv)
        swRv.isChecked = s.rvMode
        swSpeaker.isChecked = s.phoneSpeaker
        v.findViewById<Button>(R.id.btnVoiceCheck).setOnClickListener {
            // Try the current switch positions without needing OK first.
            core.voice.phoneSpeaker = swSpeaker.isChecked
            core.voice.muted = false
            voiceCheck()
        }
        val swHighways = v.findViewById<MaterialSwitch>(R.id.swHighways)
        val swFerries = v.findViewById<MaterialSwitch>(R.id.swFerries)
        val swUnpaved = v.findViewById<MaterialSwitch>(R.id.swUnpaved)
        val rg = v.findViewById<RadioGroup>(R.id.rgTheme)
        swVoice.isChecked = s.voiceOn
        sbVolume.progress = s.volume
        swEarly.isChecked = s.earlyWarnings
        swJunctions.isChecked = s.junctionPictures
        rgStyle.check(when (s.mapStyle) {
            OfflineMaps.MapStyle.STANDARD -> R.id.rbStandard
            OfflineMaps.MapStyle.TOPO -> R.id.rbTopo
            OfflineMaps.MapStyle.GREY -> R.id.rbGrey
        })
        swHighways.isChecked = s.avoidHighways
        swFerries.isChecked = s.avoidFerries
        swUnpaved.isChecked = s.avoidUnpaved
        rg.check(when (s.theme) { Settings.Theme.AUTO -> R.id.rbAuto; Settings.Theme.DAY -> R.id.rbDay; Settings.Theme.NIGHT -> R.id.rbNight })
        v.findViewById<Button>(R.id.btnAbout).setOnClickListener { showAbout() }
        v.findViewById<TextView>(R.id.versionText).text = getString(R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(v)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                s.voiceOn = swVoice.isChecked
                s.volume = sbVolume.progress
                s.earlyWarnings = swEarly.isChecked
                s.junctionPictures = swJunctions.isChecked
                s.rvMode = swRv.isChecked
                refreshQuickChips()
                s.phoneSpeaker = swSpeaker.isChecked
                s.avoidHighways = swHighways.isChecked
                s.avoidFerries = swFerries.isChecked
                s.avoidUnpaved = swUnpaved.isChecked
                s.theme = when (rg.checkedRadioButtonId) {
                    R.id.rbDay -> Settings.Theme.DAY; R.id.rbNight -> Settings.Theme.NIGHT; else -> Settings.Theme.AUTO
                }
                val style = when (rgStyle.checkedRadioButtonId) {
                    R.id.rbTopo -> OfflineMaps.MapStyle.TOPO
                    R.id.rbGrey -> OfflineMaps.MapStyle.GREY
                    else -> OfflineMaps.MapStyle.STANDARD
                }
                core.applySettings()
                if (style != s.mapStyle) setMapStyle(style) else loadStyle()
                updateBanner()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> core.applySettings() }
            .show()
    }

    /** Speak a test sentence, then report what actually happened. */
    private fun voiceCheck() {
        core.voice.speak(listOf("in_500_m", "use_right_2_lanes", "to_turn_right"))
        val waiting = AlertDialog.Builder(this).setTitle(R.string.voice_check_title)
            .setMessage(R.string.voice_checking).show()
        main.postDelayed({
            waiting.dismiss()
            val v = core.voice
            val out = v.outputDescription()
            val vol = v.mediaVolumePercent()
            val err = v.lastError ?: ""
            val msg = when (v.health) {
                VoicePlayer.Health.CLIPS_OK -> getString(R.string.voice_ok, out, vol)
                VoicePlayer.Health.USING_PHONE_VOICE -> getString(R.string.voice_fallback, out, vol, err)
                VoicePlayer.Health.FAILED -> getString(R.string.voice_failed, out, vol, err)
                VoicePlayer.Health.UNTESTED -> getString(R.string.voice_unknown, out, vol)
            }
            AlertDialog.Builder(this).setTitle(R.string.voice_check_title).setMessage(msg)
                .setPositiveButton(android.R.string.ok, null).show()
        }, 5000)
    }

    /** Before navigating: warn if the voice won't be heard. */
    private fun warnIfVoiceUnheard() {
        val v = core.voice
        if (!core.settings.voiceOn) return
        when {
            v.mediaVolumePercent() < 10 -> Toast.makeText(this, R.string.volume_low, Toast.LENGTH_LONG).show()
            v.outputDescription().startsWith("Bluetooth") -> Toast.makeText(this, R.string.volume_bt, Toast.LENGTH_LONG).show()
        }
    }

    private fun showAbout() {
        val v = layoutInflater.inflate(R.layout.dialog_about, null)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        v.findViewById<TextView>(R.id.aboutVersion).text = getString(R.string.about_version, version)
        v.findViewById<Button>(R.id.btnKofi).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL))) }
        }
        val b = AlertDialog.Builder(this).setView(v).setPositiveButton(android.R.string.ok, null)
        // Only offered when something has gone wrong and been recorded.
        CrashLog.text(this)?.let { report ->
            b.setNeutralButton(R.string.send_problem_report) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Orbit GPS problem report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                runCatching { startActivity(Intent.createChooser(send, getString(R.string.send_problem_report))) }
                CrashLog.clear(this)
            }
        }
        b.show()
    }

    // ================================================================ region files

    private fun regionMenu() {
        io.execute {
            val info = OfflineMaps.regions(this).associateWith { runCatching { core.search.info(it) }.getOrNull() }
            val catalog = RegionDownloads.fetchCatalog(this).getOrNull()
            val dl = runCatching { RegionDownloads.status(this) }.getOrNull()
            runOnUiThread { if (!isFinishing) showRegionMenu(info, catalog, dl) }
        }
    }

    private fun showRegionMenu(
        searchInfo: Map<String, com.robotdash.orbitgps.search.SearchDb.Info?>,
        catalog: List<RegionDownloads.Region>?,
        dl: RegionDownloads.Status?,
    ) {
        val status = installedSummary(searchInfo, catalog)
        val actions = ArrayList<() -> Unit>()
        val tiles = ArrayList<BigMenu.Choice>()

        if (dl != null) {
            tiles += BigMenu.Choice(getString(R.string.dl_in_progress, dl.job.regionName), "⬇", downloadDetail(dl))
            actions += { downloadMenu() }
        }
        catalog.orEmpty().forEach { r ->
            val st = regionState(r)
            val detail = if (dl?.job?.regionId == r.id) getString(R.string.dl_detail_downloading) else st.summary
            tiles += BigMenu.Choice(r.name, "🗺", detail, selected = st.baseOk)
            actions += { if (st.baseOk || st.topoOk) regionOptions(r) else regionDownloadMenu(r) }
        }
        // Files picked by hand (not from the list above).
        if (OfflineMaps.HAND in searchInfo.keys) {
            tiles += BigMenu.Choice(getString(R.string.dl_hand_files), "📁", getString(R.string.dl_hand_files_detail))
            actions += { confirmRemove(OfflineMaps.HAND, getString(R.string.dl_hand_files)) }
        }
        if (catalog == null) {
            tiles += BigMenu.Choice(getString(R.string.dl_no_catalog), "⚠", getString(R.string.dl_no_catalog_detail))
            actions += { regionMenu() }
        }
        val wifi = RegionDownloads.wifiOnly(this)
        tiles += BigMenu.Choice(getString(if (wifi) R.string.dl_wifi_only_on else R.string.dl_wifi_only_off), "📶",
            getString(R.string.dl_wifi_only_detail))
        actions += { RegionDownloads.setWifiOnly(this, !wifi); regionMenu() }
        tiles += BigMenu.Choice(getString(R.string.choose_file), "📁", getString(R.string.dl_from_files_detail))
        actions += { pickFiles.launch(arrayOf("*/*")) }

        val menu = BigMenu.show(this, getString(R.string.offline_map), tiles, message = status,
            cancelLabel = getString(R.string.close)) { actions[it]() }
        // Keep the download line moving while this menu is open.
        if (dl != null) {
            val tick = object : Runnable {
                override fun run() {
                    if (!menu.isShowing) return
                    io.execute {
                        val now = runCatching { RegionDownloads.status(this@MainActivity) }.getOrNull()
                        runOnUiThread {
                            if (now != null) BigMenu.update(menu, 0,
                                BigMenu.Choice(getString(R.string.dl_in_progress, now.job.regionName), "⬇", downloadDetail(now)))
                            main.postDelayed(this, 2000)
                        }
                    }
                }
            }
            main.postDelayed(tick, 2000)
        }
    }

    private fun downloadDetail(s: RegionDownloads.Status): String {
        val amount = getString(R.string.dl_amount, RegionDownloads.sizeText(s.doneBytes), RegionDownloads.sizeText(s.totalBytes), s.percent)
        return when (s.state) {
            RegionDownloads.State.WAITING_WIFI -> getString(R.string.dl_waiting_wifi, amount)
            RegionDownloads.State.WAITING_NETWORK -> getString(R.string.dl_waiting_network, amount)
            RegionDownloads.State.FAILED -> getString(R.string.dl_failed_short, amount)
            RegionDownloads.State.COMPLETE -> getString(R.string.dl_installing)
            RegionDownloads.State.RUNNING -> amount
        }
    }

    /** What of region [r] is on the phone, and whether it's the latest build. */
    private inner class RegionState(r: RegionDownloads.Region) {
        private val parts = RegionDownloads.parts(this@MainActivity, r.id)
        private val built = RegionDownloads.installed(this@MainActivity, r.id)
        private val topoBuilt = RegionDownloads.topoBuilt(this@MainActivity, r.id)
        val baseOk = parts.base
        // Newer files in the catalog than the ones installed (only those get downloaded).
        val baseStale = if (built == null) emptyList() else RegionDownloads.stale(this@MainActivity, r, optional = false)
        val baseCurrent = baseOk && baseStale.isEmpty()
        val topoOk = parts.topo
        val topoStale = if (topoBuilt == null) emptyList() else RegionDownloads.stale(this@MainActivity, r, optional = true)
        val topoCurrent = topoOk && topoStale.isEmpty()
        val staleText: String = (baseStale + topoStale).let { st ->
            st.map { kindName(it.kind) }.distinct().joinToString(", ") + " · " + RegionDownloads.sizeText(st.sumOf { it.size })
        }
        private val date = built?.let { getString(R.string.dl_built_on, it) } ?: getString(R.string.dl_by_hand)
        val hasTopo = r.files.any { it.optional }
        val baseSize = r.files.filter { !it.optional }.sumOf { it.size }
        val topoSize = r.files.filter { it.optional }.sumOf { it.size }
        val summary: String = when {
            !baseOk -> getString(R.string.dl_state_none, RegionDownloads.sizeText(baseSize)) +
                (if (hasTopo) getString(R.string.dl_state_topo_extra, RegionDownloads.sizeText(topoSize)) else "")
            else -> getString(if (topoOk) R.string.dl_state_with_topo else if (hasTopo) R.string.dl_state_no_topo else R.string.dl_state_base, date) +
                (if (!baseCurrent || (topoOk && !topoCurrent)) "\n" + getString(R.string.dl_state_update, staleText) else "")
        }
    }
    private fun regionState(r: RegionDownloads.Region) = RegionState(r)

    private fun kindName(kind: String) = getString(when (kind) {
        "map" -> R.string.kind_map; "routing" -> R.string.kind_routing; "search" -> R.string.kind_search
        "terrain" -> R.string.kind_terrain; else -> R.string.kind_contours
    })

    /** An installed region: update / add Topo, or take it off the phone. */
    private fun regionOptions(r: RegionDownloads.Region) {
        val st = regionState(r)
        BigMenu.show(this, r.name, listOf(
            BigMenu.Choice(getString(R.string.dl_update_or_topo), "⬇", st.summary),
            BigMenu.Choice(getString(R.string.dl_remove), "🗑", getString(R.string.dl_remove_detail,
                RegionDownloads.sizeText(OfflineMaps.dir(this, r.id).listFiles().orEmpty().sumOf { it.length() })), danger = true),
        ), cancelLabel = getString(R.string.close)) { i ->
            if (i == 0) regionDownloadMenu(r) else confirmRemove(r.id, r.name)
        }
    }

    private fun confirmRemove(regionId: String, name: String) {
        if (core.navigator != null) { Toast.makeText(this, R.string.dl_remove_driving, Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle(getString(R.string.dl_remove_q, name))
            .setMessage(R.string.dl_remove_q_detail)
            .setPositiveButton(R.string.dl_remove) { _, _ ->
                io.execute {
                    RegionDownloads.remove(this, regionId)
                    runOnUiThread {
                        core.reloadRegion(); nightShown = null; loadStyle(force = true)
                        Toast.makeText(this, getString(R.string.dl_removed, name), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun regionDownloadMenu(r: RegionDownloads.Region) {
        val st = regionState(r)
        val items = mutableListOf(BigMenu.Check(getString(R.string.dl_basic), when {
            st.baseCurrent -> getString(R.string.dl_part_installed, RegionDownloads.sizeText(st.baseSize))
            st.baseOk -> getString(R.string.dl_part_update, st.baseStale.map { kindName(it.kind) }.distinct().joinToString(", "),
                RegionDownloads.sizeText(st.baseStale.sumOf { it.size }))
            else -> getString(R.string.dl_basic_detail, RegionDownloads.sizeText(st.baseSize))
        }, checked = !st.baseCurrent))
        if (st.hasTopo) items += BigMenu.Check(getString(R.string.dl_topo), when {
            st.topoCurrent -> getString(R.string.dl_part_installed, RegionDownloads.sizeText(st.topoSize))
            st.topoOk -> getString(R.string.dl_part_update, st.topoStale.map { kindName(it.kind) }.distinct().joinToString(", "),
                RegionDownloads.sizeText(st.topoStale.sumOf { it.size }))
            else -> getString(R.string.dl_topo_detail, RegionDownloads.sizeText(st.topoSize))
        }, checked = st.topoOk && !st.topoCurrent)
        // Ticked and out of date: just the changed files. Ticked otherwise: the whole part.
        fun chosen(t: BooleanArray): List<RegionDownloads.RegionFile> {
            val base = if (!t[0]) emptyList() else if (st.baseOk && !st.baseCurrent) st.baseStale else r.files.filter { !it.optional }
            val topo = if (!t.getOrElse(1) { false }) emptyList() else if (st.topoOk && !st.topoCurrent) st.topoStale else r.files.filter { it.optional }
            return base + topo
        }
        val msg = r.description + "\n" + st.summary +
            (if (RegionDownloads.wifiOnly(this)) "\n\n" + getString(R.string.dl_wifi_note) else "")
        BigMenu.checklist(this, r.name, items, message = msg,
            actionLabel = { t -> chosen(t).sumOf { it.size }.takeIf { it > 0 }?.let { getString(R.string.dl_go, RegionDownloads.sizeText(it)) } },
        ) { t ->
            val files = chosen(t)
            val need = files.sumOf { it.size } * 2 + 200_000_000L // download + installed copy
            val free = runCatching { filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (free < need) {
                AlertDialog.Builder(this).setTitle(R.string.offline_map)
                    .setMessage(getString(R.string.dl_no_space, RegionDownloads.sizeText(need), RegionDownloads.sizeText(free)))
                    .setPositiveButton(android.R.string.ok, null).show()
                return@checklist
            }
            io.execute {
                val ok = runCatching { RegionDownloads.start(this, r, files) }
                runOnUiThread {
                    ok.onSuccess {
                        installHandled = false
                        Toast.makeText(this, getString(R.string.dl_started, r.name), Toast.LENGTH_LONG).show()
                        main.removeCallbacks(downloadTick); main.post(downloadTick)
                    }.onFailure {
                        Toast.makeText(this, it.message ?: getString(R.string.copy_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun downloadMenu() {
        io.execute {
            val s = runCatching { RegionDownloads.status(this) }.getOrNull()
            runOnUiThread {
                if (s == null || isFinishing) return@runOnUiThread
                val tiles = ArrayList<BigMenu.Choice>(); val actions = ArrayList<() -> Unit>()
                if (s.state == RegionDownloads.State.FAILED) {
                    tiles += BigMenu.Choice(getString(R.string.dl_retry), "↻"); actions += {
                        io.execute { RegionDownloads.restartUnfinished(this, !RegionDownloads.wifiOnly(this)) }
                        installHandled = false; main.post(downloadTick)
                    }
                }
                if (s.state == RegionDownloads.State.WAITING_WIFI) {
                    tiles += BigMenu.Choice(getString(R.string.dl_use_mobile), "📶", getString(R.string.dl_use_mobile_detail))
                    actions += { io.execute { RegionDownloads.restartUnfinished(this, true) } }
                }
                tiles += BigMenu.Choice(getString(R.string.dl_cancel), "✕", danger = true)
                actions += { io.execute { RegionDownloads.cancel(this) } }
                BigMenu.show(this, getString(R.string.dl_in_progress, s.job.regionName), tiles, message = downloadDetail(s)) { actions[it]() }
            }
        }
    }

    /** Set once we've dealt with a finished or failed download, so we don't keep popping up. */
    private var installHandled = false
    private var toldWaitDrive = false

    /** While the app is open, watch the download and install it the moment it's all here. */
    private val downloadTick = object : Runnable {
        override fun run() {
            io.execute {
                val s = runCatching { RegionDownloads.status(this@MainActivity) }.getOrNull()
                runOnUiThread {
                    if (s == null || !onScreen) return@runOnUiThread
                    when (s.state) {
                        RegionDownloads.State.COMPLETE -> when {
                            installHandled -> {}
                            core.navigator != null -> { // don't swap maps mid-drive
                                if (!toldWaitDrive) { toldWaitDrive = true
                                    Toast.makeText(this@MainActivity, R.string.dl_wait_drive, Toast.LENGTH_LONG).show() }
                                main.postDelayed(this, 5000)
                            }
                            else -> { installHandled = true; installDownload() }
                        }
                        RegionDownloads.State.FAILED -> if (!installHandled) {
                            installHandled = true
                            AlertDialog.Builder(this@MainActivity).setTitle(R.string.offline_map)
                                .setMessage(getString(R.string.dl_failed, s.job.regionName))
                                .setPositiveButton(R.string.dl_retry) { _, _ ->
                                    io.execute { RegionDownloads.restartUnfinished(this@MainActivity, !RegionDownloads.wifiOnly(this@MainActivity)) }
                                    installHandled = false; main.postDelayed(this, 3000)
                                }
                                .setNegativeButton(R.string.dl_cancel) { _, _ -> io.execute { RegionDownloads.cancel(this@MainActivity) } }
                                .show()
                        }
                        else -> main.postDelayed(this, 3000)
                    }
                }
            }
        }
    }

    private fun installDownload() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.offline_map)
            .setMessage(getString(R.string.dl_checking, 0))
            .setCancelable(false)
            .show()
        thread {
            val result = RegionDownloads.finish(this) { pct ->
                runOnUiThread { dialog.setMessage(getString(R.string.dl_checking, pct)) }
            }
            runOnUiThread {
                runCatching { dialog.dismiss() }
                result.onSuccess { kinds ->
                    Toast.makeText(this, getString(R.string.installed, kinds.joinToString(", ") { it.label }), Toast.LENGTH_LONG).show()
                    core.reloadRegion()
                    nightShown = null
                    loadStyle(force = true)
                }.onFailure {
                    AlertDialog.Builder(this).setTitle(R.string.offline_map)
                        .setMessage((it.message ?: getString(R.string.copy_failed)) + "\n\n" + getString(R.string.dl_start_over_q))
                        .setPositiveButton(R.string.dl_start_over) { _, _ ->
                            io.execute { RegionDownloads.startOver(this) }
                            installHandled = false; main.postDelayed(downloadTick, 3000)
                        }
                        .setNegativeButton(R.string.dl_cancel) { _, _ -> io.execute { RegionDownloads.cancel(this) } }
                        .show()
                }
            }
        }
    }

    /** "On this phone:" then each region with what it has. */
    private fun installedSummary(searchInfo: Map<String, com.robotdash.orbitgps.search.SearchDb.Info?>,
                                 catalog: List<RegionDownloads.Region>?): String {
        val regions = OfflineMaps.regions(this)
        if (regions.isEmpty()) return getString(R.string.dl_none_on_phone)
        return getString(R.string.dl_on_phone) + "\n\n" + regions.joinToString("\n\n") { id ->
            val name = if (id == OfflineMaps.HAND) getString(R.string.dl_hand_files)
                else (catalog ?: RegionDownloads.cachedCatalog(this))?.firstOrNull { it.id == id }?.name ?: id
            name + "\n" + regionSummary(id, searchInfo[id])
        }
    }

    private fun regionSummary(region: String, searchInfo: com.robotdash.orbitgps.search.SearchDb.Info?): String {
        val status = OfflineMaps.Kind.entries.filter { OfflineMaps.has(this, region, it) }.joinToString("\n") { k ->
            val f = OfflineMaps.file(this, region, k)
            val has = true
            val name = getString(
                when (k) {
                    OfflineMaps.Kind.MAP -> R.string.part_map
                    OfflineMaps.Kind.ROUTING -> R.string.part_routing
                    OfflineMaps.Kind.SEARCH -> R.string.part_search
                    OfflineMaps.Kind.TERRAIN -> R.string.part_terrain
                    OfflineMaps.Kind.CONTOURS -> R.string.part_contours
                }
            )
            val extra = if (!has) "" else buildString {
                append("  (").append(f.length() / 1_000_000).append(" MB")
                if (k == OfflineMaps.Kind.SEARCH && searchInfo != null) {
                    searchInfo.addresses?.let { append(", ").append(String.format(java.util.Locale.CANADA, "%,d", it)).append(" addresses") }
                    searchInfo.built?.let { append(", built ").append(it) }
                }
                append(")")
            }
            (if (has) "✓ " else "✗ ") + name + extra
        }
        return status
    }

    private fun importFiles(uris: List<Uri>) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.offline_map)
            .setMessage(getString(R.string.copying, 0))
            .setCancelable(false)
            .show()
        thread {
            val result = OfflineMaps.import(this, uris, { pct ->
                runOnUiThread { dialog.setMessage(getString(R.string.copying, pct)) }
            })
            runOnUiThread {
                dialog.dismiss()
                result.onSuccess { kinds ->
                    Toast.makeText(this, getString(R.string.installed, kinds.joinToString(", ") { it.label }), Toast.LENGTH_LONG).show()
                    core.reloadRegion()
                    nightShown = null
                    loadStyle(force = true)
                }.onFailure {
                    AlertDialog.Builder(this).setTitle(R.string.offline_map)
                        .setMessage(it.message ?: getString(R.string.copy_failed))
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }


    // ================================================================ lifecycle

    override fun onStart() {
        super.onStart(); mapView.onStart()
        onScreen = true
        core.holdGps("screen")
        main.removeCallbacks(statusTick); main.post(statusTick)
        loadStyle()
        lastCam = null
        lastFrameMs = 0L
        if (!smoother.hasFix) core.lastShown?.let {
            smoother.onFix(Pt(it.latitude, it.longitude), it.bearing.toDouble(), 0.0, nowMs())
        }
        startFrameLoop()
        render()
        installHandled = false
        main.removeCallbacks(downloadTick); main.post(downloadTick)
        sendSpots()
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).registerDefaultNetworkCallback(netCallback) }
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }

    override fun onStop() {
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(netCallback) }
        onScreen = false
        main.removeCallbacks(statusTick)
        if (!isChangingConfigurations) core.releaseGps("screen")
        super.onStop(); mapView.onStop()
    }

    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    override fun onDestroy() {
        core.removeListener(this)
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }
}

/** Route line / point as GeoJSON for map sources (phone and car). */
object GeoJson {
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    fun line(route: Route?): String {
        if (route == null) return EMPTY
        val coords = route.points.joinToString(",") { "[${it.lon},${it.lat}]" }
        return """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
    }
    fun point(p: Pt?): String = if (p == null) EMPTY
        else """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""
}

/** Turn arrow drawings shared by phone and car. */
object TurnIcons {
    fun drawable(icon: TurnIcon): Int = when (icon) {
        TurnIcon.STRAIGHT -> R.drawable.turn_straight
        TurnIcon.TURN -> R.drawable.turn_right
        TurnIcon.SLIGHT -> R.drawable.turn_slight
        TurnIcon.SHARP -> R.drawable.turn_sharp
        TurnIcon.UTURN -> R.drawable.turn_uturn
        TurnIcon.FORK -> R.drawable.turn_fork
        TurnIcon.ROUNDABOUT -> R.drawable.turn_roundabout
        TurnIcon.MERGE -> R.drawable.turn_merge
        TurnIcon.EXIT -> R.drawable.turn_exit
        TurnIcon.ARRIVE -> R.drawable.turn_arrive
        TurnIcon.FERRY -> R.drawable.turn_ferry
    }

    /** Drawings are right-hand; mirror them for left turns. */
    fun mirrored(step: NavStep) = step.maneuver?.side == Side.LEFT &&
        step.icon != TurnIcon.UTURN && step.icon != TurnIcon.ARRIVE && step.icon != TurnIcon.FERRY
}

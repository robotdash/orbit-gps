package com.robotdash.orbitgps.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.robotdash.orbitgps.CrashLog
import com.robotdash.orbitgps.MainActivity
import com.robotdash.orbitgps.NavCore
import com.robotdash.orbitgps.OfflineMaps
import com.robotdash.orbitgps.Settings
import com.robotdash.orbitgps.Sun
import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.PoseSmoother
import com.robotdash.orbitgps.nav.Pt
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws the map on the car screen.
 *
 * Android Auto gives navigation apps a blank surface to draw on. MapLibre's snapshotter
 * renders the offline map as a picture (flat, heading-up) — but that takes a moment, so
 * redrawing it on every GPS fix (1.11 and earlier) moved the car screen once a second.
 *
 * Now: one snapshot a bit larger than the screen is made every few seconds, and ~20 times
 * a second it is slid, rotated and scaled to where the car is predicted to be (the same
 * prediction the phone uses), with the route, destination and car arrow drawn fresh on
 * top. A new snapshot is fetched in the background before the car nears its edge.
 */
class CarMapRenderer(private val car: CarContext) : SurfaceCallback, NavCore.Listener {

    private val main = Handler(Looper.getMainLooper())
    private var container: SurfaceContainer? = null
    private var visible: Rect? = null
    private var snapshotter: MapSnapshotter? = null
    private var snapshotterKey = ""
    private var zoom = 16.0
    private var lastFrameMs = 0L

    private val smoother = PoseSmoother()

    /** The current map picture and the camera it was taken with. */
    private class Snap(val bitmap: Bitmap, val center: Pt, val bearing: Double, val zoom: Double, val key: String)
    private var snap: Snap? = null

    /** Pixels per density-independent pixel on the car screen. */
    private val ratio: Float get() = (container?.dpi ?: 160) / 160f

    init { MapLibre.getInstance(car) }

    // ------------------------------------------------------------------ surface

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        useHardware = true
        snapshotter = null
        snap = null
        requestSnapshot()
        startFrames()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = visibleArea; frameSoon() }
    override fun onStableAreaChanged(stableArea: Rect) { if (visible == null) visible = stableArea; frameSoon() }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
        main.removeCallbacks(frame)
        framesOn = false
        snapshotter?.cancel()
        snapshotter = null
        snap = null
    }

    // ------------------------------------------------------------------ NavCore

    override fun onFix(raw: Location, shown: Location) {
        val prog = NavCore.progress
        val onRoute = NavCore.mode == NavCore.Mode.NAVIGATING && prog != null && !prog.offRoute && prog.offset < 25
        smoother.onFix(Pt(shown.latitude, shown.longitude), shown.bearing.toDouble(), raw.speed.toDouble(),
            SystemClock.uptimeMillis(), if (onRoute) NavCore.navigator?.route else null, if (onRoute) prog!!.along else null)
        startFrames()
    }

    override fun onStateChanged() = frameSoon()

    // ------------------------------------------------------------------ frames

    private var framesOn = false

    /** ~20 frames a second while the car moves; slower when it's still. */
    private val frame: Runnable = object : Runnable {
        override fun run() {
            if (container == null) { framesOn = false; return }
            val moving = try { drawFrame() } catch (t: Throwable) {
                Log.w("OrbitCar", "frame failed", t)
                CrashLog.record(car, t, "car map frame")
                false
            }
            main.postDelayed(this, if (moving) FRAME_MS else IDLE_FRAME_MS)
        }
    }

    private fun startFrames() {
        if (framesOn || container == null) return
        framesOn = true
        main.post(frame)
    }

    /** Redraw now (route or state changed), without waiting for the next frame. */
    private fun frameSoon() {
        if (container == null) return
        main.removeCallbacks(frame)
        framesOn = true
        main.post(frame)
    }

    /** Where to draw the car now: predicted between fixes, or the last fix. */
    private fun pose(nowMs: Long): PoseSmoother.Pose? {
        if (smoother.hasFix) return smoother.at(nowMs)
        val loc = NavCore.lastShown ?: return null
        return PoseSmoother.Pose(loc.latitude, loc.longitude, loc.bearing.toDouble(), loc.speed.toDouble())
    }

    private fun carPt(p: PoseSmoother.Pose?) = p?.let { Pt(it.lat, it.lon) } ?: Pt(MainActivity.START.latitude, MainActivity.START.longitude)

    /** Draw one frame. Returns true if the car is moving (keep the fast frame rate). */
    private fun drawFrame(): Boolean {
        val c = container ?: return false
        if (c.surface == null || c.width <= 0 || c.height <= 0) return false
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) FRAME_MS else (now - lastFrameMs).coerceIn(1, 1000)
        lastFrameMs = now
        val p = pose(now)
        val carPt = carPt(p)
        val bearing = p?.bearing ?: 0.0
        val speed = p?.speed ?: 0.0
        // Zoom eases toward the speed-based target over ~1.5 s, like the phone.
        zoom += (zoomFor((speed * 3.6).toFloat()) - zoom) * (1 - exp(-dt / 1500.0))

        val proj = Projection(zoom, bearing, ratio.toDouble(), c.width, c.height)
        val center = carCenter(proj, carPt, c)
        val night = night()
        val key = styleKey(night)

        val s = snap
        if (s == null || needsNewSnapshot(s, proj, center, bearing, key, c)) requestSnapshot()
        paint(s?.takeIf { it.key == key }, proj, center, carPt, night)
        return speed > 0.5
    }

    private fun carCenter(proj: Projection, carPt: Pt, c: SurfaceContainer): Pt {
        // Car arrow sits centred, ~70% down the visible area.
        val va = visible ?: Rect(0, 0, c.width, c.height)
        return proj.centerFor(carPt, va.exactCenterX() - c.width / 2.0, va.top + va.height() * 0.70 - c.height / 2.0)
    }

    /** Side of the square snapshot, in screen pixels: covers the screen at any rotation, plus a margin. */
    private fun snapSide(c: SurfaceContainer) = (hypot(c.width.toDouble(), c.height.toDouble()) * SNAP_COVER).toInt()

    private fun needsNewSnapshot(s: Snap, proj: Projection, center: Pt, bearing: Double, key: String, c: SurfaceContainer): Boolean {
        if (s.key != key) return true
        if (abs(Geo.normalize(bearing - s.bearing)) > 25) return true
        if (abs(zoom - s.zoom) > 0.35) return true
        // How far the snapshot's centre has drifted from the screen centre, vs. the spare margin.
        val (x, y) = proj.toScreen(s.center, center)
        val drift = hypot(x - c.width / 2.0, y - c.height / 2.0)
        val margin = (snapSide(c) - hypot(c.width.toDouble(), c.height.toDouble())) / 2
        return drift > margin * 0.5
    }

    // ------------------------------------------------------------------ snapshots

    private val gate: DrawGate = DrawGate({ main.post(it) }) {
        try {
            takeSnapshot()
        } catch (t: Throwable) {
            Log.w("OrbitCar", "map snapshot failed", t)
            CrashLog.record(car, t, "car map")
            snapshotter = null
            gate.done()
        }
    }

    /** Ask for a fresh map picture (one at a time; extra requests fold into one). */
    private fun requestSnapshot() = gate.request()

    private fun night(): Boolean = when (NavCore.settings.theme) {
        Settings.Theme.DAY -> false
        Settings.Theme.NIGHT -> true
        Settings.Theme.AUTO -> {
            // Car screens look best dark; follow the sun like the phone, but also honour the car's own dark mode.
            car.isDarkMode || NavCore.lastFix.let { Sun.isNight(it?.latitude ?: MainActivity.START.latitude, it?.longitude ?: MainActivity.START.longitude) }
        }
    }

    private fun styleKey(night: Boolean) =
        "$night:${OfflineMaps.hasOfflineMap(car)}:${OfflineMaps.effectiveStyle(car, NavCore.settings.mapStyle)}"

    private fun styleBuilder(night: Boolean): Style.Builder =
        if (OfflineMaps.hasOfflineMap(car)) Style.Builder().fromJson(OfflineMaps.styleJson(car, night, NavCore.settings.mapStyle))
        else Style.Builder().fromUri(if (night) MainActivity.ONLINE_STYLE_NIGHT_URL else MainActivity.ONLINE_STYLE_URL)

    private fun zoomFor(kmh: Float): Double = when {
        kmh < 30 -> 16.5
        kmh < 60 -> 15.8
        kmh < 90 -> 15.0
        else -> 14.3
    }

    /** Must end with [DrawGate.done], now or when the snapshot comes back. */
    private fun takeSnapshot() {
        val c = container ?: return gate.done()
        if (c.surface == null || c.width <= 0 || c.height <= 0) return gate.done()
        val p = pose(SystemClock.uptimeMillis())
        val carPt = carPt(p)
        val bearing = p?.bearing ?: 0.0
        val z = zoom
        // Picture centred on today's screen centre (a little ahead of the car).
        val proj = Projection(z, bearing, ratio.toDouble(), c.width, c.height)
        val center = carCenter(proj, carPt, c)
        val night = night()
        val key = styleKey(night)
        val sideDp = (snapSide(c) / ratio).toInt()

        val cam = CameraPosition.Builder().target(LatLng(center.lat, center.lon)).zoom(z).bearing(bearing).tilt(0.0).build()
        val skKey = "$sideDp:$key"
        var s = snapshotter
        if (s == null || skKey != snapshotterKey) {
            s?.cancel()
            val opts = MapSnapshotter.Options(sideDp, sideDp)
                .withPixelRatio(ratio)
                .withStyleBuilder(styleBuilder(night))
                .withCameraPosition(cam)
                .withLogo(false)
            s = MapSnapshotter(car, opts)
            snapshotter = s; snapshotterKey = skKey
        } else {
            s.setCameraPosition(cam)
        }
        s.start({ shot ->
            snap = Snap(shot.bitmap, center, bearing, z, key)
            main.post { gate.done(); frameSoon() } // after MapLibre has reset the snapshotter
        }, { err ->
            Log.w("OrbitCar", "map snapshot failed: $err")
            main.post { gate.done() }
        })
    }

    // ------------------------------------------------------------------ painting

    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#0D47A1"); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#2F8CFF"); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val destFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2FD0FF") }
    private val arrowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#0C1430"); strokeJoin = Paint.Join.ROUND
    }
    private val mapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var useHardware = true
    private val matrix = Matrix()

    private fun paint(map: Snap?, proj: Projection, center: Pt, carPt: Pt, night: Boolean) {
        val c = container ?: return
        val surface = c.surface ?: return
        val canvas: Canvas = try {
            if (useHardware && android.os.Build.VERSION.SDK_INT >= 26) surface.lockHardwareCanvas() else surface.lockCanvas(null)
        } catch (e: Exception) {
            // Some car screens only take the plain (software) canvas: use that from now on.
            useHardware = false
            try { surface.lockCanvas(null) } catch (e2: Exception) { return }
        }
        try {
            canvas.drawColor(if (night) Color.parseColor("#1A2230") else Color.parseColor("#EBE8E3"))
            if (map != null) {
                val old = Projection(map.zoom, map.bearing, ratio.toDouble(), map.bitmap.width, map.bitmap.height)
                val t = SnapTransform.between(old, map.center, proj, center, map.bitmap.width / 2.0, map.bitmap.height / 2.0)
                matrix.reset()
                t.applyTo(matrix)
                canvas.drawBitmap(map.bitmap, matrix, mapPaint)
            }
            val d = ratio

            NavCore.currentRoute?.let { route ->
                val path = Path()
                var started = false
                for (p in route.points) {
                    val (x, y) = proj.toScreen(p, center)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                routeCasing.strokeWidth = 11 * d; routeLine.strokeWidth = 7 * d
                canvas.drawPath(path, routeCasing)
                canvas.drawPath(path, routeLine)
            }
            NavCore.destination?.let { dest ->
                val (x, y) = proj.toScreen(dest, center)
                canvas.drawCircle(x, y, 10 * d, white)
                canvas.drawCircle(x, y, 7 * d, destFill)
            }
            // Car arrow (always pointing up: the map is heading-up).
            val (ax, ay) = proj.toScreen(carPt, center)
            val s = 14 * d
            val arrow = Path().apply {
                moveTo(ax, ay - s); lineTo(ax + s * 0.75f, ay + s * 0.8f); lineTo(ax, ay + s * 0.35f)
                lineTo(ax - s * 0.75f, ay + s * 0.8f); close()
            }
            arrowEdge.strokeWidth = 3 * d
            canvas.drawPath(arrow, arrowFill)
            canvas.drawPath(arrow, arrowEdge)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    companion object {
        const val FRAME_MS = 50L
        const val IDLE_FRAME_MS = 500L
        /** Snapshot side = screen diagonal × this, so it can slide a while before needing a new one. */
        const val SNAP_COVER = 1.35
    }

    /**
     * How to place a snapshot (taken with camera `old` centred on oldCenter) on the screen
     * now (camera `now` centred on nowCenter): move its centre pixel to [toX],[toY], rotate
     * by [rotateDeg] and scale by [scale]. Both cameras are similarity transforms of the
     * same Web-Mercator plane, so this is exact.
     */
    internal data class SnapTransform(
        val fromX: Double, val fromY: Double, val rotateDeg: Double, val scale: Double, val toX: Double, val toY: Double,
    ) {
        fun applyTo(m: Matrix) {
            m.setTranslate((-fromX).toFloat(), (-fromY).toFloat())
            m.postRotate(rotateDeg.toFloat())
            m.postScale(scale.toFloat(), scale.toFloat())
            m.postTranslate(toX.toFloat(), toY.toFloat())
        }

        /** The same transform done by hand (for tests). */
        fun map(x: Double, y: Double): Pair<Double, Double> {
            val dx = x - fromX; val dy = y - fromY
            val r = Math.toRadians(rotateDeg)
            val rx = dx * cos(r) - dy * sin(r); val ry = dx * sin(r) + dy * cos(r)
            return (rx * scale + toX) to (ry * scale + toY)
        }

        companion object {
            fun between(old: Projection, oldCenter: Pt, now: Projection, nowCenter: Pt, oldCx: Double, oldCy: Double): SnapTransform {
                val (tx, ty) = now.toScreen(oldCenter, nowCenter)
                return SnapTransform(oldCx, oldCy, old.bearingDeg - now.bearingDeg,
                    now.scale / old.scale, tx.toDouble(), ty.toDouble())
            }
        }
    }

    /**
     * Web-Mercator projection matching MapLibre's camera (512-px tiles, flat, rotated by bearing),
     * so our route line lines up with the snapshot exactly.
     */
    internal class Projection(zoom: Double, val bearingDeg: Double, ratio: Double, val w: Int, val h: Int) {
        /** World size in pixels at this zoom. */
        val scale = 512.0 * 2.0.pow(zoom) * ratio
        private val world = scale
        private val b = Math.toRadians(bearingDeg)

        private fun wx(p: Pt) = (p.lon + 180.0) / 360.0 * world
        private fun wy(p: Pt): Double {
            val s = ln(tan(PI / 4 + Math.toRadians(p.lat) / 2))
            return (1 - s / PI) / 2 * world
        }

        /** Screen pixel of [p] when the camera is centred on [center]. */
        fun toScreen(p: Pt, center: Pt): Pair<Float, Float> {
            val dx = wx(p) - wx(center); val dy = wy(p) - wy(center)
            // world → screen: rotate by -bearing
            val sx = dx * cos(-b) - dy * sin(-b)
            val sy = dx * sin(-b) + dy * cos(-b)
            return (w / 2.0 + sx).toFloat() to (h / 2.0 + sy).toFloat()
        }

        /** Camera centre that puts [car] at screen offset (ox, oy) from the middle. */
        fun centerFor(car: Pt, ox: Double, oy: Double): Pt {
            // screen → world: rotate by +bearing
            val dx = ox * cos(b) - oy * sin(b)
            val dy = ox * sin(b) + oy * cos(b)
            val cx = wx(car) - dx; val cy = wy(car) - dy
            val lon = cx / world * 360.0 - 180.0
            val n = PI * (1 - 2 * cy / world)
            val lat = Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
            return Pt(lat, lon)
        }
    }
}

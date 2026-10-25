package com.robotdash.orbitgps

import com.robotdash.orbitgps.car.CarMapRenderer
import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarProjectionTest {
    private val car = Pt(54.5163, -128.6035)

    @Test fun carLandsWhereAsked() {
        for (bearing in listOf(0.0, 45.0, 90.0, 200.0, 315.0)) {
            val p = CarMapRenderer.Projection(15.0, bearing, 2.0, 800, 480)
            val c = p.centerFor(car, 0.0, 96.0)        // 96 px below centre
            val (x, y) = p.toScreen(car, c)
            assertEquals(400.0, x.toDouble(), 0.5)
            assertEquals(240.0 + 96, y.toDouble(), 0.5)
        }
    }

    @Test fun aheadIsUp() {
        // A point 200 m ahead in the direction of travel must appear above the car.
        for (bearing in listOf(0.0, 90.0, 180.0, 270.0, 33.0)) {
            val p = CarMapRenderer.Projection(15.0, bearing, 2.0, 800, 480)
            val c = p.centerFor(car, 0.0, 96.0)
            val ahead = Pt(
                car.lat + 200 / 111_320.0 * Math.cos(Math.toRadians(bearing)),
                car.lon + 200 / (111_320.0 * Math.cos(Math.toRadians(car.lat))) * Math.sin(Math.toRadians(bearing)),
            )
            val (ax, ay) = p.toScreen(ahead, c)
            val (cx, cy) = p.toScreen(car, c)
            assertTrue("bearing $bearing: ahead should be above", ay < cy - 20)
            assertEquals("bearing $bearing: ahead should be straight up", cx.toDouble(), ax.toDouble(), 3.0)
        }
    }

    /**
     * Car screen smoothing (1.12): an old map snapshot slid/rotated/scaled with SnapTransform
     * must put every place exactly where a brand-new snapshot would.
     */
    @Test fun snapshotTransformMatchesAFreshRender() {
        val r = java.util.Random(7)
        repeat(200) {
            val b0 = r.nextDouble() * 360; val z0 = 14 + r.nextDouble() * 3
            val b1 = b0 + (r.nextDouble() - 0.5) * 50; val z1 = z0 + (r.nextDouble() - 0.5) * 0.7
            val old = CarMapRenderer.Projection(z0, b0, 1.5, 1400, 1400)
            val now = CarMapRenderer.Projection(z1, b1, 1.5, 800, 480)
            val c0 = Pt(car.lat + (r.nextDouble() - 0.5) * 0.01, car.lon + (r.nextDouble() - 0.5) * 0.02)
            val c1 = Pt(c0.lat + (r.nextDouble() - 0.5) * 0.004, c0.lon + (r.nextDouble() - 0.5) * 0.008)
            val t = CarMapRenderer.SnapTransform.between(old, c0, now, c1, 700.0, 700.0)
            repeat(5) {
                val q = Pt(c1.lat + (r.nextDouble() - 0.5) * 0.006, c1.lon + (r.nextDouble() - 0.5) * 0.012)
                val (ox, oy) = old.toScreen(q, c0)
                val (mx, my) = t.map(ox.toDouble(), oy.toDouble())
                val (ex, ey) = now.toScreen(q, c1)
                assertEquals(ex.toDouble(), mx, 0.05)
                assertEquals(ey.toDouble(), my, 0.05)
            }
        }
    }

    /** 3D car view (3.9): the flat map leant back 45° around the car arrow. */
    @Test fun leaningTheMapBackShowsMoreRoadAheadSafely() {
        for ((w, h) in listOf(800 to 480, 1024 to 600, 1280 to 720, 1920 to 720, 1080 to 1920)) {
            val px = w / 2.0; val py = h * 0.78
            val t = CarMapRenderer.Tilt(CarMapRenderer.TILT_3D, px, py, h * CarMapRenderer.CAMERA_DIST)
            // the car stays put, map/unmap are inverses
            val car = t.map(px, py)
            assertEquals(px, car.first, 1e-9); assertEquals(py, car.second, 1e-9)
            for ((x, y) in listOf(0.0 to 0.0, w * 0.3 to h * 0.2, w.toDouble() to h.toDouble())) {
                val (fx, fy) = t.unmap(x, y)
                val (bx, by) = t.map(fx, fy)
                assertEquals(x, bx, 1e-6); assertEquals(y, by, 1e-6)
            }
            // the top of the screen shows road well beyond the screen height above the car (more ahead)
            val farTop = t.unmap(px, 0.0).second
            assertTrue("$w×$h sees ahead ${py - farTop} vs ${py} flat", py - farTop > 2.0 * py)
            // the picture (centred on the shown stretch, room for 25° of turning and some drift)
            // covers every screen corner and never reaches behind the camera
            val (mx, my) = t.shownMiddle(w, h)
            val box = CarMapRenderer.snapBox(w, h, t)
            for ((x, y) in listOf(0.0 to 0.0, w.toDouble() to 0.0, 0.0 to h.toDouble(), w.toDouble() to h.toDouble())) {
                val (fx, fy) = t.unmap(x, y)
                assertTrue("$w×$h corner covered", Math.abs(fx - mx) <= box.halfW && Math.abs(fy - my) <= box.halfH)
            }
            // worst case: the picture turned up to 15° (more turning takes a new one) and drifted
            val turn = Math.toRadians(CarMapRenderer.REFRESH_TURN_3D)
            val below = my + box.halfW * Math.sin(turn) + box.halfH * Math.cos(turn) + box.margin * 0.5 - py
            assertTrue("$w×$h picture stays in front: reaches $below below the car, limit ${t.safeBelow}",
                below < t.safeBelow * 0.8)
            assertTrue("$w×$h picture fits a GPU texture at reduced resolution", box.halfW > 0 && box.halfH > 0)
        }
    }
}

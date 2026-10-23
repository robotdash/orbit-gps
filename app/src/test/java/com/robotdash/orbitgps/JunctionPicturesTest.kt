package com.robotdash.orbitgps

import android.graphics.Bitmap
import com.robotdash.orbitgps.nav.Junction
import com.robotdash.orbitgps.nav.JunctionKind
import com.robotdash.orbitgps.nav.LaneInfo
import com.robotdash.orbitgps.nav.NavStep
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.Route
import com.robotdash.orbitgps.nav.Sign
import com.robotdash.orbitgps.nav.TurnIcon
import com.robotdash.orbitgps.voice.Side
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws sample junction pictures to build/junctions/ for a visual check, and checks the route arrow is there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JunctionPicturesTest {

    private fun render(name: String, j: Junction, night: Boolean): Bitmap {
        val step = NavStep(null, "", j.street, 0, 0.0, TurnIcon.EXIT, j)
        val route = Route(listOf(Pt(54.5, -128.6), Pt(54.501, -128.6)), doubleArrayOf(0.0, 111.0), listOf(step), 60.0)
        val bmp = JunctionRenderer.render(route, step, 1008, 624, night)!!
        File("build/junctions").mkdirs()
        File("build/junctions/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun cyanPixels(b: Bitmap): Int {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return px.count { it == JunctionRenderer.ROUTE_ARROW }
    }

    @Test fun exitAndForkPicturesHaveRouteArrow() {
        val yh16 = Sign(listOf("YH 16"), emptyList(), null)
        val cases = listOf(
            "exit_nolanes_night" to Junction(JunctionKind.HIGHWAY_EXIT, Side.RIGHT, null, yh16, "YH 16"),
            "exit_3lanes_day" to Junction(JunctionKind.HIGHWAY_EXIT, Side.RIGHT, listOf(
                LaneInfo(listOf("straight"), false), LaneInfo(listOf("straight"), false),
                LaneInfo(listOf("straight", "slight right"), true), LaneInfo(listOf("slight right"), true)),
                Sign(listOf("BC 37"), listOf("Kitimat", "Airport"), "12"), "BC 37"),
            "fork_left_day" to Junction(JunctionKind.FORK, Side.LEFT, null, Sign(emptyList(), listOf("Prince Rupert"), null), "YH 16"),
        )
        for ((name, j) in cases) for (night in listOf(false, true)) {
            val b = render("${name}_${if (night) "n" else "d"}", j, night)
            assertTrue("$name has no route arrow", cyanPixels(b) > 400)
        }
    }
}

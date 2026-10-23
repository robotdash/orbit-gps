package com.robotdash.orbitgps

import android.graphics.Bitmap
import com.robotdash.orbitgps.nav.JunctionKind
import com.robotdash.orbitgps.nav.Route
import com.robotdash.orbitgps.nav.Sign
import com.robotdash.orbitgps.voice.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JunctionTest {

    private fun route(name: String) = Route.fromOsrmJson(javaClass.getResource("/$name")!!.readText())

    @Test fun signParsing() {
        val s = Sign.parse("1 East: Langley, Hope", null)!!
        assertEquals(listOf("1 East"), s.refs); assertEquals(listOf("Langley", "Hope"), s.names)
        val e = Sign.parse("McCallum Road", "EXIT 90")!!
        assertEquals("90", e.exit); assertEquals(listOf("McCallum Road"), e.names)
        assertEquals(listOf("YH 16"), Sign.parse("YH 16", null)!!.refs)
    }

    @Test fun highwayExitGetsLanePicture() {
        val r = route("jv_hwy_exit.json")
        val exit = r.announced.first { it.junction?.kind == JunctionKind.HIGHWAY_EXIT }
        val j = exit.junction!!
        assertEquals(Side.RIGHT, j.side)
        assertEquals("61", j.sign?.exit)
        assertEquals(listOf(false, false, true), j.lanes!!.map { it.valid })
    }

    @Test fun rotaryGetsRoundaboutPicture() {
        val r = route("jv_abby_rotary.json")
        val rb = r.announced.filter { it.junction?.kind == JunctionKind.ROUNDABOUT }
        assertTrue(rb.isNotEmpty())
        rb.forEach { assertNotNull(it.junction!!.roundabout) }
    }

    @Test fun ordinaryTownRouteIsQuiet() {
        // Plain turns in Terrace shouldn't pop up pictures at every corner.
        val r = route("route_terrace_town.json")
        val n = r.announced.count { it.junction != null }
        assertTrue("too many pictures: $n of ${r.announced.size}", n <= r.announced.size / 3)
    }

    @Test fun renderAllFixtures() {
        val out = File("build/jv").apply { mkdirs() }
        val names = listOf("jv_hwy_exit.json", "jv_abby_rotary.json", "jv_dualright.json", "jv_dualright2.json",
            "jv_roundabout.json", "jv_fiveway.json", "route_terrace_town.json", "route_terrace_kitimat.json", "route_pg_lanes.json")
        val summary = StringBuilder()
        for (name in names) {
            val r = route(name)
            summary.append("$name: ${r.announced.size} steps\n")
            r.announced.forEachIndexed { i, st ->
                val j = st.junction ?: return@forEachIndexed
                summary.append("  #$i ${j.kind} ${st.instruction} lanes=${j.lanes?.map { (if (it.valid) "*" else "") + it.indications.joinToString("/") }} sign=${j.sign}\n")
                for (night in listOf(false, true)) {
                    val bmp = JunctionRenderer.render(r, st, 600, 472, night)!!
                    File(out, "${name.removeSuffix(".json")}_${i}_${j.kind}${if (night) "_n" else ""}.png").outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
        File(out, "summary.txt").writeText(summary.toString())
    }
}


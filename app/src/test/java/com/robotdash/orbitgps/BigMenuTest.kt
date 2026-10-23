package com.robotdash.orbitgps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** The driver's menus must be big: tiles at least 76 dp tall, text 21 sp. Saves a picture to build/. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w448dp-h997dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BigMenuTest {
    class Host : AppCompatActivity() {
        override fun onCreate(b: android.os.Bundle?) { setTheme(R.style.Theme_OrbitGPS); super.onCreate(b) }
    }

    @Test fun stopOrGoTilesAreLarge() {
        val a = Robolectric.buildActivity(Host::class.java).setup().get()
        var picked = -1
        val d = BigMenu.show(a, "Petro-Canada", listOf(
            BigMenu.Choice("Add as a stop", "➕", "Stop here on the way, then carry on to Work"),
            BigMenu.Choice("Go here instead", "🏁", "Drop the current trip and go here"),
        ), message = "4711 Keith Avenue, Terrace") { picked = it }
        ShadowLooper.idleMainLooper()
        val root = d.window!!.decorView
        val w = (a.resources.displayMetrics.widthPixels * 0.94).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val tiles = ArrayList<android.widget.TextView>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v !is android.widget.Button && v.isClickable && v.isShown) tiles += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        assertEquals(3, tiles.size) // two choices + cancel
        val dp = a.resources.displayMetrics.density
        tiles.forEach { assertTrue("tile ${it.text} is ${it.height / dp} dp", it.height >= 76 * dp - 1) }
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/bigmenu.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tiles[1].performClick()
        assertEquals(1, picked)
    }

    @Test fun downloadChecklistTicksAndTotals() {
        val a = Robolectric.buildActivity(Host::class.java).setup().get()
        val sizes = longArrayOf(541_000_000, 586_000_000)
        var got: BooleanArray? = null
        val d = BigMenu.checklist(a, "Terrace & Northwest BC", listOf(
            BigMenu.Check("Map, roads and search", "Installed ✓ · 541 MB · tick to download again", checked = false),
            BigMenu.Check("Topo (hills and contour lines)", "586 MB · optional, shading and 50 m contours", checked = false),
        ), message = "Installed: map, roads, search · no Topo · built 2026-10-19",
            actionLabel = { t -> sizes.indices.filter { t[it] }.sumOf { sizes[it] }.takeIf { it > 0 }?.let { "Download ${RegionDownloads.sizeText(it)}" } },
        ) { got = it }
        ShadowLooper.idleMainLooper()
        val root = d.window!!.decorView
        fun lay() {
            val w = (a.resources.displayMetrics.widthPixels * 0.94).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        lay()
        val tiles = ArrayList<android.widget.TextView>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v !is android.widget.Button && v.isClickable && v.isShown) tiles += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        assertEquals(4, tiles.size) // two boxes, Download, Cancel
        val (base, topo, go) = Triple(tiles[0], tiles[1], tiles[2])
        assertEquals("Tick what to download", go.text.toString())
        go.performClick(); assertEquals(null, got) // nothing ticked: does nothing
        topo.performClick()
        assertTrue(topo.text.startsWith("☑"))
        assertEquals("Download 586 MB", go.text.toString())
        base.performClick()
        assertEquals("Download 1.1 GB", go.text.toString())
        base.performClick() // untick again
        lay()
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/download-checklist.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        go.performClick()
        assertTrue(got!!.contentEquals(booleanArrayOf(false, true)))
    }

    @Test fun tileTextUpdatesWhileShowing() {
        val a = Robolectric.buildActivity(Host::class.java).setup().get()
        val d = BigMenu.show(a, "Offline map", listOf(
            BigMenu.Choice("Downloading British Columbia", "⬇", "800 MB of 1.7 GB (47%)"),
            BigMenu.Choice("Load from files"),
        )) {}
        ShadowLooper.idleMainLooper()
        BigMenu.update(d, 0, BigMenu.Choice("Downloading British Columbia", "⬇", "900 MB of 1.7 GB (53%)"))
        val tiles = ArrayList<android.widget.TextView>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.isClickable) tiles += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(d.window!!.decorView)
        assertTrue(tiles[0].text.toString(), tiles[0].text.contains("53%"))
        assertTrue(tiles[1].text.toString().contains("Load from files"))
    }

    @Test fun reportFormRendering() {
        val a = Robolectric.buildActivity(Host::class.java).setup().get()
        val note = android.widget.EditText(a).apply { hint = "Short note, e.g. \"Allowed, park at the back near the garden centre\"" }
        val d = BigMenu.checklist(a, "✅ Overnight parking OK",
            com.robotdash.orbitgps.spots.Spots.Flag.entries.map { BigMenu.Check(it.label) },
            message = "At 4741 Lakelse Avenue, Terrace\nSends only this spot, what it is, your note and ticks. No name, account or phone ID. Reviewed before it shows for everyone.",
            actionLabel = { "Send report" }, input = note) {}
        ShadowLooper.idleMainLooper()
        val root = d.window!!.decorView
        val w = (a.resources.displayMetrics.widthPixels * 0.94).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/report-form.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(note.height >= 76 * a.resources.displayMetrics.density - 1)
    }
}

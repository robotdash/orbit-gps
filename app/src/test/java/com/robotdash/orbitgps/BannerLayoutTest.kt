package com.robotdash.orbitgps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.robotdash.orbitgps.nav.Route
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.5 bug: with a junction picture showing, the blue turn banner stretched down the whole
 * screen (the countdown bar beside the picture asked for all the height it was offered).
 * Inflates the real banner from activity_main.xml at phone size and checks it hugs its content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w448dp-h997dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BannerLayoutTest {

    @Test fun bannerHugsJunctionPicture() {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_OrbitGPS)
        val screenW = 1008
        val screenH = 2244
        // The banner, as laid out in activity_main.xml, on top of a stand-in map.
        val banner = bannerFromMainLayout(ctx)
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.rgb(230, 228, 220)) }
        (banner.parent as? ViewGroup)?.removeView(banner)
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        banner.visibility = View.VISIBLE
        banner.findViewById<TextView>(R.id.turnDistance).text = "120 m"
        banner.findViewById<TextView>(R.id.turnText).text = "Enter the roundabout and take the 2nd exit onto YH 16/Yellowhead Highway"

        // A real junction picture, sized the way MainActivity sizes it.
        val json = File("src/test/resources/route_terrace_kitimat.json").readText()
        val route = Route.fromOsrmJson(json)
        val step = route.steps.first { it.junction != null }
        val picH = minOf((screenW * 0.62f).toInt(), (screenH * 0.38f).toInt())
        val bmp = JunctionRenderer.render(route, step, screenW, picH, night = false)!!
        banner.findViewById<View>(R.id.junctionPanel).visibility = View.VISIBLE
        banner.findViewById<ImageView>(R.id.junctionImage).setImageBitmap(bmp)
        banner.findViewById<CountdownBar>(R.id.junctionBar).fraction = 0.4f

        root.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, screenW, screenH)

        val panel = banner.findViewById<View>(R.id.junctionPanel)
        val bar = banner.findViewById<View>(R.id.junctionBar)
        val img = banner.findViewById<View>(R.id.junctionImage)
        println("panel x ${panel.left}..${panel.right} img ${img.left}..${img.right} h=${img.height} bar x ${bar.left}..${bar.right} y ${bar.top}..${bar.bottom} vis=${bar.visibility} bannerPad=${banner.paddingLeft}")
        println("banner height ${banner.height}px of $screenH; picture panel ${panel.height}px; bar ${bar.height}px")
        // Banner = header (~110 dp) + picture, nowhere near the full screen.
        assertTrue("banner ${banner.height}px is too tall", banner.height < screenH * 0.6)
        assertTrue("panel ${panel.height}px should be about the picture's height", panel.height in (picH - 40)..(picH + 40))
        assertTrue("countdown bar ${bar.height}px should follow the picture", bar.height in 1..panel.height)

        File("build/banner_check.png").outputStream().use { out ->
            val shot = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(shot))
            shot.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** Pull navBanner out of activity_main.xml without inflating the map. */
    private fun bannerFromMainLayout(ctx: android.content.Context): View {
        val inflater = android.view.LayoutInflater.from(ctx).cloneInContext(ctx)
        inflater.factory2 = object : android.view.LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: android.content.Context, attrs: android.util.AttributeSet): View? =
                if (name == "org.maplibre.android.maps.MapView") View(context, attrs) else null
            override fun onCreateView(name: String, context: android.content.Context, attrs: android.util.AttributeSet): View? = null
        }
        val main = inflater.inflate(R.layout.activity_main, FrameLayout(ctx), false)
        return main.findViewById(R.id.navBanner)
    }

    /** 1.5 drew "YH 16" on top of the "2" badge when our exit pointed at the top edge. */
    @Test fun roundaboutLabelClearsBadge() {
        val step = com.robotdash.orbitgps.nav.NavStep(
            null, "Enter the roundabout", "YH 16", 0, 0.0, com.robotdash.orbitgps.nav.TurnIcon.ROUNDABOUT,
            com.robotdash.orbitgps.nav.Junction(
                com.robotdash.orbitgps.nav.JunctionKind.ROUNDABOUT, com.robotdash.orbitgps.voice.Side.RIGHT, null, null, "YH 16",
                com.robotdash.orbitgps.nav.RoundaboutInfo(
                    listOf(com.robotdash.orbitgps.nav.RoundaboutExit(85.0, true), com.robotdash.orbitgps.nav.RoundaboutExit(338.0, true)),
                    takeIndex = 1, exitNumber = 2, leftHand = false,
                ),
            ),
        )
        val route = Route(listOf(com.robotdash.orbitgps.nav.Pt(54.5, -128.6), com.robotdash.orbitgps.nav.Pt(54.501, -128.6)), doubleArrayOf(0.0, 111.0), listOf(step), 60.0)
        for (night in listOf(false, true)) {
            val bmp = JunctionRenderer.render(route, step, 1008, 624, night)!!
            File("build/roundabout_${if (night) "night" else "day"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

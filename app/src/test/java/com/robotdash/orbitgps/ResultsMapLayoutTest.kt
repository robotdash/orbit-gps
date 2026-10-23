package com.robotdash.orbitgps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Oct 21: category results can be shown as pins. The Map button and the bar over the map. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w448dp-h997dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResultsMapLayoutTest {
    private val w = 1008; private val h = 2244

    private fun main(): View {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_OrbitGPS)
        val inflater = android.view.LayoutInflater.from(ctx).cloneInContext(ctx)
        inflater.factory2 = object : android.view.LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: android.content.Context, attrs: android.util.AttributeSet): View? =
                if (name == "org.maplibre.android.maps.MapView") View(context, attrs).apply { setBackgroundColor(Color.rgb(226, 232, 214)) } else null
            override fun onCreateView(name: String, context: android.content.Context, attrs: android.util.AttributeSet): View? = null
        }
        return inflater.inflate(R.layout.activity_main, FrameLayout(ctx), false)
    }

    private fun shoot(v: View, name: String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun listHasAMapButton() {
        val m = main()
        m.findViewById<View>(R.id.searchPanel).visibility = View.VISIBLE
        m.findViewById<View>(R.id.browseScroll).visibility = View.GONE
        m.findViewById<View>(R.id.resultsHeaderRow).visibility = View.VISIBLE
        m.findViewById<TextView>(R.id.resultsHeader).text = "🌲 Free camping nearest you"
        shoot(m, "results_list")
        val btn = m.findViewById<View>(R.id.resultsMapBtn)
        val dp = m.resources.displayMetrics.density
        assertTrue("Map button ${btn.width}x${btn.height}", btn.visibility == View.VISIBLE && btn.height >= 48 * dp - 1 && btn.right <= w)
    }

    @Test fun mapBarSitsUnderTheSearchBar() {
        val m = main()
        m.findViewById<View>(R.id.quickChips).visibility = View.GONE
        m.findViewById<View>(R.id.mapResultsBar).visibility = View.VISIBLE
        m.findViewById<TextView>(R.id.mapResultsTitle).text = "🌲 Free camping"
        shoot(m, "results_map")
        val bar = m.findViewById<View>(R.id.mapResultsBar)
        val search = m.findViewById<View>(R.id.searchBar)
        assertTrue("bar ${bar.top} below search ${search.bottom}", bar.top >= search.bottom)
        val dp = m.resources.displayMetrics.density
        assertTrue(m.findViewById<View>(R.id.mapResultsClose).width >= 48 * dp - 1)
    }
}

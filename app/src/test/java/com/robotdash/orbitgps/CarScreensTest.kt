package com.robotdash.orbitgps

import android.location.Location
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.robotdash.orbitgps.car.CarPreviewScreen
import com.robotdash.orbitgps.car.CarSearchScreen
import com.robotdash.orbitgps.car.CarTurnsScreen
import com.robotdash.orbitgps.car.NavScreen
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds every Android Auto screen the way the car would ask for it. The Car App
 * Library checks its rules (button limits, required parts) when a template is
 * built, so a template that would be rejected by the car fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CarScreensTest {

    private lateinit var car: TestCarContext
    private val route = Route.fromOsrmJson(File("src/test/resources/route_terrace_town.json").readText())

    @Before fun setUp() {
        car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        NavCore.init(ApplicationProvider.getApplicationContext())
        NavCore.stop()
    }

    private fun fix(p: Pt, bearing: Float = 270f, speed: Float = 11f) = Location("test").apply {
        latitude = p.lat; longitude = p.lon; this.bearing = bearing; this.speed = speed; accuracy = 5f
        time = System.currentTimeMillis()
    }

    @Test fun idleScreenHasSearchHomeWork() {
        NavCore.fixForTest(fix(route.points.first()))
        val t = NavScreen(car).onGetTemplate() as NavigationTemplate
        assertEquals(3, t.actionStrip!!.actions.size)
    }

    @Test fun navigatingScreenShowsTurnCard() {
        NavCore.setPreviewForTest(route, route.points.last(), "Test destination")
        NavCore.start(simulate = false)
        // Drive a little way along the route.
        for (d in 0..400 step 20) {
            val (p, b) = NavCore.pointAlong(route, d.toDouble())
            NavCore.fixForTest(fix(p, b.toFloat()))
        }
        val t = NavScreen(car).onGetTemplate() as NavigationTemplate
        val info = t.navigationInfo
        assertTrue("expected a turn card, got $info", info is RoutingInfo)
        val ri = info as RoutingInfo
        assertNotNull(ri.currentStep)
        assertNotNull(ri.currentDistance)
        assertNotNull(t.destinationTravelEstimate)
        println("Car turn card: ${ri.currentStep!!.cue} in ${ri.currentDistance}; next: ${ri.nextStep?.cue}")
        // Top strip: Search, Turns, Stop (+ Stops / Go back when there are stops); never more than 4.
        assertEquals(3, t.actionStrip!!.actions.size)
        assertEquals("Mute sits on the map strip", 1, t.mapActionStrip!!.actions.size)
        // The turn after this one says how far after it comes.
        val cue = ri.nextStep!!.cue.toString()
        assertTrue("next step: $cue", !cue.startsWith("Then") && cue.contains(" · "))

        // Turns button: a list of every turn still ahead.
        val list = CarTurnsScreen(car).onGetTemplate() as androidx.car.app.model.ListTemplate
        val rows = list.singleList!!.items.map { it as androidx.car.app.model.Row }
        rows.forEach { println("  ${it.title} — ${it.texts.firstOrNull()}") }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.first().texts.first().toString().startsWith("In "))
        NavCore.stop()
    }

    @Test fun arrivedScreen() {
        NavCore.setPreviewForTest(route, route.points.last(), "Test destination")
        NavCore.start(simulate = false)
        var d = 0.0
        while (d <= route.length) {
            val (p, b) = NavCore.pointAlong(route, d)
            NavCore.fixForTest(fix(p, b.toFloat()))
            d += 15
        }
        val t = NavScreen(car).onGetTemplate() as NavigationTemplate
        assertTrue(t.navigationInfo is MessageInfo)
        NavCore.stop()
    }

    @Test fun searchScreenBuilds() {
        val t = CarSearchScreen(car).onGetTemplate()
        assertTrue(t is SearchTemplate)
    }

    @Test fun categoryScreenBuilds() {
        val c = com.robotdash.orbitgps.search.Categories.all.first()
        assertNotNull(com.robotdash.orbitgps.car.CarCategoryScreen(car, c).onGetTemplate())
    }

    @Test fun previewScreenStates() {
        // With a route ready
        NavCore.setPreviewForTest(route, route.points.last(), "Agar Avenue")
        val s = CarPreviewScreen(car, route.points.last(), "Agar Avenue", "Terrace")
        val ok = s.onGetTemplate() as RoutePreviewNavigationTemplate
        assertNotNull(ok.navigateAction)
        // Still loading
        NavCore.stop()
        val loading = CarPreviewScreen(car, Pt(54.5, -128.6), "Somewhere", "").onGetTemplate() as RoutePreviewNavigationTemplate
        assertTrue(loading.isLoading)
    }

    @Test fun noRouteShowsMessage() {
        // No GPS fix → preview fails with a message instead of a broken screen.
        NavCore.stop()
        val s = CarPreviewScreen(car, Pt(54.5, -128.6), "Somewhere", "")
        s.lifecycle // ensure created
        val t = s.onGetTemplate()
        assertNotNull(t)
    }
}

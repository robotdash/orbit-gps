package com.robotdash.orbitgps

import androidx.test.core.app.ApplicationProvider
import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.Route
import com.robotdash.orbitgps.search.Categories
import com.robotdash.orbitgps.search.SearchDb
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NearbyTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val terrace = Pt(54.5163, -128.6035)
    private lateinit var db: SearchDb

    @Before fun setUp() {
        // The Terrace search database (≈18 MB) isn't kept in the project zip; put a copy of
        // Terrace-300mi-search.db at src/test/resources/search_terrace.db to run these tests.
        val src = File("src/test/resources/search_terrace.db")
        assumeTrue("search_terrace.db test fixture not present", src.exists())
        val dest = OfflineMaps.file(ctx, "bc", OfflineMaps.Kind.SEARCH)
        src.copyTo(dest, overwrite = true)
        db = SearchDb(ctx)
    }

    private fun cat(label: String) = Categories.all.first { it.label == label }

    @Test fun gasNearTerraceNearestFirst() {
        val found = db.nearby(cat("Gas"), terrace)
        println("Gas near Terrace: " + found.take(5).map { "${it.place.name} ${it.distance.toInt()} m" })
        assertTrue(found.size >= 5)
        assertTrue(found.first().distance < 3000)
        assertTrue(found.zipWithNext().all { (a, b) -> a.distance <= b.distance })
    }

    @Test fun everyCategoryFindsSomething() {
        for (c in Categories.all.filter { it.match.isNotEmpty() }) {   // "Overnight parking" comes only from user reports
            val n = db.nearby(c, terrace).size
            println("${c.label}: $n")
            assertTrue("${c.label} found nothing", n > 0)
        }
    }

    @Test fun gasAlongTheRouteToKitimat() {
        val r = Route.fromOsrmJson(File("src/test/resources/route_terrace_kitimat.json").readText())
        val found = db.nearby(cat("Gas"), r.points.first(), r.points)
        println("Gas along Terrace→Kitimat: " + found.take(6).map { "${it.place.name} @${(it.distance / 1000).toInt()} km, ${it.offRoute?.toInt()} m off" })
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { (it.offRoute ?: 0.0) <= 2000 })
        assertTrue(found.zipWithNext().all { (a, b) -> a.distance <= b.distance })
        // Something near Kitimat should be found ahead.
        assertTrue(found.any { Geo.distance(it.place.pt, Pt(54.0524, -128.6534)) < 5000 })
    }

    @Test fun findsHomeAddressHoweverTyped() {
        for (q in listOf("5230 centennial drive terrace bc", "5230 Centennial Dr", "5230 Centennial Drive, Terrace, BC", "5230 centennial")) {
            val r = db.search(q, terrace)
            println("'$q' -> ${r.take(2).map { "${it.name}, ${it.detail}" }}")
            assertTrue("'$q' not found", r.first().name == "5230 Centennial Drive" && r.first().detail == "Terrace")
            assertTrue(Geo.distance(r.first().pt, Pt(54.5552326, -128.6267049)) < 5)
        }
    }

    @Test fun missingHouseNumberOffersClosest() {
        val r = db.search("5231 centennial drive", terrace)
        println("closest: ${r.map { "${it.name} (${it.subtitle})" }}")
        assertTrue(r.isNotEmpty() && r.first().name.contains("Centennial"))
    }

    @Test fun streetsAndPlacesStillFound() {
        assertTrue(db.search("lakelse avenue", terrace).any { it.kind == "street" })
        assertTrue(db.search("terrace", terrace).first().kind == "city")
        assertTrue(db.search("save on foods", terrace).first().name == "Save-On-Foods")
    }

    @Test fun nearestAddressLabelsCurrentLocation() {
        // Outside Terrace's Save-On-Foods on Lakelse Avenue.
        val t0 = System.nanoTime()
        val addr = db.nearestAddress(Pt(54.5165, -128.5985))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("nearestAddress: $addr in $ms ms")
        // Oct 22: with all of BC this read 1.4 million rows into the app and froze "Report a spot".
        assertTrue("took $ms ms", ms < 3000)
        println("Nearest address: $addr")
        assertTrue(addr != null && addr.contains("Terrace"))
        // Middle of nowhere: nothing within 300 m.
        assertTrue(db.nearestAddress(Pt(56.5, -127.0)) == null)
    }

    @Test fun favouritesRenameAndRemove() {
        val saved = com.robotdash.orbitgps.search.SavedPlaces(ctx)
        val f = com.robotdash.orbitgps.search.SavedPlaces.Saved("Shell", "4655 Lakelse Avenue", Pt(54.517, -128.6))
        saved.addFavourite(f)
        saved.renameFavourite(f, "My gas station")
        assertTrue(saved.favourites().any { it.name == "My gas station" })
        saved.removeFavourite(saved.favourites().first { it.name == "My gas station" })
        assertTrue(saved.favourites().none { it.name == "My gas station" })
        saved.set(com.robotdash.orbitgps.search.SavedPlaces.Slot.HOME, f.copy(name = "Home"))
        assertTrue(saved.get(com.robotdash.orbitgps.search.SavedPlaces.Slot.HOME)?.name == "Home")
    }

    @Test fun restAreasAndLandmarks() {
        val rest = db.nearby(cat("Rest areas"), terrace)
        println("Rest areas: " + rest.take(4).map { "${it.place.name} ${(it.distance / 1000).toInt()} km" })
        val land = db.nearby(cat("Landmarks"), terrace)
        println("Landmarks: " + land.take(4).map { "${it.place.name} (${it.place.category}) ${(it.distance / 1000).toInt()} km" })
        assertTrue(rest.isNotEmpty() && land.isNotEmpty())
    }
}

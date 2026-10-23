package com.robotdash.orbitgps

import androidx.test.core.app.ApplicationProvider
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.search.SearchDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The user's home address must be found however it's typed (Oct 19: "can't find my home address"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddressSearchTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val terrace = Pt(54.5163, -128.6035)
    private lateinit var db: SearchDb

    @Before fun setUp() {
        val src = File("src/test/resources/search_terrace.db")
        assumeTrue("search_terrace.db test fixture not present", src.exists())
        src.copyTo(OfflineMaps.file(ctx, "bc", OfflineMaps.Kind.SEARCH), overwrite = true)
        db = SearchDb(ctx)
    }

    @Test fun findsHomeAddressTypedManyWays() {
        for (q in listOf(
            "5230 centennial drive terrace", "5230 Centennial Drive, Terrace", "5230 centennial dr",
            "5230 Centennial Dr Terrace BC", "5230 cent", "5230 centennial", "5230 Centennial Drive, Terrace, BC, Canada",
        )) {
            val r = db.search(q, terrace)
            println("'$q' -> " + r.take(3).map { "${it.name} | ${it.detail} | ${it.kind}" })
            assertTrue("'$q' found nothing", r.isNotEmpty())
            assertEquals("'$q'", "5230 Centennial Drive", r.first().name)
            assertEquals("Terrace", r.first().detail)
        }
    }

    /** Oct 21: a friend's house in Vernon was missing (the address fetch dropped sites near box edges). */
    @Test fun findsAnAddressAcrossTheProvince() {
        for (q in listOf(
            "3700 22 St, Vernon", "3700 22 st vernon", "3700 22nd street vernon", "3700 22 St Vernon BC",
            "3700 22 St, Vernon, BC V1T 4H9", "3700 22 St Vernon V1T4H9",
        )) {
            val r = db.search(q, terrace)                      // searching from home, 700 km away
            println("'$q' -> " + r.take(3).map { "${it.name} | ${it.detail} | ${it.kind}" })
            assertTrue("'$q' found nothing", r.isNotEmpty())
            assertEquals("'$q'", "3700 22 St", r.first().name.replace("Street", "St"))
            assertEquals("Vernon", r.first().detail)
        }
    }

    /** Oct 22: businesses OpenStreetMap doesn't have come from Overture Maps (e.g. a doctor's office). */
    @Test fun findsBusinessesByName() {
        for ((q, want) in listOf("spruce medical" to "Spruce Medical Centre", "Spruce Medical Centre" to "Spruce Medical Centre",
                                 "safeway" to "Safeway")) {
            val r = db.search(q, terrace)
            println("'$q' -> " + r.take(3).map { "${it.name} | ${it.detail} | ${it.kind}" })
            assertTrue("'$q' found nothing", r.isNotEmpty())
            assertTrue("'$q' -> ${r.first().name}", r.first().name.startsWith(want))
            assertTrue("'$q' should be in Terrace: ${r.first().detail}", r.first().detail.contains("Terrace"))
        }
    }

    @Test fun infoShowsTheAddresses() {
        val i = db.info("bc")!!
        println("search file: $i")
        assertTrue(i.addresses!! > 90_000)
    }
}

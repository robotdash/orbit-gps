package com.robotdash.orbitgps

import androidx.test.core.app.ApplicationProvider
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.spots.Spots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Oct 22: spots reported by Orbit users (spots.orbitgps.ca). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SpotsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    // The same shape the live service sends (checked against spots.orbitgps.ca/spots.json).
    private val sample = """{"version":"2026-10-22T17:28:55Z","count":3,"spots":[
        {"id":7,"t":"overnight_ok","lat":54.5208,"lon":-128.5776,"n":"Walmart: back of lot","f":"bigrig,toilets","up":4,"down":0,"c":"2026-10-01T10:00:00Z"},
        {"id":8,"t":"no_overnight","lat":54.523,"lon":-128.59,"n":"Security knocks","f":"","up":0,"down":0,"c":null},
        {"id":9,"t":"potable_water","lat":54.5163,"lon":-128.6035,"n":"","f":"","up":0,"down":0,"c":"2026-10-22T10:00:00Z"},
        {"id":10,"t":"something_new","lat":1,"lon":1,"n":"","f":"","up":0,"down":0,"c":null}]}"""

    @Test fun readsTheListAndSkipsUnknownTypes() {
        val s = Spots.parse(sample)
        assertEquals(listOf(7L, 8L, 9L), s.map { it.id })
        assertEquals(listOf(Spots.Flag.BIG_RIG, Spots.Flag.TOILETS), s[0].flags)
        assertEquals(null, s[1].confirmed)
    }

    @Test fun spotsShowUnderTheRightCategories() {
        File(ctx.filesDir, "spots.json").writeText(sample)
        val now = java.time.Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
        val free = Spots.places(ctx, "Free camping", now)
        assertEquals(setOf("✅ Overnight parking OK", "🚫 No overnight parking"), free.map { it.name }.toSet())
        val walmart = free.first { it.name.startsWith("✅") }
        assertEquals(7L, Spots.idOf(walmart))
        assertTrue(walmart.detail, walmart.detail.contains("Walmart: back of lot") && walmart.detail.contains("Big rigs OK")
            && walmart.detail.contains("confirmed 3 weeks ago") && walmart.detail.contains("👍 4"))
        assertEquals(listOf("💧 Fresh potable water"), Spots.places(ctx, "Fresh water", now).map { it.name })
        assertTrue(Spots.places(ctx, "Gas", now).isEmpty())
    }

    @Test fun reportsWaitForSignalThenSend() {
        Spots.report(ctx, Spots.Type.FREE_CAMPING, Pt(54.6, -128.4), "Gravel pull-off by the river", setOf(Spots.Flag.BIG_RIG))
        Spots.vote(ctx, 7, good = true)
        assertEquals(1, Spots.waiting(ctx))
        // No signal: nothing lost.
        assertEquals(0, Spots.flush(ctx) { _, _ -> -1 })
        assertEquals(1, Spots.waiting(ctx))
        // Signal back: both go, report first.
        val sent = ArrayList<Pair<String, String>>()
        assertEquals(1, Spots.flush(ctx) { path, body -> sent += path to body; 200 })
        assertEquals(listOf("report", "vote"), sent.map { it.first })
        assertTrue(sent[0].second, sent[0].second.contains("\"free_camping\"") && sent[0].second.contains("bigrig"))
        assertEquals(0, Spots.waiting(ctx))
        assertTrue(Spots.votedOn(ctx, 7))
    }

    @Test fun refusedReportIsDroppedButBusyServerIsRetried() {
        Spots.report(ctx, Spots.Type.SANI_DUMP, Pt(54.5, -128.6), "", emptySet())
        Spots.flush(ctx) { _, _ -> 429 }            // too many today: keep it
        assertEquals(1, Spots.waiting(ctx))
        Spots.flush(ctx) { _, _ -> 400 }            // refused as bad data: don't retry forever
        assertEquals(0, Spots.waiting(ctx))
    }

    @Test fun freshness() {
        val now = java.time.Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
        assertEquals("confirmed today", Spots.confirmedText("2026-10-22T08:00:00Z", now))
        assertEquals("confirmed 5 days ago", Spots.confirmedText("2026-10-17T08:00:00Z", now))
        assertEquals("confirmed 3 months ago", Spots.confirmedText("2026-07-15T08:00:00Z", now))
        assertEquals(null, Spots.confirmedText(null, now))
    }
}

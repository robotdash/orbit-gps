package com.robotdash.orbitgps

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.robotdash.orbitgps.nav.Outline
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.RouteService
import com.robotdash.orbitgps.search.SearchDb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 3.4: several regions on the phone at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MultiRegionTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun box(w: Double, s: Double, e: Double, n: Double) = Outline.fromGeoJson(JSONObject(
        """{"type":"Polygon","coordinates":[[[$w,$s],[$e,$s],[$e,$n],[$w,$n],[$w,$s]]]}"""))!!

    @Test fun mapStyleShowsEveryRegion() {
        val json = File("src/main/assets/offline_style_topo.json").readText()
        val out = JSONObject(OfflineMaps.withFiles(json, mapOf(
            "PMTILES_URL" to listOf("pmtiles://file:///bc.pmtiles", "pmtiles://file:///ab.pmtiles"),
            "DEM_URL" to listOf("pmtiles://file:///bc-terrain.pmtiles"),
            "CONTOUR_URL" to emptyList())))
        val src = out.getJSONObject("sources")
        assertEquals("pmtiles://file:///bc.pmtiles", src.getJSONObject("protomaps").getString("url"))
        assertEquals("pmtiles://file:///ab.pmtiles", src.getJSONObject("protomaps@1").getString("url"))
        assertTrue(src.has("terrain")); assertFalse(src.has("terrain@1"))
        assertFalse("no contour files: no contour source", src.has("contours"))
        val layers = out.getJSONArray("layers")
        val ids = (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        assertEquals("layer ids unique", ids.size, ids.toSet().size)
        val before = JSONObject(json).getJSONArray("layers")
        val vector = (0 until before.length()).count { before.getJSONObject(it).optString("source") == "protomaps" }
        val contour = (0 until before.length()).count { before.getJSONObject(it).optString("source") == "contours" }
        assertEquals(before.length() + vector - contour, layers.length())
        // each region's copy right after the original, so drawing order is kept
        val i = ids.indexOf("water")
        if (i >= 0) assertEquals("water@1", ids[i + 1])
        (0 until layers.length()).map { layers.getJSONObject(it) }.filter { it.getString("id").endsWith("@1") }
            .forEach { assertEquals("protomaps@1", it.getString("source")) }
    }

    @Test fun oldSingleRegionFilesMoveIntoTheirRegion() {
        val maps = File(ctx.filesDir, "maps").apply { deleteRecursively(); mkdirs() }
        File(maps, "region.pmtiles").writeBytes(ByteArray(2048))
        File(maps, "search.db").writeBytes(ByteArray(2048))
        File(maps, "terrain.pmtiles").writeBytes(ByteArray(2048))
        ctx.getSharedPreferences("regions", 0).edit().putString("installedId", "bc").putString("installedBuilt", "2026-10-22")
            .putString("topoId", "bc").commit()
        assertEquals(listOf("bc"), OfflineMaps.regions(ctx))
        assertTrue(OfflineMaps.has(ctx, "bc", OfflineMaps.Kind.MAP))
        assertTrue(OfflineMaps.has(ctx, "bc", OfflineMaps.Kind.TERRAIN))
        assertFalse(File(maps, "region.pmtiles").exists())
        assertEquals("2026-10-22", RegionDownloads.installed(ctx, "bc"))
        // hand-picked files from before have no region
        File(maps, "routing.tar").writeBytes(ByteArray(2048))
        ctx.getSharedPreferences("regions", 0).edit().clear().commit()
        assertEquals(listOf("bc", OfflineMaps.HAND), OfflineMaps.regions(ctx))
        RegionDownloads.remove(ctx, "bc")
        assertEquals(listOf(OfflineMaps.HAND), OfflineMaps.regions(ctx))
        RegionDownloads.remove(ctx, OfflineMaps.HAND)
        assertTrue(OfflineMaps.regions(ctx).isEmpty())
    }

    @Test fun routingPicksTheRegionHoldingTheTrip() {
        // BC + 50 km reaches Canmore; Alberta + 50 km reaches Golden.
        val bc = "bc" to (box(-139.0, 48.0, -113.5, 60.5) to box(-139.0, 48.3, -114.1, 60.0))
        val ab = "ab" to (box(-120.9, 48.5, -109.1, 60.5) to box(-120.0, 49.0, -110.0, 60.0))
        val terrace = Pt(54.52, -128.60); val golden = Pt(51.30, -116.96); val canmore = Pt(51.09, -115.36)
        val calgary = Pt(51.05, -114.07); val edmonton = Pt(53.55, -113.49)
        assertEquals("bc", RouteService.pick(listOf(terrace, golden), listOf(bc, ab)))
        assertEquals("ab", RouteService.pick(listOf(calgary, edmonton), listOf(bc, ab)))
        assertEquals("both hold it; more of it in Alberta", "ab", RouteService.pick(listOf(calgary, canmore), listOf(bc, ab)))
        assertTrue("Golden to Canmore: both hold it", RouteService.pick(listOf(golden, canmore), listOf(bc, ab)) in setOf("bc", "ab"))
        // no single region holds Terrace → Edmonton: best try
        assertEquals("bc", RouteService.pick(listOf(terrace, edmonton, terrace), listOf(bc, ab)))
        // a region loaded by hand (no outline) is used when no outlined region fits
        assertEquals("files", RouteService.pick(listOf(terrace, edmonton), listOf(bc, ab, "files" to (null to null))))
        assertNull(RouteService.pick(listOf(terrace), emptyList()))
    }

    private fun searchDb(region: String, rows: List<Array<Any>>) {
        val f = OfflineMaps.file(ctx, region, OfflineMaps.Kind.SEARCH)
        f.delete()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { d ->
            d.execSQL("CREATE VIRTUAL TABLE places USING fts4(search, name, kind, category, detail, lat, lon, rank, " +
                "notindexed=name, notindexed=kind, notindexed=category, notindexed=detail, notindexed=lat, notindexed=lon, notindexed=rank)")
            d.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
            d.execSQL("INSERT INTO meta VALUES ('built','2026-10-22'),('addresses','1')")
            rows.forEach { d.execSQL("INSERT INTO places VALUES (?,?,?,?,?,?,?,?)", it) }
            repeat(4) { d.execSQL("INSERT INTO places VALUES ('filler','filler','poi','x','',0,0,10)") }  // > 1 KB
        }
    }

    @Test fun searchLooksInEveryRegionOnce() {
        File(ctx.filesDir, "maps").deleteRecursively()
        // Golden is in both (BC's own, and Alberta's 50 km strip); Calgary only in Alberta's.
        searchDb("bc", listOf(arrayOf("golden", "Golden", "town", "", "British Columbia", 51.2975, -116.9631, 3),
            arrayOf("tim hortons cafe terrace", "Tim Hortons", "poi", "cafe", "Terrace", 54.51, -128.60, 10)))
        searchDb("ab", listOf(arrayOf("golden", "Golden", "town", "", "British Columbia", 51.2976, -116.9632, 3),
            arrayOf("calgary", "Calgary", "city", "", "Alberta", 51.05, -114.07, 1),
            arrayOf("tim hortons cafe canmore", "Tim Hortons", "poi", "cafe", "Canmore", 51.09, -115.35, 10)))
        val db = SearchDb(ctx)
        assertEquals(1, db.search("golden", null).count { it.name == "Golden" })
        assertEquals(1, db.search("calgary", null).size)
        assertEquals("one per town", 2, db.search("tim hortons", null).size)
        val cafes = db.nearby(com.robotdash.orbitgps.search.Categories.all.first { it.label == "Coffee" }, Pt(51.09, -115.35))
        assertEquals("Canmore", cafes.first().place.detail)
        assertEquals("2026-10-22", db.info("ab")!!.built)
        db.close()
    }

    @Test fun liveCatalogOutlinesPickTheRightRegion() {
        val all = RegionDownloads.parseCatalog(javaClass.classLoader!!.getResource("regions-catalog-v2.json").readText())
        val regions = all.map { it.id to (it.area to it.core) }
        fun pick(vararg p: Pt) = RouteService.pick(p.toList(), regions)
        val terrace = Pt(54.52, -128.60); val vancouver = Pt(49.28, -123.12); val golden = Pt(51.30, -116.96)
        val calgary = Pt(51.05, -114.07); val edmonton = Pt(53.55, -113.49); val jasper = Pt(52.88, -118.08)
        assertEquals("bc", pick(terrace, vancouver))
        assertEquals("ab", pick(calgary, edmonton))
        assertEquals("ab", pick(calgary, golden))      // Golden is in Alberta's 50 km strip
        assertEquals("ab", pick(edmonton, jasper))
        assertEquals("bc", pick(golden, Pt(50.70, -119.27)))   // Golden - Salmon Arm
        val seattle = Pt(47.61, -122.33); val portland = Pt(45.52, -122.68); val bellingham = Pt(48.75, -122.48)
        assertEquals("wa", pick(seattle, portland))
        assertEquals("wa", pick(vancouver, seattle))       // Vancouver is in Washington's strip, Seattle isn't in BC's
        assertEquals("bc", pick(vancouver, Pt(49.10, -122.30)))   // Vancouver - Abbotsford stays in BC
        assertTrue(pick(vancouver, bellingham) in setOf("bc", "wa"))
        assertTrue(all.none { it.id == "terrace" })
    }

    @Test fun catalogOutlinesAreKept() {
        val text = """{"version":1,"regions":[{"id":"ab","name":"Alberta","built":"2026-10-22","files":[],
            "area":{"type":"Polygon","coordinates":[[[-120,48],[-109,48],[-109,61],[-120,61],[-120,48]]]}}]}"""
        val ab = RegionDownloads.parseCatalog(text).single()
        assertTrue(Pt(51.05, -114.07) in ab.area!!)
        assertFalse(Pt(49.28, -123.12) in ab.area!!)
        assertNull(ab.core)
        // and survive a round trip (they're kept on the phone for offline routing)
        val again = Outline.fromGeoJson(ab.area!!.toGeoJson())!!
        assertTrue(Pt(51.05, -114.07) in again)
    }
}

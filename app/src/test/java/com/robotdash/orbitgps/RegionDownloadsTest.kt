package com.robotdash.orbitgps

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RegionDownloadsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun readsTheLiveCatalog() {
        val text = javaClass.classLoader!!.getResource("regions-catalog.json").readText()
        val all = RegionDownloads.parseCatalog(text)
        assertEquals(listOf("bc", "terrace"), all.map { it.id })
        val bc = all[0]
        assertEquals(setOf("map", "routing", "search", "terrain", "contours"), bc.files.map { it.kind }.toSet())
        bc.files.forEach { assertTrue(it.url, it.url.startsWith("regions/bc/")) }
        assertTrue("BC without Topo under 2 GB", bc.size(false) < 2_000_000_000)
        val r = all[1]
        assertEquals("terrace", r.id)
        assertEquals(9, r.files.size)
        // Topo is optional: basic download is map + routing + search only
        assertEquals(setOf("map", "routing", "search"), r.files(false).map { it.kind }.toSet())
        assertTrue(r.size(true) > r.size(false))
        r.files.forEach { assertTrue(it.url, it.url.startsWith("regions/terrace/")); assertEquals(64, it.sha256.length) }
        println("basic ${RegionDownloads.sizeText(r.size(false))}, with topo ${RegionDownloads.sizeText(r.size(true))}")
    }

    @Test fun sizes() {
        assertEquals("541 MB", RegionDownloads.sizeText(541_300_000))
        assertEquals("1.1 GB", RegionDownloads.sizeText(1_130_000_000))
    }

    /** A fake routing tar, split in two like the big files on the server. */
    private fun fakeRegion(): Pair<RegionDownloads.Region, List<ByteArray>> {
        val tar = ByteArray(3000) { (it % 251).toByte() }
        "ustar".toByteArray().copyInto(tar, 257)
        val parts = listOf(tar.copyOfRange(0, 1700), tar.copyOfRange(1700, 3000))
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val files = parts.mapIndexed { i, b ->
            RegionDownloads.RegionFile("routing", "test-routing.tar.part${i + 1}", "regions/test/x/test-routing.tar.part${i + 1}",
                b.size.toLong(), sha(b), false)
        }
        return RegionDownloads.Region("test", "Test", "", "2026-10-19", files) to parts
    }

    private fun arrive(parts: List<ByteArray>, names: List<String>) {
        val d = File(ctx.getExternalFilesDir("regions"), "test")
        d.mkdirs()
        parts.forEachIndexed { i, b -> File(d, names[i]).writeBytes(b) }
    }

    @Test fun finishedDownloadIsCheckedJoinedInstalledAndTidied() {
        val (region, parts) = fakeRegion()
        RegionDownloads.start(ctx, region, withTopo = false)
        assertNotNull(RegionDownloads.job(ctx))
        arrive(parts, region.files.map { it.name })

        val kinds = RegionDownloads.finish(ctx) {}.getOrThrow()
        assertEquals(listOf(OfflineMaps.Kind.ROUTING), kinds)
        assertTrue(OfflineMaps.file(ctx, "test", OfflineMaps.Kind.ROUTING).readBytes().contentEquals(parts[0] + parts[1]))
        assertEquals("2026-10-19", RegionDownloads.installed(ctx, "test"))
        assertNull("job cleared", RegionDownloads.job(ctx))
        assertFalse("downloads deleted", File(ctx.getExternalFilesDir("regions"), "test").exists())
    }

    @Test fun damagedFileIsRefusedAndKeptForRetry() {
        val (region, parts) = fakeRegion()
        RegionDownloads.start(ctx, region, withTopo = false)
        val bad = parts.map { it.copyOf() }; bad[1][10] = (bad[1][10] + 1).toByte()
        arrive(bad, region.files.map { it.name })

        val r = RegionDownloads.finish(ctx) {}
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!, r.exceptionOrNull()!!.message!!.contains("damaged"))
        assertNotNull("job kept so it can be fetched again", RegionDownloads.job(ctx))
        assertNull(RegionDownloads.installed(ctx, "test"))

        RegionDownloads.cancel(ctx)
        assertNull(RegionDownloads.job(ctx))
    }

    @Test fun topoOnlyDownloadKeepsTheMapRecord() {
        val (region, parts) = fakeRegion()
        RegionDownloads.start(ctx, region, withTopo = false)
        arrive(parts, region.files.map { it.name })
        RegionDownloads.finish(ctx) {}.getOrThrow()
        assertEquals("2026-10-19", RegionDownloads.installed(ctx, "test"))
        assertNull(RegionDownloads.topoBuilt(ctx, "test"))

        // later: just the Topo files (faked here as an optional part)
        val topo = region.files.map { it.copy(optional = true) }
        val later = region.copy(built = "2026-11-01", files = topo)
        RegionDownloads.start(ctx, later, topo)
        arrive(parts, topo.map { it.name })
        RegionDownloads.finish(ctx) {}.getOrThrow()
        assertEquals("map record untouched", "2026-10-19", RegionDownloads.installed(ctx, "test"))
        assertEquals("2026-11-01", RegionDownloads.topoBuilt(ctx, "test"))
    }

    /**
     * Oct 21: BC search was rebuilt (addresses were missing). Only the search files change
     * folder in the catalog, so a phone with BC from 2.4 (which only kept the build date)
     * should be offered just the search update, and be current after installing it.
     */
    @Test fun updateDownloadsOnlyChangedFiles() {
        val (region, parts) = fakeRegion()                       // two routing parts, "2026-10-19"
        val old = region.copy(files = region.files.map { it.copy(url = "regions/test/2026-10-19/${it.name}") })
        RegionDownloads.start(ctx, old, withTopo = false)
        arrive(parts, old.files.map { it.name })
        RegionDownloads.finish(ctx) {}.getOrThrow()
        // Pretend this was installed by 2.4: forget the per-file record, keep the date.
        ctx.getSharedPreferences("regions", android.content.Context.MODE_PRIVATE).edit().remove("urls:test").commit()
        assertTrue("nothing stale yet", RegionDownloads.stale(ctx, old, optional = false).isEmpty())

        // New catalog: part2 rebuilt into a new folder, part1 unchanged.
        val newer = old.copy(built = "2026-10-22", files = listOf(old.files[0],
            old.files[1].copy(url = "regions/test/2026-10-22/${old.files[1].name}")))
        val stale = RegionDownloads.stale(ctx, newer, optional = false)
        assertEquals("a changed kind comes down whole (parts are joined)", newer.files.map { it.url }, stale.map { it.url })

        RegionDownloads.start(ctx, newer, stale)
        arrive(parts, newer.files.map { it.name })
        RegionDownloads.finish(ctx) {}.getOrThrow()
        assertTrue("current after the update", RegionDownloads.stale(ctx, newer, optional = false).isEmpty())
        assertEquals("2026-10-22", RegionDownloads.installed(ctx, "test"))
    }
}

package com.robotdash.orbitgps

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks a freshly built region pack is recognised by the import code, part by part.
 * Run with -Porbit.region=/path/to/folder (skipped otherwise).
 */
class RegionFilesTest {
    @Test fun eachFileIsRecognised() {
        val dir = System.getProperty("orbit.region")?.let(::File)
        assumeTrue("orbit.region not set", dir != null && dir.isDirectory)
        val expect = mapOf("map" to OfflineMaps.Kind.MAP, "routing" to OfflineMaps.Kind.ROUTING,
            "search" to OfflineMaps.Kind.SEARCH, "terrain" to OfflineMaps.Kind.TERRAIN, "contours" to OfflineMaps.Kind.CONTOURS)
        val firstParts = dir!!.listFiles()!!.filter { !it.name.contains(".part") || it.name.endsWith(".part1") }
        var checked = 0
        for (f in firstParts.sortedBy { it.name }) {
            val want = expect.entries.firstOrNull { f.name.contains("-${it.key}") }?.value ?: continue
            val head = ByteArray(65536)
            val n = f.inputStream().use { inp -> var t = 0; while (t < head.size) { val r = inp.read(head, t, head.size - t); if (r < 0) break; t += r }; t }
            val got = OfflineMaps.sniff(head, n, f.name)
            println("${f.name}: $got")
            assertEquals(f.name, want, got)
            checked++
        }
        assertEquals("files checked", 5, checked)
    }
}

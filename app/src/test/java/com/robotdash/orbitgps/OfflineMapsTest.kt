package com.robotdash.orbitgps

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMapsTest {
    private fun head(name: String): ByteArray = javaClass.getResourceAsStream("/heads/$name")!!.use { it.readBytes() }

    @Test fun recognisesEachPmtilesFile() {
        val map = head("map.bin"); val terrain = head("terrain.bin"); val contours = head("contours.bin")
        assertEquals(OfflineMaps.Kind.MAP, OfflineMaps.sniff(map, map.size, "Terrace-300mi.pmtiles.part1"))
        assertEquals(OfflineMaps.Kind.TERRAIN, OfflineMaps.sniff(terrain, terrain.size, "x.pmtiles.part1"))
        assertEquals(OfflineMaps.Kind.CONTOURS, OfflineMaps.sniff(contours, contours.size, "renamed.pmtiles"))
    }

    @Test fun topoStylesDropContoursCleanly() {
        val dir = java.io.File("src/main/assets")
        for (f in listOf("offline_style_topo.json", "offline_style_topo_night.json")) {
            val json = dir.resolve(f).readText()
            assertTrue(json.contains("DEM_URL") && json.contains("CONTOUR_URL"))
            val out = JSONObject(OfflineMaps.withoutSource(json, "contours"))
            assertFalse(out.getJSONObject("sources").has("contours"))
            val layers = out.getJSONArray("layers")
            for (i in 0 until layers.length()) assertFalse(layers.getJSONObject(i).optString("source") == "contours")
            assertTrue((0 until layers.length()).any { layers.getJSONObject(it).getString("id") == "hillshade" })
        }
    }

    @Test fun everyStyleHasDayAndNight() {
        val dir = java.io.File("src/main/assets")
        for (b in listOf("offline_style", "offline_style_topo", "offline_style_grey"))
            for (n in listOf("", "_night")) {
                val j = JSONObject(dir.resolve("$b$n.json").readText())
                assertTrue(j.getJSONObject("sources").getJSONObject("protomaps").getString("url") == "PMTILES_URL")
            }
    }
}

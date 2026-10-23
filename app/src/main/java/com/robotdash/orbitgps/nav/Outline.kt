package com.robotdash.orbitgps.nav

import org.json.JSONArray
import org.json.JSONObject

/**
 * A region's outline (GeoJSON Polygon or MultiPolygon, simplified to ~1 km), used to tell which
 * downloaded region a place is in.
 */
class Outline(private val polygons: List<List<DoubleArray>>) {   // each polygon: rings, ring = lon,lat,lon,lat...

    operator fun contains(p: Pt): Boolean = polygons.any { rings ->
        // inside the outer ring and not in a hole
        rings.isNotEmpty() && inRing(rings[0], p) && rings.drop(1).none { inRing(it, p) }
    }

    private fun inRing(r: DoubleArray, p: Pt): Boolean {
        var inside = false
        var j = r.size - 2
        var i = 0
        while (i < r.size) {
            val xi = r[i]; val yi = r[i + 1]; val xj = r[j]; val yj = r[j + 1]
            if ((yi > p.lat) != (yj > p.lat) && p.lon < (xj - xi) * (p.lat - yi) / (yj - yi) + xi) inside = !inside
            j = i; i += 2
        }
        return inside
    }

    fun toGeoJson(): JSONObject = JSONObject().put("type", "MultiPolygon").put("coordinates", JSONArray().apply {
        polygons.forEach { rings ->
            put(JSONArray().apply {
                rings.forEach { r ->
                    put(JSONArray().apply { for (i in r.indices step 2) put(JSONArray().put(r[i]).put(r[i + 1])) })
                }
            })
        }
    })

    companion object {
        fun fromGeoJson(o: JSONObject): Outline? {
            val g = if (o.optString("type") == "Feature") o.optJSONObject("geometry") ?: return null else o
            val c = g.optJSONArray("coordinates") ?: return null
            fun ring(a: JSONArray) = DoubleArray(a.length() * 2) { k -> a.getJSONArray(k / 2).getDouble(k % 2) }
            fun poly(a: JSONArray) = (0 until a.length()).map { ring(a.getJSONArray(it)) }
            return when (g.optString("type")) {
                "Polygon" -> Outline(listOf(poly(c)))
                "MultiPolygon" -> Outline((0 until c.length()).map { poly(c.getJSONArray(it)) })
                else -> null
            }
        }
    }
}

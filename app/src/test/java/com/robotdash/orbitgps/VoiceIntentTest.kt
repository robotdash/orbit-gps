package com.robotdash.orbitgps

import com.robotdash.orbitgps.car.GeoQuery
import com.robotdash.orbitgps.search.Categories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceIntentTest {
    @Test fun geoQueries() {
        assertEquals("Babine Animal Hospital", GeoQuery.parse("geo:0,0?q=Babine+Animal+Hospital")!!.query)
        val c = GeoQuery.parse("geo:54.5,-128.6")!!
        assertEquals(54.5, c.pt!!.lat, 1e-9); assertEquals("", c.query)
        val l = GeoQuery.parse("geo:0,0?q=54.78,-127.16(Smithers)")!!
        assertEquals(-127.16, l.pt!!.lon, 1e-9); assertEquals("", l.query)
        assertEquals("coffee shop", GeoQuery.parse("geo:0,0?q=coffee%20shop&mode=d&intent=navigation")!!.query)
        assertNull(GeoQuery.parse("https://example.com"))
    }

    @Test fun categoryWords() {
        assertEquals("Gas", Categories.match("gas station")?.label)
        assertEquals("Vet", Categories.match("find a vet near me")?.label)
        assertEquals("Sani dumps", Categories.match("sani dump")?.label)
        assertEquals("Coffee", Categories.match("Coffee shops")?.label)
        assertNull(Categories.match("Babine Animal Hospital"))
    }

    @Test fun rvTilesOnlyWhenAsked() {
        assertEquals(0, Categories.visible(false).count { it.rv })
        assertEquals(6, Categories.visible(true).count { it.rv })
    }
}

package com.robotdash.orbitgps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SunTest {
    private fun terrace(h: Int, m: Int = 0): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("America/Vancouver")).apply { set(2026, Calendar.OCTOBER, 16, h, m, 0) }

    @Test fun terraceSunriseSunset() {
        val (rise, set) = Sun.riseSetUtcHours(54.5163, -128.6035, terrace(12))!!
        // Terrace, Oct 16 (checked against the astral library): sunrise 15:06 UTC (8:06 am), sunset 01:35 UTC (6:35 pm)
        assertEquals(15.10, rise, 0.1)
        assertEquals(1.59, set, 0.1)
    }

    @Test fun dayAndNight() {
        assertTrue(Sun.isNight(54.5163, -128.6035, terrace(6, 30)))   // before sunrise
        assertFalse(Sun.isNight(54.5163, -128.6035, terrace(12)))     // noon
        assertFalse(Sun.isNight(54.5163, -128.6035, terrace(17, 30))) // late afternoon
        assertTrue(Sun.isNight(54.5163, -128.6035, terrace(19)))      // after sunset
        assertTrue(Sun.isNight(54.5163, -128.6035, terrace(23)))
    }
}

package com.robotdash.orbitgps

import com.robotdash.orbitgps.voice.GuidanceTracker
import com.robotdash.orbitgps.voice.Lanes
import com.robotdash.orbitgps.voice.Maneuver
import com.robotdash.orbitgps.voice.ManeuverType
import com.robotdash.orbitgps.voice.PromptEngine
import com.robotdash.orbitgps.voice.Side
import com.robotdash.orbitgps.voice.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PromptEngineTest {

    private val clipDir = File("src/main/assets/voice")
    private val clips = clipDir.listFiles()!!.map { it.nameWithoutExtension }.toSet()

    @Test fun lanePhrases() {
        assertEquals("use_right_2_lanes", PromptEngine.laneClip(Lanes(listOf(false, false, true, true))))
        assertEquals("use_right_lane", PromptEngine.laneClip(Lanes(listOf(false, false, true))))
        assertEquals("use_left_lane", PromptEngine.laneClip(Lanes(listOf(true, false, false))))
        assertEquals("use_left_2_lanes", PromptEngine.laneClip(Lanes(listOf(true, true, false))))
        assertEquals("use_middle_lane", PromptEngine.laneClip(Lanes(listOf(false, true, false))))
        assertEquals("use_middle_lanes", PromptEngine.laneClip(Lanes(listOf(false, true, true, false))))
        assertNull(PromptEngine.laneClip(Lanes(listOf(true, true, true))))      // all lanes fine
        assertNull(PromptEngine.laneClip(Lanes(listOf(true, false, true))))     // split, skip
        assertNull(PromptEngine.laneClip(null))
    }

    @Test fun distanceRounding() {
        assertEquals("in_800_m", PromptEngine.distanceClip(780.0))
        assertEquals("in_2_km", PromptEngine.distanceClip(2100.0))
        assertEquals("in_300_m", PromptEngine.distanceClip(310.0))
    }

    @Test fun lanePromptOnPrepare() {
        val m = Maneuver(ManeuverType.TURN, Side.RIGHT, lanes = Lanes(listOf(false, true, true)))
        assertEquals(
            listOf("in_300_m", "use_right_2_lanes", "to_turn_right"),
            PromptEngine.build(Stage.PREPARE, m, 300.0, 50.0)
        )
        // Early warning in town: just the turn, no lanes yet.
        assertEquals(listOf("in_500_m", "turn_right"), PromptEngine.build(Stage.EARLY, m, 500.0, 50.0))
    }

    @Test fun thenChaining() {
        val m = Maneuver(ManeuverType.TURN, Side.RIGHT)
        val next = Maneuver(ManeuverType.TURN, Side.LEFT, turnCount = 2)
        assertEquals(
            listOf("now", "turn_right", "then", "take_2_left"),
            PromptEngine.build(Stage.ACTION, m, 30.0, 40.0, next, gapToNext = 100.0)
        )
        // Far apart: no "then".
        assertEquals(listOf("now", "turn_right"),
            PromptEngine.build(Stage.ACTION, m, 30.0, 40.0, next, gapToNext = 900.0))
    }

    @Test fun arrival() {
        val a = Maneuver(ManeuverType.ARRIVE, Side.LEFT)
        assertEquals(listOf("in_200_m", "dest_ahead_left"), PromptEngine.build(Stage.PREPARE, a, 200.0, 40.0))
        assertEquals(listOf("dest_on_left"), PromptEngine.build(Stage.ACTION, a, 10.0, 20.0))
    }

    @Test fun highwayGetsEarlyLaneAdvice() {
        val exit = Maneuver(ManeuverType.EXIT, Side.RIGHT, lanes = Lanes(listOf(false, false, true)))
        assertEquals(
            listOf("in_2_km", "use_right_lane", "to_take_exit"),
            PromptEngine.build(Stage.EARLY, exit, 2000.0, 100.0)
        )
        assertEquals(2000.0, PromptEngine.stageDistances(100.0).early, 0.1)
    }

    @Test fun trackerSpeaksEachStageOnce() {
        val said = mutableListOf<List<String>>()
        val t = GuidanceTracker { said += it }
        val m = Maneuver(ManeuverType.TURN, Side.LEFT)
        // Approach at 50 km/h from 700 m.
        for (d in 700 downTo 0 step 10) t.update(0, m, d.toDouble(), 50.0)
        assertEquals(3, said.size)
        assertEquals("in_500_m", said[0][0])
        assertEquals("in_200_m", said[1][0])
        assertEquals("now", said[2][0])
    }

    @Test fun shortSegmentSkipsEarlyWarning() {
        val said = mutableListOf<List<String>>()
        val t = GuidanceTracker { said += it }
        val m = Maneuver(ManeuverType.TURN, Side.RIGHT)
        for (d in 150 downTo 0 step 10) t.update(1, m, d.toDouble(), 40.0)
        assertEquals(2, said.size) // prepare + now
    }

    /** Every phrase the engine can produce must have a recorded clip. */
    @Test fun allClipsExist() {
        val sides = listOf(Side.LEFT, Side.RIGHT, null)
        val used = mutableSetOf<String>()
        for (type in ManeuverType.entries) for (side in sides) {
            for (count in listOf(null, 1, 2, 3, 4)) for (exitNo in listOf(null, 1, 2, 3, 4, 5))
                for (hw in listOf(false, true)) {
                    val m = Maneuver(type, side, count, exitNo, Lanes(listOf(false, true, true)), hw)
                    for (st in Stage.entries) for (speed in listOf(30.0, 50.0, 80.0, 110.0)) {
                        used += PromptEngine.build(st, m, 500.0, speed, m, 50.0)
                    }
                }
        }
        for (d in listOf(50.0, 120.0, 260.0, 480.0, 700.0, 1200.0, 1700.0, 2600.0, 9000.0))
            used += PromptEngine.distanceClip(d)
        for (m in 900..300_000 step 50) PromptEngine.continueClip(m.toDouble())?.let { used += it }
        val missing = used - clips
        assertTrue("Missing clips: $missing", missing.isEmpty())
    }

    /** Reported on the 1.5 drive: 1.6 and 1.9 km were both called "1 km", 3.9 km "2 km". */
    @Test fun continueUsesNearestDistance() {
        fun say(km: Double) = PromptEngine.continueClip(km * 1000)
        assertEquals("continue_for_1_5_km", say(1.6))
        assertEquals("continue_for_2_km", say(1.9))
        assertEquals("continue_for_4_km", say(3.9))
        assertEquals("continue_for_1_km", say(1.0))
        assertEquals("continue_for_1_km", say(1.2))
        assertEquals("continue_for_2_5_km", say(2.6))
        assertEquals("continue_for_10_km", say(10.9))
        assertEquals("continue_for_25_km", say(23.0))
        assertEquals("continue_for_200_km", say(225.0))
        assertEquals("follow_road", say(240.0))
        assertEquals(null, say(0.8))
        // Never more than 15% off once past the first few kilometres.
        for (m in 3_000..230_000 step 100) {
            val id = PromptEngine.continueClip(m.toDouble())!!
            val km = id.removePrefix("continue_for_").removeSuffix("_km").replace('_', '.').toDouble()
            assertTrue("$m m -> $id", kotlin.math.abs(km - m / 1000.0) / (m / 1000.0) <= 0.15)
        }
    }
}

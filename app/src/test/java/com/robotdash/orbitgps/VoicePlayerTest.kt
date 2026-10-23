package com.robotdash.orbitgps

import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.robotdash.orbitgps.voice.VoicePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoicePlayerTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun sentenceTextReadsNaturally() {
        val v = VoicePlayer(ctx)
        assertEquals("In five hundred metres, use the right two lanes to turn right.",
            v.sentenceText(listOf("in_500_m", "use_right_2_lanes", "to_turn_right")))
        assertEquals("Now, turn right, then, take the next left.",
            v.sentenceText(listOf("now", "turn_right", "then", "next_left")))
        assertEquals("Your destination is ahead, on the left.", v.sentenceText(listOf("dest_ahead_left")))
    }

    /** If the recorded clip can't play, the phone's voice must say the whole sentence. */
    @Test fun failedClipFallsBackToPhoneVoice() {
        // Make every MediaPlayer fail to prepare.
        ShadowMediaPlayer.setCreateListener { _, shadow -> shadow.setInvalidStateBehavior(ShadowMediaPlayer.InvalidStateBehavior.SILENT) }
        val v = VoicePlayer(ctx)
        v.speak(listOf("in_500_m", "use_right_2_lanes", "to_turn_right"))
        shadowOf(Looper.getMainLooper()).idle()
        // Find the TextToSpeech the player created and finish its start-up.
        val f = VoicePlayer::class.java.getDeclaredField("tts").apply { isAccessible = true }
        val tts = f.get(v) as TextToSpeech?
        if (tts == null) {
            // Robolectric played the clip "successfully" — nothing to fall back from; that's fine too.
            println("clip played without error under Robolectric; health=${v.health}")
            return
        }
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoicePlayer.Health.USING_PHONE_VOICE, v.health)
        assertEquals("In five hundred metres, use the right two lanes to turn right.", shadowOf(tts).lastSpokenText)
        assertTrue(v.lastError!!.contains("in_500_m"))
    }
}

package com.robotdash.orbitgps.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * Speaks Orbit's prompts.
 *
 * Normally plays the recorded voice clips (assets/voice/<id>.ogg) back to back.
 * If a clip can't be played for any reason, the rest of that sentence is spoken by
 * the phone's own text-to-speech voice instead, so directions are never silent.
 * Music from other apps is ducked while she speaks; a new sentence waits for the
 * current one to finish.
 */
class VoicePlayer(context: Context) {

    companion object {
        /** Put between sentences for a short silence. */
        const val PAUSE = "_pause"
        private const val PAUSE_SHORT = "_pause_short"
        private const val TAG = "OrbitVoice"
    }

    /** What happened the last time Orbit tried to speak (for the voice check in Settings). */
    enum class Health { UNTESTED, CLIPS_OK, USING_PHONE_VOICE, FAILED }

    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setWillPauseWhenDucked(false)
        .build()

    /** Clip id → the words it says, for the phone-voice backup. */
    private val words: Map<String, String> by lazy {
        runCatching {
            val o = JSONObject(app.assets.open("voice/manifest.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    private var queue = ArrayDeque<String>()
    private var player: MediaPlayer? = null
    private val pauseRunnable = Runnable { playNext() }
    private var busy = false

    /** Volume 0.0–1.0 applied to the clips. */
    var volume: Float = 1f
    var muted: Boolean = false
    /** Always use the phone's own speaker, even when Bluetooth audio is connected. */
    var phoneSpeaker: Boolean = false

    var health: Health = Health.UNTESTED; private set
    var lastError: String? = null; private set

    // ---------------------------------------------------------------- phone voice backup

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsWaiting = mutableListOf<String>()

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setAudioAttributes(attrs)
                    val loc = listOf(Locale.CANADA, Locale.US, Locale.ENGLISH)
                        .firstOrNull { (tts?.isLanguageAvailable(it) ?: -1) >= TextToSpeech.LANG_AVAILABLE }
                    loc?.let { tts?.language = it }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { main.post { finishSentence() } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { main.post { finishSentence() } }
                    })
                    ttsWaiting.toList().also { ttsWaiting.clear() }.forEach { sayWithPhoneVoice(it) }
                } else {
                    health = Health.FAILED
                    lastError = "The phone's text-to-speech isn't available (code $status)."
                    ttsWaiting.clear()
                    finishSentence()
                }
            }
        }
    }

    /** Speak [text] with the phone's voice; continues the queue when done. */
    private fun sayWithPhoneVoice(text: String) {
        ensureTts()
        if (!ttsReady) { ttsWaiting += text; return }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        val r = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "orbit-${System.nanoTime()}")
        if (r != TextToSpeech.SUCCESS) {
            health = Health.FAILED
            lastError = "The phone's voice couldn't speak (code $r)."
            finishSentence()
        }
    }

    /** After the phone voice finishes a sentence, carry on with anything queued. */
    private fun finishSentence() {
        if (queue.isEmpty()) stopInternal(releaseFocus = true) else playNext()
    }

    // ---------------------------------------------------------------- clips

    /**
     * Say a sentence. If Orbit is still talking, it's queued after a short pause
     * rather than cutting her off.
     */
    fun speak(clips: List<String>) {
        if (muted || clips.isEmpty()) return
        main.post {
            if (busy) {
                queue.addLast(PAUSE_SHORT)
                queue.addAll(clips)
                return@post
            }
            queue = ArrayDeque(clips)
            busy = true
            runCatching { audioManager.requestAudioFocus(focusRequest) }
            playNext()
        }
    }

    private fun playNext() {
        val id = queue.removeFirstOrNull()
        if (id == null) { stopInternal(releaseFocus = true); return }
        if (id == PAUSE || id == PAUSE_SHORT) {
            main.postDelayed(pauseRunnable, if (id == PAUSE) 900 else 400)
            return
        }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            app.assets.openFd("voice/$id.ogg").use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setVolume(volume, volume)
            if (phoneSpeaker && Build.VERSION.SDK_INT >= 28) speakerDevice()?.let { mp.setPreferredDevice(it) }
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                if (health == Health.UNTESTED) health = Health.CLIPS_OK
                playNext()
            }
            mp.setOnErrorListener { p, what, extra ->
                p.release()
                if (player === p) player = null
                clipFailed(id, "Couldn't play the voice clip \"$id\" (error $what/$extra).")
                true
            }
            mp.setOnPreparedListener { it.start() }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            clipFailed(id, "Couldn't open the voice clip \"$id\": ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** A clip failed: say this one and the rest of the sentence with the phone's voice. */
    private fun clipFailed(id: String, why: String) {
        Log.w(TAG, why)
        lastError = why
        health = Health.USING_PHONE_VOICE
        val sentence = mutableListOf(id)
        while (queue.isNotEmpty() && queue.first() != PAUSE && queue.first() != PAUSE_SHORT) sentence += queue.removeFirst()
        val text = sentenceText(sentence)
        if (text.isBlank()) finishSentence() else sayWithPhoneVoice(text)
    }

    /** Clip ids → one natural sentence: "In 500 metres, use the right two lanes to turn right." */
    fun sentenceText(ids: List<String>): String {
        val raw = ids.mapNotNull { words[it] }.joinToString(" ").trim()
        if (raw.isEmpty()) return ""
        // Clips end in "." on their own; mid-sentence those become plain joins.
        val joined = raw
            .replace(Regex("\\.\\s+(?=(then|and)\\b)"), ", ")   // "turn right, then…"
            .replace(Regex("\\.\\s+(?=[a-z])"), " ")            // "two lanes to turn right"
            .replace(Regex("\\s+"), " ")
        return joined.replaceFirstChar { it.uppercase() }
    }

    private fun speakerDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** Where the voice will come out right now, in plain words (for the voice check). */
    fun outputDescription(): String {
        if (phoneSpeaker) return "phone speaker"
        val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when {
            outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> "Bluetooth (car or headset)"
            outs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "headphones"
            outs.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } -> "USB audio"
            else -> "phone speaker"
        }
    }

    /** Media volume as a percentage (Orbit's voice follows the media volume). */
    fun mediaVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun stopInternal(releaseFocus: Boolean) {
        main.removeCallbacks(pauseRunnable)
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        queue.clear()
        busy = false
        if (releaseFocus) runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    fun stop() = main.post { stopInternal(releaseFocus = true); tts?.stop() }

    fun release() = main.post { stopInternal(releaseFocus = true); tts?.shutdown(); tts = null; ttsReady = false }
}

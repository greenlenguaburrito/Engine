package com.trucknav.pro.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Speaks turn-by-turn announcements aloud using the on-device TTS engine. */
class VoiceGuidanceEngine(context: Context) {

    private var ready = false
    private val pendingUtterances = ArrayDeque<String>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts.language = Locale.US
                flushPending()
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Deprecated in API")
            override fun onError(utteranceId: String?) = Unit
        })
    }

    /** Announcements queue (not interrupt) so back-to-back maneuvers aren't cut off mid-sentence. */
    fun speak(text: String) {
        if (!ready) {
            pendingUtterances.addLast(text)
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    private fun flushPending() {
        while (pendingUtterances.isNotEmpty()) {
            speak(pendingUtterances.removeFirst())
        }
    }

    fun stopSpeaking() {
        tts.stop()
        pendingUtterances.clear()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

package com.sms.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.io.File

/**
 * Debug builds only: says a sentence into files/speak.wav with Android's
 * own voice, for testing the transcription of voice messages.
 * adb shell am broadcast -n com.sms.app.debug/com.sms.app.DebugSpeakReceiver --es text "Hello there"
 */
class DebugSpeakReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val done = goAsync()
        val app = context.applicationContext
        var tts: TextToSpeech? = null
        tts = TextToSpeech(app) { status ->
            val engine = tts ?: return@TextToSpeech done.finish()
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech done.finish()
            engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { engine.shutdown(); done.finish() }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { engine.shutdown(); done.finish() }
            })
            engine.synthesizeToFile(text, null, File(app.filesDir, "speak.wav"), "speak")
        }
    }
}

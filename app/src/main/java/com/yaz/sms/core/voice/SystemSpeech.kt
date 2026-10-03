package com.yaz.sms.core.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The phone's own on-device speech recognizer, when it has one with the
 * phone's language already installed (on stock Pixels, Google's; on
 * GrapheneOS, theirs once it ships): nothing to fetch, the phone's
 * language only. Never a recognizer that sends the sound to a server.
 */
internal class SystemSpeech(private val context: Context) {

    /** Whether the phone hears its own language on the device, nothing to fetch. Asked once. */
    suspend fun ready(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)) return false
        return withTimeoutOrNull(5_000) { withContext(Dispatchers.Main) { installed() } } ?: false
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun installed(): Boolean = suspendCancellableCoroutine { done ->
        val asker = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        if (asker == null) {
            done.resume(false)
            return@suspendCancellableCoroutine
        }
        val language = Locale.getDefault().toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        fun finish(ok: Boolean) {
            runCatching { asker.destroy() }
            if (done.isActive) done.resume(ok)
        }
        done.invokeOnCancellation { runCatching { asker.destroy() } }
        runCatching {
            asker.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) =
                    finish(support.installedOnDeviceLanguages.any { it.equals(language, true) || it.substringBefore('-').equals(language.substringBefore('-'), true) })
                override fun onError(error: Int) = finish(false)
            })
        }.onFailure { finish(false) }
    }

    /** The words in [pcm] (16 kHz mono, -1 to 1), or null. */
    suspend fun hear(pcm: FloatArray): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val pipe = ParcelFileDescriptor.createPipe()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            // The whole message, through its pauses, to its end.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        // The sound goes into the pipe as the recognizer reads it.
        val feeder = Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                runCatching {
                    val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    pcm.forEach { bytes.putShort((it * 32767f).toInt().coerceIn(-32768, 32767).toShort()) }
                    out.write(bytes.array())
                }
            }
        }.apply { start() }
        return try {
            withContext(Dispatchers.Main) { listen(intent) }
        } finally {
            runCatching { pipe[0].close() }
            withContext(Dispatchers.IO) { feeder.join(2000) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun listen(intent: Intent): String? = suspendCancellableCoroutine { done ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val words = StringBuilder()
        fun finish(text: String?) {
            runCatching { recognizer.destroy() }
            if (done.isActive) done.resume(text)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segment: Bundle) {
                segment.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    if (words.isNotEmpty()) words.append(' ')
                    words.append(it)
                }
            }
            override fun onEndOfSegmentedSession() = finish(words.toString())
            override fun onResults(results: Bundle) {
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (words.isEmpty()) words.append(it.trim()) }
                finish(words.toString())
            }
            override fun onError(error: Int) = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(words.toString())
                else -> finish(words.toString().takeIf { it.isNotEmpty() })
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        done.invokeOnCancellation { runCatching { recognizer.cancel(); recognizer.destroy() } }
        recognizer.startListening(intent)
    }
}

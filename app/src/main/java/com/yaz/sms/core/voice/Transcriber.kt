package com.yaz.sms.core.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.yaz.sms.core.common.writeTextAtomically
import com.yaz.sms.core.dial.Sisters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/**
 * Voice messages written out by the Voice app, the phone's speech recognition: SMS keeps no engine and no model.
 * The message is decoded to 16 kHz mono 16-bit PCM (AudioDecode), written into a pipe and handed to Voice's
 * recognition service through SpeechRecognizer with RecognizerIntent.EXTRA_AUDIO_SOURCE (Android 13), the session
 * lasting as long as the sound (EXTRA_SEGMENTED_SESSION = EXTRA_AUDIO_SOURCE, RecognizerIntent's reference
 * https://developer.android.com/reference/android/speech/RecognizerIntent). The recognizer is made for one named
 * service and driven from the main looper as AOSP's CTS client does (SpeechRecognitionActivity: createSpeechRecognizer
 * with a ComponentName, every call posted on the main looper)
 * https://android.googlesource.com/platform/cts/+/refs/heads/main/tests/tests/voiceRecognition/src/android/voicerecognition/cts/SpeechRecognitionActivity.java
 * and as Voice's own debug client does (RecognizerTestActivity: the pipe, the intent, the listener).
 * The words are kept in the app's own files, so each message is heard once.
 */
class Transcriber(private val context: Context, scope: CoroutineScope) {

    sealed interface State {
        data object Working : State
        data class Done(val text: String) : State
        data object Failed : State
    }

    private val file = File(context.filesDir, "transcripts.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _texts = MutableStateFlow(load())
    /** The words of each voice message heard, by its address. */
    val texts: StateFlow<Map<String, String>> = _texts.asStateFlow()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    // One message at a time: Voice listens to one caller at once.
    private val one = Mutex()

    init {
        // The speech model SMS once fetched into its files (files/speech): Voice keeps the models now.
        scope.launch(Dispatchers.IO) {
            val old = File(context.filesDir, "speech")
            if (old.exists()) runCatching { old.deleteRecursively() }
        }
    }

    @Volatile private var seen = 0L
    @Volatile private var seenAvailable = false

    /**
     * Whether Voice, ours and signed with our key, is on the phone, on Android 13 or later (a sound handed in
     * through EXTRA_AUDIO_SOURCE is read only from there; before, Voice would listen to the microphone). Read
     * again every few seconds, not at each drawing.
     */
    val available: Boolean
        get() {
            val now = System.currentTimeMillis()
            if (now - seen > 5_000) {
                seenAvailable = Build.VERSION.SDK_INT >= 33 && voicePackage() != null
                seen = now
            }
            return seenAvailable
        }

    /**
     * The package of the Voice app, or null: it must be one of ours (the debug package in a debug build only,
     * Sisters.packages) and signed as this app, compared as the phone kit's Sisters.isOurs does
     * (PackageManager.checkSignatures,
     * https://developer.android.com/reference/android/content/pm/PackageManager#checkSignatures(java.lang.String,%20java.lang.String)).
     * Sisters.isOurs lists Dialer, SMS and Contacts only, so the comparison is made here.
     */
    private fun voicePackage(): String? = Sisters.packages("voice").firstOrNull { pkg ->
        runCatching { context.packageManager.checkSignatures(context.packageName, pkg) == PackageManager.SIGNATURE_MATCH }
            .getOrDefault(false)
    }

    /** Hears [uri] once, when Voice is there and it was not heard yet. */
    suspend fun transcribe(uri: Uri) {
        if (!available) return
        val key = uri.toString()
        if (key in _texts.value || _states.value[key] == State.Working) return
        set(key, State.Working)
        val result = try {
            one.withLock { if (Build.VERSION.SDK_INT >= 33) listen(uri) else State.Failed }
        } catch (gone: kotlinx.coroutines.CancellationException) {
            // Left before it was heard: heard the next time it is shown.
            _states.value = _states.value - key
            throw gone
        }
        if (result is State.Done) {
            _texts.value = _texts.value + (key to result.text)
            withContext(Dispatchers.IO) { runCatching { file.writeTextAtomically(json.encodeToString(_texts.value)) } }
        }
        set(key, result)
    }

    /** A message deleted: its words go too. */
    fun forget(uris: Collection<String>) {
        if (uris.none { it in _texts.value }) return
        _texts.value = _texts.value - uris.toSet()
        runCatching { file.writeTextAtomically(json.encodeToString(_texts.value)) }
    }

    private fun set(key: String, state: State) {
        _states.value = _states.value + (key to state)
    }

    private fun load(): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }.getOrDefault(emptyMap())

    /**
     * The words of [uri]: the sound decoded and written into a pipe off the main thread, Voice listening on the
     * main thread (SpeechRecognizer must be created and called there,
     * https://developer.android.com/reference/android/speech/SpeechRecognizer).
     */
    @RequiresApi(33)
    private suspend fun listen(uri: Uri): State {
        val pkg = voicePackage() ?: return State.Failed
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return State.Failed
        val (read, write) = pipe
        return try {
            coroutineScope {
                // RecognizerTestActivity.pipe: the samples written into a pipe whose read end goes to the service.
                val fed = async(Dispatchers.IO) {
                    runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { AudioDecode.stream(context, uri, it) } }
                }
                val heard = withContext(Dispatchers.Main) { withTimeoutOrNull(TIMEOUT_MS) { recognize(pkg, read) } }
                // The read end closed, a writer still blocked on the pipe ends on the broken pipe.
                runCatching { read.close() }
                val decoded = fed.await()
                when {
                    heard == null -> State.Failed
                    decoded.isFailure && heard !is State.Failed -> State.Failed
                    else -> heard
                }
            }
        } finally {
            runCatching { read.close() }
            runCatching { write.close() }
        }
    }

    /** SpeechRecognizer on Voice's service, called on the main thread; the answer when the session ends. */
    @RequiresApi(33)
    private suspend fun recognize(pkg: String, audio: ParcelFileDescriptor): State {
        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(context, ComponentName(pkg, SERVICE))
        }.getOrNull() ?: return State.Failed
        val answer = CompletableDeferred<State>()
        val words = StringBuilder()
        // RecognizerTestActivity's listener, and AOSP's CTS callbacks for a segmented session
        // (onSegmentResults, onEndOfSegmentedSession).
        recognizer.setRecognitionListener(object : RecognitionListener {
            private fun text(bundle: Bundle?) = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onSegmentResults(segmentResults: Bundle) {
                val next = text(segmentResults)
                if (next.isNotEmpty()) {
                    if (words.isNotEmpty()) words.append(' ')
                    words.append(next)
                }
            }
            override fun onEndOfSegmentedSession() {
                answer.complete(State.Done(sentence(words.toString())))
            }
            override fun onResults(results: Bundle?) {
                answer.complete(State.Done(sentence(text(results).ifEmpty { words.toString() })))
            }
            override fun onError(error: Int) {
                // Nothing said in the message is no failure; any other error is tried again next time.
                val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                answer.complete(if (silent) State.Done("") else State.Failed)
            }
        })
        val asked = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioDecode.RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            // A session that lasts as long as the sound handed in
            // https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_SEGMENTED_SESSION
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            // The language is found from the sound, not taken from the phone's
            if (Build.VERSION.SDK_INT >= 34) putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
        }
        return try {
            recognizer.startListening(asked)
            answer.await()
        } finally {
            runCatching { recognizer.cancel() }
            recognizer.destroy()
        }
    }

    private companion object {
        /** Voice's recognition service (its manifest), the same in its debug build. */
        const val SERVICE = "com.yaz.voice.recognition.VoiceRecognitionService"

        /** A voice message of ten minutes at most (AudioDecode) is written out in well under this. */
        const val TIMEOUT_MS = 20 * 60_000L

        /** The words, the first letter capital. */
        fun sentence(text: String) = text.trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}

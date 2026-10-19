package com.yaz.sms.core.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A source of sound for a dictation: 16 kHz, mono, 16-bit. The microphone, or a file when the engine is tried. */
interface PcmSource {
    /** Opens the source; throws when it cannot be (no right to the microphone). */
    fun start()
    /**
     * Fills [buffer] with the next sound and gives how many samples: -1 when the source is over (the end of a file:
     * the words still held are written out), another negative number when it failed.
     */
    fun read(buffer: ShortArray): Int
    fun release()
    /**
     * True for a source that cannot wait (the microphone: sound not read in a second is lost); the phrases held for
     * whisper are then not bounded. A file or a stream waits, so reading stops while whisper is behind.
     */
    val realTime: Boolean get() = false
}

/**
 * The microphone, read the way Transcribro reads it: AudioRecord built with an AudioFormat of 16 kHz, mono, 16 bits
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/recognitionservice/MainRecognitionService.kt
 * (onStartListening, AudioRecord.Builder().setAudioFormat(...).build(), startRecording, read, stop, release).
 * A second of room in its buffer, so the voice detector's work never loses sound. [attribution]: a context whose
 * attribution source names another app (a recognition service listening for it), given to the recorder with
 * setContext as Transcribro does, so Android's microphone light names that app; null for the app itself.
 */
class MicSource(private val attribution: Context? = null) : PcmSource {
    private var record: AudioRecord? = null

    override val realTime: Boolean get() = true

    override fun start() {
        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setSampleRate(Dictation.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minimum = AudioRecord.getMinBufferSize(Dictation.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val made = AudioRecord.Builder()
            .apply { attribution?.let { setContext(it) } }
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, Dictation.SAMPLE_RATE * 2))
            .build()
        if (made.state != AudioRecord.STATE_INITIALIZED) {
            made.release()
            throw IllegalStateException("the microphone could not be opened")
        }
        record = made
        made.startRecording()
    }

    // AudioRecord's errors are negative (ERROR is -1): any of them is a failure here, never the end of a source
    override fun read(buffer: ShortArray): Int = (record?.read(buffer, 0, buffer.size) ?: -2).let { if (it < 0) -2 else it }

    override fun release() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}

/** The voice detector's model, in the app's assets, copied out to be read by path and checked against its SHA-256. */
internal object SileroModel {
    /** Silero VAD v6.2.0 in ggml form (MIT), as models/download-vad-model.sh of whisper.cpp 1.9.4 fetches it from
     *  https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin */
    const val ASSET = "voice/ggml-silero-v6.2.0.bin"
    const val SHA256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"

    /** The model as a file whose sum is the pinned one, or null. */
    fun file(context: Context): File? = runCatching {
        val file = File(context.cacheDir, ASSET)
        if (!file.exists() || sum(file) != SHA256) {
            file.parentFile?.mkdirs()
            context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        file.takeIf { sum(it) == SHA256 }
    }.getOrNull()

    private fun sum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Dictation: the words said into the microphone come as they are recognised. Two engines, the app picks:
 * SYSTEM, the phone's own on-device recognizer, live (partial words, then the finished segment); WHISPER, whisper.cpp
 * with Silero's voice detector, phrase by phrase: the sound is cut into phrases when the speaker pauses a second
 * (VadSegmenter), each phrase is written out and handed over. Both end by themselves after three seconds of silence.
 * Microphone, recognizer, detector and model are open only while a dictation runs and all released when it ends.
 * Nothing is logged: neither the words nor the sound.
 *
 * Sources: the microphone loop and the detector-driven phrases are Transcribro's recognition service (record with
 * AudioRecord, detect speech, transcribe each speech segment) and whisper.cpp's examples/stream/stream.cpp in its
 * voice-detection mode, see VadSegmenter; the live system recognizer is SystemSpeech.Live.
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/recognitionservice/MainRecognitionService.kt
 * https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/stream/stream.cpp
 *
 * Everything is called on the main thread and answers on it.
 */
class Dictation(private val context: Context) {

    enum class Engine { SYSTEM, WHISPER }

    enum class Why {
        /** The user ended it. */
        USER,
        /** Three seconds of silence after speech. */
        SILENCE,
        /** Nothing was said. */
        NO_SPEECH,
        /** The app has no right to the microphone yet. */
        NO_MICROPHONE_RIGHT,
        /** The engine cannot run here (no recognizer, language not installed, no engine in this build). */
        UNAVAILABLE,
        FAILED,
    }

    interface Listener {
        /** The words of the phrase being said, as far as known (SYSTEM only); replaces the previous ones. */
        fun partial(text: String)
        /** The words of a finished phrase. */
        fun committed(text: String)
        /** The dictation is over, everything released. */
        fun stopped(why: Why)
        /**
         * The loudness now, in dB, as Android's RecognitionListener.onRmsChanged gives it; for whisper, Kõnele's
         * getRmsdb (10 log10 of the root mean square of the 16-bit samples just read)
         * https://github.com/Kaljurand/speechutils/blob/master/app/src/main/java/ee/ioc/phon/android/speechutils/AbstractAudioRecorder.java
         */
        fun level(rmsDb: Float) = Unit
        /** The language heard (whisper's code, "fr"), after the first phrase written out: whisper_full_lang_id. */
        fun language(code: String) = Unit
        /**
         * A finished phrase with its words and their times, in milliseconds from the start of the dictation (the
         * phrase's place in the sound plus the word's place in the phrase), as Android's RecognitionPart counts
         * (getTimestampMillis: from the start of the recognition session)
         * https://developer.android.com/reference/android/speech/RecognitionPart
         * WHISPER only, when [Dictation.start] was asked for timings. By default it hands the text to [committed], so
         * a listener that ignores times is unchanged; one that wants them overrides this and not [committed].
         */
        fun committedWords(text: String, words: List<Whisper.Word>) = committed(text)
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    /** A dictation is running or still finishing its last words. */
    val busy: Boolean get() = session != null

    /**
     * Starts listening. SYSTEM asks the phone's recognizer for [language]; WHISPER needs [model] (the file of
     * SpeechModel) and writes [language] when whisper knows it, else finds it from the sound. [source] is the sound,
     * the microphone when null. [silenceMs]: silence after speech that ends it (an app asking for another length:
     * RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS). [prompt]: words the speech likely holds,
     * given to whisper (Whisper.transcribe). [timings]: WHISPER writes each phrase out word by word and
     * [Listener.committedWords] gets the times. [stopOnSilence] false: no silence and no nothing-said ends it ([silenceMs]
     * ignored), only the source's end or [stop] (a session whose audio is a stream). False when one is already running;
     * otherwise the answer comes to [listener].
     */
    fun start(
        engine: Engine,
        language: Locale,
        model: File?,
        listener: Listener,
        source: PcmSource? = null,
        silenceMs: Long = SILENCE_MS,
        prompt: String? = null,
        timings: Boolean = false,
        stopOnSilence: Boolean = true,
    ): Boolean {
        if (session != null) return false
        if (source == null && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            main.post { listener.stopped(Why.NO_MICROPHONE_RIGHT) }
            return true
        }
        val made: Session = when (engine) {
            Engine.SYSTEM -> SystemSession(language, listener, silenceMs)
            Engine.WHISPER -> WhisperSession(language, model, listener, source ?: MicSource(), silenceMs, prompt, timings, stopOnSilence)
        }
        session = made
        if (!made.begin()) {
            session = null
            main.post { listener.stopped(Why.UNAVAILABLE) }
        }
        return true
    }

    /**
     * Ends the dictation. With [flush] the words still held are written out first (the user ended it); without, they
     * are dropped and nothing more is reported (the keyboard left, another field).
     */
    fun stop(flush: Boolean) {
        session?.stop(flush)
    }

    /**
     * A phrase to write out and where it begins in the sound (ms from the start of the dictation), the place VadSegmenter
     * gives; the start time of a segment as whisper.h counts it
     * https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/include/whisper.h
     */
    private class Queued(val pcm: FloatArray, val startMs: Long)

    private interface Session {
        fun begin(): Boolean
        fun stop(flush: Boolean)
    }

    private fun finished(made: Session) {
        if (session === made) session = null
    }

    private inner class SystemSession(language: Locale, private val listener: Listener, silenceMs: Long) :
        Session, SystemSpeech.LiveCallback {
        private val live = SystemSpeech.Live(context, language, silenceMs, this)
        private var userStopped = false
        private var heard = false
        private var cancelled = false

        override fun begin() = live.start()

        override fun stop(flush: Boolean) {
            userStopped = true
            if (flush) live.stop() else {
                cancelled = true
                live.cancel()
                finished(this)
            }
        }

        override fun partial(text: String) {
            heard = true
            if (!cancelled) listener.partial(text)
        }

        override fun segment(text: String) {
            heard = true
            if (!cancelled) listener.committed(text)
        }

        // The recognizer's onRmsChanged passed on
        // https://developer.android.com/reference/android/speech/RecognitionListener#onRmsChanged(float)
        override fun level(rmsDb: Float) {
            if (!cancelled) listener.level(rmsDb)
        }

        override fun ended(error: Int) {
            finished(this)
            if (cancelled) return
            val why = when {
                error == 0 && userStopped -> Why.USER
                error == 0 && heard -> Why.SILENCE
                error == 0 -> Why.NO_SPEECH
                error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Why.NO_MICROPHONE_RIGHT
                error == android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> Why.UNAVAILABLE
                else -> Why.FAILED
            }
            listener.stopped(why)
        }
    }

    private inner class WhisperSession(
        private val language: Locale,
        private val model: File?,
        private val listener: Listener,
        private val source: PcmSource,
        private val silenceMs: Long,
        private val prompt: String?,
        private val timings: Boolean,
        private val stopOnSilence: Boolean,
    ) : Session {
        @Volatile private var stopRequested = false
        @Volatile private var flush = false
        @Volatile private var cancelled = false
        @Volatile private var why = Why.USER
        @Volatile private var spoke = false
        @Volatile private var writerGone = false

        // A bounded queue: reading a file waits for whisper when it is behind; the microphone cannot wait, so its
        // phrases are only held. https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/LinkedBlockingQueue.html
        private val phrases = LinkedBlockingQueue<Any>(if (source.realTime) Int.MAX_VALUE else MAX_QUEUED)
        private val end = Any()

        override fun begin(): Boolean {
            // A source handed in (an opened file) is released when the dictation cannot start, as when it ends
            if (!Whisper.loaded || model == null || !model.exists()) { source.release(); return false }
            val vadFile = SileroModel.file(context) ?: run { source.release(); return false }
            Whisper.abort(false)
            try {
                source.start()
            } catch (denied: SecurityException) {
                main.post { finished(this); listener.stopped(Why.NO_MICROPHONE_RIGHT) }
                source.release()
                return true
            } catch (failed: RuntimeException) {
                source.release()
                return false
            }
            Thread({ listen(vadFile) }, "dictation-listen").start()
            Thread({ write(model) }, "dictation-write").start()
            return true
        }

        override fun stop(flush: Boolean) {
            this.flush = flush
            if (!flush) {
                cancelled = true
                Whisper.abort(true)
            }
            why = Why.USER
            stopRequested = true
        }

        /** The sound is read in chunks of four windows (128 ms), each given to the detector, whose answers cut the phrases. */
        private fun listen(vadFile: File) {
            val libDir = context.applicationInfo.nativeLibraryDir
            val vad = Whisper.vadOpen(libDir, vadFile.path)
            val segmenter = VadSegmenter(VadSegmenter.Params(stopSilenceMs = silenceMs.toInt(), neverStops = !stopOnSilence))
            val shorts = ShortArray(SAMPLE_RATE / 10)
            val chunk = FloatArray(CHUNK)
            var held = 0
            try {
                if (vad == 0L) fail(Why.FAILED)
                while (!stopRequested) {
                    val n = source.read(shorts)
                    if (n == -1) {
                        // The source is over: what is held is written out, as when the user ends it
                        if (!stopRequested) {
                            flush = true
                            why = if (spoke) Why.SILENCE else Why.NO_SPEECH
                            stopRequested = true
                        }
                        break
                    }
                    if (n < 0) { fail(Why.FAILED); break }
                    // Kõnele's getRmsdb over the samples just read (see Listener.level)
                    var squares = 0L
                    for (k in 0 until n) squares += shorts[k].toLong() * shorts[k]
                    val rms = if (n > 0) kotlin.math.sqrt(squares.toDouble() / n) else 0.0
                    val db = if (rms > 1) (10 * kotlin.math.log10(rms)).toFloat() else 0f
                    if (!cancelled) main.post { if (!cancelled) listener.level(db) }
                    var i = 0
                    while (i < n && !stopRequested) {
                        val take = minOf(n - i, CHUNK - held)
                        for (k in 0 until take) chunk[held + k] = shorts[i + k] / 32768f
                        held += take
                        i += take
                        if (held == CHUNK) {
                            held = 0
                            val probs = Whisper.vadProbs(vad, chunk)
                            if (probs == null) { fail(Why.FAILED); break }
                            handle(segmenter.feed(chunk, probs), vad)
                        }
                    }
                }
                if (flush && !cancelled) handle(segmenter.flush(), vad)
            } finally {
                source.release()
                if (vad != 0L) Whisper.vadClose(vad)
                finishQueue()
            }
        }

        // LinkedBlockingQueue.offer(e, timeout, unit) in a loop: waits for room while whisper is behind, and gives up
        // at once when the dictation is cancelled or the writer is gone, so a cancel never waits on a full queue
        // https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/LinkedBlockingQueue.html
        private fun enqueue(phrase: Queued) {
            while (!cancelled && !writerGone) {
                if (phrases.offer(phrase, 100, TimeUnit.MILLISECONDS)) return
            }
        }

        // The end marker always gets through: what waits is dropped when nobody will write it out any more
        // https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/LinkedBlockingQueue.html
        private fun finishQueue() {
            while (!phrases.offer(end, 100, TimeUnit.MILLISECONDS)) {
                if (cancelled || writerGone) phrases.clear()
            }
        }

        private fun handle(events: List<VadSegmenter.Event>, vad: Long) {
            for (event in events) when (event) {
                is VadSegmenter.Event.Phrase -> {
                    enqueue(Queued(event.pcm, event.startSample * 1000L / SAMPLE_RATE))
                    if (event.endsUtterance) Whisper.vadReset(vad)
                }
                is VadSegmenter.Event.Stop -> {
                    why = if (event.reason == VadSegmenter.Reason.SILENCE) Why.SILENCE else Why.NO_SPEECH
                    stopRequested = true
                }
                VadSegmenter.Event.Speech -> spoke = true
            }
        }

        private fun fail(reason: Why) {
            why = reason
            cancelled = true
            stopRequested = true
        }

        /** The model is opened at the start, in parallel with the listening, and closed with the last phrase. */
        private fun write(modelFile: File) {
            val libDir = context.applicationInfo.nativeLibraryDir
            var handle = 0L
            try {
                handle = Whisper.open(libDir, modelFile.path)
                if (handle == 0L) { fail(Why.FAILED); return }
                val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
                val code = languageCode(language)
                var told = false
                while (true) {
                    val next = phrases.take()
                    if (next === end) break
                    if (cancelled) continue
                    val phrase = next as Queued
                    val bytes = (if (timings) Whisper.transcribeWords(handle, phrase.pcm, threads, code, true, prompt)
                        else Whisper.transcribe(handle, phrase.pcm, threads, code, true, prompt)) ?: continue
                    if (!told) {
                        told = true
                        Whisper.heardLanguage(handle)?.let { heard -> main.post { if (!cancelled) listener.language(heard) } }
                    }
                    if (cancelled) continue
                    if (timings) {
                        // Word times are made absolute: the phrase's place in the sound plus the word's in the phrase
                        val words = Whisper.parseWords(bytes).map {
                            Whisper.Word(it.text, phrase.startMs + it.startMs, phrase.startMs + it.endMs)
                        }
                        val text = words.joinToString(" ") { it.text }
                        if (text.isNotEmpty()) main.post { if (!cancelled) listener.committedWords(text, words) }
                    } else {
                        val text = String(bytes, StandardCharsets.UTF_8).trim()
                        if (text.isNotEmpty()) main.post { if (!cancelled) listener.committed(text) }
                    }
                }
            } finally {
                writerGone = true
                if (handle != 0L) Whisper.close(handle)
                val reported = if (cancelled && why == Why.USER) null else why
                main.post {
                    finished(this)
                    if (reported != null) listener.stopped(reported)
                }
            }
        }
    }

    /** whisper's code for [language] when it knows it, else "auto". */
    private fun languageCode(language: Locale): String {
        // Java still gives the old codes of Hebrew, Indonesian and Yiddish
        val code = when (val c = language.language) { "iw" -> "he"; "in" -> "id"; "ji" -> "yi"; else -> c }
        return if (code.isNotEmpty() && Whisper.knows(code)) code else "auto"
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** Silence after speech that ends a dictation (Transcribro's MIN_SILENCE_DURATION_MS). */
        const val SILENCE_MS = 3_000L
        /** Four windows of Silero's 512 samples: one call of the detector. */
        private const val CHUNK = 4 * 512
        /** Phrases waiting for whisper when the sound is a file or a stream: reading waits beyond this. */
        private const val MAX_QUEUED = 3
    }
}

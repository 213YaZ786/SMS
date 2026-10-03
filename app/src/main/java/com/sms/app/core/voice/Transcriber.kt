package com.sms.app.core.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.sms.app.core.common.writeTextAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteOrder
import java.util.Locale

/**
 * Voice messages written out on the phone itself, with whisper.cpp and the
 * speech model the user fetched once: no Google, no server, any language.
 * The words are kept in the app's own files, so each message is heard once.
 */
class Transcriber(private val context: Context, private val scope: CoroutineScope, settings: com.sms.app.data.settings.SettingsStore) {

    sealed interface State {
        data object Working : State
        data class Done(val text: String) : State
        data object Failed : State
    }

    val model = SpeechModel(context, scope, settings)

    private val file = File(context.filesDir, "transcripts.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _texts = MutableStateFlow(load())
    /** The words of each voice message heard, by its address. */
    val texts: StateFlow<Map<String, String>> = _texts.asStateFlow()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    // One message at a time; the model stays open while messages come, then lets its memory go.
    private val one = Mutex()
    private var handle = 0L
    private var closer: Job? = null

    private val system = SystemSpeech(context)

    /** Whether this phone can write voice messages out: the engine in the app (64-bit ARM), or its own recognizer. */
    val available: Boolean get() = (Whisper.loaded && Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) || model.system.value

    /** Hears [uri] once, when the model is there and it was not heard yet. */
    suspend fun transcribe(uri: Uri) {
        if (!available || model.state.value != SpeechModel.State.Ready) return
        val key = uri.toString()
        if (key in _texts.value || _states.value[key] == State.Working) return
        set(key, State.Working)
        val result = try {
            one.withLock {
                if (model.usesSystem) {
                    val pcm = withContext(Dispatchers.Default) { runCatching { decode(uri) }.getOrNull() }
                    val words = pcm?.let { if (it.isEmpty()) "" else system.hear(it) }
                    words?.let { State.Done(sentence(it)) } ?: State.Failed
                } else withContext(Dispatchers.Default) { hear(uri) }
            }
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

    private fun hear(uri: Uri): State {
        val pcm = runCatching { decode(uri) }.getOrNull() ?: return State.Failed
        if (pcm.isEmpty()) return State.Done("")
        closer?.cancel()
        if (handle == 0L) handle = Whisper.open(context.applicationInfo.nativeLibraryDir, model.file.path)
        if (handle == 0L) return State.Failed
        // Half the cores, at least two: quick enough, and the phone stays smooth.
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val bytes = Whisper.transcribe(handle, pcm, threads)
        closer = scope.launch {
            delay(30_000)
            one.withLock { if (handle != 0L) { Whisper.close(handle); handle = 0L } }
        }
        return bytes?.let { State.Done(sentence(String(it, Charsets.UTF_8))) } ?: State.Failed
    }

    /** The message's sound as 16 kHz mono samples between -1 and 1. */
    private fun decode(uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        val out = FloatCollector()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return FloatArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val resampler = Resampler()
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            val buffer = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2).also { buffer.asShortBuffer().get(it) }
                            codec.releaseOutputBuffer(o, false)
                            resampler.push(shorts, channels, rate, out)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            // Ten minutes at most: a voice message, not a recording of a day.
                            if (out.size > RATE * 600) break
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
        return out.toArray()
    }

    private class FloatCollector {
        private var data = FloatArray(RATE * 30)
        var size = 0
            private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    /** Mixes down to mono and brings any rate to 16 kHz, across buffers. */
    private class Resampler {
        private var position = 0.0
        private var last = 0f
        fun push(samples: ShortArray, channels: Int, rate: Int, out: FloatCollector) {
            val ch = channels.coerceAtLeast(1)
            val frames = samples.size / ch
            if (frames == 0) return
            val mono = FloatArray(frames) { f -> var sum = 0f; for (c in 0 until ch) sum += samples[f * ch + c]; sum / ch / 32768f }
            val step = rate.toDouble() / RATE
            // Position runs in input frames; -1 is the last frame of the buffer before.
            while (position < frames - 1) {
                val i = kotlin.math.floor(position).toInt()
                val t = (position - i).toFloat()
                val a = if (i < 0) last else mono[i]
                val b = mono[i + 1]
                out.add(a + (b - a) * t)
                position += step
            }
            position -= frames
            last = mono[frames - 1]
        }
    }

    private companion object {
        const val RATE = 16_000

        /** Whisper's words, without the space it starts with. */
        fun sentence(text: String) = text.trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}

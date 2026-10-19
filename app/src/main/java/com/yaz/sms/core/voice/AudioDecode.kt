package com.yaz.sms.core.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * A recorded sound (a voice message, a shared audio file) as 16 kHz mono samples between -1 and 1, read by Android's
 * own extractor and decoders (they run in the system's sandboxed media processes, never a decoder of the app's).
 * Moved from SMS's core/voice/Transcriber.kt (decode, FloatCollector, Resampler), which follows MediaCodec's
 * "synchronous processing using buffers" https://developer.android.com/reference/android/media/MediaCodec
 * and MediaExtractor's track selection https://developer.android.com/reference/android/media/MediaExtractor
 */
object AudioDecode {

    /** The first audio track of [uri], at most [maxSeconds]; empty when there is none. Throws when it cannot be read. */
    fun decode(context: Context, uri: Uri, maxSeconds: Int = 600): FloatArray {
        val decoder = Decoder.open(context, uri) ?: return FloatArray(0)
        val out = FloatCollector(Dictation.SAMPLE_RATE * 30)
        try {
            while (decoder.pull(out)) {
                if (out.size > Dictation.SAMPLE_RATE * maxSeconds) break
            }
        } finally {
            decoder.close()
        }
        return out.toArray()
    }

    /**
     * The extractor, the codec and the resampler of one sound, [pull]ed one step at a time: [decode] pulls to the end
     * (or its cap), [DecodedSource] only as the samples are read. The loop is SMS's Transcriber.decode, cut in steps
     * (MediaCodec "synchronous processing using buffers").
     */
    private class Decoder private constructor(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
        private var rate: Int,
        private var channels: Int,
        /** The track's length in milliseconds from its format, 0 when it does not say. */
        val durationMs: Long,
    ) {
        private val info = MediaCodec.BufferInfo()
        private val resampler = Resampler()
        private var inputDone = false

        /**
         * One step: a chunk of the file given to the codec, a chunk of sound taken from it, resampled into [out].
         * False once the end of the sound was reached (its last samples are in [out]).
         * MediaCodec's synchronous loop https://developer.android.com/reference/android/media/MediaCodec
         */
        fun pull(out: FloatCollector): Boolean {
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
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
                }
            }
            return true
        }

        fun close() {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        companion object {
            /**
             * The first audio track of [uri] ready to be pulled, or null when there is none. Throws when it cannot be
             * read. MediaExtractor's track selection, the track format's KEY_DURATION (microseconds)
             * https://developer.android.com/reference/android/media/MediaExtractor
             * https://developer.android.com/reference/android/media/MediaFormat#KEY_DURATION
             */
            fun open(context: Context, uri: Uri): Decoder? {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    if (track == null) {
                        extractor.release()
                        return null
                    }
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)
                    val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val micros = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                    val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                    try {
                        codec.configure(format, null, null, 0)
                        codec.start()
                    } catch (failed: RuntimeException) {
                        codec.release()
                        throw failed
                    }
                    return Decoder(extractor, codec, rate, channels, (micros / 1000L).coerceAtLeast(0L))
                } catch (failed: Throwable) {
                    extractor.release()
                    throw failed
                }
            }
        }
    }

    /**
     * A recorded sound read as it is heard, for a Dictation: decoded and resampled to 16 kHz mono only as [read] asks
     * (the same Decoder as [decode], pulled lazily), so a long recording costs no memory and has no length cap. The
     * dictation's own reading speed, which waits for whisper (Dictation's bounded queue), sets the pace.
     * [durationMs] comes from the track's format (0 when it says none), for a progress count. Open it with [open] off
     * the main thread; PcmSource.start has nothing left to do, [release] closes it (also when never heard).
     * As Keyboard's DictationTestReceiver plays a file into the engine (FileSound), lazily decoded.
     */
    class DecodedSource private constructor(private val decoder: Decoder) : PcmSource {
        private val out = FloatCollector(Dictation.SAMPLE_RATE)
        private var over = false
        private var released = false

        val durationMs: Long get() = decoder.durationMs

        override fun start() = Unit

        // PcmSource.read: -1 at the end of the sound, another negative number when the codec failed
        override fun read(buffer: ShortArray): Int {
            if (released) return -2
            while (out.waiting == 0) {
                if (over) return -1
                try {
                    if (!decoder.pull(out)) over = true
                } catch (failed: RuntimeException) {
                    return -2
                }
            }
            return out.drain(buffer)
        }

        override fun release() {
            if (released) return
            released = true
            decoder.close()
        }

        companion object {
            /** [uri] ready to be heard: null when it has no audio track; throws when it cannot be read. Blocking. */
            fun open(context: Context, uri: Uri): DecodedSource? = Decoder.open(context, uri)?.let { DecodedSource(it) }
        }
    }

    /** Samples gathered in a growing array (SMS's Transcriber.FloatCollector); [drain] hands them out in order. */
    private class FloatCollector(initial: Int) {
        private var data = FloatArray(initial)
        var size = 0
            private set
        private var taken = 0

        /** Samples added and not yet drained. */
        val waiting: Int get() = size - taken

        fun add(v: Float) {
            if (taken == size) {
                // everything was drained: start over at the front
                size = 0
                taken = 0
            }
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray() = data.copyOf(size)

        /** The next samples as 16-bit, as many as [into] holds and are waiting. */
        fun drain(into: ShortArray): Int {
            val n = minOf(into.size, size - taken)
            for (k in 0 until n) into[k] = (data[taken + k] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            taken += n
            return n
        }
    }

    /** Mixes down to mono and brings any rate to 16 kHz, across buffers (SMS's Transcriber.Resampler). */
    private class Resampler {
        private var position = 0.0
        private var last = 0f
        fun push(samples: ShortArray, channels: Int, rate: Int, out: FloatCollector) {
            val ch = channels.coerceAtLeast(1)
            val frames = samples.size / ch
            if (frames == 0) return
            val mono = FloatArray(frames) { f -> var sum = 0f; for (c in 0 until ch) sum += samples[f * ch + c]; sum / ch / 32768f }
            val step = rate.toDouble() / Dictation.SAMPLE_RATE
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
}

/**
 * Decoded samples handed to a Dictation in place of the microphone, then -1: the end of the sound writes out what
 * is held (PcmSource's contract). As Keyboard's DictationTestReceiver plays a file into the engine (FileSound).
 */
class SamplesSource(private val samples: FloatArray) : PcmSource {
    private var at = 0
    override fun start() = Unit
    override fun read(buffer: ShortArray): Int {
        if (at >= samples.size) return -1
        val n = minOf(buffer.size, samples.size - at)
        for (k in 0 until n) buffer[k] = (samples[at + k] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        at += n
        return n
    }
    override fun release() = Unit
}

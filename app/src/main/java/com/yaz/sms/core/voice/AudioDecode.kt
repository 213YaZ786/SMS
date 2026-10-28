package com.yaz.sms.core.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.OutputStream
import java.nio.ByteOrder

/**
 * A recorded sound (a voice message) as 16 kHz mono 16-bit little-endian PCM, read by Android's own extractor and
 * decoders (they run in the system's sandboxed media processes, never a decoder of the app's), and written as it is
 * decoded into a stream (the pipe handed to Voice's recognition service), so a long message costs no memory.
 * Moved from SMS's former core/voice/AudioDecode.kt (itself moved from Transcriber.decode, FloatCollector, Resampler),
 * which follows MediaCodec's "synchronous processing using buffers"
 * https://developer.android.com/reference/android/media/MediaCodec
 * and MediaExtractor's track selection https://developer.android.com/reference/android/media/MediaExtractor
 */
object AudioDecode {

    /** What the stream holds: EXTRA_AUDIO_SOURCE_SAMPLING_RATE of the recognition request. */
    const val RATE = 16_000

    /**
     * The first audio track of [uri] written to [sink], at most [maxSeconds]; returns the samples written, 0 when
     * there is no audio track. Throws when it cannot be read or when the sink is closed on the other side.
     * Blocking: call it off the main thread.
     */
    fun stream(context: Context, uri: Uri, sink: OutputStream, maxSeconds: Int = 600): Long {
        val decoder = Decoder.open(context, uri) ?: return 0L
        val out = PcmOut(sink)
        try {
            while (decoder.pull(out)) {
                if (out.samples > RATE.toLong() * maxSeconds) break
            }
            out.flush()
        } finally {
            decoder.close()
        }
        return out.samples
    }

    /** The extractor, the codec and the resampler of one sound, [pull]ed one step at a time. */
    private class Decoder private constructor(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
        private var rate: Int,
        private var channels: Int,
    ) {
        private val info = MediaCodec.BufferInfo()
        private val resampler = Resampler()
        private var inputDone = false

        /**
         * One step: a chunk of the file given to the codec, a chunk of sound taken from it, resampled into [out].
         * False once the end of the sound was reached (its last samples are in [out]).
         * MediaCodec's synchronous loop https://developer.android.com/reference/android/media/MediaCodec
         */
        fun pull(out: PcmOut): Boolean {
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
             * read. MediaExtractor's track selection
             * https://developer.android.com/reference/android/media/MediaExtractor
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
                    val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                    try {
                        codec.configure(format, null, null, 0)
                        codec.start()
                    } catch (failed: RuntimeException) {
                        codec.release()
                        throw failed
                    }
                    return Decoder(extractor, codec, rate, channels)
                } catch (failed: Throwable) {
                    extractor.release()
                    throw failed
                }
            }
        }
    }

    /** 16-bit little-endian samples gathered in a buffer and written to [sink] when it is full. */
    private class PcmOut(private val sink: OutputStream) {
        private val bytes = ByteArray(32_768)
        private var held = 0
        var samples = 0L
            private set

        fun add(v: Float) {
            val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[held++] = s.toByte()
            bytes[held++] = (s shr 8).toByte()
            samples++
            if (held == bytes.size) flush()
        }

        fun flush() {
            if (held > 0) sink.write(bytes, 0, held)
            held = 0
        }
    }

    /** Mixes down to mono and brings any rate to 16 kHz, across buffers (SMS's former Transcriber.Resampler). */
    private class Resampler {
        private var position = 0.0
        private var last = 0f
        fun push(samples: ShortArray, channels: Int, rate: Int, out: PcmOut) {
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
}

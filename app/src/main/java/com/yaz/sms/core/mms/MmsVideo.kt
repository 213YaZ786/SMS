package com.yaz.sms.core.mms

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.yaz.sms.BuildConfig
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.yaz.sms.core.security.MediaCheck
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * What a film is turned into so that it fits the carrier's size, worked out from the size and the
 * length alone. Signal Android's VideoBitRateCalculator (AGPL-3.0, removed from the repository in
 * commit c53abe0941, video/lib/src/main/java/org/thoughtcrime/securesms/video/VideoBitRateCalculator.java
 * and its VideoConstants.kt): the size less a tenth as margin, less the sound's share, spread over
 * the seconds is the picture's bit rate, kept between a floor and a ceiling, and the picture is
 * made smaller when the rate is low. Signal's numbers are for 100 MB uploads (floor 500 kbit/s,
 * sound 192 kbit/s); here they are scaled to a message of a few hundred kilobytes: the ceiling
 * and the largest picture are those of Signal's current standard level (QualityTier 480 p at
 * 1 Mbit/s, lib/video/.../utils/VideoConstants.kt), the sound is mono and low, and a picture size
 * is kept only if it gets at least the bits per pixel of that same level.
 */
internal object MmsVideoBudget {
    /** Signal's margin: the file may come out a tenth over the rate asked (upperFileSizeLimit / 1.1). */
    private const val MARGIN = 1.1
    /** Mono, low: a few hundred kilobytes cannot carry Signal's 128 to 192 kbit/s of sound. */
    const val AUDIO_BIT_RATE = 32_000
    /** Signal's standard level: 1 Mbit/s at most. */
    private const val VIDEO_MAX_BIT_RATE = 1_000_000
    /** Signal's VIDEO_MINIMUM_TARGET_BIT_RATE scaled down: under this the picture is not worth sending. */
    private const val VIDEO_MIN_BIT_RATE = 64_000
    /** Signal's standard level, 480 p at 1 Mbit/s and 30 frames a second (VIDEO_FRAME_RATE): its bits per pixel. */
    private const val BITS_PER_PIXEL = 1_000_000.0 / (854 * 480 * 30)
    private const val FRAME_RATE = 30
    /** Short sides tried, largest first; 480 is Signal's standard level, the rest are the usual steps below. */
    private val SHORT_SIDES = intArrayOf(480, 360, 240, 144)

    class Plan(val videoBitRate: Int, val audioBitRate: Int, val shortSide: Int)

    /**
     * How to turn a film of [durationMs] and [width] x [height] into at most [limitBytes], or null when even
     * the floor does not fit (Signal throws VideoSizeException "Size constraints could not be met").
     * A picture is never made larger than it is: [Plan.shortSide] is at most the film's own.
     */
    fun plan(durationMs: Long, limitBytes: Long, width: Int, height: Int): Plan? {
        if (durationMs <= 0 || limitBytes <= 0 || width <= 0 || height <= 0) return null
        val seconds = durationMs / 1000.0
        // VideoBitRateCalculator.getTargetVideoBitRate: the size guide less the sound, as bits over the seconds.
        val guide = limitBytes / MARGIN - seconds * AUDIO_BIT_RATE / 8
        val rate = (guide * 8 / seconds).toInt()
        if (guide <= 0 || rate < VIDEO_MIN_BIT_RATE) return null
        val video = minOf(rate, VIDEO_MAX_BIT_RATE)
        val own = minOf(width, height)
        val aspect = maxOf(width, height).toDouble() / own
        // Signal switches to a lower format when the rate is low (LOW_RES_TARGET_VIDEO_BITRATE).
        val side = SHORT_SIDES.firstOrNull { it <= own && video >= BITS_PER_PIXEL * it * it * aspect * FRAME_RATE }
            ?: minOf(own, SHORT_SIDES.last())
        return Plan(video, AUDIO_BIT_RATE, side)
    }

    /**
     * The next plan once [previous] came out at [producedBytes], over [limitBytes], with the rates the encoder
     * really delivered ([measuredVideo], [measuredAudio] in bit/s, 0 when unknown), or null when no picture is left
     * to give. Signal's StreamingTranscoder only checks the output against the limit and fails
     * (lib/video/src/main/java/org/thoughtcrime/securesms/video/StreamingTranscoder.java, VideoSizeException) and
     * Media3's DefaultEncoderFactory clamps a request to the encoder's own range
     * (https://github.com/androidx/media/blob/release/libraries/transformer/src/main/java/androidx/media3/transformer/DefaultEncoderFactory.java),
     * so an encoder may deliver more or less than asked: here the request is corrected by what was delivered.
     * The picture steps one size down, and the video rate asked is the room left once the sound really
     * delivered is taken off the size allowed (the margin of [plan] kept), divided by the share of the request the
     * encoder delivered, and never above the previous request times the overshoot (limit / produced).
     */
    fun retry(previous: Plan, durationMs: Long, limitBytes: Long, producedBytes: Long, measuredVideo: Int, measuredAudio: Int, ownShortSide: Int): Plan? {
        if (durationMs <= 0 || limitBytes <= 0 || producedBytes <= 0) return null
        val seconds = durationMs / 1000.0
        val allowed = limitBytes / MARGIN * 8 / seconds
        val audio = maxOf(previous.audioBitRate, measuredAudio)
        val room = allowed - audio
        if (room < VIDEO_MIN_BIT_RATE) return null
        val delivered = if (measuredVideo > 0) measuredVideo.toDouble() / previous.videoBitRate else 1.0
        val byRoom = room / delivered
        val byOvershoot = previous.videoBitRate * limitBytes.toDouble() / producedBytes / MARGIN
        val video = minOf(byRoom, byOvershoot, previous.videoBitRate * 0.9).toInt().coerceAtLeast(VIDEO_MIN_BIT_RATE)
        val side = SHORT_SIDES.firstOrNull { it < previous.shortSide && it <= ownShortSide } ?: previous.shortSide
        return Plan(video, previous.audioBitRate, side)
    }
}

/**
 * A film too big for the carrier, turned into an H.264 and AAC MP4 that fits, with Media3 Transformer
 * (Apache-2.0), the way the library's own demo does it
 * (https://github.com/androidx/media/blob/release/demos/transformer/src/main/java/androidx/media3/demo/transformer/TransformerActivity.java:
 * Transformer.Builder with setVideoMimeType / setAudioMimeType, DefaultEncoderFactory with the requested
 * video encoder settings, ChannelMixingAudioProcessor to mono, Presentation.createForShortSide,
 * Transformer.Listener, cancel) and its guide, https://developer.android.com/media/media3/transformer.
 * Signal Android uses the same constant bit rate for its transcodes
 * (lib/video/.../videoconverter/VideoTrackConverter.java, BITRATE_MODE_CBR).
 */
internal object MmsVideo {
    /** One try may run this long before it is cancelled. */
    const val TIMEOUT_MS = 120_000L
    /** Tries in all: the first, then up to two corrected by what the encoder delivered. */
    const val TRIES = 3

    /**
     * The film at [uri] as bytes of at most [limit], or null: not a film, unreadable, too long for
     * the size, or still too big after [TRIES] tries, each asking less than the one before.
     */
    fun fit(context: Context, uri: Uri, limit: Int): ByteArray? {
        if (MediaCheck.kind(context, uri) != MediaCheck.Kind.VIDEO) {
            log("not a film by its bytes")
            return null
        }
        val probe = probe(context, uri)
        if (probe == null) {
            log("film unreadable by MediaMetadataRetriever")
            return null
        }
        log("film ${probe.durationMs} ms ${probe.width}x${probe.height}, limit $limit bytes")
        val dir = File(context.cacheDir, "mms").apply { mkdirs() }
        val out = File(dir, "video-${System.nanoTime()}.mp4")
        try {
            var next = MmsVideoBudget.plan(probe.durationMs, limit.toLong(), probe.width, probe.height)
            repeat(TRIES) { attempt ->
                val asked = next
                if (asked == null) {
                    log("try ${attempt + 1}: under the floor, refused")
                    return null
                }
                log("try ${attempt + 1}: limit $limit bytes, video ${asked.videoBitRate} bit/s, audio ${asked.audioBitRate} bit/s, short side ${asked.shortSide}")
                out.delete()
                val made = transcode(context, uri, asked, minOf(probe.width, probe.height), out) ?: return null
                // A file that is empty or missing is a failed export, as Transformer's own docs treat an error:
                // its output "is not deleted" but is not to be used.
                val size = out.length()
                log("try ${attempt + 1}: produced $size bytes")
                if (size in 1..limit.toLong()) return out.readBytes()
                if (size <= 0) return null
                next = MmsVideoBudget.retry(asked, probe.durationMs, limit.toLong(), size, made.video, made.audio, minOf(probe.width, probe.height))
            }
            return null
        } finally {
            out.delete()
        }
    }

    /**
     * Debug builds only; sizes, lengths and codes, never a name, an address or a path. The demo reports its export the
     * same way, ExportResult and ExportException.getErrorCodeName in TransformerActivity.onCompleted / onError
     * (https://github.com/androidx/media/blob/release/demos/transformer/src/main/java/androidx/media3/demo/transformer/TransformerActivity.java),
     * and the build flag is the one SmsApplication already reads.
     */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d("MmsVideo", message)
    }

    /**
     * The classes of an exception and of its causes, without their messages (which may carry names or paths): the
     * cause chain of ExportException (https://github.com/androidx/media/blob/release/libraries/transformer/src/main/java/androidx/media3/transformer/ExportException.java).
     */
    private fun causes(e: Throwable): String =
        generateSequence(e) { it.cause }.take(6).joinToString(" <- ") { it.javaClass.simpleName }

    /** The rates the encoders really delivered, in bit/s, 0 when the library does not say. */
    class Made(val video: Int, val audio: Int)

    /**
     * Constant bit rate when an H.264 encoder of the device supports it, else variable: Media3 warns that "setting an
     * unsupported bitrateMode may cause encoder instantiation failure"
     * (DefaultEncoderFactory.Builder.setRequestedVideoEncoderSettings,
     * https://github.com/androidx/media/blob/release/libraries/transformer/src/main/java/androidx/media3/transformer/DefaultEncoderFactory.java);
     * support is asked of the platform with MediaCodecInfo.EncoderCapabilities.isBitrateModeSupported
     * (https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities). CBR is what Signal's
     * transcodes use (VideoTrackConverter, BITRATE_MODE_CBR) and holds a size best.
     */
    private fun bitrateMode(): Int {
        val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        val supported = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && MimeTypes.VIDEO_H264 in info.supportedTypes &&
                    info.getCapabilitiesForType(MimeTypes.VIDEO_H264).encoderCapabilities?.isBitrateModeSupported(cbr) == true
            }
        }.getOrDefault(false)
        return if (supported) cbr else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    }

    class Probe(val durationMs: Long, val width: Int, val height: Int)

    /** Length and size of the picture, read by the platform (MediaMetadataRetriever), as MmsTransport.durationMs does. */
    private fun probe(context: Context, uri: Uri): Probe? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun key(k: Int) = retriever.extractMetadata(k)?.toLongOrNull()
            val duration = key(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: return null
            val width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: return null
            val height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: return null
            Probe(duration, width, height).takeIf { it.durationMs > 0 && width > 0 && height > 0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Runs the export on a thread of its own (a Transformer must be used from one thread with a Looper)
     * and waits for it here, cancelling it after [TIMEOUT_MS]. True when [out] holds the film.
     */
    private fun transcode(context: Context, uri: Uri, plan: MmsVideoBudget.Plan, ownShortSide: Int, out: File): Made? {
        val thread = HandlerThread("MmsVideo").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        val ok = AtomicReference<Made?>()
        val running = AtomicReference<Transformer?>()
        handler.post {
            try {
                val videoEffects = if (plan.shortSide < ownShortSide) listOf<Effect>(Presentation.createForShortSide(plan.shortSide)) else emptyList()
                // The demo's "mix to mono": one matrix per input channel count.
                val mono = ChannelMixingAudioProcessor()
                for (channels in 1..6) mono.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, 1))
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(listOf<AudioProcessor>(mono), videoEffects))
                    .build()
                val transformer = Transformer.Builder(context.applicationContext)
                    .setLooper(thread.looper)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context.applicationContext)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder()
                                    .setBitrate(plan.videoBitRate)
                                    .setBitrateMode(bitrateMode())
                                    .build()
                            )
                            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(plan.audioBitRate).build())
                            .build()
                    )
                    // Nothing about where or when the film was shot goes out: the muxer is handed the input's
                    // metadata entries (the place among them) and this provider empties the list
                    // (InAppMp4Muxer.MetadataProvider: "an entry can be added or removed").
                    .setMuxerFactory(InAppMp4Muxer.Factory { entries -> entries.clear() })
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            log("export done: ${exportResult.fileSizeBytes} bytes, ${exportResult.width}x${exportResult.height}, video ${exportResult.averageVideoBitrate} bit/s, audio ${exportResult.averageAudioBitrate} bit/s")
                            ok.set(Made(exportResult.averageVideoBitrate.coerceAtLeast(0), exportResult.averageAudioBitrate.coerceAtLeast(0)))
                            done.countDown()
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            log("export failed: ${exportException.errorCodeName} (${exportException.errorCode}), ${causes(exportException)}")
                            done.countDown()
                        }

                        // onFallbackApplied, Transformer.Listener of Media3
                        // (https://github.com/androidx/media/blob/release/libraries/transformer/src/main/java/androidx/media3/transformer/Transformer.java):
                        // the library changed the request to suit the device, which explains a size or a format that differs.
                        override fun onFallbackApplied(
                            composition: Composition,
                            originalTransformationRequest: TransformationRequest,
                            fallbackTransformationRequest: TransformationRequest
                        ) {
                            log("fallback: ${originalTransformationRequest.videoMimeType}/${originalTransformationRequest.audioMimeType} -> ${fallbackTransformationRequest.videoMimeType}/${fallbackTransformationRequest.audioMimeType}")
                        }
                    })
                    .build()
                running.set(transformer)
                transformer.start(edited, out.path)
            } catch (e: Exception) {
                log("export could not start: ${causes(e)}")
                done.countDown()
            }
        }
        val finished = runCatching { done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) log("export timed out after $TIMEOUT_MS ms")
        // Cancelled on the transformer's own thread, then the thread ends once that is done.
        handler.post { runCatching { running.get()?.cancel() } }
        thread.quitSafely()
        return if (finished) ok.get() else null
    }
}

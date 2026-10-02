package com.sms.app.feature.thread

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sms.app.core.mms.MmsPart
import com.sms.app.ui.component.ZoneSurface
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.icon.AppIcons
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * A voice message being recorded: AAC in an .m4a in the app's cache,
 * small enough for a picture message (about 4 kB a second), and the
 * loudness of the voice as it goes, for the bars.
 */
class VoiceTake(context: Context) {
    val file: File = File(File(context.cacheDir, "shared").apply { mkdirs() }, "voice-${System.currentTimeMillis()}.m4a")
    val started = System.currentTimeMillis()
    val levels = mutableStateListOf<Float>()
    var locked by mutableStateOf(false)
    private val recorder: MediaRecorder = MediaRecorder(context).apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(1)
        setAudioSamplingRate(22_050)
        setAudioEncodingBitRate(32_000)
        setOutputFile(file.path)
        prepare()
        start()
    }

    /** The voice's loudness now, 0 to 1. */
    fun level(): Float = runCatching { (recorder.maxAmplitude / 22_000f).coerceIn(0f, 1f) }.getOrDefault(0f)

    /** Stops; the file when it is long enough to be a message, else nothing. */
    fun finish(keep: Boolean): File? {
        val long = System.currentTimeMillis() - started >= MIN_MS
        runCatching { recorder.stop() }
        recorder.release()
        if (keep && long && file.length() > 0) return file
        file.delete()
        return null
    }

    companion object {
        /** Shorter than this, a press was not meant as a message. */
        const val MIN_MS = 700L

        fun start(context: Context): VoiceTake? = runCatching { VoiceTake(context) }.getOrNull()
    }
}

/** The voice drawn as it is recorded: bars of the accent, the newest on the right, and the time. */
@Composable
fun VoiceLevels(take: VoiceTake, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(take) {
        while (isActive) {
            take.levels.add(take.level())
            if (take.levels.size > 60) take.levels.removeAt(0)
            now = System.currentTimeMillis()
            delay(80)
        }
    }
    val red = MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(horizontal = 16.dp)) {
        Text(clock(now - take.started), style = MaterialTheme.typography.labelLarge, color = red)
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.weight(1f).height(28.dp)) {
            val bar = 3.dp.toPx()
            val gap = 3.dp.toPx()
            val fit = (size.width / (bar + gap)).toInt()
            val shown = take.levels.takeLast(fit)
            shown.forEachIndexed { i, level ->
                val h = (size.height * (0.15f + 0.85f * level)).coerceAtLeast(bar)
                val x = size.width - (shown.size - i) * (bar + gap)
                drawRoundRect(red, Offset(x, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
            }
        }
    }
}

/**
 * A voice message in the conversation: play and pause, its bars filling
 * as it plays, and how long it is.
 */
@Composable
fun VoiceTile(part: MmsPart, mine: Boolean) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val length by produceState(0, part.uri) {
        value = withContext(Dispatchers.IO) { runCatching { MediaPlayer.create(context, part.uri)?.let { p -> p.duration.also { p.release() } } }.getOrNull() ?: 0 }
    }
    // Bars that do not change from one look to the next: drawn from the file's own bytes.
    val shape by produceState(List(28) { 0.4f }, part.uri) { value = withContext(Dispatchers.IO) { barsOf(context, part.uri) } }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            val p = player ?: break
            progress = runCatching { p.currentPosition.toFloat() / p.duration.coerceAtLeast(1) }.getOrDefault(0f)
            delay(50)
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    ZoneSurface(shape = RoundedCornerShape(22.dp), accent = mine, modifier = Modifier.width(240.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Box(
                Modifier.size(40.dp).clickable {
                    haptics.tick()
                    if (playing) {
                        player?.pause()
                        playing = false
                    } else {
                        val p = player ?: runCatching { MediaPlayer.create(context, part.uri) }.getOrNull()?.also { created ->
                            created.setOnCompletionListener {
                                playing = false
                                progress = 0f
                            }
                            player = created
                        }
                        if (p != null) {
                            p.start()
                            playing = true
                        }
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (playing) AppIcons.Pause else AppIcons.Play, contentDescription = if (playing) "Pause" else "Play", tint = accent)
            }
            Canvas(Modifier.weight(1f).height(26.dp).padding(horizontal = 6.dp)) {
                val bar = 3.dp.toPx()
                val gap = (size.width - shape.size * bar) / (shape.size - 1).coerceAtLeast(1)
                shape.forEachIndexed { i, level ->
                    val h = (size.height * level).coerceAtLeast(bar)
                    val x = i * (bar + gap)
                    val done = i.toFloat() / shape.size < progress
                    drawRoundRect(if (done) accent else faint, Offset(x, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
                }
            }
            Text(clock(length.toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 0:07, 1:32. */
private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Twenty-eight bars from the file's bytes, steady for the same file. */
private fun barsOf(context: Context, uri: Uri): List<Float> {
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return List(28) { 0.4f }
    val step = (bytes.size / 28).coerceAtLeast(1)
    return List(28) { i ->
        val from = i * step
        val chunk = bytes.copyOfRange(from.coerceAtMost(bytes.size), (from + step).coerceAtMost(bytes.size))
        val values = chunk.map { (it.toInt() and 0xff).toDouble() }
        val mean = values.average()
        val spread = if (values.isEmpty()) 0.0 else values.sumOf { (it - mean) * (it - mean) } / values.size
        (0.25f + (kotlin.math.sqrt(spread) / 90.0).toFloat()).coerceIn(0.2f, 1f)
    }
}

/** The mic, grown while it records. */
@Composable
fun rememberMicScale(recording: Boolean): Float {
    val scale by animateFloatAsState(if (recording) 1.5f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "mic")
    return scale
}

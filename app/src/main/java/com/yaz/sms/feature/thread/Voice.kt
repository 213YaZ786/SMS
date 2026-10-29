package com.yaz.sms.feature.thread

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ShapeDefaults
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
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
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

    // Recording counts as busy for the update (AppBusy, update kit contract) until finish().
    private var counted = true.also { com.yaz.sms.core.update.AppBusy.voiceStarted() }

    /** The voice's loudness now, 0 to 1. */
    fun level(): Float = runCatching { (recorder.maxAmplitude / 22_000f).coerceIn(0f, 1f) }.getOrDefault(0f)

    /** Stops; the file when it is long enough to be a message, else nothing. */
    fun finish(keep: Boolean): File? {
        val long = System.currentTimeMillis() - started >= MIN_MS
        runCatching { recorder.stop() }
        recorder.release()
        if (counted) {
            counted = false
            com.yaz.sms.core.update.AppBusy.voiceStopped()
        }
        if (keep && long && file.length() > 0) return file
        file.delete()
        return null
    }

    companion object {
        /** Shorter than this, a press was not meant as a message. */
        const val MIN_MS = 700L

        fun start(context: Context): VoiceTake? {
            // Recordings already sent, or let go, do not stay: gone after an hour.
            File(context.cacheDir, "shared").listFiles { f -> f.name.startsWith("voice-") && System.currentTimeMillis() - f.lastModified() > 3_600_000 }
                ?.forEach { it.delete() }
            return runCatching { VoiceTake(context) }.getOrNull()
        }
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
fun VoiceTile(part: MmsPart, mine: Boolean, inBubble: Boolean = false) {
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
    // Playing counts as busy for the update (AppBusy), while it plays and not after.
    DisposableEffect(playing) {
        if (playing) com.yaz.sms.core.update.AppBusy.voiceStarted()
        onDispose { if (playing) com.yaz.sms.core.update.AppBusy.voiceStopped() }
    }
    LaunchedEffect(playing) {
        while (playing) {
            val p = player ?: break
            progress = runCatching { p.currentPosition.toFloat() / p.duration.coerceAtLeast(1) }.getOrDefault(0f)
            delay(50)
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    VoiceFrame(inBubble, mine) {
      Column(Modifier.animateContentSize()) {
        // In a bubble the tile's own margins (MessageBody) are the padding.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = if (inBubble) Modifier else Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
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
        Transcript(part)
      }
    }
}

/**
 * A voice message's frame: in a message's bubble it has none, as Element X's TimelineItemVoiceView
 * (https://github.com/element-hq/element-x-android, AGPL-3.0, features/messages/impl/src/main/kotlin/io/element/android/
 * features/messages/impl/timeline/components/event/TimelineItemVoiceView.kt) sits in the event bubble;
 * alone, a pane of glass of its own.
 */
@Composable
private fun VoiceFrame(inBubble: Boolean, mine: Boolean, content: @Composable () -> Unit) {
    if (inBubble) Box(Modifier.width(240.dp)) { content() }
    // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
    else ZoneSurface(shape = ShapeDefaults.ExtraLarge, accent = mine, modifier = Modifier.width(240.dp), content = content)
}

/**
 * The words of a voice message under it, heard on the phone the first
 * time it is shown, by the Voice app; three lines, the rest on a tap.
 * Without Voice on the phone, nothing shows.
 */
@Composable
private fun Transcript(part: MmsPart) {
    val transcriber: com.yaz.sms.core.voice.Transcriber = org.koin.compose.koinInject()
    val store: com.yaz.sms.data.settings.SettingsStore = org.koin.compose.koinInject()
    val settings by store.settings.collectAsState()
    if (!transcriber.available || !settings.transcribeVoice) return
    val key = part.uri.toString()
    val texts by transcriber.texts.collectAsState()
    val states by transcriber.states.collectAsState()
    LaunchedEffect(key) { transcriber.transcribe(part.uri) }
    val words = texts[key]
    val state = states[key]
    val faint = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.6f)
    val modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
    when {
        !words.isNullOrBlank() -> {
            var open by remember { mutableStateOf(false) }
            Text(
                words,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = modifier.clickable { open = !open }
            )
        }
        state == com.yaz.sms.core.voice.Transcriber.State.Working -> {
            // Three dots that rise in turn while it listens.
            val wave = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
            val t by wave.animateFloat(0f, 3f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)), label = "t")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
                Text("Writing it out", style = MaterialTheme.typography.bodySmall, color = faint)
                repeat(3) { i ->
                    val lift = (1f - kotlin.math.abs(t - i - 0.5f).coerceAtMost(1f))
                    Text(".", style = MaterialTheme.typography.bodySmall, color = faint, modifier = Modifier.graphicsLayer { translationY = -4.dp.toPx() * lift })
                }
            }
        }
        else -> Unit
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

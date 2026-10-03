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
      Column(Modifier.animateContentSize()) {
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
        Transcript(part)
      }
    }
}

/**
 * The words of a voice message under it, heard on the phone the first
 * time it is shown; three lines, the rest on a tap. Before the speech
 * model is on the phone, a tap offers to fetch it, its size said first.
 */
@Composable
private fun Transcript(part: MmsPart) {
    val transcriber: com.yaz.sms.core.voice.Transcriber = org.koin.compose.koinInject()
    val store: com.yaz.sms.data.settings.SettingsStore = org.koin.compose.koinInject()
    val settings by store.settings.collectAsState()
    if (!transcriber.available || !settings.transcribeVoice) return
    val haptics = rememberHaptics()
    val key = part.uri.toString()
    val texts by transcriber.texts.collectAsState()
    val states by transcriber.states.collectAsState()
    val model by transcriber.model.state.collectAsState()
    LaunchedEffect(key, model) { transcriber.transcribe(part.uri) }
    var asking by remember { mutableStateOf(false) }
    if (asking) SpeechModelDialog(transcriber.model, onDismiss = { asking = false })
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
        model is com.yaz.sms.core.voice.SpeechModel.State.Fetching -> {
            val done = (model as com.yaz.sms.core.voice.SpeechModel.State.Fetching).done
            Column(modifier) {
                Text("Getting the speech model · ${(done * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = faint)
                androidx.compose.material3.LinearProgressIndicator(progress = { done }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
            }
        }
        model == com.yaz.sms.core.voice.SpeechModel.State.Missing || model == com.yaz.sms.core.voice.SpeechModel.State.Failed -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    haptics.tick()
                    asking = true
                }
            ) {
                Icon(AppIcons.TextFormat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (model == com.yaz.sms.core.voice.SpeechModel.State.Failed) "Could not get the speech model · Try again" else "Write it out",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        else -> Unit
    }
}

/**
 * The speech model where it is set (Settings): the choices, then one
 * action for where it stands: get it (its size said), its progress with
 * Stop, or on the phone with Remove.
 */
@Composable
internal fun SpeechModelPanel(model: com.yaz.sms.core.voice.SpeechModel, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val state by model.state.collectAsState()
    val metered = remember { model.metered() }
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        ModelChoices(model)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            when (val s = state) {
                is com.yaz.sms.core.voice.SpeechModel.State.Fetching -> {
                    Column(Modifier.weight(1f)) {
                        Text("Getting it · ${(s.done * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.LinearProgressIndicator(progress = { s.done }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
                    }
                    androidx.compose.material3.TextButton(onClick = { haptics.tick(); model.cancel() }) { Text("Stop") }
                }
                com.yaz.sms.core.voice.SpeechModel.State.Ready -> {
                    Text(if (model.usesSystem) "Ready, nothing to fetch" else "${model.pin.label} on the phone", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (!model.usesSystem) androidx.compose.material3.TextButton(onClick = { haptics.reject(); model.remove() }) { Text("Remove") }
                }
                else -> {
                    Text(
                        if (state == com.yaz.sms.core.voice.SpeechModel.State.Failed) "Could not get it" else if (metered) "On mobile data" else "Not on the phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state == com.yaz.sms.core.voice.SpeechModel.State.Failed || metered) MaterialTheme.colorScheme.error else androidx.compose.material3.LocalContentColor.current,
                        modifier = Modifier.weight(1f)
                    )
                    com.yaz.sms.ui.component.BoldButton(onClick = { haptics.done(); model.fetch() }) { Text("Get it · ${model.pin.bytes / 1_000_000} MB") }
                }
            }
        }
    }
}

/** The speech model offered: the two sizes to choose from, fetched once. */
@Composable
internal fun SpeechModelDialog(model: com.yaz.sms.core.voice.SpeechModel, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    val metered = remember { model.metered() }
    val state by model.state.collectAsState()
    com.yaz.sms.ui.component.ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech model") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Text("On this phone only, any language.", style = MaterialTheme.typography.bodyMedium)
                ModelChoices(model)
                if (metered && state != com.yaz.sms.core.voice.SpeechModel.State.Ready) {
                    Text("On mobile data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                haptics.done()
                if (state != com.yaz.sms.core.voice.SpeechModel.State.Ready) model.fetch()
                onDismiss()
            }) { Text(if (state == com.yaz.sms.core.voice.SpeechModel.State.Ready) "Done" else "Get it") }
        },
        dismissButton = {
            if (state == com.yaz.sms.core.voice.SpeechModel.State.Ready) androidx.compose.material3.TextButton(onClick = {
                haptics.reject()
                model.remove()
                onDismiss()
            }) { Text("Remove") } else androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Not now") }
        }
    )
}

/**
 * The ways to write voice messages out: the phone's own recognizer when it
 * already has the phone's language (nothing to fetch), and the two speech
 * models side by side, name, size and what each is good at.
 */
@Composable
internal fun ModelChoices(model: com.yaz.sms.core.voice.SpeechModel) {
    val haptics = rememberHaptics()
    val state by model.state.collectAsState()
    val system by model.system.collectAsState()
    // Read again when the choice changes.
    var chosen by remember(system) { mutableStateOf(if (model.usesSystem) com.yaz.sms.core.voice.SpeechModel.SYSTEM else model.pin.name) }
    @Composable
    fun Choice(key: String, title: String, size: String, line: String, modifier: Modifier, onPick: () -> Unit) {
        val on = key == chosen
        val lift by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0.96f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "pick")
        com.yaz.sms.ui.component.FloatingPane(
            shape = RoundedCornerShape(20.dp),
            accent = on,
            onClick = {
                if (state is com.yaz.sms.core.voice.SpeechModel.State.Fetching) return@FloatingPane
                haptics.tick()
                onPick()
                chosen = key
            },
            modifier = modifier.graphicsLayer { scaleX = lift; scaleY = lift }
        ) {
            // The same three lines in each, so the tiles are the same size.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(size, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        if (system) {
            val language = remember { java.util.Locale.getDefault().let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } } }
            Choice(com.yaz.sms.core.voice.SpeechModel.SYSTEM, "This phone's", "Nothing to fetch", "$language only", Modifier.fillMaxWidth()) { model.chooseSystem() }
        }
        // Side by side, of equal height; the one this phone runs best is chosen at first.
        Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max)
        ) {
            com.yaz.sms.core.voice.SpeechModel.PINS.forEach { pin ->
                Choice(
                    pin.name, pin.label, "${pin.bytes / 1_000_000} MB", pin.quality,
                    Modifier.weight(1f).fillMaxHeight()
                ) { model.choose(pin) }
            }
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

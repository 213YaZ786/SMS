package com.yaz.sms.feature.thread

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.WindowManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.yaz.sms.ui.component.Haptics
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
class VoiceTake(
    context: Context,
    /** The most the file may weigh (an MMS), 0 for no limit: the recorder stops by itself there and [onFull] tells. */
    maxSize: Long = 0L,
    private val onFull: () -> Unit = {},
    private val onBroken: () -> Unit = {}
) {
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
        // AOSP's LevelTrackingMediaRecorder.startRecording(onInfo, onError, maxSize) sets the carrier's size and both
        // listeners before prepare (AudioRecordView.onRecordButtonTouchDown passes MmsConfig's getMaxMessageSize).
        if (maxSize > 0) setMaxFileSize(maxSize)
        setOnInfoListener { mr, what, extra -> onInfo(mr, what, extra) }
        setOnErrorListener { mr, what, extra -> onError(mr, what, extra) }
        setOutputFile(file.path)
        // A recorder that fails to start is let go, never left holding the microphone.
        try {
            prepare()
            start()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/ui/mediapicker/AudioRecordView.java#onInfo
    // From MediaRecorder.OnInfoListener.
    private fun onInfo(mr: MediaRecorder, what: Int, extra: Int) {
        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
            // Max size reached. Finish recording immediately.
            onFinishedRecording()
        } else if (what != MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING &&
            what != MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED
        ) {
            // These are unknown errors (802 and 803 only inform on newer Android, they are not errors).
            onErrorWhileRecording(what, extra)
        }
    }

    // From MediaRecorder.OnErrorListener.
    private fun onError(mr: MediaRecorder, what: Int, extra: Int) {
        onErrorWhileRecording(what, extra)
    }

    private fun onErrorWhileRecording(what: Int, extra: Int) {
        android.util.Log.w("VoiceTake", "Error occurred during audio recording what=$what, extra=$extra")
        onBroken()
    }

    private fun onFinishedRecording() {
        onFull()
    }

    // not ported: AudioRecordView.onRecordButtonTouchDown and onRecordButtonTouchUp (the touch is onTouch below),
    // stopRecording (VoiceTake.finish), playAudioStartSound and playAudioEndSound (no sounds here), setMode and
    // updateVisualState (the composer draws the take), onPause and onDetachedFromWindow (the delegate's lifecycle),
    // the 300 ms minimum and the 500 ms ending buffer (VoiceTake.MIN_MS, the recorder is stopped at once).
    /** How long it has gone on, in milliseconds. */
    fun elapsedTime(): Long = System.currentTimeMillis() - started

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

        fun start(context: Context, maxSize: Long = 0L, onFull: () -> Unit = {}, onBroken: () -> Unit = {}): VoiceTake? {
            // Recordings already sent, or let go, do not stay: gone after a day (one kept in the bar waits for Send).
            File(context.cacheDir, "shared").listFiles { f -> f.name.startsWith("voice-") && System.currentTimeMillis() - f.lastModified() > 86_400_000 }
                ?.forEach { it.delete() }
            return runCatching { VoiceTake(context, maxSize, onFull, onBroken) }.getOrNull()
        }
    }
}

/**
 * What the finger on the microphone tells the composer: Signal's AudioRecordingHandler as MicrophoneRecorderView
 * calls it.
 */
interface AudioRecordingHandler {
    fun onRecordPermissionRequired()
    fun onRecorderAlreadyInUse()
    fun onRecordPressed()
    fun onRecordReleased()
    fun onRecordMoved(offsetX: Float, offsetY: Float)
    fun onRecordCanceled(byUser: Boolean)
    fun onRecordLocked()
    fun onRecordSaved()
}

/**
 * MicrophoneRecorderView.onTouch: a press asks for the permission when it is missing, says the microphone is in use
 * while a call holds it (AudioManager mode IN_CALL or IN_COMMUNICATION), else starts; a move is told to the handler,
 * a slide up to the lock target locks, a slide left past [cancelAt] cancels, a release (or a cancelled touch) ends it.
 * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/components/MicrophoneRecorderView.java#onTouch
 * not ported: cancelAction (the delegate's onRecorderCanceled), saveAction (onRecordSaveDraft), lockAction (the
 * take's locked flag), unlockAction (the delegate's onRecorderFinished), hideUi, isRecordingLocked, isRecording,
 * setHandler (the handler is an argument), FloatingRecordButton and LockDropTarget (drawn by the composer from the
 * handler's moves).
 */
internal suspend fun PointerInputScope.onTouch(
    context: Context,
    delegate: VoiceMessageRecordingDelegate,
    handler: AudioRecordingHandler,
    cancelAt: Float,
    lockAt: Float
) = awaitEachGesture {
    val down = awaitFirstDown()
    val audioManager = ContextCompat.getSystemService(context, AudioManager::class.java)
    var isMicPossiblyInUse = false
    if (audioManager != null) {
        isMicPossiblyInUse = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION || audioManager.mode == AudioManager.MODE_IN_CALL
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        handler.onRecordPermissionRequired()
        return@awaitEachGesture
    } else if (isMicPossiblyInUse) {
        handler.onRecorderAlreadyInUse()
        return@awaitEachGesture
    } else if (delegate.hasActiveSession()) {
        return@awaitEachGesture
    }
    handler.onRecordPressed()
    // The recorder could not start: the delegate told so, nothing runs.
    if (!delegate.hasActiveSession()) return@awaitEachGesture
    var cancelled = false
    var locked = false
    while (delegate.hasActiveSession()) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        val moved = change.position - down.position
        handler.onRecordMoved(moved.x.coerceAtMost(0f), moved.y.coerceAtMost(0f))
        change.consume()
        if (moved.x < -cancelAt) {
            cancelled = true
            break
        }
        if (moved.y < -lockAt) {
            locked = true
            handler.onRecordLocked()
            break
        }
    }
    handler.onRecordMoved(0f, 0f)
    when {
        cancelled -> handler.onRecordCanceled(true)
        !locked -> handler.onRecordReleased()
    }
}

/**
 * The voice message being recorded and what becomes of it, as Signal's VoiceMessageRecordingDelegate does and as
 * Element X's voice composer presenter handles the screen going away: a take begun keeps the screen on; finished it
 * goes to [SessionCallback.sendVoiceNote]; thrown away by the user it is deleted; stopped by anything else (the
 * screen pausing, another app taking the sound, the carrier's size reached) it is kept for the bar
 * ([SessionCallback.saveEphemeralVoiceNoteDraft]), never sent and never lost.
 * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/conversation/v2/VoiceMessageRecordingDelegate.kt#VoiceMessageRecordingDelegate
 * https://raw.githubusercontent.com/element-hq/element-x-android/develop/features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/voicemessages/composer/DefaultVoiceMessageComposerPresenter.kt#DefaultVoiceMessageComposerPresenter
 *
 * not ported: VoiceRecorderWakeLock (the window flag keeps the screen on)
 * not ported: requestedOrientation locked (the app handles its own rotation)
 * not ported: Session (Rx: a take stops synchronously and is a whole file when finish returns)
 * not ported: onSubscribe (Rx)
 * not ported: onSuccess (Rx: finish returns the file)
 * not ported: onError (Rx: VoiceTake's onBroken)
 * not ported: dispose (Rx)
 * not ported: isDisposed (Rx)
 * not ported: savePeriodicDraftSnapshot (no draft of a sound is kept in a database; a take is kept when it stops)
 * not ported: present (no presenter state; keepScreenOn is the window flag)
 * not ported: Factory (dependency injection)
 * not ported: create (dependency injection)
 * not ported: previewState (a kept take is a chip, played in the thread once sent)
 * not ported: displayTime (same)
 * not ported: finishedWaveform (same)
 * not ported: handleVoiceMessagePlayerEvent (same)
 * not ported: sendVoiceMessage (the composer sends the kept file)
 * not ported: sendMessage (same)
 * not ported: deleteRecording (the file is deleted by VoiceTake.finish)
 * not ported: handleVoiceMessageRecorderEvent (the handler's calls)
 * not ported: handleEvent (same)
 * not ported: captureComposerEvent (analytics)
 */
class VoiceMessageRecordingDelegate(
    private val context: Context,
    private val haptics: Haptics,
    private val sessionCallback: SessionCallback,
    /** The most a take may weigh (the MMS size), 0 for no limit; asked at each start. */
    private val maxBytes: () -> Long
) {
    /** The take going on, null when none. */
    var session by mutableStateOf<VoiceTake?>(null)
        private set

    private var audioFocus: AudioFocusRequest? = null

    fun hasActiveSession(): Boolean = session != null

    fun elapsedTime(): Long = session?.elapsedTime() ?: 0L

    fun onRecorderStarted() {
        beginRecording()
    }

    fun onRecorderLocked() {
        session?.locked = true
        haptics.tick()
    }

    fun onRecorderFinished() {
        vibrateAndResetOrientation(0)
        if (session != null) completeRecording()
    }

    fun onRecorderCanceled(byUser: Boolean) {
        if (session == null) return
        vibrateAndResetOrientation(if (byUser) 50 else 0)
        if (byUser) {
            discardRecording()
        } else {
            saveDraft()
        }
    }

    fun onRecordSaveDraft() {
        if (session == null) return
        vibrateAndResetOrientation(0)
        saveDraft()
    }

    /** Element's handleLifecycleEvent: pausing keeps the take as it is; the end of the screen throws it away. */
    fun handleLifecycleEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_PAUSE -> finishRecording()
            Lifecycle.Event.ON_DESTROY -> cancelRecording()
            else -> {}
        }
    }

    /** Ends the take and keeps it (Element's finishRecording). */
    private fun finishRecording() = onRecorderCanceled(byUser = false)

    /** Ends the take and throws it away (Element's cancelRecording). */
    private fun cancelRecording() = onRecorderCanceled(byUser = true)

    /** Signal's vibrateAndResetOrientation: a firm buzz for a take thrown away, and the screen may sleep again. */
    private fun vibrateAndResetOrientation(milliseconds: Long) {
        if (milliseconds >= 50) haptics.reject()
        activityOf(context)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        releaseAudioFocus()
    }

    private fun beginRecording() {
        activityOf(context)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sessionCallback.onSessionWillBegin()
        startRecording()
    }

    /** Element's startRecording: the sound focus first (something else taking it ends the take, kept), then the recorder. */
    private fun startRecording() {
        requestAudioFocus()
        val take = VoiceTake.start(
            context,
            maxBytes(),
            onFull = {
                // The size of an MMS reached: the take is kept as it is (AOSP's onInfo).
                onRecordSaveDraft()
                sessionCallback.onMaximumSize()
            },
            onBroken = {
                cancelRecording()
                sessionCallback.onRecorderFailed()
            }
        )
        if (take == null) {
            vibrateAndResetOrientation(0)
            sessionCallback.onRecorderUnavailable()
            return
        }
        session = take
    }

    private fun completeRecording() {
        val take = session ?: return
        session = null
        take.finish(keep = true)?.let(sessionCallback::sendVoiceNote)
    }

    private fun discardRecording() {
        val take = session ?: return
        session = null
        take.finish(keep = false)
        sessionCallback.cancelEphemeralVoiceNoteDraft(take.file)
    }

    private fun saveDraft() {
        val take = session ?: return
        session = null
        take.finish(keep = true)?.let(sessionCallback::saveEphemeralVoiceNoteDraft)
    }

    private fun requestAudioFocus() {
        val manager = ContextCompat.getSystemService(context, AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                // Something else grabbed focus (phone call, etc): finish gracefully so the user keeps the partial take.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) finishRecording()
            }
            .build()
        audioFocus = request
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun releaseAudioFocus() {
        val request = audioFocus ?: return
        audioFocus = null
        ContextCompat.getSystemService(context, AudioManager::class.java)?.let { runCatching { it.abandonAudioFocusRequest(request) } }
    }

    interface SessionCallback {
        fun onSessionWillBegin()
        fun sendVoiceNote(file: File)
        fun cancelEphemeralVoiceNoteDraft(file: File)
        fun saveEphemeralVoiceNoteDraft(file: File)

        /** The recorder could not start (the microphone taken, or no encoder). */
        fun onRecorderUnavailable()

        /** The recorder reported an error while it ran: the take is gone. */
        fun onRecorderFailed()

        /** The carrier's size for a picture message was reached: the take is kept. */
        fun onMaximumSize()
    }
}

/** The activity under a context, when there is one (a Compose context is often a wrapper of it). */
private fun activityOf(context: Context): Activity? {
    var c = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
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
 * time it is shown, by the Language app; three lines, the rest on a tap.
 * Without Language on the phone, nothing shows.
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

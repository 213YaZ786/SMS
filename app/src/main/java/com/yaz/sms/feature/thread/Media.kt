package com.yaz.sms.feature.thread

import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import com.yaz.sms.ui.component.SafePlayer
import com.yaz.sms.ui.component.GlassVideo
import com.yaz.sms.ui.component.DarkGround
import com.yaz.sms.core.security.SafeImages
import com.yaz.sms.core.security.MediaCheck
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.yaz.sms.core.mms.ContentType
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.core.mms.mediaWord
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * A picture, video or sound of a message. A picture shows itself and
 * opens large in a tap, a film plays in the app's own player, a sound in
 * its line; another file opens in the app chosen for it. What a part
 * really is comes from its first bytes, not from the type it was sent
 * with. From an unknown number ([held]) nothing is read before a tap.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaTile(part: MmsPart, mine: Boolean, held: Boolean = false, onMore: (() -> Unit)? = null) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var video by remember { mutableStateOf(false) }
    // Keyed on [held] too: the contacts load after the first frame, and a contact's media must not stay held.
    var shown by remember(part.uri, held) { mutableStateOf(!held) }
    val kind by rememberKind(part.uri)
    val image = isPicture(part, kind)
    // A sound goes to Android's player only once its first bytes say it is one (a 3GP or MP4 holds sounds too).
    val sound = ContentType.isAudioType(part.contentType) && (kind == MediaCheck.Kind.AUDIO || kind == MediaCheck.Kind.VIDEO)
    if (!shown && sound) {
        HeldTile(
            "Sound from an unknown number, tap to load",
            AppIcons.Mic
        ) { shown = true }
    } else if (image || isFilm(part, kind)) {
        // A picture or a film alone, if one is handed here: the conversation lays them out in the message's
        // bubble (MediaGallery); this is the same tile in a box of its own.
        Box(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(20.dp))) {
            MediaCell(part, kind, held, single = true, onMore = onMore)
        }
    } else if (sound) {
        VoiceTile(part, mine)
    } else {
        ZoneSurface(
            shape = RoundedCornerShape(20.dp),
            accent = mine,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).combinedClickable(
                onClick = {
                    haptics.tick()
                    if (ContentType.isVideoType(part.contentType) && kind == MediaCheck.Kind.VIDEO) video = true else openOutside(context, part)
                },
                onLongClick = onMore,
                onLongClickLabel = if (onMore != null) "More" else null
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Icon(
                    when {
                        part.contentType.contains("vcard") -> AppIcons.ContactPage
                        ContentType.isVideoType(part.contentType) || ContentType.isAudioType(part.contentType) -> AppIcons.Play
                        else -> AppIcons.AttachFile
                    },
                    contentDescription = null
                )
                Spacer(Modifier.width(10.dp))
                // A contact card shows the person's name, read from the card itself.
                val person by androidx.compose.runtime.produceState<String?>(null, part.uri) {
                    if (part.contentType.contains("vcard")) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(part.uri)?.bufferedReader()?.use { r ->
                                r.lineSequence().take(200).firstOrNull { it.startsWith("FN:") || it.startsWith("FN;") }?.substringAfter(':')?.trim()?.take(60)
                            }
                        }.getOrNull()
                    }
                }
                Text(person ?: part.name ?: mediaWord(part.contentType), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (video) VideoViewer(part.uri, onClose = { video = false }, part = part)
    }
}

/** Saves one part in the background and says so. */
internal fun savePart(context: Context, part: MmsPart) {
    Thread {
        val n = com.yaz.sms.core.mms.SaveParts.save(context, listOf(part))
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, if (n > 0) "Saved" else "Could not save", android.widget.Toast.LENGTH_SHORT).show()
        }
    }.start()
}

/**
 * A picture on the whole screen, over a dark ground: pinch or double tap to
 * zoom, pan while zoomed; at rest, drawn down (or up) it follows the finger
 * and closes past a threshold.
 */
@Composable
internal fun PictureViewer(uri: Uri, onClose: () -> Unit, part: MmsPart? = null) {
    val still by rememberPicture(uri, 2048)
    val moving by rememberGifFrame(uri, 1440, part?.contentType?.contains("gif", ignoreCase = true) == true)
    val image = moving ?: still
    val context = LocalContext.current
    val haptics = com.yaz.sms.ui.component.rememberHaptics()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    val appear = remember { androidx.compose.animation.core.Animatable(0.9f) }
    var zoomed by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f)) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val height = constraints.maxHeight.toFloat()
            val gone = (kotlin.math.abs(drag.value) / (height * 0.5f)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f * (1f - gone)))
                    .pointerInput(zoomed) {
                        if (zoomed) return@pointerInput
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (kotlin.math.abs(drag.value) > height * 0.18f) {
                                        haptics.tick()
                                        drag.animateTo(kotlin.math.sign(drag.value) * height, androidx.compose.animation.core.tween(180))
                                        onClose()
                                    } else {
                                        drag.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f))
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f) } }
                        ) { change, dy ->
                            change.consume()
                            scope.launch { drag.snapTo(drag.value + dy) }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                image?.let { picture ->
                    com.yaz.sms.ui.component.Zoomable(
                        modifier = Modifier.graphicsLayer {
                            translationY = drag.value
                            val s = appear.value * (1f - 0.15f * gone)
                            scaleX = s
                            scaleY = s
                        },
                        onZoomChanged = { zoomed = it }
                    ) {
                        Image(picture, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                Box(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) {
                    FloatingAction(AppIcons.Close, "Close", onClose)
                }
                // Saved or handed on from the viewer too, as AOSP Messaging's photo viewer offers.
                if (part != null) Row(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) {
                    FloatingAction(AppIcons.Download, "Save", onClick = { savePart(context, part) })
                    Spacer(Modifier.width(10.dp))
                    FloatingAction(AppIcons.Share, "Share", onClick = { shareOutside(context, part) })
                }
            }
        }
    }
}

/**
 * A picture read in the background, no larger than about [side] pixels,
 * decoded in the isolated decoder: what a message carries is anyone's file.
 */
@Composable
fun rememberPicture(uri: Uri, side: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, uri, side) {
        value = SafeImages.decode(context, uri, side)?.asImageBitmap()
    }
}

/**
 * An animated GIF's frame of the moment: the frames decoded in the isolated
 * DecoderService (SafeImages.frames), each shown for its delay as Glide's
 * GifFrameLoader does (https://github.com/bumptech/glide), and only while the
 * screen is started (repeatOnLifecycle,
 * https://developer.android.com/topic/libraries/architecture/coroutines#restart).
 * Null for a still picture, which then shows rememberPicture's.
 */
@Composable
fun rememberGifFrame(uri: Uri, side: Int, gif: Boolean): State<ImageBitmap?> {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    return produceState<ImageBitmap?>(null, uri, side, gif) {
        if (!gif) return@produceState
        val frames = SafeImages.frames(context, uri, side) ?: return@produceState
        val pictures = frames.pictures.map { it.asImageBitmap() }
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            var i = 0
            while (true) {
                value = pictures[i]
                kotlinx.coroutines.delay(frames.delays[i].toLong())
                i = (i + 1) % pictures.size
            }
        }
    }
}

/**
 * A film's still for its tile, as AOSP Messaging's VideoThumbnailRequest
 * takes it (MediaMetadataRetriever.getFrameAtTime): the frame is decoded by
 * Android's media process, never in the app; asked at the size shown
 * (getScaledFrameAtTime) instead of scaled after.
 */
@Composable
fun rememberVideoFrame(uri: Uri, side: Int): State<ImageBitmap?> {
    val still by rememberVideoStill(uri, side)
    return remember { derivedStateOf { still?.frame } }
}

/** A film's still and its length in milliseconds, each null when Android's media process cannot give it. */
class VideoStill(val frame: ImageBitmap?, val durationMs: Long?)

/**
 * A film's still as [rememberVideoFrame] takes it, with its length from the same MediaMetadataRetriever
 * (extractMetadata METADATA_KEY_DURATION, https://developer.android.com/reference/android/media/MediaMetadataRetriever),
 * for the badge Element X puts on a film (VideoOverlay in TimelineItemGalleryView.kt,
 * https://github.com/element-hq/element-x-android). Capped at 100 hours.
 */
@Composable
fun rememberVideoStill(uri: Uri, side: Int): State<VideoStill?> {
    val context = LocalContext.current
    return produceState<VideoStill?>(null, uri, side) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, uri)
                    VideoStill(
                        retriever.getScaledFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side)?.asImageBitmap(),
                        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceIn(0L, 360_000_000L)
                    )
                }
            }.getOrNull()
        }
    }
}

/**
 * What each part really is, read from their first bytes, all together; null while they are read.
 * The same MediaCheck as [rememberKind], for a message's parts in one go.
 */
@Composable
fun rememberKinds(uris: List<Uri>): State<List<MediaCheck.Kind?>> {
    val context = LocalContext.current
    return produceState<List<MediaCheck.Kind?>>(uris.map { null }, uris) {
        // A text message has no part: nothing to read.
        if (uris.isNotEmpty()) value = withContext(Dispatchers.IO) { uris.map { MediaCheck.kind(context, it) } }
    }
}

/** What a part really is, read from its first bytes; null while it is read. */
@Composable
fun rememberKind(uri: Uri): State<MediaCheck.Kind?> {
    val context = LocalContext.current
    return produceState<MediaCheck.Kind?>(null, uri) {
        value = withContext(Dispatchers.IO) { MediaCheck.kind(context, uri) }
    }
}

/**
 * A film on the whole screen, in the app's own player: never handed to
 * another app, read by Media3 limited to the formats of messages.
 */
@Composable
internal fun VideoViewer(uri: Uri, onClose: () -> Unit, part: MmsPart? = null) {
    val context = LocalContext.current
    var muted by remember { mutableStateOf(false) }
    val player = remember(uri) {
        SafePlayer.build(context).apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    LifecycleResumeEffect(player) { onPauseOrDispose { player.pause() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DarkGround {
            Box(Modifier.fillMaxSize()) {
                GlassVideo(player = player, muted = muted, onMutedChange = { muted = it })
                Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)) { FloatingAction(AppIcons.Close, "Close", onClose) }
                if (part != null) Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) {
                    FloatingAction(AppIcons.Download, "Save", onClick = { savePart(context, part) })
                    Spacer(Modifier.width(10.dp))
                    FloatingAction(AppIcons.Share, "Share", onClick = { shareOutside(context, part) })
                }
            }
        }
    }
}

/** What a part from an unknown number waits behind: a tap, before anything reads it. */
@Composable
private fun HeldTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onShow: () -> Unit) {
    val haptics = rememberHaptics()
    ZoneSurface(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable {
            haptics.tick()
            onShow()
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * A copy of [part] another app may read once, with the type it goes out
 * under (the sender's only when the bytes agree, MediaCheck.typeFor), or
 * null for an app hidden in a message.
 */
private fun sharedCopy(context: Context, part: MmsPart): Pair<Uri, String>? = runCatching {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val type = MediaCheck.typeFor(context, part.uri, com.yaz.sms.core.mms.MediaPrivacy.safeType(part.contentType))
    val file = File(dir, "media." + com.yaz.sms.core.mms.MediaPrivacy.extension(type))
    context.contentResolver.openInputStream(part.uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
    // An app hidden in a message is never handed to the installer.
    if (com.yaz.sms.core.mms.MediaPrivacy.isApp(part.contentType, file)) {
        file.delete()
        android.widget.Toast.makeText(context, "Apps can't be opened from a message.", android.widget.Toast.LENGTH_LONG).show()
        return null
    }
    FileProvider.getUriForFile(context, "${context.packageName}.mms", file) to type
}.getOrNull()

/**
 * A file opened in the app chosen for it: a copy shared for that one
 * opening. With no app for it, AOSP Messaging's answer
 * (UiUtils, activity_not_found_message): a toast, never a silent tap.
 */
internal fun openOutside(context: Context, part: MmsPart) {
    val (shared, type) = sharedCopy(context, part) ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(shared, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        android.widget.Toast.makeText(context, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** A part offered to the app the user picks, as AOSP Messaging shares one (photo_view_menu action_share). */
internal fun shareOutside(context: Context, part: MmsPart) {
    val (shared, type) = sharedCopy(context, part) ?: return
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(type)
                    .putExtra(Intent.EXTRA_STREAM, shared)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                null
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

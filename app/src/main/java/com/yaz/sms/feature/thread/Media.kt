package com.yaz.sms.feature.thread

import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.CompositionLocalProvider
import com.yaz.sms.ui.component.LocalViewerChrome
import com.yaz.sms.ui.component.ViewerChromeEffect
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
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
 *
 * A file, a card or a sound is drawn inside the message's bubble, with no surface of its own, as Element X's
 * TimelineItemAttachmentView (https://github.com/element-hq/element-x-android, AGPL-3.0,
 * features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/components/event/
 * TimelineItemAttachmentView.kt, used by TimelineItemFileView and TimelineItemAudioView): a 36 dp box with the
 * icon, 8 dp apart from the name (two lines at most) over a line of detail. [interactive] false draws a copy
 * for the menu to lift, a tile that opens nothing.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaTile(part: MmsPart, mine: Boolean, held: Boolean = false, onMore: (() -> Unit)? = null, interactive: Boolean = true) {
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
            AppIcons.Mic,
            interactive
        ) { shown = true }
    } else if (image || isFilm(part, kind)) {
        // A picture or a film alone, if one is handed here: the conversation lays them out in the message's
        // bubble (MediaGallery); this is the same tile in a box of its own.
        // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
        Box(Modifier.widthIn(max = 280.dp).clip(ShapeDefaults.Large)) {
            MediaCell(part, kind, held, single = true, onMore = onMore)
        }
    } else if (sound) {
        VoiceTile(part, mine, inBubble = true)
    } else {
        // An iPhone's shared location is a place card, opening the map app; read only once shown (so not
        // from a number held), a few kilobytes at most. A file that holds no place stays a tile.
        val located = isLocationCard(part)
        val spot by androidx.compose.runtime.produceState<com.yaz.sms.core.sms.Places.Place?>(null, part.uri, shown) {
            if (located && shown) value = withContext(Dispatchers.IO) { readLocation(context, part.uri) }
        }
        val place = spot
        if (place != null) PlaceCard(place) else {
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
        val shape = ShapeDefaults.Large
        AttachmentRow(
            icon = when {
                part.contentType.contains("vcard") -> AppIcons.ContactPage
                ContentType.isVideoType(part.contentType) || ContentType.isAudioType(part.contentType) -> AppIcons.Play
                else -> AppIcons.AttachFile
            },
            name = person ?: part.name ?: mediaWord(part.contentType),
            detail = attachmentDetail(part),
            modifier = if (!interactive) Modifier else Modifier.clip(shape).combinedClickable(
                onClick = {
                    haptics.tick()
                    if (located && !shown) shown = true
                    else if (ContentType.isVideoType(part.contentType) && kind == MediaCheck.Kind.VIDEO) video = true else openOutside(context, part)
                },
                onLongClick = onMore,
                onLongClickLabel = if (onMore != null) "More" else null
            )
        )
        if (video) VideoViewer(part.uri, onClose = { video = false }, part = part)
        }
    }
}

/**
 * An iPhone's shared location: its card, type text/x-vlocation, named `Current Location.loc.vcf`. AirMessage
 * Android tells a location card from a contact card by the same type and name (airmessage-android, Apache-2.0,
 * https://github.com/airmessage/airmessage-android/blob/0758ea0273f942881fd8de605aa349b40a1c404c/app/src/main/java/me/tagavari/airmessage/messaging/FileDisplayMetadata.kt,
 * LocationDetailed; the type from iOS's IMFileManager.MIMETypeOfPath).
 */
internal fun isLocationCard(part: MmsPart): Boolean =
    part.contentType.lowercase().contains("x-vlocation") || part.name?.lowercase()?.endsWith(".loc.vcf") == true

/**
 * The place in a location card, read as AirMessage's FileDisplayMetadata.LocationDetailed reads its file
 * (https://github.com/airmessage/airmessage-android/blob/0758ea0273f942881fd8de605aa349b40a1c404c/app/src/main/java/me/tagavari/airmessage/messaging/FileDisplayMetadata.kt),
 * the first [com.yaz.sms.core.sms.Places.CARD_MAX] bytes of it and no more.
 */
internal fun readLocation(context: Context, uri: Uri): com.yaz.sms.core.sms.Places.Place? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(com.yaz.sms.core.sms.Places.CARD_MAX)
        var n = 0
        while (n < buffer.size) {
            val got = input.read(buffer, n, buffer.size - n)
            if (got < 0) break
            n += got
        }
        com.yaz.sms.core.sms.Places.fromVLocation(String(buffer, 0, n, Charsets.UTF_8))
    }
}.getOrNull()

/**
 * A file or card in a bubble: the icon in a 36 dp box, the name over a line of detail. Element X's
 * TimelineItemAttachmentHeaderView (https://github.com/element-hq/element-x-android, AGPL-3.0,
 * features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/components/event/
 * TimelineItemAttachmentView.kt): box 36 dp of 4 dp corners, 24 dp icon, 8 dp between, the name at most two
 * lines in the body size, the detail one line in the small size and the secondary colour. The box takes the
 * bubble's text colour at a low alpha where Element X paints its canvas colour.
 */
@Composable
private fun AttachmentRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, detail: String?, modifier: Modifier = Modifier) {
    val ink = androidx.compose.material3.LocalContentColor.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(end = 4.dp)) {
        Box(Modifier.size(36.dp).background(ink.copy(alpha = 0.12f), ShapeDefaults.ExtraSmall), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.layout.Column(Modifier.widthIn(max = 240.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.66f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/**
 * The line under a file's name: its kind. Element X's fileExtensionAndSize (the extension, then the size);
 * the size is not read here, since a part from an unknown number is not opened before a tap. A part whose
 * bytes disagree with the type it was sent as (a "photo" that is no picture) is a plain file.
 */
private fun attachmentDetail(part: MmsPart): String {
    val type = part.contentType.lowercase()
    return when {
        type.contains("vcard") -> "Contact card"
        ContentType.isImageType(type) || ContentType.isVideoType(type) || ContentType.isAudioType(type) -> "File"
        else -> (part.name?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 } ?: type.substringAfter('/', "").substringBefore(';').takeIf { it.isNotEmpty() && it.length <= 12 })
            ?.uppercase() ?: "File"
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // A tap on the picture hides Close, Save and Share and the system bars, the next brings them back
    // (the player kit's ViewerChrome: ReFra's and Glimpse's viewers, Google Photos).
    val chrome = remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ViewerChromeEffect(chrome.value)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val height = constraints.maxHeight.toFloat()
            val gone = (kotlin.math.abs(drag.value) / (height * 0.5f)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f * (1f - gone)))
                    // Where the picture is not (still loading) a tap flips the chrome too; on the picture Zoomable takes it.
                    .pointerInput(chrome) { detectTapGestures(onTap = { chrome.value = !chrome.value }) }
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
                        onZoomChanged = { zoomed = it },
                        onTap = { chrome.value = !chrome.value }
                    ) {
                        Image(picture, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                // The bars may be hidden, so the buttons keep the place they have (statusBarsIgnoringVisibility).
                AnimatedVisibility(chrome.value, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(16.dp)) {
                        FloatingAction(AppIcons.Close, "Close", onClose)
                    }
                }
                // Saved or handed on from the viewer too, as AOSP Messaging's photo viewer offers.
                if (part != null) AnimatedVisibility(chrome.value, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                    Row(Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(16.dp)) {
                        FloatingAction(AppIcons.Download, "Save", onClick = { savePart(context, part) })
                        Spacer(Modifier.width(10.dp))
                        FloatingAction(AppIcons.Share, "Share", onClick = { shareOutside(context, part) })
                    }
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // A tap on the film hides Close, Save, Share, the player's controls and the system bars together (GlassVideo
    // follows LocalViewerChrome), the next brings them back: ReFra's and Glimpse's viewers, Google Photos.
    val chrome = remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ViewerChromeEffect(chrome.value)
        DarkGround {
            CompositionLocalProvider(LocalViewerChrome provides chrome) {
                Box(Modifier.fillMaxSize()) {
                    GlassVideo(player = player, muted = muted, onMutedChange = { muted = it })
                    // The bars may be hidden, so the buttons keep the place they have (statusBarsIgnoringVisibility).
                    AnimatedVisibility(chrome.value, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
                        Box(Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(16.dp)) {
                            FloatingAction(AppIcons.Close, "Close", onClose)
                        }
                    }
                    if (part != null) AnimatedVisibility(chrome.value, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                        Row(Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(16.dp)) {
                            FloatingAction(AppIcons.Download, "Save", onClick = { savePart(context, part) })
                            Spacer(Modifier.width(10.dp))
                            FloatingAction(AppIcons.Share, "Share", onClick = { shareOutside(context, part) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * What a part from an unknown number waits behind: a tap, before anything reads it. In the message's
 * bubble like the file it stands for (AttachmentRow); [interactive] false for the copy the menu lifts.
 */
@Composable
private fun HeldTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, interactive: Boolean, onShow: () -> Unit) {
    val haptics = rememberHaptics()
    val shape = ShapeDefaults.Large
    AttachmentRow(
        icon = icon,
        name = label,
        detail = null,
        modifier = if (!interactive) Modifier else Modifier.clip(shape).clickable {
            haptics.tick()
            onShow()
        }
    )
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

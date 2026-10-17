package com.yaz.sms.feature.thread

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.mms.ContentType
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.core.mms.mediaWord
import com.yaz.sms.core.security.MediaCheck
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons

/*
 * The pictures and films of one message, in the bubble. Ported from Element X, AGPL-3.0
 * (https://github.com/element-hq/element-x-android, features/messages/impl/src/main/kotlin/io/element/android/
 * features/messages/impl/timeline/components/event/): TimelineItemAspectRatioBox.kt, TimelineItemGalleryView.kt
 * (the grids, the "+N" tile, the film's badge), TimelineItemImageView.kt and TimelineItemVideoView.kt
 * (a single picture or film in its aspect ratio box, a play mark on the film). What each tile reads and
 * decodes stays as it was: the isolated decoder, MediaCheck, nothing before a tap from an unknown number.
 */

private const val MIN_HEIGHT_IN_DP = 100
private const val MAX_HEIGHT_IN_DP = 360
private const val DEFAULT_ASPECT_RATIO = 1.33f
private const val MIN_ASPECT_RATIO = 0.001f
private const val MAX_ASPECT_RATIO = 10f

/**
 * A box of the given ratio, 100 to 360 dp high, the ratio kept within reach so no size can break the layout.
 * Element X's TimelineItemAspectRatioBox (event/TimelineItemAspectRatioBox.kt).
 */
@Composable
internal fun MediaAspectBox(
    aspectRatio: Float?,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val safeAspectRatio = aspectRatio?.coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO) ?: DEFAULT_ASPECT_RATIO
    Box(
        modifier = modifier
            .heightIn(min = MIN_HEIGHT_IN_DP.dp, max = MAX_HEIGHT_IN_DP.dp)
            .aspectRatio(safeAspectRatio, false),
        contentAlignment = contentAlignment,
        content = content
    )
}

/** The ratios of the pictures already seen, so a bubble keeps its size when it scrolls back in. */
private val seenRatios = LruCache<String, Float>(256)

/** Element X's grid, in its own units: the gallery 264 wide, the rows 130 and 85 high, 4 between (TimelineItemGalleryView.kt). */
private const val GALLERY_WIDTH = 264f
private const val GRID_SPACING = 4f
private const val TWO_IMAGE_ROW_HEIGHT = 130f
private const val THREE_IMAGE_ROW_HEIGHT = 85f
private const val MAX_TILES = 5

private fun cellRatio(inRow: Int, height: Float) = ((GALLERY_WIDTH - (inRow - 1) * GRID_SPACING) / inRow) / height

/**
 * The media of a message: one in its aspect ratio box; two stacked; three, one wide over two; four or
 * more, two over two or three, the last of the fifth showing "+N" for the rest. Each tile opens the
 * viewer. Element X's TimelineItemGalleryView (event/TimelineItemGalleryView.kt: SingleItemLayout,
 * TwoItemLayout, ThreeItemLayout, FourPlusItemLayout), its heights kept as ratios of the width.
 */
@Composable
internal fun MediaGallery(
    parts: List<MmsPart>,
    kinds: List<MediaCheck.Kind?>,
    held: Boolean,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    interactive: Boolean = true
) {
    if (parts.isEmpty()) return
    if (parts.size == 1) {
        MediaCell(parts[0], kinds.getOrNull(0), held, modifier, single = true, onMore = onMore, interactive = interactive)
        return
    }
    // One tile of the grid: Element X's GalleryItemCell call (https://github.com/element-hq/element-x-android, TimelineItemGalleryView.kt).
    @Composable
    fun Cell(i: Int, m: Modifier, more: Int = 0) =
        MediaCell(parts[i], kinds.getOrNull(i), held, m, more = more, onMore = onMore, interactive = interactive)

    val spacing = GRID_SPACING.dp
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        when (parts.size) {
            2 -> for (i in 0..1) Cell(i, Modifier.fillMaxWidth().aspectRatio(cellRatio(1, TWO_IMAGE_ROW_HEIGHT)))
            3 -> {
                Cell(0, Modifier.fillMaxWidth().aspectRatio(cellRatio(1, TWO_IMAGE_ROW_HEIGHT)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (i in 1..2) Cell(i, Modifier.weight(1f).aspectRatio(cellRatio(2, TWO_IMAGE_ROW_HEIGHT)))
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (i in 0..1) Cell(i, Modifier.weight(1f).aspectRatio(cellRatio(2, TWO_IMAGE_ROW_HEIGHT)))
                }
                val overflow = parts.size > MAX_TILES
                val bottom = if (overflow) 3 else minOf(parts.size - 2, 3)
                val ratio = if (bottom == 3) cellRatio(3, THREE_IMAGE_ROW_HEIGHT) else cellRatio(2, TWO_IMAGE_ROW_HEIGHT)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (i in 0 until bottom) {
                        val last = overflow && i == bottom - 1
                        Cell(2 + i, Modifier.weight(1f).aspectRatio(ratio), if (last) parts.size - MAX_TILES else 0)
                    }
                }
            }
        }
    }
}

/**
 * One picture or film. [single]: in its own aspect ratio box (Element X's TimelineItemImageView and
 * TimelineItemVideoView); else it fills the cell [modifier] gives, cropped (TimelineItemGalleryView's
 * GalleryItemCell). [more]: "+N" over it for the media left out (RemainingCountOverlay).
 *
 * From an unknown number ([held]) a picture waits for a tap before anything reads it, and a film shows
 * no still until it is played.
 */
@Composable
internal fun MediaCell(
    part: MmsPart,
    kind: MediaCheck.Kind?,
    held: Boolean,
    modifier: Modifier = Modifier,
    single: Boolean = false,
    more: Int = 0,
    onMore: (() -> Unit)? = null,
    interactive: Boolean = true
) {
    val key = part.uri.toString()
    var ratio by remember(key) { mutableStateOf<Float?>(seenRatios.get(key)) }
    // The tile itself, in a box of its ratio or in the cell it is given (https://github.com/element-hq/element-x-android,
    // TimelineItemImageView.kt and TimelineItemGalleryView.kt GalleryItemCell).
    @Composable
    fun Inside(fill: Modifier) = CellContent(
        part, kind, held, fill, side = if (single) 720 else 480, more = more, onMore = onMore, interactive = interactive,
        onRatio = { r ->
            if (single && ratio != r) {
                seenRatios.put(key, r)
                ratio = r
            }
        }
    )
    if (single) MediaAspectBox(ratio, modifier) { Inside(Modifier.fillMaxSize()) } else Inside(modifier)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CellContent(
    part: MmsPart,
    kind: MediaCheck.Kind?,
    held: Boolean,
    modifier: Modifier,
    side: Int,
    more: Int,
    onMore: (() -> Unit)?,
    interactive: Boolean,
    onRatio: (Float) -> Unit
) {
    val haptics = rememberHaptics()
    var large by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf(false) }
    // Keyed on [held] too: the contacts load after the first frame, and a contact's media must not stay held.
    var shown by remember(part.uri, held) { mutableStateOf(!held) }
    val film = isFilm(part, kind)
    val scheme = MaterialTheme.colorScheme
    val tap = Modifier.combinedClickable(
        enabled = interactive,
        onClick = {
            haptics.tick()
            when {
                !shown && !film -> shown = true
                film -> video = true
                else -> large = true
            }
        },
        onLongClick = onMore,
        onLongClickLabel = if (onMore != null) "More" else null
    )
    Box(modifier.background(scheme.surfaceVariant.copy(alpha = 0.5f)).then(tap), contentAlignment = Alignment.Center) {
        when {
            // A picture from an unknown number: nothing read before this tap.
            !shown && !film -> HeldFace(
                if (ContentType.isImageType(part.contentType)) AppIcons.Image else AppIcons.AttachFile,
                "Photo from an unknown number, tap to show"
            )
            // A film from an unknown number: its name and a play mark, no still; a tap plays it.
            !shown -> HeldFace(AppIcons.Play, part.name ?: mediaWord(part.contentType), Modifier.semantics { contentDescription = "Video" })
            film -> FilmFace(part, side, onRatio)
            else -> PictureFace(part, side, onRatio)
        }
        if (more > 0) {
            Box(Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Text("+$more", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
    if (large) PictureViewer(part.uri, onClose = { large = false }, part = part)
    if (video) VideoViewer(part.uri, onClose = { video = false }, part = part)
}

/** What a tile from an unknown number shows before the tap: a mark and the words. */
@Composable
private fun HeldFace(icon: androidx.compose.ui.graphics.vector.ImageVector, words: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.padding(8.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(4.dp))
        Text(words, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** A picture, still or moving (the decoder's frames), cropped to its cell. */
@Composable
private fun PictureFace(part: MmsPart, side: Int, onRatio: (Float) -> Unit) {
    val still by rememberPicture(part.uri, side)
    val moving by rememberGifFrame(part.uri, side, part.contentType.contains("gif", ignoreCase = true))
    val picture = moving ?: still
    LaunchedEffect(still) { still?.let { if (it.height > 0) onRatio(it.width.toFloat() / it.height) } }
    if (picture != null) {
        Image(picture, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Icon(AppIcons.Image, contentDescription = null)
    }
}

/**
 * A film's first frame under a play mark, its length on a badge at the start of the bottom (the end is
 * the time's): Element X's VideoOverlay (TimelineItemGalleryView.kt), a pill instead of its fading
 * ground. Named "Video" for TalkBack whether its still came or not.
 */
@Composable
private fun FilmFace(part: MmsPart, side: Int, onRatio: (Float) -> Unit) {
    val still by rememberVideoStill(part.uri, side)
    val frame = still?.frame
    LaunchedEffect(frame) { frame?.let { if (it.height > 0) onRatio(it.width.toFloat() / it.height) } }
    Box(Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = "Video" }, contentAlignment = Alignment.Center) {
        if (frame != null) Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        androidx.compose.material3.Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)) {
            Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.padding(14.dp).size(32.dp))
        }
        still?.durationMs?.let { length ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(AppIcons.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(formatShort(length), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/**
 * A length as minutes:seconds ("0:00", "1:05", "120:00"). Element X's Duration.formatShort
 * (libraries/ui-utils/src/main/kotlin/io/element/android/libraries/ui/utils/time/DurationExt.kt) on milliseconds.
 */
internal fun formatShort(millis: Long): String {
    val total = millis.coerceAtLeast(0) / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** A declared picture that the first bytes did not give the lie to: shown as one (a film dressed as a photo fails to decode and shows its mark). */
internal fun isPicture(part: MmsPart, kind: MediaCheck.Kind?): Boolean =
    ContentType.isImageType(part.contentType) && kind != MediaCheck.Kind.OTHER

/** A declared film the first bytes agree with, or have not yet been read for. */
internal fun isFilm(part: MmsPart, kind: MediaCheck.Kind?): Boolean =
    ContentType.isVideoType(part.contentType) && (kind == null || kind == MediaCheck.Kind.VIDEO)

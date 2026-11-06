package com.yaz.sms.feature.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yaz.sms.core.mms.Attachment
import com.yaz.sms.ui.component.FloatingPane
import androidx.compose.foundation.clickable
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.delay

/** What the + offers to send with the text. */
enum class Drop(val icon: ImageVector, val label: String) {
    EFFECTS(AppIcons.AutoAwesome, "Effects"),
    PHOTOS(AppIcons.Image, "Photos"),
    CAMERA(AppIcons.PhotoCamera, "Camera"),
    FILE(AppIcons.AttachFile, "File"),
    CONTACT(AppIcons.ContactPage, "Contact"),
    PLACE(AppIcons.Place, "Place"),
    LIVE(AppIcons.MyLocation, "Live location"),
    POLL(AppIcons.Poll, "Poll")
}

private val ButtonSize = 52.dp

/**
 * The + of the composer: a tap shows, above it, what can go with a message
 * ([AttachTiles], drawn by the composer); a tap again puts them away.
 * [open] tells the composer to veil its field while they are out.
 */
@Composable
fun AttachArc(open: Boolean, onOpen: (Boolean) -> Unit, drops: List<Drop> = Drop.entries.filter { it != Drop.POLL && it != Drop.LIVE }, onPick: (Drop) -> Unit) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val turn by animateFloatAsState(if (open) 45f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "turn")

    Box {
        // A tap opens the panel of tiles above the message bar, a tap again closes it.
        FloatingPane(
            shape = CircleShape,
            onClick = {
                haptics.tick()
                onOpen(!open)
            },
            modifier = Modifier.size(ButtonSize)
        ) {
            Box(Modifier.size(ButtonSize), contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.Add,
                    contentDescription = if (open) "Close" else "Add a photo, a file or a contact",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.graphicsLayer { rotationZ = turn }
                )
            }
        }
    }
}

/**
 * What the + offers, over the conversation: standalone round buttons of
 * glass, each with its name under it, in rows above the +, popping in one
 * after the other. Drawn in the composer (not a popup) so the glass bends
 * what lies under it.
 */
@Composable
fun AttachTiles(drops: List<Drop>, onPick: (Drop) -> Unit) {
    val haptics = rememberHaptics()
    // Four to a row at most, as even as the count allows (7: 4 and 3, 6: 3 and 3), measured from the names, the
    // font and the width (BalancedFlow, MessageMenu.kt, from PersonActions), each row spread over the whole width.
    BalancedFlow(
        labels = drops.map { it.label },
        style = MaterialTheme.typography.labelMedium,
        floor = 64.dp,
        gap = 10.dp,
        modifier = Modifier.padding(bottom = 10.dp),
        fill = true
    ) { i, tileModifier ->
        val drop = drops[i]
        Tile(drop, order = i, tileModifier) {
            haptics.firm()
            onPick(drop)
        }
    }
}

/** One of them: a round of glass with the icon, its name under it; pops in a little after the one before. */
@Composable
private fun Tile(drop: Drop, order: Int, modifier: Modifier, onClick: () -> Unit) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(35L * order)
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
    }
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.graphicsLayer {
            scaleX = 0.5f + 0.5f * pop.value
            scaleY = 0.5f + 0.5f * pop.value
            alpha = pop.value.coerceIn(0f, 1f)
            translationY = (1f - pop.value) * 30f
        }
    ) {
        FloatingPane(shape = CircleShape, onClick = onClick, modifier = Modifier.size(58.dp)) {
            Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                Icon(drop.icon, contentDescription = drop.label, tint = MaterialTheme.colorScheme.primary)
            }
        }
        // The name as plain text under its round (the user, 2026-10-30), as the labels under Dialer's call
        // buttons; a tap on it does what its icon does, without a pane of its own. A halo in the page's colour
        // keeps it readable over a bubble behind it (TextStyle shadow, see haloShadow in MessageMenu.kt).
        TileLabel(
            drop.label,
            MaterialTheme.typography.labelMedium,
            androidx.compose.material3.LocalContentColor.current,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable(interactionSource = null, indication = null, onClick = onClick),
            halo = true
        )
    }
}

// What goes in the message, kept as AOSP Messaging's DraftMessageData keeps its attachments:
// https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/data/DraftMessageData.java#addOneAttachmentNoNotify
// not ported: addAttachment (isSinglePartOnly clears the others; none of our attachments stands alone),
// addPendingAttachment and addOnePendingAttachmentNoNotify (nothing is pending: a pick is a Uri at once),
// hasPendingAttachments, removeExistingAttachments, destroyAsync (the files are the cache's, not ours to delete).

/** DraftMessageData.getAttachmentLimit: how many the message holds (AOSP's default is 10; this app keeps 5). */
internal fun getAttachmentLimit(): Int = 5

/** MmsUtils.MAX_VIDEO_ATTACHMENT_COUNT of AOSP's sms/MmsUtils.java: one film in a picture message. */
internal const val MAX_VIDEO_ATTACHMENT_COUNT = 1

/** What is said when the limit is reached, AOSP's onDraftAttachmentLimitReached. */
internal const val LIMIT_REACHED_SAID = "Up to 5 at a time"

/** What is said when a second film is refused (DraftMessageData.checkDraftForSend, at pick time here). */
internal const val VIDEO_LIMIT_SAID = "Only one video in a message"

/** DraftMessageData.getAttachmentCount. */
internal fun getAttachmentCount(mAttachments: List<Attachment>): Int = mAttachments.size

/** DraftMessageData.getVideoAttachmentCount. */
internal fun getVideoAttachmentCount(mAttachments: List<Attachment>): Int =
    mAttachments.count { it.contentType.startsWith("video/", ignoreCase = true) }

/** DraftMessageData.containsAttachment. */
internal fun containsAttachment(mAttachments: List<Attachment>, contentUri: android.net.Uri): Boolean =
    mAttachments.any { it.uri == contentUri }

/** DraftMessageData.removeAttachment. */
internal fun removeAttachment(mAttachments: MutableList<Attachment>, attachment: Attachment) {
    val existingAttachment = mAttachments.firstOrNull { it.uri == attachment.uri } ?: return
    mAttachments.remove(existingAttachment)
}

/** How many more can come in before the limit. */
internal fun attachmentRoom(mAttachments: List<Attachment>): Int = (getAttachmentLimit() - getAttachmentCount(mAttachments)).coerceAtLeast(0)

/**
 * Tries to add one attachment, guarding against duplicates and going over the limit; [oneVideoOnly] is true outside
 * the encrypted chat, where a picture message holds one film. Returns what to say when it was refused (the limit or
 * the second film), null when it came in. A duplicate replaces the earlier one in its place, as AOSP does.
 */
internal fun addOneAttachmentNoNotify(mAttachments: MutableList<Attachment>, attachment: Attachment, oneVideoOnly: Boolean): String? {
    // Check duplication.
    val existingAttachment = mAttachments.indexOfFirst { it.uri == attachment.uri }
    if (existingAttachment >= 0) {
        mAttachments[existingAttachment] = attachment
        return null
    }
    val reachedLimit = getAttachmentCount(mAttachments) >= getAttachmentLimit()
    if (reachedLimit) {
        // Never go over the limit.
        return LIMIT_REACHED_SAID
    }
    if (oneVideoOnly && attachment.contentType.startsWith("video/", ignoreCase = true) &&
        getVideoAttachmentCount(mAttachments) >= MAX_VIDEO_ATTACHMENT_COUNT
    ) return VIDEO_LIMIT_SAID
    mAttachments.add(attachment)
    return null
}

// https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/util/UriUtil.java#getContentSize
// https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/data/DraftMessageData.java#getIsMessageOverLimit
// not ported: UriUtil's isLocalResourceUri (any Uri the pickers returned is read here), getMediaDurationMs (the sender
// reads the length), persistContent and copyContent (nothing is copied at pick time), and DraftMessageData's
// CheckDraftForSendTask (the sender checks again; this is the same test made when the file is picked).

/**
 * UriUtil.getContentSize: the size in bytes of what [uri] holds, 0 when it cannot be told. The provider's own
 * OpenableColumns.SIZE first (no open needed), else the file descriptor's stat size as AOSP reads it.
 */
internal fun getContentSize(context: Context, uri: Uri): Long {
    val fromColumn = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L
    if (fromColumn > 0) return fromColumn
    return runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> Math.max(pfd.statSize, 0) }
    }.getOrNull() ?: 0L
}

/**
 * DraftMessageData.getIsMessageOverLimit: the size the attachments could be made as small as before sending, added
 * up, against the carrier's [maxMessageSize]. A picture is shrunk and a film cut down by the sender, so these count
 * for nothing here; a sound, a file or a card goes as it is, so it counts whole (getMinimumSizeInBytesForSending).
 */
internal fun getIsMessageOverLimit(context: Context, mAttachmentsCopy: List<Attachment>, maxMessageSize: Int): Boolean {
    if (mAttachmentsCopy.size > getAttachmentLimit()) {
        return true
    }
    // Aggregate the size from all the attachments.
    var totalSize = 0L
    for (attachment in mAttachmentsCopy) {
        totalSize += getMinimumSizeInBytesForSending(context, attachment)
    }
    return totalSize > maxMessageSize
}

/** MessagePartData.getMinimumSizeInBytesForSending: nothing for what the sender shrinks, the whole size otherwise. */
internal fun getMinimumSizeInBytesForSending(context: Context, attachment: Attachment): Long =
    if (attachment.contentType.startsWith("image/") || attachment.contentType.startsWith("video/")) 0L
    else getContentSize(context, attachment.uri)

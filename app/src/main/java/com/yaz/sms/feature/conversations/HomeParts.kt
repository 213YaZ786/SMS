package com.yaz.sms.feature.conversations

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons

internal val LINE_WIDTH = 640.dp

/** A section's name above its conversations. */
@Composable
internal fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().padding(start = 8.dp, top = 8.dp)
    )
}

/**
 * The newest code received, on top of everything for ten minutes: its
 * digits large, a tap copies them and the chip says so.
 */
@Composable
internal fun CodeChip(c: Conversation, code: String, from: String?) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var copied by remember(code) { mutableStateOf(false) }
    // A new code unfolds from the top with a tick.
    val unfold = remember(code) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(code) {
        haptics.tick()
        unfold.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f))
    }
    val pop by animateFloatAsState(if (copied) 1.04f else 1f, spring(dampingRatio = 0.4f, stiffness = 600f), label = "pop")
    // Corners from Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
    val shape = ShapeDefaults.ExtraLarge
    ZoneSurface(
        shape = shape,
        modifier = Modifier
            .widthIn(max = LINE_WIDTH)
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pop
                scaleY = pop * (0.3f + 0.7f * unfold.value)
                alpha = unfold.value.coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
            .clip(shape)
            .clickable {
                haptics.done()
                val clip = ClipData.newPlainText("Code", code).apply {
                    description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                }
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                copied = true
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Code from ${from ?: c.address}, ${minutesAgo(c.date)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RollingCode(code.chunked(3).joinToString(" "))
            }
            ZoneSurface(shape = CircleShape, accent = true) {
                AnimatedContent(copied, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "copied") { done ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Icon(if (done) AppIcons.Done else AppIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (done) "Copied" else "Copy", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private fun minutesAgo(date: Long): String {
    val m = ((System.currentTimeMillis() - date) / 60_000).coerceAtLeast(0)
    return if (m < 1) "now" else "$m min"
}

/**
 * Every service in one pane with two more under it, a stack: banks,
 * deliveries, codes. A tap opens it below, another folds it back.
 */
@Composable
internal fun ServicesStack(services: List<Conversation>, open: Boolean, onToggle: () -> Unit) {
    val haptics = rememberHaptics()
    val unread = services.sumOf { it.unread }
    val newest = services.maxByOrNull { it.date }
    val spread by animateFloatAsState(if (open) 0f else 1f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "spread")
    Box(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        // The panes underneath, seen only while folded.
        for (k in 2 downTo 1) {
            ZoneSurface(
                shape = ShapeDefaults.ExtraLarge,
                modifier = Modifier
                    .matchParentSizeOf()
                    .padding(horizontal = (10 * k).dp)
                    .offset(y = (6 * k * spread).dp)
                    .graphicsLayer { alpha = 0.55f * spread }
            ) { Box(Modifier.height(60.dp)) }
        }
        val shape = ShapeDefaults.ExtraLarge
        ZoneSurface(
            shape = shape,
            modifier = Modifier.fillMaxWidth().clip(shape).clickable {
                haptics.toggle(!open)
                onToggle()
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Services", style = MaterialTheme.typography.titleMedium, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal)
                    Text(
                        newest?.let { "${it.address}: ${it.snippet}" } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ZoneSurface(shape = CircleShape, accent = unread > 0) {
                    Text(
                        if (unread > 0) "$unread new" else "${services.size}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/** Fills the box its siblings set, under them. */
private fun Modifier.matchParentSizeOf(): Modifier = this.fillMaxWidth().height(64.dp)

/** What a pinned avatar shows of a conversation: its name, photo and look. */
internal class PinnedFace(
    name: String,
    photo: String?,
    look: com.yaz.sms.core.dial.ContactLook.Look?,
    /** A group's people, for the cluster of their faces (GroupFace); empty for a person. */
    members: List<PinnedFace> = emptyList()
) {
    val name: String = name
    val photo: String? = photo
    val look: com.yaz.sms.core.dial.ContactLook.Look? = look
    val members: List<PinnedFace> = members
}

/** iOS Messages keeps at most nine pinned conversations as avatars. */
internal const val PINNED_FACES_MAX = 9

/**
 * The pinned conversations as round avatars wrapping over the top of the list, as iOS Messages shows
 * its pinned conversations: the name under the avatar, and an unread one carries its count on the avatar
 * and its last message in a small bubble of glass above it. Wrapped in a FlowRow, never scrolled sideways
 * (Jetpack Compose FlowRow, androidx/compose/foundation/foundation-layout/.../FlowLayout.kt,
 * https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/FlowLayout.kt:
 * maxItemsInEachRow and itemVerticalAlignment), three to a row on a phone, more as the window widens.
 * The press sink and the count badge are this screen's own Face and ConversationLine. A tap opens, a long
 * press glances (the same Peek as a row); [selected] is null out of the multi select.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PinnedFaces(
    pinned: List<Conversation>,
    face: (Conversation) -> PinnedFace,
    selected: (Conversation) -> Boolean?,
    onOpen: (Conversation) -> Unit,
    onPeek: (Conversation) -> Unit
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth()) {
        val gap = 8.dp
        val columns = ((maxWidth + gap) / (104.dp + gap)).toInt().coerceIn(3, PINNED_FACES_MAX)
        val cell = (maxWidth - gap * (columns - 1)) / columns
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.Bottom,
            maxItemsInEachRow = columns,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)
        ) {
            pinned.take(PINNED_FACES_MAX).forEach { c ->
                key(c.threadId) { PinnedFaceItem(c, face(c), selected(c), Modifier.width(cell), { onOpen(c) }, { onPeek(c) }) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PinnedFaceItem(c: Conversation, face: PinnedFace, selected: Boolean?, modifier: Modifier, onOpen: () -> Unit, onPeek: () -> Unit) {
    val haptics = rememberHaptics()
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "sink")
    val unread = c.unread > 0
    val last = if (unread && !c.fromMe) com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(c.snippet))).trim() else ""
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .graphicsLayer {
                scaleX = sink
                scaleY = sink
            }
            .clip(ShapeDefaults.Large)
            .combinedClickable(
                interactionSource = press,
                indication = null,
                onClickLabel = "Open",
                onLongClickLabel = "Glance",
                onClick = {
                    haptics.tick()
                    onOpen()
                },
                onLongClick = {
                    haptics.firm()
                    onPeek()
                }
            )
    ) {
        // The last message of an unread conversation, two lines at most, above the avatar.
        if (last.isNotEmpty()) {
            ZoneSurface(shape = ShapeDefaults.Large, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    last,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Box {
            // Taken in the multi select: the check stands where the face was.
            if (selected == true) Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(AppIcons.Done, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary)
            }
            else FaceAvatar(face.name, face.photo, face.look, face.members, 64.dp)
            if (unread) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-2).dp)
                        // A round of 20 dp that grows with its text into a pill: a minimum, never a size.
                        .defaultMinSize(20.dp, 20.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(horizontal = 5.dp)
                ) {
                    Text(
                        if (c.unread > 9) "9+" else c.unread.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        Text(
            face.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** The digits roll up into place one after the other, as Dialer's timer does. */
@Composable
private fun RollingCode(text: String) {
    Row {
        text.forEachIndexed { i, c ->
            val roll = remember(text) { androidx.compose.animation.core.Animatable(0f) }
            androidx.compose.runtime.LaunchedEffect(text) {
                kotlinx.coroutines.delay(i * 45L)
                roll.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f))
            }
            Text(
                c.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - roll.value) * 40f
                    alpha = roll.value.coerceIn(0f, 1f)
                }
            )
        }
    }
}

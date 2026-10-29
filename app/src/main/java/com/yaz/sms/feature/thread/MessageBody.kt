package com.yaz.sms.feature.thread

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.core.security.MediaCheck

/**
 * What a message's bubble holds, with its time (and for one's own, its status) in it. Laid out as
 * Element X's TimelineItemEventRow (https://github.com/element-hq/element-x-android, AGPL-3.0,
 * features/messages/impl/.../timeline/components/TimelineItemEventRow.kt, CommonLayout and the
 * timestampPosition choice per content):
 * - a poll: the footer under it (TimestampPosition.Below);
 * - one picture or film without words: the footer on a dark pill over it (Overlay);
 * - several without words: the grid, the footer under it (Below, as for its gallery);
 * - media with words: the media on top, the words under it in the same glass, the footer on their
 *   last line when it fits (Aligned);
 * - a file, a card or a sound ([tiles]): inside the bubble above the words, as Element X's
 *   TimelineItemFileView and TimelineItemAudioView sit in the event bubble with their caption under
 *   (event/TimelineItemEventContentView.kt: the attachment, then CaptionView, in one column); the footer
 *   on the last line of the words when there are some, else under the tile;
 * - words alone: the footer on the last line when it fits, else under them (Aligned); under them
 *   when the words run both ways, from Signal's V2FooterPositionDelegate
 *   (https://github.com/signalapp/Signal-Android, AGPL-3.0).
 *
 * [onMore] is the long press of the media tiles (the message's menu); [interactive] false draws a copy
 * for the menu to lift, tiles that open nothing.
 */
@Composable
internal fun MessageBody(
    words: AnnotatedString,
    plain: String,
    blurred: Boolean,
    poll: (@Composable () -> Unit)?,
    visual: List<MmsPart>,
    kinds: List<MediaCheck.Kind?>,
    held: Boolean,
    footer: @Composable (mismatched: Boolean, onMedia: Boolean) -> Unit,
    onMore: (() -> Unit)?,
    interactive: Boolean = true,
    /** The parts that are no picture or film (files, cards, sounds), drawn in the bubble above the words. */
    tiles: List<MmsPart> = emptyList(),
    mine: Boolean = false,
    /** A picture message's subject, already cleansed (MmsSubject): bold at the head of the words, above them. */
    subject: String? = null
) {
    val caption = words.isNotEmpty()
    val head = subject?.takeIf { it.isNotBlank() }
    val mixed = remember(plain) { hasMixedTextDirection(plain) }
    val gallery = @Composable { modifier: Modifier ->
        MediaGallery(visual, kinds, held, modifier, onMore = onMore, interactive = interactive)
    }
    when {
        poll != null -> WithFooter(FooterAt.Below, { footer(it, false) }) { poll() }
        tiles.isNotEmpty() ->
            WithFooter(if (!caption || mixed) FooterAt.Below else FooterAt.Aligned, { footer(it, false) }, words = plain) { onLayout ->
                Column {
                    // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
                    if (visual.isNotEmpty()) Box(Modifier.padding(4.dp).clip(ShapeDefaults.Large).align(Alignment.CenterHorizontally)) { gallery(Modifier) }
                    tiles.forEach { part ->
                        Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = if (caption || head != null) 0.dp else 2.dp)) {
                            MediaTile(part, mine, held = held, onMore = onMore, interactive = interactive)
                        }
                    }
                    if (head != null) SubjectLine(head, 4.dp, bottom = if (caption) 0.dp else 6.dp)
                    if (caption) Words(words, blurred, if (head != null) 2.dp else 4.dp, onLayout)
                }
            }
        visual.isNotEmpty() && !caption && head == null && visual.size == 1 ->
            WithFooter(FooterAt.Overlay, { footer(it, true) }) { gallery(Modifier) }
        visual.isNotEmpty() && !caption && head == null ->
            WithFooter(FooterAt.Below, { footer(it, false) }) {
                Box(Modifier.padding(4.dp).clip(ShapeDefaults.Large)) { gallery(Modifier) }
            }
        visual.isNotEmpty() ->
            WithFooter(if (!caption || mixed) FooterAt.Below else FooterAt.Aligned, { footer(it, false) }, words = plain) { onLayout ->
                Column {
                    Box(Modifier.padding(4.dp).clip(ShapeDefaults.Large).align(Alignment.CenterHorizontally)) { gallery(Modifier) }
                    if (head != null) SubjectLine(head, 4.dp, bottom = if (caption) 0.dp else 6.dp)
                    if (caption) Words(words, blurred, if (head != null) 2.dp else 4.dp, onLayout)
                }
            }
        head != null ->
            WithFooter(if (!caption || mixed) FooterAt.Below else FooterAt.Aligned, { footer(it, false) }, words = plain) { onLayout ->
                Column {
                    SubjectLine(head, 10.dp, bottom = if (caption) 0.dp else 6.dp)
                    if (caption) Words(words, blurred, 2.dp, onLayout)
                }
            }
        else ->
            WithFooter(if (mixed) FooterAt.Below else FooterAt.Aligned, { footer(it, false) }, words = plain) { onLayout ->
                Words(words, blurred, 10.dp, onLayout)
            }
    }
}

/**
 * A picture message's subject, bold above the words, in the bubble's text colour. AOSP Messaging's
 * ConversationMessageView.updateMessageSubject draws it over the message text in its bubble (the cleansed
 * subject, shown only when there is one; https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/ui/conversation/ConversationMessageView.java);
 * the weight is iOS Messages' (its subject is the bold first line), AOSP draws it plain behind a "Subject:" label.
 */
@Composable
private fun SubjectLine(subject: String, top: Dp, bottom: Dp) {
    Text(
        subject,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = top, bottom = bottom)
    )
}

/**
 * The words of a message. An edit changes them in a soft blur, not at a stroke; the last line is
 * measured for the footer (Element X's ContentAvoidingLayout.measureLastTextLine, as fixed in
 * [measureLastLine]). Padded 16 dp at the sides, the bottom short so the footer's line sits level
 * with the last line of words.
 */
@Composable
private fun Words(text: AnnotatedString, blurred: Boolean, top: Dp, onLayout: (ContentAvoidingLayoutData) -> Unit) {
    val measure = measureLastLine(onLayout, extraStart = 16.dp, extraEnd = 16.dp)
    AnimatedContent(
        text,
        transitionSpec = {
            fadeIn(tween(420, delayMillis = 120)) togetherWith fadeOut(tween(260))
        },
        label = "words"
    ) { shown ->
        val blur by transition.animateDp(label = "blur") { if (it == androidx.compose.animation.EnterExitState.Visible) 0.dp else 6.dp }
        Text(
            shown,
            style = MaterialTheme.typography.bodyLarge,
            onTextLayout = measure,
            modifier = Modifier.blur(if (blurred) 14.dp else blur).padding(start = 16.dp, end = 16.dp, top = top, bottom = 6.dp)
        )
    }
}

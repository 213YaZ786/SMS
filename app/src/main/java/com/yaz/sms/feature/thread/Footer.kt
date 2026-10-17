package com.yaz.sms.feature.thread

import android.text.TextDirectionHeuristics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yaz.sms.ui.icon.AppIcons

/**
 * Where a message's time goes, Element X's TimestampPosition
 * (https://github.com/element-hq/element-x-android, AGPL-3.0,
 * features/messages/impl/.../timeline/components/TimestampPosition.kt): over the content
 * (a picture), on its last line when it fits (words), always under it (a poll, a gallery).
 */
internal enum class FooterAt { Overlay, Aligned, Below }

/**
 * Where an outgoing message stands, drawn as ticks beside its time: the clock while it leaves, one
 * tick sent, two delivered, two in the accent read. The words are for TalkBack.
 */
internal enum class Delivery(val word: String) {
    Sending("Sending"), Sent("Sent"), Delivered("Delivered"), Read("Read"), Failed("Not sent")
}

/**
 * True when [text] holds letters of both directions: its time then goes under it, as Signal does
 * (https://github.com/signalapp/Signal-Android, AGPL-3.0,
 * core/util-jvm/src/main/java/org/signal/core/util/BidiUtil.kt hasMixedTextDirection). Fixed: Signal's
 * test reads a right to left letter as only DIRECTIONALITY_RIGHT_TO_LEFT, so every Arabic letter
 * (DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) made a text of Arabic alone mixed; both count as right to left here.
 * It also steps by code point, where Signal's loop indexes the text by code unit.
 */
internal fun hasMixedTextDirection(text: CharSequence?): Boolean {
    if (text == null) return false
    var isLtr: Boolean? = null
    var i = 0
    while (i < text.length) {
        val codePoint = Character.codePointAt(text, i)
        i += Character.charCount(codePoint)
        if (!Character.isLetter(codePoint)) continue
        val direction = Character.getDirectionality(codePoint)
        val letterIsLtr = when (direction) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> true
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> false
            else -> continue
        }
        if (isLtr != null && isLtr != letterIsLtr) return true
        isLtr = letterIsLtr
    }
    return false
}

/**
 * The direction [text] reads in, from its first strong letter, else [fallback]: the role of Element X's
 * TextDirection.detect in WithTimestampLayout (https://github.com/element-hq/element-x-android,
 * TimelineItemEventRow.kt), here Android's own heuristics
 * (https://developer.android.com/reference/android/text/TextDirectionHeuristics) since Compose has no detect.
 */
internal fun directionOf(text: CharSequence, fallback: LayoutDirection): LayoutDirection {
    if (text.isEmpty()) return fallback
    val rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, 0, text.length)
    val rtlFirst = TextDirectionHeuristics.FIRSTSTRONG_RTL.isRtl(text, 0, text.length)
    // The two disagree when no letter is strong: the layout's own direction then.
    return if (rtl != rtlFirst) fallback else if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
}

/**
 * The room the status tick always keeps: the widest of the ticks (two ticks, delivered and read) at
 * the size drawn, so a status that changes never moves the bubble. Signal's
 * DeliveryStatusView.reservedWidth (https://github.com/signalapp/Signal-Android, AGPL-3.0,
 * app/src/main/java/org/thoughtcrime/securesms/components/DeliveryStatusView.java).
 */
private val TickSide = 14.dp
private val TickReserved = TickSide * (AppIcons.DoneAll.defaultWidth.value / AppIcons.DoneAll.defaultHeight.value)

/**
 * A message's time, with its star and, for one's own, its status as ticks. [onMedia]: over a picture,
 * on a dark pill. Element X's TimelineEventTimestampView (https://github.com/element-hq/element-x-android,
 * features/messages/impl/.../timeline/components/TimelineEventTimestampView.kt: the time, the error mark
 * after it, the gap on the side facing the content, swapped when the content runs the other way) with
 * Signal's ticks (DeliveryStatusView).
 */
@Composable
internal fun MessageFooter(
    time: String,
    delivery: Delivery?,
    starred: Boolean,
    modifier: Modifier = Modifier,
    onMedia: Boolean = false,
    mismatched: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (onMedia) Color.White else LocalContentColor.current.copy(alpha = 0.66f)
    val gap = 10.dp
    val edge = 12.dp
    // The bottom keeps the footer's line level with the last line of words, whose own bottom padding is 6 dp.
    val pad = if (!mismatched) PaddingValues(start = gap, end = edge, bottom = 8.dp) else PaddingValues(start = edge, end = gap, bottom = 8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onMedia) {
            modifier
                .background(scheme.scrim.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        } else {
            modifier.padding(pad)
        }
    ) {
        if (starred) {
            Icon(AppIcons.Star, contentDescription = "Starred", tint = if (onMedia) scheme.inversePrimary else scheme.primary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(time, style = MaterialTheme.typography.labelSmall, color = ink)
        if (delivery != null) {
            Spacer(Modifier.width(4.dp))
            val read = delivery == Delivery.Read
            val failed = delivery == Delivery.Failed
            // Read draws itself, stroke by stroke, the moment it comes.
            val draw = remember { Animatable(if (read) 1f else 0f) }
            LaunchedEffect(read) { if (read) draw.animateTo(1f, tween(520)) }
            Box(Modifier.widthIn(min = TickReserved), contentAlignment = Alignment.CenterEnd) {
                Icon(
                    when (delivery) {
                        Delivery.Sending -> AppIcons.Schedule
                        Delivery.Sent -> AppIcons.Done
                        Delivery.Delivered, Delivery.Read -> AppIcons.DoneAll
                        Delivery.Failed -> AppIcons.Error
                    },
                    contentDescription = delivery.word,
                    tint = when {
                        failed -> scheme.error
                        read -> if (onMedia) scheme.inversePrimary else scheme.primary
                        else -> ink
                    },
                    modifier = Modifier.size(TickSide).drawWithContent {
                        if (!read) drawContent() else clipRect(right = size.width * draw.value) { this@drawWithContent.drawContent() }
                    }
                )
            }
        }
    }
}

/**
 * A message's content with its footer: over the content, on its last line, or under it. Element X's
 * WithTimestampLayout (https://github.com/element-hq/element-x-android, AGPL-3.0,
 * features/messages/impl/.../timeline/components/TimelineItemEventRow.kt): the content runs in the
 * direction of its text so the footer keeps to its end, the footer in the layout's own; and, from
 * Signal's V2FooterPositionDelegate (https://github.com/signalapp/Signal-Android, AGPL-3.0,
 * app/src/main/java/org/thoughtcrime/securesms/conversation/v2/items/V2FooterPositionDelegate.kt),
 * text of both directions has its footer under it.
 *
 * [words] is the text the footer follows, for its direction; empty for content with none
 * (adapted from Element X's WithTimestampLayout, which reads the event's body).
 */
@Composable
internal fun WithFooter(
    at: FooterAt,
    footer: @Composable (mismatched: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    words: CharSequence = "",
    content: @Composable (onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit) -> Unit
) {
    when (at) {
        FooterAt.Overlay -> Box(modifier, contentAlignment = Alignment.Center) {
            content {}
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { footer(false) }
        }
        FooterAt.Aligned -> {
            val original = LocalLayoutDirection.current
            val direction = remember(words, original) { directionOf(words, original) }
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                ContentAvoidingLayout(
                    modifier = modifier,
                    content = { content(this::onContentLayoutChange) },
                    overlay = {
                        // The footer keeps the layout's own direction.
                        CompositionLocalProvider(LocalLayoutDirection provides original) { footer(original != direction) }
                    }
                )
            }
        }
        FooterAt.Below -> Column(modifier) {
            content {}
            Box(Modifier.align(Alignment.End)) { footer(false) }
        }
    }
}

package com.yaz.sms.feature.thread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.mms.mediaWord
import com.yaz.sms.core.sms.Effects
import com.yaz.sms.data.sms.Message

/** The excerpt and the words of an SMS written as `«excerpt»` and a line break before the words. */
internal data class SmsQuote(val excerpt: String, val rest: String)

/**
 * What a bubble shows of the message it answers: who wrote it when known, the excerpt, and the quoted
 * message's row ([uid]) when it was found, to jump to it.
 */
internal data class QuoteShown(val sender: String?, val excerpt: String, val uid: String?)

/**
 * The reply written in a text: PendingSends puts `«excerpt»` and a line break before the words of an SMS or
 * MMS (the encrypted chat sends the quote as its own field). Read strictly: the text starts with `«`, the first
 * `»` followed by a line break closes it, the excerpt is one non-empty line and words follow; anything else is
 * plain text. No app has a standard for it: the form is this app's own and what other apps send in this shape
 * only looks like a quote.
 */
internal object QuoteForm {
    fun split(text: String): SmsQuote? {
        if (!text.startsWith('«')) return null
        val end = text.indexOf("»\n", 1)
        if (end < 2) return null
        val excerpt = text.substring(1, end)
        if ('\n' in excerpt || excerpt.isBlank()) return null
        val rest = text.substring(end + 2)
        if (rest.isBlank()) return null
        return SmsQuote(excerpt, rest)
    }

    /** What is quoted of [m] when it is answered: its own words (no effect mark, no quote of its own), else the kind of media. */
    fun quotable(m: Message): String =
        wordsOf(m).ifBlank { mediaWord(m.parts.firstOrNull()?.contentType) }

    private fun wordsOf(m: Message): String {
        val said = Effects.read(m.body).first
        return (split(said)?.rest ?: said).trim()
    }

    /**
     * The message an SMS quote answers: the newest message before [quoting] (the list is oldest first) whose text
     * starts with the excerpt less its ellipsis (PendingSends cuts a long text to 58 characters and adds "…").
     * A message without words matches by the word for its media, as [quotable] quotes it.
     */
    fun originalOf(messages: List<Message>, quoting: Message, excerpt: String): Message? {
        val needle = excerpt.removeSuffix("…").replace('\n', ' ').trim()
        if (needle.isEmpty()) return null
        val at = messages.indexOfFirst { it.uid == quoting.uid }
        val earlier = if (at >= 0) messages.subList(0, at) else messages
        return earlier.lastOrNull { m ->
            val words = quotable(m).replace('\n', ' ')
            words.startsWith(needle) || Effects.read(m.body).first.replace('\n', ' ').startsWith(needle)
        }
    }
}

/**
 * The block at the top of a bubble that shows the message it answers: a rounded pane, a bar in the accent at the
 * start, who wrote it (when known) and its excerpt on two lines at most. Element X's InReplyToView
 * (https://github.com/element-hq/element-x-android, AGPL-3.0, libraries/matrixui/src/main/kotlin/io/element/android/libraries/matrix/ui/messages/reply/InReplyToView.kt:
 * ReplyToReadyContent, the name over the text, maxLines 2, ellipsis; and its use in
 * features/messages/impl/.../timeline/components/TimelineItemEventRow.kt, CommonLayout: a rounded block above the
 * content with a margin round it, a tap goes to the message) with Signal's QuoteView
 * (https://github.com/signalapp/Signal-Android, AGPL-3.0, app/src/main/java/org/thoughtcrime/securesms/components/QuoteView.java:
 * the bar at the start, the author over the text). The pane is Material 3's Large corner (what sits inside a pane).
 *
 * [onClick] null draws it without a tap (the copy the long press menu lifts); [onLongClick] opens the message's
 * menu, which a tappable child would otherwise swallow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuoteBlock(
    quote: QuoteShown,
    mine: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val ink = LocalContentColor.current
    // On the user's own bubble the accent is the ground: the bar takes the text's colour there.
    val bar = if (mine) ink.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val click = if (onClick == null && onLongClick == null) Modifier else Modifier.combinedClickable(
        onClickLabel = "Go to the message",
        onLongClickLabel = "More",
        onLongClick = onLongClick,
        onClick = { onClick?.invoke() }
    )
    Row(
        modifier
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 2.dp)
            .clip(ShapeDefaults.Large)
            .background(ink.copy(alpha = 0.08f))
            .drawBehind {
                val w = 3.dp.toPx()
                drawRect(bar, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height))
            }
            .then(click)
    ) {
        Column(Modifier.padding(start = 11.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
            quote.sender?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = bar, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                quote.excerpt.take(300),
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * A column whose children all have the width of the widest. Element X's EqualWidthColumn
 * (https://github.com/element-hq/element-x-android, AGPL-3.0,
 * libraries/designsystem/src/main/kotlin/io/element/android/libraries/designsystem/components/EqualWidthColumn.kt),
 * as it is: the quote above a bubble's content takes the content's width, the content the quote's if wider.
 */
@Composable
internal fun EqualWidthColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val measurables = subcompose(0, content).map { it.measure(constraints) }
        if (measurables.isEmpty()) {
            layout(0, 0) {}
        } else if (measurables.size == 1) {
            val measurable = measurables.first()
            layout(measurable.width, measurable.height) {
                measurable.placeRelative(0, 0)
            }
        } else {
            val maxWidth = measurables.maxOf { it.width }
            val newConstraints = constraints.copy(minWidth = maxWidth)
            val newMeasurables = if (measurables.all { it.width == maxWidth }) {
                measurables
            } else {
                subcompose(1, content).map { it.measure(newConstraints) }
            }
            val totalHeight = newMeasurables.sumOf { it.height }
            layout(maxWidth, totalHeight) {
                var yPosition = 0
                newMeasurables.forEach { measurable ->
                    measurable.placeRelative(0, yPosition)
                    yPosition += measurable.height
                }
            }
        }
    }
}

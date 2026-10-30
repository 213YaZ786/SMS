package com.yaz.sms.feature.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaz.sms.core.sms.ReactionGroups
import com.yaz.sms.ui.component.FloatingPane

/**
 * The reactions under a message: one chip for each emoji, "❤️ 2" when several gave it, the user's own in accent glass.
 *
 * Ported from Element X Android (AGPL-3.0, https://github.com/element-hq/element-x-android),
 * features/messages/impl/.../timeline/components/TimelineItemReactionsView.kt (the reactions in a flow with 4 dp
 * between items and rows, from the message's side) and MessagesReactionButton.kt (ReactionContent: the emoji at
 * 15 sp over a 20 sp line, the count after a 4 dp gap only above one, the count's colour stronger when the user's
 * own; the chip's 10 dp by 4 dp padding; clearAndSetSemantics for the spoken form; highlighted = the user reacted).
 * The tap is MessagesReactionButton's onClick (a tap toggles the user's own reaction with that emoji, the timeline's
 * onReactionClick); its long press (who reacted) is not ported, the long press stays free. Without [onToggle] (the
 * conversation list's preview, inside a card that takes the tap) the chip lets the tap through to the card.
 * Not ported: its custom expand / "show more" layout (TimelineItemReactionsLayout), reactions here are few.
 * The chip is drawn in this app's glass (FloatingPane), accent when highlighted, in place of Element X's filled surface.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionChips(groups: List<ReactionGroups.Group>, mine: Boolean, modifier: Modifier = Modifier, onToggle: ((String) -> Unit)? = null) {
    if (groups.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, if (mine) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groups.forEach { group -> ReactionChip(group, onToggle) }
    }
}

@Composable
private fun ReactionChip(group: ReactionGroups.Group, onToggle: ((String) -> Unit)?) {
    val spoken = buildString {
        append(group.displayKey)
        if (group.count > 1) append(", ").append(group.count)
        if (group.isHighlighted) append(", yours")
    }
    FloatingPane(shape = CircleShape, accent = group.isHighlighted, onClick = onToggle?.let { toggle -> { toggle(group.key) } }) {
        // The spoken form sits on the content, so the pane's own click (a tap, for TalkBack too) is kept.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(group.displayKey, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp))
            if (group.count > 1) {
                Spacer(Modifier.width(4.dp))
                Text(
                    group.count.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    // The pane's own ink when highlighted (readable on the accent), a quieter one otherwise.
                    color = if (group.isHighlighted) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

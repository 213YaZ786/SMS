package com.yaz.sms.feature.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaz.sms.core.sms.ReactionGroups
import com.yaz.sms.ui.glass.LocalGlass

/**
 * The reactions of a message as Signal's pills: at most three, the busiest first; past three, the first two and one
 * "+N" pill for the rest. Where they sit (over the bubble's bottom edge) is the call site's.
 *
 * Ported from Signal-Android (AGPL-3.0, https://github.com/signalapp/Signal-Android):
 * - app/src/main/java/org/thoughtcrime/securesms/reactions/ReactionsConversationView.java: buildSortedReactionsList
 *   (sorted by count, more than 3 become the first two plus one merged pill, selected if any merged one is the
 *   user's) and buildPill (the emoji, the count only above one, the merged pill with no emoji and "+N").
 * - app/src/main/res/layout/reactions_pill.xml: 26 dp high, 7 dp inside, 1 dp margin each side, emoji 17 dp, 4 dp gap,
 *   count 12 sp sans-serif-medium.
 * - app/src/main/res/drawable/reaction_pill_background.xml and reaction_pill_background_selected.xml: fully round,
 *   a 1 dp stroke in the page's background colour (the cut-out against the bubble).
 * Changed for this app: the fill is the quiet glass tint (or surfaceVariant without glass), the user's own is marked
 * by a thin accent outline in place of Signal's fill; the pill is at least 26 dp so the 200 % font does not cut it.
 * A tap on an emoji's pill toggles it (onToggle, as before from Element X's MessagesReactionButton); the "+N" pill
 * toggles nothing. Without [onToggle] (the conversation list's preview, inside a card that takes the tap) the pill
 * lets the tap through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionChips(groups: List<ReactionGroups.Group>, mine: Boolean, modifier: Modifier = Modifier, onToggle: ((String) -> Unit)? = null) {
    if (groups.isEmpty()) return
    val sorted = groups.sortedByDescending { it.count }
    val pills: List<Pill> =
        if (sorted.size > 3) {
            val rest = sorted.drop(2)
            sorted.take(2).map { Pill(it, it.count, it.isHighlighted) } + Pill(null, rest.sumOf { it.count }, rest.any { it.isHighlighted })
        } else sorted.map { Pill(it, it.count, it.isHighlighted) }
    // The column around already puts this row on the bubble's side (end for [mine], start otherwise).
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        pills.forEach { ReactionPill(it, onToggle) }
    }
}

/** One pill: a [group] with its emoji, or null for the merged "+N" pill. */
private class Pill(val group: ReactionGroups.Group?, val count: Int, val mine: Boolean)

@Composable
private fun ReactionPill(pill: Pill, onToggle: ((String) -> Unit)?) {
    val group = pill.group
    val spoken = buildString {
        if (group != null) {
            append(group.displayKey)
            if (pill.count > 1) append(", ").append(pill.count)
        } else append("+").append(pill.count)
        if (pill.mine) append(", yours")
    }
    val fill = LocalGlass.current?.zoneTint ?: MaterialTheme.colorScheme.surfaceVariant
    val outline = if (pill.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background
    val tap = if (group != null && onToggle != null) ({ onToggle(group.key) }) else null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, outline, CircleShape)
            .let { if (tap != null) it.clickable(role = Role.Button, onClick = tap) else it }
            .heightIn(min = 26.dp)
            .padding(horizontal = 7.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        if (group != null) {
            Text(group.displayKey, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp))
        }
        if (group == null || pill.count > 1) {
            if (group != null) Spacer(Modifier.width(4.dp))
            Text(
                if (group == null) "+${pill.count}" else pill.count.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
                color = if (pill.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

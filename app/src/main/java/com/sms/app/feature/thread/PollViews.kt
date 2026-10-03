package com.sms.app.feature.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sms.app.core.sms.Polls
import com.sms.app.ui.component.ZoneAlertDialog
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.icon.AppIcons
import kotlinx.coroutines.delay

/** A new poll: a question and two to six choices. */
@Composable
internal fun PollDialog(onSend: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    val ready = question.isNotBlank() && options.count { it.isNotBlank() } >= 2
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Poll") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.replace('\n', ' ').take(200) },
                    placeholder = { Text("Question") },
                    modifier = Modifier.fillMaxWidth()
                )
                options.forEachIndexed { i, option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = option,
                            onValueChange = { options[i] = it.replace('\n', ' ').take(100) },
                            singleLine = true,
                            placeholder = { Text("Choice ${i + 1}") },
                            leadingIcon = { Text(Polls.keys[i]) },
                            modifier = Modifier.weight(1f)
                        )
                        if (options.size > 2) IconButton(onClick = {
                            haptics.tick()
                            options.removeAt(i)
                        }) { Icon(AppIcons.Close, contentDescription = "Remove") }
                    }
                }
                AnimatedVisibility(options.size < Polls.MAX, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    TextButton(onClick = {
                        haptics.tick()
                        options.add("")
                    }) {
                        Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add a choice")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                haptics.done()
                onSend(question, options.toList())
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * A poll in its bubble: each choice a bar that fills with its share of
 * the votes, the user's own marked; a tap votes, another tap on the same
 * choice takes the vote back, a tap on another changes it.
 */
@Composable
internal fun PollCard(poll: Polls.Poll, counts: Map<String, Int>, mine: List<String>, onVote: (String?) -> Unit) {
    val haptics = rememberHaptics()
    val votes = Polls.tally(counts, poll.options.size)
    val total = votes.sum()
    val myChoice = mine.map(Polls::choiceOf).firstOrNull { it >= 0 } ?: -1
    val accent = MaterialTheme.colorScheme.primary
    val track = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.06f)
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Poll, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(poll.question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        poll.options.forEachIndexed { i, option ->
            val share = if (total > 0) votes[i] / total.toFloat() else 0f
            // The bars grow in as the poll is first shown, then follow each vote.
            val grown = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(60L * i)
                grown.animateTo(1f, tween(600))
            }
            val fill by animateFloatAsState(share, spring(dampingRatio = 0.7f, stiffness = 240f), label = "share")
            val chosen = i == myChoice
            val press = remember { Animatable(1f) }
            LaunchedEffect(chosen) {
                if (chosen) {
                    press.snapTo(0.94f)
                    press.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
                }
            }
            Box(
                Modifier
                    .padding(vertical = 3.dp)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .graphicsLayer { scaleX = press.value; scaleY = press.value }
                    .clip(RoundedCornerShape(14.dp))
                    .drawBehind {
                        val r = CornerRadius(14.dp.toPx())
                        drawRoundRect(track, cornerRadius = r)
                        val w = size.width * fill * grown.value
                        if (w > 0f) drawRoundRect(accent.copy(alpha = if (chosen) 0.38f else 0.18f), size = Size(w, size.height), cornerRadius = r)
                    }
                    .clickable {
                        haptics.tick()
                        onVote(if (chosen) null else Polls.keys[i])
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(option, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (chosen) Icon(AppIcons.Done, contentDescription = "Your vote", tint = accent, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                    if (total > 0) Text(
                        "${(share * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        Text(
            when {
                total == 0 -> "Tap a choice to vote"
                total == 1 -> "1 vote"
                else -> "$total votes"
            },
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 6.dp, start = 2.dp)
        )
    }
}

package com.yaz.sms.feature.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons

/**
 * The row above the composer's bar: the members offered while an @ is being typed as compact glass pills on the
 * start side, wrapping when they do not fit, and Send later's clock at the end (kept in its place above Send).
 * The offering is Signal's MentionsPickerFragmentV2 over the composer, the query from ComposeText
 * (core/chat/Mentions.kt); the pills wrap in Compose's FlowRow, the recipe of
 * https://developer.android.com/develop/ui/compose/layouts/flow (a row of chips that wraps), drawn as the app's
 * other glass pills (the "Copy tracking number" pill of ThreadScreen: FloatingPane, circle, label large).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MentionLine(
    names: List<String>,
    laterReady: Boolean,
    onMention: (String) -> Unit,
    onLater: () -> Unit,
    // The text styles, when shown, on this same row before the pills (the user, 2026-10-28: on the clock's line).
    styles: (@Composable () -> Unit)? = null
) {
    val haptics = rememberHaptics()
    AnimatedVisibility(visible = names.isNotEmpty() || laterReady || styles != null, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                styles?.invoke()
                names.forEach { who ->
                    FloatingPane(shape = CircleShape, onClick = {
                        haptics.tick()
                        onMention(who)
                    }) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.widthIn(max = 240.dp).padding(horizontal = 14.dp, vertical = 8.dp)
                                .semantics { contentDescription = "Mention $who" }
                        ) {
                            Icon(AppIcons.AlternateEmail, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(
                                who,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
            // Send later, in sight over Send as soon as there are words: the same 40 dp pane, centred over Send's 52 dp.
            AnimatedVisibility(
                visible = laterReady,
                enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f)) + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingPane(shape = CircleShape, onClick = onLater, modifier = Modifier.padding(start = 8.dp, end = 6.dp).size(40.dp)) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Schedule, contentDescription = "Send later", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val shape = RoundedCornerShape(22.dp)
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
                Text("Code from ${from ?: c.address} · ${minutesAgo(c.date)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .matchParentSizeOf()
                    .padding(horizontal = (10 * k).dp)
                    .offset(y = (6 * k * spread).dp)
                    .graphicsLayer { alpha = 0.55f * spread }
            ) { Box(Modifier.height(60.dp)) }
        }
        val shape = RoundedCornerShape(22.dp)
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

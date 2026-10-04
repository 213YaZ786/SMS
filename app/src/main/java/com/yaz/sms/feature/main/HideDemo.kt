package com.yaz.sms.feature.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Android's page for the connection's notification in miniature, playing
 * what the user does there: a finger turns Show notifications off, the
 * icon leaves the top of the screen and the notification goes. Six
 * seconds, again and again, while the guide shows it.
 */
@Composable
internal fun HideDemo(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val loop = rememberInfiniteTransition(label = "hide")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    // 0 → 1 over [a, b], back to 0 over [c, d].
    fun span(a: Float, b: Float, c: Float = 2f, d: Float = 2f) = when {
        t < a -> 0f
        t < b -> (t - a) / (b - a)
        t < c -> 1f
        t < d -> 1f - (t - c) / (d - c)
        else -> 0f
    }
    val on = 1f - span(0.30f, 0.38f, 0.88f, 0.96f)
    val gone = span(0.46f, 0.56f, 0.88f, 0.96f)
    val folded = span(0.50f, 0.62f, 0.88f, 0.96f)
    val ring = span(0.26f, 0.32f, 0.50f, 0.60f)
    val press = when {
        t < 0.14f -> -1f
        t < 0.24f -> (t - 0.14f) / 0.10f
        t < 0.46f -> 1f
        else -> -1f
    }
    val compact = LocalConfiguration.current.screenWidthDp < 380
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerLowest)
            .semantics { contentDescription = "Android's notification page: Show notifications turned off" }
    ) {
        Column(Modifier.padding(bottom = 10.dp)) {
            // The top of the screen: the time, and the app's icon until the notification is off.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("12:30", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(9.dp).graphicsLayer { alpha = 1f - gone; scaleX = 1f - 0.8f * gone; scaleY = 1f - 0.8f * gone }.background(scheme.primary, CircleShape))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.padding(horizontal = 10.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceContainerHigh)
                        .border(2.dp, scheme.primary.copy(alpha = ring), RoundedCornerShape(14.dp))
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Show notifications", style = MaterialTheme.typography.labelLarge)
                            if (!compact) Text("Encrypted chat connection", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                        // The switch, as Android draws it.
                        Box(Modifier.size(38.dp, 22.dp).clip(CircleShape).background(lerp(scheme.outlineVariant, scheme.primary, on))) {
                            Box(Modifier.padding(3.dp).offset(x = 16.dp * on).size(16.dp).background(lerp(scheme.surface, scheme.onPrimary, on), CircleShape))
                        }
                    }
                }
                // The finger, landing on the switch.
                if (press >= 0f) {
                    val tap = if (t in 0.24f..0.36f) 0.8f else 1f
                    Box(
                        Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)
                            .offset(x = 30.dp * (1f - press.coerceAtMost(1f)), y = 30.dp * (1f - press.coerceAtMost(1f)))
                            .size(30.dp)
                            .graphicsLayer { alpha = if (t < 0.36f) press else 1f - (t - 0.36f) / 0.10f; scaleX = tap; scaleY = tap }
                            .background(scheme.primary.copy(alpha = 0.28f), CircleShape)
                            .border(2.dp, scheme.primary.copy(alpha = 0.5f), CircleShape)
                    )
                }
            }
            // The notification in the shade, then gone.
            Column(
                Modifier.padding(horizontal = 10.dp, vertical = 3.dp * (1f - folded)).fillMaxWidth()
                    .height(52.dp * (1f - folded))
                    .graphicsLayer { alpha = 1f - folded }
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Encrypted chat connected", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text("SMS · tap to open", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

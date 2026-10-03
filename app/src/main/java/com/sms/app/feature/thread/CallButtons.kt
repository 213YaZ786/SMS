package com.sms.app.feature.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.icon.AppIcons
import kotlinx.coroutines.launch

/** The green of a phone call. */
val CallGreen = Color(0xFF1E9E4A)

/** The ways to call this person: by phone, and over the encrypted chat by voice or video. */
class CallChoices(val phone: (() -> Unit)?, val encrypted: (() -> Unit)?, val video: (() -> Unit)?)

/**
 * A round button of coloured glass at the top of a conversation: one tap
 * and it swells, a ring of its colour leaves it, and it acts; [onHold],
 * when given, is the same button's other way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCallButton(icon: ImageVector, label: String, color: Color, onTap: () -> Unit, iconTint: Color = Color.White, holdLabel: String? = null, onHold: (() -> Unit)? = null, diameter: androidx.compose.ui.unit.Dp = 48.dp) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val swell = remember { Animatable(1f) }
    val ring = remember { Animatable(0f) }
    fun act(then: () -> Unit) {
        haptics.firm()
        scope.launch { ring.snapTo(0f); ring.animateTo(1f, tween(520)) }
        scope.launch {
            swell.animateTo(1.14f, tween(90))
            swell.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 700f))
            then()
        }
    }
    Box(
        Modifier
            .size(diameter)
            // The ring of its colour, leaving it as it acts.
            .drawBehind {
                val t = ring.value
                if (t > 0f && t < 1f) drawCircle(color.copy(alpha = 0.45f * (1f - t)), radius = size.minDimension / 2 * (1f + 0.9f * t), style = Stroke((3f - 2f * t).dp.toPx()))
            }
            .graphicsLayer { scaleX = swell.value; scaleY = swell.value }
            .clip(CircleShape)
            // Coloured glass: its colour through it, light caught on top, a soft rim.
            .drawBehind {
                drawCircle(Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.80f))))
                drawCircle(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.30f), 0.5f to Color.Transparent))
                listOf(1f to 0.30f, 3f to 0.12f).forEach { (w, a) ->
                    drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = a), Color.Transparent)), size.minDimension / 2 - (w / 2).dp.toPx(), style = Stroke(w.dp.toPx()))
                }
            }
            .combinedClickable(
                onClickLabel = label,
                onClick = { act(onTap) },
                onLongClickLabel = holdLabel,
                onLongClick = onHold?.let { hold -> { act(hold) } }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(22.dp))
    }
}

/**
 * The call at the top right: the green phone call, which opens Dialer
 * with the number ready, its Call a second tap on purpose against a slip.
 * The encrypted calls are in the conversation's tools.
 */
@Composable
fun CallButtons(choices: CallChoices) {
    choices.phone?.let { phone -> GlassCallButton(AppIcons.Call, "Call", CallGreen, phone, diameter = 44.dp) }
}

package com.yaz.sms.feature.thread

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
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
 * The conversation's ways to reach the person, folded at the top right
 * under one button of four squares: unfolded downwards, the phone call
 * (Dialer's dialpad, its Call a second tap against a slip), with the
 * encrypted chat the encrypted call and the video call, and Add contact
 * for a number not yet saved. The rest stays on the person's page.
 */
@Composable
fun CallButtons(choices: CallChoices, onAddContact: (() -> Unit)? = null) {
    val actions = buildList {
        choices.phone?.let { add(Reach(AppIcons.Call, "Call", CallGreen, Color.White, it)) }
        choices.encrypted?.let { add(Reach(AppIcons.CallLocked, "Encrypted call", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, it)) }
        choices.video?.let { add(Reach(AppIcons.Videocam, "Video call", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, it)) }
        onAddContact?.let { add(Reach(AppIcons.PersonAdd, "Add contact", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, it)) }
    }
    if (actions.isEmpty()) return
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        com.yaz.sms.ui.component.FloatingAction(if (open) AppIcons.Close else AppIcons.GridView, if (open) "Close" else "Calls and contact", { open = !open })
        if (open) {
            val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }
            androidx.compose.ui.window.Popup(
                popupPositionProvider = remember { Below(margin) },
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true)
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    actions.forEachIndexed { i, reach ->
                        Drop(i) {
                            GlassCallButton(reach.icon, reach.label, reach.color, {
                                open = false
                                reach.onTap()
                            }, iconTint = reach.tint, diameter = 48.dp)
                        }
                    }
                }
            }
        }
    }
}

private class Reach(val icon: ImageVector, val label: String, val color: Color, val tint: Color, val onTap: () -> Unit)

/** Falls into place from the button, a little after the one above it. */
@Composable
private fun Drop(order: Int, content: @Composable () -> Unit) {
    val fall = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(order * 45L)
        fall.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 420f))
    }
    Box(Modifier.graphicsLayer {
        val p = fall.value
        alpha = p.coerceIn(0f, 1f)
        val sc = 0.6f + 0.4f * p
        scaleX = sc
        scaleY = sc
        translationY = (1f - p) * -20.dp.toPx()
    }) { content() }
}

/** Under its anchor, centred on it, kept on screen. */
private class Below(val margin: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize
    ): androidx.compose.ui.unit.IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        return androidx.compose.ui.unit.IntOffset(
            x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            anchorBounds.bottom + margin
        )
    }
}

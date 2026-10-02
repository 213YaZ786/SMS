package com.sms.app.feature.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.sms.app.ui.component.ZoneSurface
import com.sms.app.ui.component.rememberHaptics
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** One action of a message's menu: its icon, its word, red when it takes something away. */
data class MessageAction(val icon: ImageVector, val label: String, val danger: Boolean = false, val run: () -> Unit)

/** The six reactions offered over a message of the encrypted chat. */
val Reactions = listOf("❤️", "😂", "👍", "😮", "😢", "🙏")

/**
 * A message held: over it, the reactions hop into place one by one (on
 * the encrypted chat only); under it, the actions by name. The message,
 * drawn again by [held] at its [bounds], lifts above the veil.
 * Where there is no room under it, the actions go above.
 */
@Composable
fun MessageMenu(
    bounds: Rect,
    mine: Boolean,
    reactions: List<String>?,
    chosen: List<String>,
    actions: List<MessageAction>,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit,
    held: @Composable () -> Unit
) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val gap = with(density) { 10.dp.roundToPx() }
    val edge = with(density) { 12.dp.roundToPx() }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var listSize by remember { mutableStateOf(IntSize.Zero) }
    Popup(
        popupPositionProvider = remember { Whole },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        ) {
            var window by remember { mutableStateOf(IntSize.Zero) }
            Box(Modifier.fillMaxSize().onSizeChanged { window = it })
            // The side of the message: the user's own on the right.
            fun xFor(width: Int): Int {
                val x = if (mine) bounds.right.roundToInt() - width else bounds.left.roundToInt()
                return x.coerceIn(edge, (window.width - width - edge).coerceAtLeast(edge))
            }
            // The message itself, above the veil, lifted a little.
            val rise = remember { Animatable(0f) }
            LaunchedEffect(Unit) { rise.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }
            Box(
                Modifier
                    .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                    .graphicsLayer {
                        val s = 1f + 0.05f * rise.value
                        scaleX = s
                        scaleY = s
                        translationY = -4.dp.toPx() * rise.value
                    }
            ) { held() }
            val below = bounds.bottom.roundToInt() + gap
            val fitsBelow = below + listSize.height + edge < window.height
            val listY = if (fitsBelow) below else (bounds.top.roundToInt() - gap - listSize.height - (if (reactions != null) rowSize.height + gap else 0)).coerceAtLeast(edge)
            val rowY = if (fitsBelow) (bounds.top.roundToInt() - gap - rowSize.height).coerceAtLeast(edge) else listY + listSize.height + gap

            if (reactions != null) {
                ZoneSurface(
                    shape = CircleShape,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .offset { IntOffset(xFor(rowSize.width), rowY) }
                        .onSizeChanged { rowSize = it }
                        .popIn(0)
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        reactions.forEachIndexed { i, emoji ->
                            val hop = remember { Animatable(1f) }
                            LaunchedEffect(Unit) {
                                delay(40L * i)
                                hop.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f))
                            }
                            val on = emoji in chosen
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .graphicsLayer {
                                        translationY = hop.value * 10.dp.toPx()
                                        alpha = 1f - hop.value
                                    }
                                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent, CircleShape)
                                    .clickable(onClickLabel = emoji) {
                                        haptics.tick()
                                        onReact(emoji)
                                        onDismiss()
                                    },
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Text(emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }

            ZoneSurface(
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .offset { IntOffset(xFor(listSize.width), listY) }
                    .widthIn(min = 200.dp, max = 280.dp)
                    .onSizeChanged { listSize = it }
                    .popIn(1)
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    actions.forEach { action ->
                        val tint = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    haptics.tick()
                                    onDismiss()
                                    action.run()
                                }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        ) {
                            Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(action.label, style = MaterialTheme.typography.bodyLarge, color = tint)
                        }
                    }
                }
            }
        }
    }
}

/** Grows from small with a spring, the second part a beat after the first. */
@Composable
private fun Modifier.popIn(order: Int): Modifier {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(30L * order)
        grow.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 600f))
    }
    return graphicsLayer {
        val s = 0.6f + 0.4f * grow.value
        scaleX = s
        scaleY = s
        alpha = grow.value.coerceIn(0f, 1f)
    }
}

/** The popup over the whole window, its parts placed inside by the message's bounds. */
private object Whole : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

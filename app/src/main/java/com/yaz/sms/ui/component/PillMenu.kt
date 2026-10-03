package com.yaz.sms.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

/**
 * The long press menu of the other apps' posts, for a call or a contact: a
 * small upright pill of glass where the finger is, one icon per action,
 * each answering with its own small motion before it acts.
 *
 * The line puts [tracker] on its outer box, calls [open] on a long press,
 * and places [PillMenu] inside that box. With [columns] above one the pill
 * becomes a rounded square of tiles, an icon over its name, so that many
 * actions do not make a tall column.
 */
@Stable
class PillMenuState internal constructor(private val haptics: Haptics) {
    internal var pressAt by mutableStateOf(Offset.Zero)
    internal var shown by mutableStateOf(false)

    val tracker: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            pressAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
        }
    }

    fun open() {
        haptics.firm()
        shown = true
    }

    fun close() {
        shown = false
    }
}

@Composable
fun rememberPillMenu(): PillMenuState {
    val haptics = rememberHaptics()
    return remember { PillMenuState(haptics) }
}

enum class PillMotion { DROP, WIGGLE, BOUNCE }

data class PillItem(
    val icon: ImageVector,
    val label: String,
    val motion: PillMotion,
    val tint: Color? = null,
    val action: () -> Unit
)

@Composable
fun PillMenu(state: PillMenuState, items: List<PillItem>, columns: Int = 1) {
    if (!state.shown) return
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val at = IntOffset(state.pressAt.x.toInt(), state.pressAt.y.toInt())
    Popup(
        popupPositionProvider = remember(at) { AtPoint(at, margin) },
        onDismissRequest = state::close,
        properties = PopupProperties(focusable = true)
    ) {
        val appear = remember { MutableTransitionState(false) }.apply { targetState = true }
        AnimatedVisibility(
            visibleState = appear,
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.5f) +
                expandVertically(tween(180), expandFrom = Alignment.CenterVertically)
        ) {
            if (columns > 1) ZoneSurface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp) {
                Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { item ->
                                PillTile(item) {
                                    state.close()
                                    item.action()
                                }
                            }
                        }
                    }
                }
            } else ZoneSurface(shape = RoundedCornerShape(50), shadowElevation = 6.dp) {
                Column(
                    modifier = Modifier.padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items.forEach { item ->
                        PillButton(item) {
                            state.close()
                            item.action()
                        }
                    }
                }
            }
        }
    }
}

/** An item's motion: plays it, then runs the action once. */
@Stable
private class PillPlay(private val scope: kotlinx.coroutines.CoroutineScope) {
    val drop = Animatable(0f)
    val turn = Animatable(0f)
    private var busy = false

    fun run(motion: PillMotion, onDone: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            when (motion) {
                PillMotion.DROP -> drop.animateTo(1f, tween(180))
                PillMotion.WIGGLE -> {
                    turn.animateTo(-20f, tween(70))
                    turn.animateTo(20f, tween(110))
                    turn.animateTo(0f, tween(70))
                }
                PillMotion.BOUNCE -> {
                    drop.animateTo(0.6f, tween(110))
                    drop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
                }
            }
            onDone()
        }
    }
}

@Composable
private fun PillIcon(item: PillItem, play: PillPlay, label: String?) {
    Icon(
        item.icon,
        contentDescription = label,
        tint = item.tint ?: MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(24.dp).graphicsLayer {
            translationY = play.drop.value * 12.dp.toPx()
            rotationZ = play.turn.value
            if (item.motion == PillMotion.DROP) alpha = 1f - play.drop.value * 0.8f
        }
    )
}

/** A button of the pill: its motion plays, then the action runs. */
@Composable
private fun PillButton(item: PillItem, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val play = remember { PillPlay(scope) }
    IconButton(onClick = { play.run(item.motion, onDone) }, modifier = Modifier.size(48.dp)) {
        PillIcon(item, play, item.label)
    }
}

/** A tile of the square menu: the icon over its name, the same motion. */
@Composable
private fun PillTile(item: PillItem, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val play = remember { PillPlay(scope) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(92.dp)
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable { play.run(item.motion, onDone) }
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        PillIcon(item, play, null)
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Centres a popup on [point] of its anchor, kept on screen. */
private class AtPoint(val point: IntOffset, val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = anchorBounds.left + point.x - popupContentSize.width / 2
        val y = anchorBounds.top + point.y - popupContentSize.height / 2
        return IntOffset(
            x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        )
    }
}

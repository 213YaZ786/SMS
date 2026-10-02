package com.sms.app.feature.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.sms.app.ui.component.FloatingPane
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.icon.AppIcons
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/** What the + offers to send with the text. */
enum class Drop(val icon: ImageVector, val label: String) {
    PHOTOS(AppIcons.Image, "Photos"),
    CAMERA(AppIcons.PhotoCamera, "Camera"),
    FILE(AppIcons.AttachFile, "File"),
    CONTACT(AppIcons.ContactPage, "Contact")
}

private val ButtonSize = 52.dp
private val DropSize = 52.dp
private val Radius = 104.dp

/** Where each drop settles: a quarter of a circle around the +, from straight up to its right. */
private val Angles = listOf(90.0, 60.0, 30.0, 0.0)

/**
 * The + of the composer, in drops of mercury: a tap splits it into four
 * drops that spring out on an arc around it, far from Send; a tap on a
 * drop picks it. Held and slid, the drop under the finger swells and
 * names itself, and letting go picks it. The drops come back into the +.
 * [open] tells the composer to veil its field while they are out.
 */
@Composable
fun AttachArc(open: Boolean, onOpen: (Boolean) -> Unit, onPick: (Drop) -> Unit) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val radius = with(density) { Radius.toPx() }
    val reach = with(density) { (DropSize / 2 + 8.dp).toPx() }
    var hover by remember { mutableIntStateOf(-1) }
    val turn by animateFloatAsState(if (open) 45f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "turn")

    fun dropAt(from: Offset): Int {
        Angles.forEachIndexed { i, a ->
            val r = Math.toRadians(a)
            val centre = Offset((radius * cos(r)).toFloat(), (-radius * sin(r)).toFloat())
            if (hypot((from - centre).x, (from - centre).y) < reach) return i
        }
        return -1
    }

    Box {
        FloatingPane(
            shape = CircleShape,
            modifier = Modifier.size(ButtonSize).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    var sliding = false
                    var opened = false
                    val wasOpen = open
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val moved = change.position - down.position
                        if (!sliding && hypot(moved.x, moved.y) > viewConfiguration.touchSlop) {
                            sliding = true
                            if (!wasOpen) {
                                opened = true
                                haptics.tick()
                                onOpen(true)
                            }
                        }
                        if (sliding) {
                            val now = dropAt(change.position - centre)
                            if (now != hover) {
                                hover = now
                                if (now >= 0) haptics.tick()
                            }
                            change.consume()
                        }
                    }
                    if (sliding) {
                        val picked = hover
                        hover = -1
                        if (picked >= 0) {
                            haptics.firm()
                            onOpen(false)
                            onPick(Drop.entries[picked])
                        } else if (opened) {
                            onOpen(false)
                        }
                    } else {
                        haptics.tick()
                        onOpen(!wasOpen)
                    }
                }
            }
        ) {
            Box(Modifier.size(ButtonSize), contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.Add,
                    contentDescription = if (open) "Close" else "Add a photo, a file or a contact",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.graphicsLayer { rotationZ = turn }
                )
            }
        }
        if (open) {
            val pad = with(density) { (Radius + DropSize).roundToPx() }
            Popup(
                popupPositionProvider = remember(pad) { ArcPlace(pad) },
                onDismissRequest = { onOpen(false) },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true, clippingEnabled = false)
            ) {
                // The popup's box: its corner at the +'s centre, the drops around it.
                val side = with(density) { (pad * 2).toDp() }
                Box(Modifier.size(side)) {
                    Drop.entries.forEachIndexed { i, drop ->
                        MercuryDrop(drop, i, Angles[i], hovered = hover == i, centre = pad) {
                            haptics.firm()
                            onOpen(false)
                            onPick(drop)
                        }
                    }
                }
            }
        }
    }
}

/** One drop: rolls out of the + along its ray, spinning into place, a little after the one before. */
@Composable
private fun MercuryDrop(drop: Drop, order: Int, angle: Double, hovered: Boolean, centre: Int, onClick: () -> Unit) {
    val density = LocalDensity.current
    val out = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(35L * order)
        out.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
    }
    val swell by animateFloatAsState(if (hovered) 1.3f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "swell")
    val r = Math.toRadians(angle)
    val radius = with(density) { Radius.toPx() }
    val half = with(density) { (DropSize / 2).roundToPx() }
    Box(
        Modifier.offset {
            val d = radius * out.value
            IntOffset(centre + (d * cos(r)).roundToInt() - half, centre - (d * sin(r)).roundToInt() - half)
        }
    ) {
        FloatingPane(
            shape = CircleShape,
            accent = hovered,
            onClick = onClick,
            modifier = Modifier.size(DropSize).graphicsLayer {
                val s = (0.4f + 0.6f * out.value) * swell
                scaleX = s
                scaleY = s
                rotationZ = -180f * (1f - out.value)
                alpha = out.value.coerceIn(0f, 1f)
            }
        ) {
            Box(Modifier.size(DropSize), contentAlignment = Alignment.Center) {
                Icon(drop.icon, contentDescription = drop.label, tint = MaterialTheme.colorScheme.primary)
            }
        }
        // Its name, while the finger is on it.
        if (hovered) {
            FloatingPane(shape = CircleShape, modifier = Modifier.offset(y = -(DropSize + 4.dp)).align(Alignment.Center)) {
                Text(drop.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

/** Places the drops' box so that its centre sits on the + button's centre. */
private class ArcPlace(val pad: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(anchorBounds.center.x - pad, anchorBounds.center.y - pad)
}

package com.yaz.sms.feature.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.delay

/** What the + offers to send with the text. */
enum class Drop(val icon: ImageVector, val label: String) {
    EFFECTS(AppIcons.AutoAwesome, "Effects"),
    PHOTOS(AppIcons.Image, "Photos"),
    CAMERA(AppIcons.PhotoCamera, "Camera"),
    FILE(AppIcons.AttachFile, "File"),
    CONTACT(AppIcons.ContactPage, "Contact"),
    PLACE(AppIcons.Place, "Place"),
    POLL(AppIcons.Poll, "Poll")
}

private val ButtonSize = 52.dp

/**
 * The + of the composer: a tap opens, above the message bar, rows of large
 * tiles (photos, camera, file, contact, place, poll), each its icon and its
 * name; a tap on one picks it. [open] tells the composer to veil its field
 * while the panel is out.
 */
@Composable
fun AttachArc(open: Boolean, onOpen: (Boolean) -> Unit, drops: List<Drop> = Drop.entries.filter { it != Drop.POLL }, onPick: (Drop) -> Unit) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val turn by animateFloatAsState(if (open) 45f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "turn")

    Box {
        // A tap opens the panel of tiles above the message bar, a tap again closes it.
        FloatingPane(
            shape = CircleShape,
            onClick = {
                haptics.tick()
                onOpen(!open)
            },
            modifier = Modifier.size(ButtonSize)
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
            val lift = with(density) { (ButtonSize + 12.dp).roundToPx() }
            val margin = with(density) { 12.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(lift, margin) { AbovePlace(lift, margin) },
                onDismissRequest = { onOpen(false) },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true)
            ) {
                // Rows of tiles, three to a row: an icon and its name, large enough to hit.
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(max = 420.dp).graphicsLayer {
                        val k = appear.value
                        alpha = k.coerceIn(0f, 1f)
                        translationY = (1f - k) * 40f
                        scaleX = 0.92f + 0.08f * k
                        scaleY = 0.92f + 0.08f * k
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                    }
                ) {
                    androidx.compose.foundation.layout.Column(
                        Modifier.padding(10.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                    ) {
                        drops.chunked(3).forEachIndexed { row, line ->
                            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                line.forEachIndexed { col, drop ->
                                    Tile(drop, order = row * 3 + col) {
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
        }
    }
}

/** A tile of the panel: the icon on a round, its name under it; pops in a little after the one before. */
@Composable
private fun Tile(drop: Drop, order: Int, onClick: () -> Unit) {
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        delay(30L * order)
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
    }
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(width = 104.dp, height = 96.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
            .clickable(onClickLabel = drop.label, onClick = onClick)
            .padding(top = 10.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(drop.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(drop.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The panel's place: its lower left corner above the +, kept on screen. */
private class AbovePlace(val lift: Int, val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(
            anchorBounds.left.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            (anchorBounds.bottom - lift - popupContentSize.height).coerceAtLeast(margin)
        )
}

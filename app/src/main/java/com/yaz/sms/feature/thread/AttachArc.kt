package com.yaz.sms.feature.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
 * The + of the composer: a tap shows, above it, what can go with a message
 * ([AttachTiles], drawn by the composer); a tap again puts them away.
 * [open] tells the composer to veil its field while they are out.
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
    }
}

/**
 * What the + offers, over the conversation: standalone round buttons of
 * glass, each with its name under it, in rows above the +, popping in one
 * after the other. Drawn in the composer (not a popup) so the glass bends
 * what lies under it.
 */
@Composable
fun AttachTiles(drops: List<Drop>, onPick: (Drop) -> Unit) {
    val haptics = rememberHaptics()
    androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(bottom = 10.dp)
    ) {
        drops.chunked(4).forEachIndexed { row, line ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                line.forEachIndexed { col, drop ->
                    Tile(drop, order = row * 4 + col) {
                        haptics.firm()
                        onPick(drop)
                    }
                }
            }
        }
    }
}

/** One of them: a round of glass with the icon, its name under it; pops in a little after the one before. */
@Composable
private fun Tile(drop: Drop, order: Int, onClick: () -> Unit) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(35L * order)
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
    }
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 64.dp).graphicsLayer {
            scaleX = 0.5f + 0.5f * pop.value
            scaleY = 0.5f + 0.5f * pop.value
            alpha = pop.value.coerceIn(0f, 1f)
            translationY = (1f - pop.value) * 30f
        }
    ) {
        FloatingPane(shape = CircleShape, onClick = onClick, modifier = Modifier.size(58.dp)) {
            Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                Icon(drop.icon, contentDescription = drop.label, tint = MaterialTheme.colorScheme.primary)
            }
        }
        // The name on a small pane of its own, readable over any message.
        FloatingPane(shape = CircleShape, modifier = Modifier.padding(top = 4.dp)) {
            Text(drop.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

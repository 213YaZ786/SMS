package com.yaz.sms.feature.thread

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ShapeDefaults
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
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.text.input.maxLength
import androidx.core.text.isDigitsOnly
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.yaz.sms.ui.component.BoldButton
import com.yaz.sms.ui.component.BoldIconButton
import com.yaz.sms.ui.component.QuietButton
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** One action of a message's menu: its icon, its word, red when it takes something away. */
data class MessageAction(val icon: ImageVector, val label: String, val danger: Boolean = false, val run: () -> Unit)

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
    onMoreReactions: (() -> Unit)? = null,
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
            // The side of the message: the user's own on the right. The reaction bar and the actions hang off the
            // message's own bounds (Signal's ReactionOverlayPlacement, see MenuPlacement), inside the conversation
            // column, which on a tablet is narrower than the window.
            val columnInset = with(density) { com.yaz.sms.navigation.LocalReadableInset.current.roundToPx() }
            fun xFor(width: Int): Int =
                MenuPlacement.x(!mine, bounds.left.roundToInt(), bounds.right.roundToInt(), width, window.width, columnInset, edge)
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
                        // Any other emoji, from Android's picker.
                        onMoreReactions?.let { more ->
                            Box(
                                Modifier.size(44.dp).clickable(onClickLabel = "More reactions") {
                                    haptics.tick()
                                    onDismiss()
                                    more()
                                },
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                androidx.compose.material3.Icon(com.yaz.sms.ui.icon.AppIcons.Add, contentDescription = "More reactions", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            Palette(
                actions,
                onDismiss,
                Modifier
                    .offset { IntOffset(xFor(listSize.width), listY) }
                    .onSizeChanged { listSize = it }
            )
        }
    }
}

/**
 * The actions as a palette: a pane of glass with a tile for each, the
 * icon over its word, four to a line, rising one by one. Nothing scrolls,
 * everything reads at a glance; what takes something away is in red.
 */
@Composable
fun Palette(actions: List<MessageAction>, onDismiss: () -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    // Lines as even as can be: 5 = 3 + 2, 6 = 3 + 3, 7 = 4 + 3, 8 = 4 + 4.
    val lines = (actions.size + 3) / 4
    val perLine = if (lines == 0) 1 else (actions.size + lines - 1) / lines
    // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
    ZoneSurface(shape = ShapeDefaults.ExtraLarge, shadowElevation = 6.dp, modifier = modifier.popIn(1)) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            actions.chunked(perLine).forEachIndexed { line, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { i, action ->
                        val rise = remember { Animatable(1f) }
                        LaunchedEffect(Unit) {
                            delay(30L * (line * perLine + i))
                            rise.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 500f))
                        }
                        val tint = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(78.dp)
                                .graphicsLayer {
                                    translationY = rise.value * 8.dp.toPx()
                                    alpha = 1f - rise.value
                                }
                                .clip(ShapeDefaults.Large)
                                .clickable(onClickLabel = action.label) {
                                    haptics.tick()
                                    onDismiss()
                                    action.run()
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            // Each action in a drop of polished glass of its own (rim, bevel, shadow), the
                            // colour in the icon only: an accent wash flattened them into plain discs.
                            ZoneSurface(shape = CircleShape, modifier = Modifier.size(56.dp)) {
                                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                action.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A palette under something tapped (the name at the top of a
 * conversation), over a veil that closes it.
 */
@Composable
fun PaletteMenu(bounds: Rect, actions: List<MessageAction>, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val edge = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 10.dp.roundToPx() }
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
            var size by remember { mutableStateOf(IntSize.Zero) }
            Palette(
                actions,
                onDismiss,
                Modifier
                    .offset { IntOffset(((window.width - size.width) / 2).coerceAtLeast(edge), bounds.bottom.roundToInt() + gap) }
                    .onSizeChanged { size = it }
            )
        }
    }
}

/** The most copies "Send again" makes at once, so that the carrier does not take them for spam. */
const val SEND_AGAIN_MAX = 10

/**
 * The seconds between two copies of "Send again": the idea of a pause between two messages of a run, so the carrier
 * does not take them for spam, from https://github.com/CodingInfinite/BulkSMSSender and
 * https://github.com/AhsenSaeed/AndroidBulkSmsSender (no licence file: idea only, no code taken).
 */
const val SEND_AGAIN_GAP = 2

/**
 * Material Icons "remove" (Apache-2.0, https://github.com/google/material-design-icons, src/content/remove/materialicons/24px.svg),
 * the minus of the count; the plus is AppIcons.Add. Built as AppIcons.build does.
 */
private val Minus: ImageVector by lazy {
    ImageVector.Builder("Remove", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString("M19 13H5v-2h14v2z").toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black)
        )
    }.build()
}

/**
 * "Send again": the words of the user's message (first lines), a count from 1 to [SEND_AGAIN_MAX] chosen with a
 * round glass button each side of the number, Send and Cancel. The user's own idea ("le dupliquer X fois, X
 * ajustable"): no messaging app has it, so it is built on this app's own parts: ZoneAlertDialog (the app's dialog),
 * BoldIconButton, BoldButton and QuietButton (the app's glass buttons, Material 3 shapes), and the queueing
 * itself is ThreadScreen's sendAgain over core/sms/PendingSends. The count steps on Material 3's stepper idea
 * (a number between a minus and a plus); haptics are the app's own (tick on a step, done on Send, reject at a bound).
 */
@Composable
fun SendAgainDialog(words: String, attachments: Int, onSend: (Int) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    // The count is typed in a small field (Android's text input docs: a state-based BasicTextField, digits only
    // by an InputTransformation), or stepped with the buttons, which rewrite the field. Empty or 0 is no count.
    val field = androidx.compose.foundation.text.input.rememberTextFieldState("1")
    val typed = field.text.toString().toIntOrNull()
    val count = typed?.takeIf { it in 1..SEND_AGAIN_MAX }
    val number = typed ?: 0
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send again") },
        text = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val shown = words.ifBlank { if (attachments == 1) "1 attachment" else "$attachments attachments" }
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    BoldIconButton(onClick = {
                        if (number > 1) {
                            field.setTextAndPlaceCursorAtEnd((number - 1).toString())
                            haptics.tick()
                        } else haptics.reject()
                    }, enabled = number > 1) { Icon(Minus, contentDescription = "Fewer copies") }
                    CountField(field)
                    BoldIconButton(onClick = {
                        if (number < SEND_AGAIN_MAX) {
                            field.setTextAndPlaceCursorAtEnd((number + 1).toString())
                            haptics.tick()
                        } else haptics.reject()
                    }, enabled = number < SEND_AGAIN_MAX) { Icon(com.yaz.sms.ui.icon.AppIcons.Add, contentDescription = "More copies") }
                }
            }
        },
        confirmButton = {
            BoldButton(filled = true, enabled = count != null, onClick = {
                haptics.done()
                count?.let(onSend)
            }) { Text("Send") }
        },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The count of "Send again" typed directly: a state-based BasicTextField, number keyboard, one line, two digits
 * at most and digits only, from Android's text input docs (the phone-number example:
 * `InputTransformation.maxLength(10).then { if (!asCharSequence().isDigitsOnly()) revertAllChanges() }`),
 * https://developer.android.com/develop/ui/compose/text/user-input
 * A number over [SEND_AGAIN_MAX] is replaced by what was just typed (held from 1 to the maximum), so the field never shows a count that will not be sent.
 * Digits centred, in the size of the number it replaces.
 */
@Composable
private fun CountField(state: androidx.compose.foundation.text.input.TextFieldState) {
    // Over the maximum, what was just typed replaces the number ("3" in a field holding "1" gives 3, not 13
    // or 31): the new text minus `originalText`, the buffer's text before the edit. InputTransformation and
    // TextFieldBuffer.originalText, Compose's user-input page:
    // https://developer.android.com/develop/ui/compose/text/user-input
    val transformation = remember {
        androidx.compose.foundation.text.input.InputTransformation {
            val now = asCharSequence().toString()
            if (!now.isDigitsOnly()) {
                revertAllChanges()
            } else if ((now.toIntOrNull() ?: 0) > SEND_AGAIN_MAX) {
                val before = originalText.toString()
                val typedNow = when {
                    before.isNotEmpty() && now.startsWith(before) -> now.removePrefix(before)
                    before.isNotEmpty() && now.endsWith(before) -> now.removeSuffix(before)
                    else -> now.takeLast(1)
                }.ifEmpty { now.takeLast(1) }
                replace(0, length, (typedNow.toIntOrNull() ?: SEND_AGAIN_MAX).coerceIn(1, SEND_AGAIN_MAX).toString())
            }
        }.then(androidx.compose.foundation.text.input.InputTransformation.maxLength(2))
    }
    androidx.compose.foundation.text.BasicTextField(
        state = state,
        modifier = Modifier
            .width(72.dp)
            .clip(ShapeDefaults.Large)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(vertical = 6.dp)
            // Tapped, the number is selected whole, so "3" typed replaces it (with the cursor before
            // the 1, "3" made 31, held at 10). TextFieldState.edit and its selection, Compose's
            // user-input page: https://developer.android.com/develop/ui/compose/text/user-input
            .onFocusChanged { focus ->
                if (focus.isFocused) state.edit { selection = androidx.compose.ui.text.TextRange(0, length) }
            }
            .semantics { contentDescription = "Number of copies" },
        textStyle = MaterialTheme.typography.headlineMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        ),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
        ),
        lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
        inputTransformation = transformation,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
    )
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

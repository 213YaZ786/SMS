package com.yaz.sms.feature.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.isSpecified
import com.yaz.sms.ui.component.rememberGroupLabelSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    // The pane's height with all its rows, reported by Palette, whatever room it is given.
    var naturalPane by remember { mutableStateOf(0) }
    // The conversation's own window: the bounds are in its coordinates, the dialog's window may sit elsewhere.
    val host = androidx.compose.ui.platform.LocalView.current
    val heldBounds = bounds
    // A window of its own over the conversation, blurred behind it, as PillMenu does (this app's
    // ui/component/PillMenu.kt, Modules' panes kit; AOSP's window-blur recipe,
    // https://source.android.com/docs/core/display/window-blurs): Dialog with usePlatformDefaultWidth = false and
    // decorFitsSystemWindows = false, BlurBehind and rememberCrossWindowBlur from ui/component/ZoneDialog.kt.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // Blur off (battery saver, GrapheneOS): BlurBehind gives a stronger dim, the same as PillMenu's.
        val blur = com.yaz.sms.ui.component.rememberCrossWindowBlur()
        com.yaz.sms.ui.component.BlurBehind(radiusDp = 24, blur = blur)
        // Where the dialog's window starts on the screen, against the conversation's: PillMenu maps the item's
        // bounds the same way (item = state.bounds.translate(-at), positionOnScreen).
        var origin by remember { mutableStateOf<Offset?>(null) }
        val bounds = origin?.let { at ->
            val hostAt = IntArray(2).also { host.getLocationOnScreen(it) }
            heldBounds.translate(Offset(hostAt[0].toFloat(), hostAt[1].toFloat()) - at)
        } ?: heldBounds
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionOnScreen() }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        ) {
            var window by remember { mutableStateOf(IntSize.Zero) }
            Box(Modifier.fillMaxSize().onSizeChanged { window = it })
            // The reaction bar and the actions are centred on the message's own bounds (see MenuPlacement), inside
            // the conversation column, which on a tablet is narrower than the window.
            val columnInset = with(density) { com.yaz.sms.navigation.LocalReadableInset.current.roundToPx() }
            fun xFor(width: Int): Int =
                MenuPlacement.x(bounds.left.roundToInt(), bounds.right.roundToInt(), width, window.width, columnInset, edge)
            // The message itself, above the veil, lifted a little.
            val rise = remember { Animatable(0f) }
            LaunchedEffect(Unit) { rise.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }
            // The room is the window less the system bars (WindowInsets.safeDrawing, Android's inset types,
            // https://developer.android.com/develop/ui/compose/system/insets) and the edge margin: the reactions
            // bar, the lifted message and the pane all stay inside it. As long as the pane, whole, fits under the
            // message or above it, everything hangs off the message's own place. Otherwise (the 200 % font, a tall
            // message) the three are stacked by priority from the message's place: the reactions bar always whole,
            // then the message, whole if it fits with a first part of the pane, else clipped to a quarter of the
            // window with a soft fade at the cut (Signal's long press shows a clipped preview of a long message),
            // then the pane takes the rest and its grid scrolls.
            val bars = WindowInsets.safeDrawing
            val top = bars.getTop(density) + edge
            val bottomLimit = window.height - bars.getBottom(density) - edge
            val msgTop = bounds.top.roundToInt()
            val msgH = bounds.height.roundToInt()
            // The lift below (scale 1.05 around the centre, 4 dp up) draws the message beyond its bounds: graphicsLayer
            // changes only the drawing, never the layout (TransformOrigin.Center,
            // https://developer.android.com/develop/ui/compose/graphics/draw/modifiers). Its top edge rises by half the
            // growth plus 4 dp, its bottom edge drops by half the growth less 4 dp; the gaps are kept beyond those
            // edges, else a tall bubble at 200 % touches the reactions bar.
            val rise4 = with(density) { 4.dp.roundToPx() }
            val liftTop = (msgH * 0.025f).roundToInt() + rise4
            val liftBottom = ((msgH * 0.025f).roundToInt() - rise4).coerceAtLeast(0)
            val reactionsH = if (reactions != null) rowSize.height + gap + liftTop else 0
            val below = bounds.bottom.roundToInt() + liftBottom + gap
            val roomBelow = bottomLimit - below
            val roomAbove = msgTop - (if (reactions != null) reactionsH else liftTop) - gap - top
            val fitsBelow = naturalPane <= roomBelow
            val fitsAbove = naturalPane <= roomAbove
            // What goes above the message's lifted top: the reactions bar and its gap, or only the lift.
            val headH = if (reactions != null) reactionsH else liftTop
            // A message partly scrolled out at the top or the bottom is brought fully on screen with the rest
            // stacked around it, never with the bar pinned to the edge over it.
            val offScreen = msgTop - headH < top || bounds.bottom.roundToInt() + liftBottom > bottomLimit
            val stacked = window.height > 0 && (offScreen || (!fitsBelow && !fitsAbove))
            val total = bottomLimit - top
            val paneMin = minOf(naturalPane, with(density) { 220.dp.roundToPx() })
            val shownH = if (!stacked || total - headH - gap - liftBottom - msgH >= paneMin) msgH else minOf(msgH, window.height / 4)
            val paneRoom = total - headH - shownH - liftBottom - gap
            val stackH = headH + shownH + liftBottom + gap + minOf(naturalPane, paneRoom).coerceAtLeast(0)
            val stackTop = (msgTop - headH).coerceIn(top, maxOf(top, bottomLimit - stackH))
            val msgY = if (stacked) stackTop + headH else msgTop
            val listY = when {
                stacked -> msgY + shownH + liftBottom + gap
                fitsBelow -> below
                reactions != null -> (msgTop - gap - reactionsH - listSize.height).coerceAtLeast(top)
                else -> (msgTop - liftTop - gap - listSize.height).coerceAtLeast(top)
            }
            val rowY = when {
                stacked -> stackTop
                fitsBelow -> (msgTop - liftTop - gap - rowSize.height).coerceAtLeast(top)
                else -> listY + listSize.height + gap
            }
            val paneMax = when {
                window.height <= 0 -> Dp.Unspecified
                stacked -> with(density) { paneRoom.coerceAtLeast(0).toDp() }
                else -> with(density) {
                    maxOf(roomBelow, roomAbove).coerceAtLeast(220.dp.roundToPx()).coerceAtMost(total - reactionsH).toDp()
                }
            }
            // The message itself, above the veil, lifted a little; clipped with a fade when only part of it fits.
            val clipped = shownH < msgH
            Box(
                Modifier
                    .offset { IntOffset(bounds.left.roundToInt(), msgY) }
                    .size(with(density) { bounds.width.toDp() }, with(density) { shownH.toDp() })
                    .graphicsLayer {
                        val s = 1f + 0.05f * rise.value
                        scaleX = s
                        scaleY = s
                        translationY = -4.dp.toPx() * rise.value
                    }
                    // Cut short, the end fades out instead of ending on a hard line: the layer is drawn offscreen and a
                    // gradient from opaque to transparent is drawn over its last 48 dp with BlendMode.DstIn, the bottom
                    // edge of OuterTune's fadingEdge (GPL-3.0,
                    // https://github.com/OuterTune/OuterTune/blob/main/app/src/main/java/com/dd3boh/outertune/ui/utils/FadingEdge.kt),
                    // clipped to the bounds first (https://developer.android.com/develop/ui/compose/graphics/draw/modifiers).
                    .then(
                        if (clipped) {
                            Modifier
                                .clipToBounds()
                                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(Color.Black, Color.Transparent),
                                            startY = size.height - 48.dp.toPx(),
                                            endY = size.height
                                        ),
                                        blendMode = BlendMode.DstIn
                                    )
                                }
                        } else Modifier
                    )
            ) {
                if (clipped) {
                    Box(Modifier.wrapContentSize(androidx.compose.ui.Alignment.TopStart, unbounded = true).size(with(density) { bounds.width.toDp() }, with(density) { msgH.toDp() })) { held() }
                } else held()
            }

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
                    .onSizeChanged { listSize = it },
                maxHeight = paneMax,
                onNatural = { naturalPane = it }
            )
        }
    }
}

/**
 * Tiles of words in a flow that adapts, taken from PersonActions in this app (ui/component/PersonActions.kt, the
 * suite's model): the least width of a tile is measured, not guessed, as the widest label on one line in the current
 * style, font scale and language (TextMeasurer, https://developer.android.com/develop/ui/compose/text/measure-text-layout),
 * plus [side] each side, never under [floor]; how many fit a line follows, then the rows are balanced (5 tiles that do
 * not fit one row make 3 + 2, never 4 + 1), and when one line labels leave a lone tile the least width becomes the
 * widest word, so labels wrap between words (FlowRow with maxItemsInEachRow, weight and fillMaxRowHeight,
 * https://developer.android.com/develop/ui/compose/layouts/flow). [maxPerRow] keeps the design's count on a wide
 * window. [fill] spreads the flow over the whole width, else it is as wide as its tiles. [tile] draws one tile with
 * the modifier it must take; its label is a [TileLabel].
 * Official: "Check that your app's layouts adapt correctly without breaking, overlapping, or hiding content"
 * (https://developer.android.com/develop/ui/compose/accessibility/scalable-content).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BalancedFlow(
    labels: List<String>,
    style: TextStyle,
    floor: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    rowGap: Dp = gap,
    side: Dp = 0.dp,
    maxPerRow: Int = 4,
    fill: Boolean = false,
    tile: @Composable FlowRowScope.(index: Int, tileModifier: Modifier) -> Unit
) {
    if (labels.isEmpty()) return
    BoxWithConstraints(if (fill) modifier.fillMaxWidth() else modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val available = maxWidth
        // The widest label on one line and the widest single word, in px.
        val widths = remember(labels, style, density) {
            val oneLine = labels.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
            val word = labels.maxOf { label ->
                label.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
            }
            oneLine to word
        }
        val oneLineWidth = maxOf(floor, with(density) { widths.first.toDp() } + side * 2)
        val wordWidth = maxOf(floor, with(density) { widths.second.toDp() } + side * 2)
        fun perRowFor(width: Dp): Int {
            val fit = ((available + gap) / (width + gap)).toInt().coerceIn(1, maxPerRow)
            val lines = (labels.size + fit - 1) / fit
            return (labels.size + lines - 1) / lines
        }
        fun lone(perRow: Int) = labels.size > perRow && labels.size % perRow == 1
        // One line labels first; the widest word instead (labels wrapping between words, as Google Messages' and iOS's
        // menu labels do) when it gives more tiles to a line, or when the one line width leaves a lone last tile and
        // the word width does not. A lone tile stays only when neither width avoids it.
        val perOneLine = perRowFor(oneLineWidth)
        val perWord = perRowFor(wordWidth)
        val wrapped = !lone(perWord) && (perWord > perOneLine || lone(perOneLine))
        val perRow = if (wrapped) perWord else perOneLine
        val minWidth = minOf(if (wrapped) wordWidth else oneLineWidth, available)
        val inRow = minOf(perRow, labels.size)
        // The row is as wide as its tiles' least widths and gaps added in whole pixels, as FlowRow rounds each of them
        // (Dp.roundToPx): added in dp it came one pixel short of three tiles (3 x 78 dp + 2 x 6 dp = 645.75 px, three
        // tiles of 205 px and two gaps of 16 px need 647), and the third tile of a line dropped to the next.
        val rowWidth = minOf(with(density) { (minWidth.roundToPx() * inRow + gap.roundToPx() * (inRow - 1)).toDp() }, available)
        // One size for every label of the group, computed once from the width a tile gets in a full row
        // (rememberGroupLabelSize, ui/component/GroupLabelSize.kt: the ladder of TextAutoSize.StepBased asked of the
        // whole group); the TileLabels below read it, none sizes itself.
        val tileWidth = ((if (fill) available else rowWidth) - gap * (inRow - 1)) / inRow
        val labelSize = rememberGroupLabelSize(labels, style, tileWidth - side * 2)
        CompositionLocalProvider(LocalGroupLabelSize provides labelSize) {
            FlowRow(
                if (fill) Modifier.fillMaxWidth() else Modifier.width(rowWidth),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(rowGap),
                maxItemsInEachRow = perRow
            ) {
                labels.indices.forEach { i -> tile(i, Modifier.weight(1f).fillMaxRowHeight().widthIn(min = minWidth)) }
            }
        }
    }
}

/** The size a [BalancedFlow] computed for its group; unspecified outside one, where a label sizes itself. */
internal val LocalGroupLabelSize = compositionLocalOf { TextUnit.Unspecified }

/**
 * A halo in the page's own colour for plain text drawn over messages: a TextStyle shadow, offset 0, blur 12 dp,
 * the surface colour at high alpha (https://developer.android.com/develop/ui/compose/text/style-text#add-shadow),
 * so it reads in light and dark over any bubble.
 */
@Composable
internal fun haloShadow(): Shadow =
    Shadow(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), Offset.Zero, with(LocalDensity.current) { 12.dp.toPx() })

/** The text drawn twice at the same place, so its halo is dense enough; the second copy is silent to TalkBack. */
@Composable
internal fun HaloStack(modifier: Modifier = Modifier, draw: @Composable (Modifier) -> Unit) {
    Box(modifier) {
        draw(Modifier)
        draw(Modifier.clearAndSetSemantics {})
    }
}

/**
 * The name of a tile: lines break between words only. In a [BalancedFlow] it is drawn at the one size the group
 * computed (no autosize of its own, so two tiles never read in two sizes); alone, a single word still too wide
 * shrinks as the last resort, Compose's TextAutoSize.StepBased
 * (https://developer.android.com/develop/ui/compose/text/autosize), as the label of PersonActionTile in this app.
 * [halo] puts a halo in the page's colour behind it, for plain text over messages ([haloShadow]).
 */
@Composable
internal fun TileLabel(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, halo: Boolean = false) {
    val group = LocalGroupLabelSize.current
    val grouped = group.isSpecified
    val size = if (grouped && group.value < style.fontSize.value) group else style.fontSize
    val shadow = if (halo) haloShadow() else null
    val look = style.copy(color = color, textAlign = TextAlign.Center, fontSize = size, shadow = shadow ?: style.shadow)
    val draw: @Composable (Modifier) -> Unit = { m ->
        if (grouped) {
            BasicText(text, m, style = look)
        } else {
            BasicText(text, m, style = look, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp))
        }
    }
    if (halo) HaloStack(modifier, draw) else draw(modifier)
}

/**
 * The actions as a palette: a pane of glass with a tile for each, the
 * icon over its word, four to a line at most, rising one by one. Nothing scrolls,
 * everything reads at a glance; what takes something away is in red. The lines
 * are measured and balanced ([BalancedFlow]), so a longer language or a larger
 * font gives fewer to a line and names on two lines, never a cut word.
 */
@Composable
fun Palette(
    actions: List<MessageAction>,
    onDismiss: () -> Unit,
    modifier: Modifier,
    maxHeight: Dp = Dp.Unspecified,
    onNatural: (Int) -> Unit = {}
) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    // The window less the pane's padding and its edge margin on each side.
    val room = (LocalConfiguration.current.screenWidthDp - 44).dp
    // Taller than [maxHeight] (the window less the system bars and the reactions bar, the 200 % font), the grid
    // scrolls inside the pane and the pane stays on screen (Modifier.verticalScroll under heightIn,
    // https://developer.android.com/develop/ui/compose/touch-input/pointer-input/scroll).
    val inner = if (maxHeight.isSpecified) Modifier.heightIn(max = (maxHeight - 24.dp).coerceAtLeast(0.dp)).verticalScroll(rememberScrollState()) else Modifier
    // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
    ZoneSurface(shape = ShapeDefaults.ExtraLarge, shadowElevation = 6.dp, modifier = modifier.popIn(1)) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 12.dp).widthIn(max = room).then(inner)) {
            BalancedFlow(
                labels = actions.map { it.label },
                style = MaterialTheme.typography.labelLarge,
                floor = 78.dp,
                gap = 6.dp,
                rowGap = 10.dp,
                // Inside the scroll the grid is measured whole: its height, with the pane's padding, is the pane's natural one.
                modifier = Modifier.onSizeChanged { onNatural(it.height + with(density) { 24.dp.roundToPx() }) }
            ) { i, tileModifier ->
                val action = actions[i]
                val rise = remember { Animatable(1f) }
                LaunchedEffect(Unit) {
                    delay(30L * i)
                    rise.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 500f))
                }
                val tint = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = tileModifier
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
                    TileLabel(
                        action.label,
                        MaterialTheme.typography.labelLarge,
                        if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
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

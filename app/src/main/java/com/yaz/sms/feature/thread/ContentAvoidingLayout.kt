package com.yaz.sms.feature.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A layout of two children, the content and the overlay: the overlay (a message's time) goes on the
 * content's last line when it fits there, to the right of the content when the content is narrower
 * than its room, else on a row of its own under it.
 *
 * Ported from Element X, AGPL-3.0 (https://github.com/element-hq/element-x-android,
 * libraries/ui-common/src/main/kotlin/io/element/android/libraries/ui/common/layout/ContentAvoidingLayout.kt):
 * the measuring and placing are the same; the test mode column is left out (the screenshot tests draw
 * the real layout), and the measure of the last line is fixed for right-to-left, see [measureLastLine].
 */
@Composable
internal fun ContentAvoidingLayout(
    overlay: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    overlayOffset: DpOffset = DpOffset.Zero,
    shrinkContent: Boolean = false,
    content: @Composable ContentAvoidingLayoutScope.() -> Unit
) {
    val scope = remember { ContentAvoidingLayoutScopeInstance() }
    Layout(
        modifier = modifier,
        content = {
            scope.content()
            overlay()
        }
    ) { measurables, constraints ->
        // The overlay first, in case the content has to shrink for it.
        val overlayPlaceable = measurables.last().measure(Constraints(minWidth = 0, maxWidth = constraints.maxWidth))
        val contentConstraints = if (shrinkContent) {
            Constraints(minWidth = 0, maxWidth = constraints.maxWidth - overlayPlaceable.width)
        } else {
            Constraints(minWidth = 0, maxWidth = constraints.maxWidth)
        }
        val contentPlaceable = measurables.first().measure(contentConstraints)

        var layoutWidth = contentPlaceable.width
        var layoutHeight = contentPlaceable.height

        val data = scope.data.value

        // Free space: the content's width less the part of it the overlay cannot cover.
        val freeSpace = max(contentPlaceable.width - data.nonOverlappingContentWidth, 0)

        when {
            // Content and overlay together are wider than the room: the overlay takes a row of its own.
            !data.canOverlay() || !shrinkContent && data.nonOverlappingContentWidth + overlayPlaceable.width > constraints.maxWidth -> {
                layoutHeight += overlayPlaceable.height + overlayOffset.y.roundToPx()
            }
            // The content is narrower than the room: the overlay may go to its right.
            contentPlaceable.width < constraints.maxWidth -> {
                // Both fit in the width already: nothing to widen.
                if (freeSpace < overlayPlaceable.width + spacing.roundToPx()) {
                    val calculatedWidth = max(data.nonOverlappingContentWidth + overlayPlaceable.width + spacing.roundToPx(), contentPlaceable.width)
                    layoutWidth = min(calculatedWidth, constraints.maxWidth)
                }
            }
            else -> Unit
        }

        layoutWidth = max(layoutWidth, constraints.minWidth)
        layoutHeight = max(layoutHeight, constraints.minHeight)

        layout(layoutWidth, layoutHeight) {
            contentPlaceable.placeRelative(0, 0)
            overlayPlaceable.placeRelative(layoutWidth - overlayPlaceable.width, layoutHeight - overlayPlaceable.height + overlayOffset.y.roundToPx())
        }
    }
}

/**
 * What the content tells the layout, in pixels, as Element X's ContentAvoidingLayoutData:
 * its full size, and the size of the part the overlay cannot cover.
 */
internal data class ContentAvoidingLayoutData(
    val contentWidth: Int = 0,
    val contentHeight: Int = 0,
    val nonOverlappingContentWidth: Int = contentWidth,
    val nonOverlappingContentHeight: Int = contentHeight
) {
    fun canOverlay(): Boolean = contentWidth != Int.MAX_VALUE

    companion object {
        val NotOverlapping = ContentAvoidingLayoutData(contentWidth = Int.MAX_VALUE, contentHeight = Int.MAX_VALUE)
    }
}

/** Element X's ContentAvoidingLayoutScope: how the content reports its last line. */
internal interface ContentAvoidingLayoutScope {
    fun onContentLayoutChange(data: ContentAvoidingLayoutData)
}

private class ContentAvoidingLayoutScopeInstance(
    val data: MutableState<ContentAvoidingLayoutData> = mutableStateOf(ContentAvoidingLayoutData())
) : ContentAvoidingLayoutScope {
    override fun onContentLayoutChange(data: ContentAvoidingLayoutData) {
        this.data.value = data
    }
}

/**
 * The text's last line, measured for [ContentAvoidingLayout]: for use as `onTextLayout` of a Text.
 *
 * Ported from Element X, AGPL-3.0 (https://github.com/element-hq/element-x-android,
 * ContentAvoidingLayout.measureLastTextLine), and fixed:
 * - Element X reads getLineLeft whenever the paragraph is right to left. The overlay sits at the
 *   layout's end, so what it must avoid is the line's extent from the start edge to the end edge:
 *   with the layout right to left that is the width less the line's left (a line flush right whose
 *   left is far over leaves little room), not the left itself, which took a full line for an empty
 *   one and the reverse.
 * - Text running against the layout's direction (a Hebrew line in a left to right layout) has its
 *   last line against the overlay's side: nothing may overlay it, as Signal puts the time under
 *   such text (V2FooterPositionDelegate, https://github.com/signalapp/Signal-Android).
 *
 * [extraStart] and [extraEnd] are the padding the Text sits in, outside its own layout.
 */
@Composable
internal fun measureLastLine(
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    extraStart: Dp = 0.dp,
    extraEnd: Dp = 0.dp
): (TextLayoutResult) -> Unit {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val start = with(density) { extraStart.roundToPx() }
    val end = with(density) { extraEnd.roundToPx() }
    return { text: TextLayoutResult ->
        val last = text.lineCount - 1
        val paragraph = runCatching { text.getParagraphDirection(0) }.getOrNull()
        val against = when (paragraph) {
            ResolvedTextDirection.Rtl -> layoutDirection == LayoutDirection.Ltr
            ResolvedTextDirection.Ltr -> layoutDirection == LayoutDirection.Rtl
            else -> false
        }
        if (against) {
            onContentLayoutChange(ContentAvoidingLayoutData.NotOverlapping)
        } else {
            val lineWidth = if (layoutDirection == LayoutDirection.Rtl) {
                text.size.width - text.getLineLeft(last).roundToInt()
            } else {
                text.getLineRight(last).roundToInt()
            }
            onContentLayoutChange(
                ContentAvoidingLayoutData(
                    contentWidth = text.size.width + start + end,
                    contentHeight = text.size.height,
                    nonOverlappingContentWidth = lineWidth + start,
                    nonOverlappingContentHeight = text.getLineBottom(last).roundToInt()
                )
            )
        }
    }
}

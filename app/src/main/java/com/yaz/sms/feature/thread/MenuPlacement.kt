package com.yaz.sms.feature.thread

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the long press overlay puts the held bubble, the reactions bar and the actions pane: Signal Android's
 * ReactionOverlayPlacement (AGPL-3.0), a pure function so it is tested without a screen. The three hang on the
 * bubble's side on ONE shared edge (the start of a received message, the end of a sent one), 12 dp apart, the bubble
 * drawn where it stands with no scale.
 *
 * Ports:
 * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/conversation/ReactionOverlayPlacement.kt#ReactionOverlayPlacement
 * (Metrics, Placement, DpConverter, MENU_PADDING_DP, of, menuOffsetX by isMessageOnLeft, endY and reactionBarY, the
 * clamp at the status bar)
 * not ported: isWideLayout (the menu never sits beside the bar in a phone's conversation)
 * not ported: scrubber (Signal's drag over the emoji)
 * not ported: snapshotScale (no scale: the message is cut instead)
 * not ported: halfOffsetFromScale (no scale)
 * not ported: barOffsetForTouch (it places the bar under the finger: here the bar is always on the bubble)
 * not ported: REACTION_BAR_TOP_PADDING_DP (the bar is not under the finger)
 * not ported: REACTION_BAR_OFFSET_DP (the bar is not under the finger)
 * not ported: TIGHT_MENU_THRESHOLD_DP (no halved menu: the pane scrolls)
 * not ported: lastSeenDownY (no finger position kept)
 *
 * Added for this app: [Metrics.columnInset], the readable column of a tablet, which the shared edge stays inside;
 * and a message without a reactions bar (reactionBarHeight 0), which asks no room above.
 */
object MenuPlacement {

    /** Pixels of the overlay, y growing downwards. */
    data class Metrics(
        val overlayWidth: Int,
        val overlayHeight: Int,
        val statusBarHeight: Int,
        val navigationBarHeight: Int,
        val bubbleX: Float,
        val bubbleY: Float,
        val bubbleWidth: Int,
        val snapshotHeight: Int,
        val reactionBarWidth: Int,
        val reactionBarHeight: Int,
        val menuMaxWidth: Int,
        val menuMaxHeight: Int,
        val isMessageOnLeft: Boolean,
        /** Each side of the window left out of the readable column (0 on a phone). */
        val columnInset: Int = 0
    )

    /** [snapshotHeight] is what shows of the bubble (cut when nothing else fits), [menuHeight] null when the pane is whole. */
    data class Placement(
        val snapshotY: Float,
        val snapshotHeight: Int,
        val reactionBarX: Float,
        val reactionBarY: Float,
        val menuHeight: Int?,
        val menuOffsetX: Float,
        val menuOffsetY: Float
    )

    private const val MENU_PADDING_DP = 12f

    fun interface DpConverter {
        fun toPx(dp: Float): Float
    }

    fun of(metrics: Metrics, dp: DpConverter): Placement {
        val menuPadding = dp.toPx(MENU_PADDING_DP)
        val roomTop = metrics.statusBarHeight + menuPadding
        val overlayHeight = (metrics.overlayHeight - metrics.navigationBarHeight).toFloat()
        val roomBottom = overlayHeight - menuPadding
        val reactionBarHeight = metrics.reactionBarHeight
        // No bar (a message that takes no reaction): nothing asked above the message.
        val barBlock = if (reactionBarHeight > 0) reactionBarHeight + menuPadding else 0f
        val menuHeight = metrics.menuMaxHeight.toFloat()
        var snapshotHeight = metrics.snapshotHeight
        var endY = metrics.bubbleY
        var menuHeightOverride: Int? = null
        val reactionBarY: Float
        val menuOffsetY: Float

        // The bar needs its room above the message: a message too high is pushed down (Signal's endY).
        if (endY - barBlock < roomTop) endY = roomTop + barBlock
        val menuFitsBelowItem = endY + snapshotHeight + menuPadding + menuHeight <= roomBottom
        val menuFitsAboveItem = endY - barBlock - menuPadding - menuHeight >= roomTop && endY + snapshotHeight <= roomBottom

        if (menuFitsBelowItem) {
            reactionBarY = endY - menuPadding - reactionBarHeight
            menuOffsetY = endY + snapshotHeight + menuPadding
        } else if (menuFitsAboveItem) {
            // No room under the message: the pane over the bar over the message.
            reactionBarY = endY - menuPadding - reactionBarHeight
            menuOffsetY = (if (reactionBarHeight > 0) reactionBarY else endY) - menuPadding - menuHeight
        } else if (roomTop + barBlock + snapshotHeight + menuPadding + menuHeight <= roomBottom) {
            // Signal's endY = overlayHeight - menuMaxHeight - menuPadding - snapshotHeight.
            endY = roomBottom - menuHeight - menuPadding - snapshotHeight
            reactionBarY = endY - menuPadding - reactionBarHeight
            menuOffsetY = endY + snapshotHeight + menuPadding
        } else {
            // The 200 % font: the bar at the top, the message cut at a quarter of the window, the pane scrolls.
            snapshotHeight = min(snapshotHeight, (overlayHeight / 4f).roundToInt())
            endY = roomTop + barBlock
            reactionBarY = roomTop
            menuOffsetY = endY + snapshotHeight + menuPadding
            menuHeightOverride = max(roomBottom - menuOffsetY, 0f).toInt()
        }

        // The strip never rises past the status bar.
        val clampedReactionBarY = max(reactionBarY, -metrics.statusBarHeight.toFloat())

        // One shared edge: the bubble's start for a received message, its end for a sent one, held inside the column.
        fun edgeX(width: Int): Float {
            val x = if (metrics.isMessageOnLeft) metrics.bubbleX else -width + metrics.bubbleX + metrics.bubbleWidth
            val low = metrics.columnInset + menuPadding
            val high = metrics.overlayWidth - metrics.columnInset - menuPadding - width
            return if (high < low) low else x.coerceIn(low, high)
        }

        return Placement(
            snapshotY = endY,
            snapshotHeight = snapshotHeight,
            reactionBarX = edgeX(metrics.reactionBarWidth),
            reactionBarY = clampedReactionBarY,
            menuHeight = menuHeightOverride,
            menuOffsetX = edgeX(metrics.menuMaxWidth),
            menuOffsetY = menuOffsetY
        )
    }
}

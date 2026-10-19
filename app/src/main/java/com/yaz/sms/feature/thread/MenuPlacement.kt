package com.yaz.sms.feature.thread

/**
 * Where the long press panel and the reaction bar sit across the window, on the lifted message's own bounds.
 *
 * Ported from Signal Android (AGPL-3.0, https://github.com/signalapp/Signal-Android),
 * app/src/main/java/org/thoughtcrime/securesms/conversation/ReactionOverlayPlacement.kt, the branch where the menu
 * goes under the message (not beside the strip): `menuOffsetX = if (isMessageOnLeft) contextMenuX else
 * -menuMaxWidth + contextMenuX + bubbleWidth`, so the menu starts at the message's leading edge for a message on
 * the left and ends at its trailing edge for one on the right. Signal's overlay is as wide as the conversation;
 * here the window is wider than the conversation column on a tablet, so the result is held inside the column
 * (the readable column, the window less [columnInset] on each side) with an [edge] margin.
 */
object MenuPlacement {

    /**
     * The x of a panel [width] wide for a message at [bubbleLeft] to [bubbleRight], on the left when [onLeft]. All in
     * pixels of the window. The panel keeps inside [columnInset] to `windowWidth - columnInset`, [edge] from its sides;
     * wider than that, it starts at the column's left margin.
     */
    fun x(onLeft: Boolean, bubbleLeft: Int, bubbleRight: Int, width: Int, windowWidth: Int, columnInset: Int, edge: Int): Int {
        val anchored = if (onLeft) bubbleLeft else -width + bubbleLeft + (bubbleRight - bubbleLeft)
        val low = columnInset + edge
        val high = windowWidth - columnInset - edge - width
        return if (high < low) low else anchored.coerceIn(low, high)
    }
}

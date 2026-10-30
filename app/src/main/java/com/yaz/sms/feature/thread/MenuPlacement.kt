package com.yaz.sms.feature.thread

import kotlin.math.roundToInt

/**
 * Where the long press panel and the reaction bar sit across the window, on the lifted message's own bounds.
 *
 * Both are centred on the message (the user, 2026-10-30), with the offset of Compose's BiasAlignment.Horizontal at
 * bias 0, `(space - size) / 2f` rounded (androidx, compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/Alignment.kt,
 * https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/Alignment.kt),
 * the space being the message's width. The result is then held inside the conversation column as Signal Android's
 * ReactionOverlayPlacement keeps its menu inside the overlay (AGPL-3.0, https://github.com/signalapp/Signal-Android,
 * app/src/main/java/org/thoughtcrime/securesms/conversation/ReactionOverlayPlacement.kt): the readable column is the
 * window less [columnInset] on each side, with an [edge] margin.
 */
object MenuPlacement {

    /**
     * The x of a panel [width] wide for a message at [bubbleLeft] to [bubbleRight]. All in pixels of the window. The
     * panel keeps inside [columnInset] to `windowWidth - columnInset`, [edge] from its sides; wider than that, it
     * starts at the column's left margin.
     */
    fun x(bubbleLeft: Int, bubbleRight: Int, width: Int, windowWidth: Int, columnInset: Int, edge: Int): Int {
        val centred = bubbleLeft + ((bubbleRight - bubbleLeft - width).toFloat() / 2f).roundToInt()
        val low = columnInset + edge
        val high = windowWidth - columnInset - edge - width
        return if (high < low) low else centred.coerceIn(low, high)
    }
}

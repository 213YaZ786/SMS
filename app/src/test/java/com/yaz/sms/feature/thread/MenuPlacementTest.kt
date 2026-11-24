package com.yaz.sms.feature.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signal Android's ReactionOverlayPlacementTest cases that this placement keeps, at density 1.
 *
 * Ports:
 * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/test/java/org/thoughtcrime/securesms/conversation/ReactionOverlayPlacementTest.kt#ReactionOverlayPlacementTest
 * (BASE, DENSITY, place, the narrow layout with room leaves the message where it is, the message lifted when the menu
 * will not fit below, the strip never rises past the status bar, the strip hugs the leading edge for an incoming
 * message and the trailing edge for an outgoing one)
 * not ported: the wide layout tests (no wide layout)
 * not ported: the shrunk message tests (no scale)
 * not ported: the menu halved test (no halved menu: the pane scrolls)
 * not ported: the emoji row centred on the strip background (no scrubber)
 * not ported: the wide menu held on screen (no wide layout)
 * not ported: the wide menu beside the strip (no scrubber)
 */
class MenuPlacementTest {

    private val density1 = MenuPlacement.DpConverter { it }

    // A tall window, a short message near the middle.
    private val base = MenuPlacement.Metrics(
        overlayWidth = 1080, overlayHeight = 2000, statusBarHeight = 60, navigationBarHeight = 40,
        bubbleX = 80f, bubbleY = 800f, bubbleWidth = 600, snapshotHeight = 200,
        reactionBarWidth = 700, reactionBarHeight = 120, menuMaxWidth = 700, menuMaxHeight = 500, isMessageOnLeft = true
    )

    private fun place(m: MenuPlacement.Metrics) = MenuPlacement.of(m, density1)

    @Test
    fun roomLeavesTheMessageWhereItIs() {
        val p = place(base)
        assertEquals(base.bubbleY, p.snapshotY)
        assertEquals(base.bubbleY - 12f - base.reactionBarHeight, p.reactionBarY)
        // Equal gaps: bar, message, pane 12 apart.
        assertEquals(12f, p.menuOffsetY - (p.snapshotY + base.snapshotHeight))
        assertEquals(null, p.menuHeight)
    }

    @Test
    fun menuNotFittingBelowGoesAboveTheBar() {
        val p = place(base.copy(bubbleY = 1300f))
        assertEquals(1300f, p.snapshotY)
        assertEquals(p.reactionBarY - 12f - base.menuMaxHeight, p.menuOffsetY)
    }

    @Test
    fun menuFittingNowhereLiftsTheMessage() {
        val p = place(base.copy(bubbleY = 1700f, snapshotHeight = 200, menuMaxHeight = 1500))
        assertEquals(1960f - 12f - 1500f - 12f - 200f, p.snapshotY)
    }

    @Test
    fun theStripNeverRisesPastTheStatusBar() {
        listOf(base.copy(bubbleY = -400f), base.copy(bubbleY = 0f), base.copy(overlayHeight = 700, snapshotHeight = 600)).forEach {
            assertTrue(place(it).reactionBarY >= -it.statusBarHeight.toFloat())
        }
    }

    @Test
    fun threeShareTheBubblesSideEdge() {
        val incoming = place(base)
        assertEquals(base.bubbleX, incoming.reactionBarX)
        assertEquals(base.bubbleX, incoming.menuOffsetX)
        val outgoing = place(base.copy(isMessageOnLeft = false, bubbleX = 400f))
        val right = 400f + base.bubbleWidth
        assertEquals(right, outgoing.reactionBarX + base.reactionBarWidth)
        assertEquals(right, outgoing.menuOffsetX + base.menuMaxWidth)
    }

    @Test
    fun sentAndReceivedShareOneEdgeWhateverTheWidths() {
        // Bar and pane of different widths still end on the bubble's end (sent) or start on its start (received).
        val sent = place(base.copy(isMessageOnLeft = false, bubbleX = 400f, reactionBarWidth = 640, menuMaxWidth = 520))
        assertEquals(sent.reactionBarX + 640f, sent.menuOffsetX + 520f)
        assertEquals(1000f, sent.menuOffsetX + 520f)
        val received = place(base.copy(reactionBarWidth = 640, menuMaxWidth = 520))
        assertEquals(received.reactionBarX, received.menuOffsetX)
    }

    @Test
    fun theEdgeStaysInsideTheReadableColumn() {
        // A tablet: 440 px each side outside the column, the 12 margin inside it.
        val tablet = base.copy(overlayWidth = 1920, columnInset = 440, bubbleX = 430f)
        assertEquals(440f + 12f, place(tablet).menuOffsetX)
        val sent = place(tablet.copy(isMessageOnLeft = false, bubbleX = 1100f, bubbleWidth = 400))
        assertEquals(1920f - 440f - 12f, sent.menuOffsetX + base.menuMaxWidth)
    }

    @Test
    fun aPaneWiderThanTheColumnStartsAtItsMargin() {
        val p = place(base.copy(overlayWidth = 1000, columnInset = 100, menuMaxWidth = 900, bubbleX = 500f))
        assertEquals(112f, p.menuOffsetX)
    }

    @Test
    fun font200StacksBarMessageAndPaneWithTheMessageCutAtAQuarter() {
        // A tall pane and a tall message: nothing fits, the bar at the top, the message cut, the pane takes the rest.
        val p = place(base.copy(snapshotHeight = 1400, menuMaxHeight = 1500, bubbleY = 400f))
        assertEquals(60f + 12f, p.reactionBarY)
        // A quarter of the 1960 px above the navigation bar.
        assertEquals(490, p.snapshotHeight)
        assertEquals(p.snapshotY + 490f + 12f, p.menuOffsetY)
        assertNotNull(p.menuHeight)
        assertEquals(1960f - 12f, p.menuOffsetY + p.menuHeight!!)
    }

    @Test
    fun aMessageAtTheTopIsPushedDownForTheBar() {
        val p = place(base.copy(bubbleY = 70f))
        assertEquals(60f + 12f + 120f + 12f, p.snapshotY)
        assertEquals(p.snapshotY - 12f - 120f, p.reactionBarY)
        assertTrue(p.reactionBarY >= 60f)
    }

    @Test
    fun aMessageAtTheBottomStaysPutWithThePaneAboveTheBar() {
        val p = place(base.copy(bubbleY = 1700f))
        assertEquals(1700f, p.snapshotY)
        assertEquals(1700f - 12f - 120f, p.reactionBarY)
        assertEquals(p.reactionBarY - 12f - 500f, p.menuOffsetY)
        assertTrue(p.snapshotY + base.snapshotHeight <= 2000f - 40f - 12f)
    }

    @Test
    fun noBarAsksNoRoomAbove() {
        val p = place(base.copy(reactionBarHeight = 0, reactionBarWidth = 0, bubbleY = 1300f))
        assertEquals(1300f, p.snapshotY)
        assertEquals(1300f - 12f - 500f, p.menuOffsetY)
    }
}

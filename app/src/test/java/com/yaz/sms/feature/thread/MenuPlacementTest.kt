package com.yaz.sms.feature.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cases in the manner of Signal Android's ReactionOverlayPlacementTest
 * (app/src/test/java/org/thoughtcrime/securesms/conversation/ReactionOverlayPlacementTest.kt: a 1080 px wide window,
 * a message at x 80 and 600 wide, a menu 400 wide; an incoming message hangs off the leading edge, an outgoing one
 * off the trailing edge), then the readable column of a tablet.
 */
class MenuPlacementTest {

    private val edge = 12

    @Test
    fun `a message on the left starts its menu at its leading edge`() {
        assertEquals(80, MenuPlacement.x(true, 80, 680, 400, 1080, 0, edge))
    }

    @Test
    fun `a message on the right ends its menu at its trailing edge`() {
        assertEquals(280, MenuPlacement.x(false, 400, 680, 400, 1080, 0, edge))
    }

    @Test
    fun `a short message on the right keeps the menu inside the window`() {
        assertEquals(1080 - edge - 400, MenuPlacement.x(false, 900, 1070, 400, 1080, 0, edge))
    }

    @Test
    fun `a message at the window's edge keeps the margin`() {
        assertEquals(edge, MenuPlacement.x(true, 0, 300, 400, 1080, 0, edge))
    }

    @Test
    fun `on a tablet the menu stays in the conversation column`() {
        // 1920 px window, column from 440 to 1480.
        assertEquals(460, MenuPlacement.x(true, 460, 900, 500, 1920, 440, edge))
        assertEquals(1480 - edge - 500, MenuPlacement.x(false, 1100, 1480, 500, 1920, 440, edge))
        assertEquals(440 + edge, MenuPlacement.x(true, 18, 300, 500, 1920, 440, edge))
    }

    @Test
    fun `a menu wider than the column starts at the column's margin`() {
        assertEquals(440 + edge, MenuPlacement.x(true, 460, 900, 1200, 1920, 440, edge))
        assertEquals(440 + edge, MenuPlacement.x(false, 460, 900, 1200, 1920, 440, edge))
    }
}

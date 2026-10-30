package com.yaz.sms.feature.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cases in the manner of Signal Android's ReactionOverlayPlacementTest
 * (app/src/test/java/org/thoughtcrime/securesms/conversation/ReactionOverlayPlacementTest.kt: a 1080 px wide window,
 * a message at x 80 and 600 wide, a menu 400 wide), the menu centred on the message, then the readable column of a
 * tablet.
 */
class MenuPlacementTest {

    private val edge = 12

    @Test
    fun `the menu is centred on the message`() {
        assertEquals(180, MenuPlacement.x(80, 680, 400, 1080, 0, edge))
        assertEquals(340, MenuPlacement.x(400, 680, 400, 1080, 0, edge))
    }

    @Test
    fun `a menu wider than the message is centred on it too`() {
        // Message 100 to 400 (centre 250), menu 400: 50.
        assertEquals(50, MenuPlacement.x(100, 400, 400, 1080, 0, edge))
    }

    @Test
    fun `a short message on the right keeps the menu inside the window`() {
        assertEquals(1080 - edge - 400, MenuPlacement.x(900, 1070, 400, 1080, 0, edge))
    }

    @Test
    fun `a message at the window's edge keeps the margin`() {
        assertEquals(edge, MenuPlacement.x(0, 300, 400, 1080, 0, edge))
    }

    @Test
    fun `on a tablet the menu stays in the conversation column`() {
        // 1920 px window, column from 440 to 1480.
        assertEquals(440 + edge, MenuPlacement.x(430, 930, 500, 1920, 440, edge))
        assertEquals(800, MenuPlacement.x(800, 1300, 500, 1920, 440, edge))
        assertEquals(1480 - edge - 500, MenuPlacement.x(1100, 1480, 500, 1920, 440, edge))
        assertEquals(440 + edge, MenuPlacement.x(18, 300, 500, 1920, 440, edge))
    }

    @Test
    fun `a menu wider than the column starts at the column's margin`() {
        assertEquals(440 + edge, MenuPlacement.x(460, 900, 1200, 1920, 440, edge))
    }
}

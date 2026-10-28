package com.yaz.sms.feature.conversations

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The row's two small rules, as Dialer's CallLine takes them (an empty name falls back to the formatted number,
 * then "Unknown"; AOSP Dialer's CallLogEntryText.java) and as Signal's ConversationListItem.java sets the unread
 * indicator's text from the count (https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/conversationlist/ConversationListItem.java),
 * capped at "9+". Plain JUnit, like the app's other logic tests.
 */
class ConversationRowTest {

    @Test
    fun unreadCountShowsTheNumberUpToNine() {
        assertEquals("1", unreadLabel(1))
        assertEquals("9", unreadLabel(9))
    }

    @Test
    fun unreadCountCapsAtNinePlus() {
        assertEquals("9+", unreadLabel(10))
        assertEquals("9+", unreadLabel(99))
        assertEquals("9+", unreadLabel(100_000))
    }

    @Test
    fun aNameIsKept() {
        assertEquals("Marie", conversationTitle("Marie", "+33 6 12 34 56 78"))
    }

    @Test
    fun anEmptyOrBlankNameFallsBackToTheNumber() {
        assertEquals("+33 6 12 34 56 78", conversationTitle(null, "+33 6 12 34 56 78"))
        assertEquals("+33 6 12 34 56 78", conversationTitle("", "+33 6 12 34 56 78"))
        assertEquals("(415) 555-1234", conversationTitle("   ", "(415) 555-1234"))
    }

    @Test
    fun noNameAndNoNumberIsUnknown() {
        assertEquals("Unknown", conversationTitle(null, ""))
        assertEquals("Unknown", conversationTitle(" ", "  "))
    }
}

package com.sms.app.core.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyRulesTest {

    private fun first(text: String, phone: String = "en") = ReplyRules.suggest(text, phone).firstOrNull()

    @Test
    fun theMessagesPeopleGetMost() {
        assertEquals("You're welcome!", first("Thanks a lot for yesterday"))
        assertEquals("I'll call you", first("Call me when you can"))
        assertEquals("Good, and you?", first("Hey, how are you?"))
        assertEquals("Sounds good!", first("Are we still on for lunch tomorrow at 12:30?"))
        assertEquals("Yes", first("Did you get the parcel?"))
        assertEquals("Hi! 👋", first("Hello!"))
    }

    @Test
    fun theLanguageIsReadFromTheMessage() {
        assertEquals("fr", ReplyRules.language("Merci pour hier soir", "en"))
        assertEquals("es", ReplyRules.language("Muchas gracias por todo", "en"))
        assertEquals("de", ReplyRules.language("Danke für die Hilfe", "en"))
        assertEquals("fr", ReplyRules.language("OK ?", "fr"))
    }

    @Test
    fun noAnswerBeforeTheAppSpeaksTheLanguage() {
        assertTrue(ReplyRules.suggest("Merci pour hier soir", "fr").isEmpty())
        assertTrue(ReplyRules.suggest("Danke für die Hilfe", "de").isEmpty())
    }

    @Test
    fun otherMessagesGetNothing() {
        assertTrue(ReplyRules.suggest("I got home.", "en").isEmpty())
        assertTrue(ReplyRules.suggest("Your verification code is 482913", "en").isEmpty())
        assertTrue(ReplyRules.suggest("", "en").isEmpty())
    }
}

package com.yaz.sms.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsTest {

    @Test
    fun aCardIsOneVcardWithTheLookEscaped() {
        val card = Cards.vcard("Amélie, la vraie; ici", """{"color":-1,"letters":"AP"}""")
        assertTrue(card.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(card.contains("""FN:Amélie\, la vraie\; ici"""))
        assertTrue(card.contains("""X-YAZ-LOOK:{"color":-1\,"letters":"AP"}"""))
        assertTrue(card.endsWith("END:VCARD\r\n"))
    }

    @Test
    fun noLookNoLine() {
        assertEquals("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Bob\r\nEND:VCARD\r\n", Cards.vcard("Bob", null))
    }
}

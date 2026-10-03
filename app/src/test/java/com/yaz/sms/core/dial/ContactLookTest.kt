package com.yaz.sms.core.dial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactLookTest {

    @Test
    fun readsWhatContactsWrites() {
        val look = ContactLook.parse("""{"v":1,"color":-1754827,"vibration":"Heartbeat","bypass":true,"tone":"content://media/internal/audio/media/12","letters":"ABC","font":"serif","emoji":"🌻"}""")!!
        assertEquals(-1754827, look.color)
        assertEquals("Heartbeat", look.vibration)
        assertTrue(look.bypass)
        assertEquals("content://media/internal/audio/media/12", look.tone)
        assertEquals("AB", look.letters)
        assertEquals("serif", look.font)
        assertEquals("🌻", look.emoji)
    }

    @Test
    fun missingOrStrangeFieldsAreLeftOut() {
        val look = ContactLook.parse("""{"color":0,"tone":"file:///sdcard/x.mp3","font":"comic","new":42}""")!!
        assertNull(look.color)
        assertNull(look.tone)
        assertNull(look.font)
        assertFalse(look.bypass)
        assertNull(ContactLook.parse("not json"))
        assertNull(ContactLook.parse("{\"color\":1," + " ".repeat(5000) + "}"))
    }
}

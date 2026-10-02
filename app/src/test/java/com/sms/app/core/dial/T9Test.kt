package com.sms.app.core.dial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9Test {

    private fun entry(name: String, number: String, starred: Boolean = false) =
        PhoneEntry(name.hashCode().toLong(), name, number, T9.clean(number), null, starred)

    private val book = listOf(
        entry("John Smith", "+33 6 12 34 56 78"),
        entry("Joëlle Martin", "06 98 76 54 32"),
        entry("Ahmed", "01 23 45 67 89", starred = true),
        entry("Zoé-Anne Petit", "07 11 22 33 44")
    )

    @Test fun lettersBecomeKeys() {
        assertEquals("5646", T9.digitsOf("John"))
        assertEquals("56", T9.digitsOf("Jo"))
    }

    @Test fun accentsAreDropped() {
        assertEquals(T9.digitsOf("Joelle"), T9.digitsOf("Joëlle"))
        assertEquals("963", T9.digitsOf("Zoé"))
    }

    @Test fun findsByTheStartOfAnyWord() {
        val names = T9.search("7648", book).map { it.entry.name }
        assertEquals(listOf("John Smith"), names)
        // "Anne" after a hyphen counts as a word of its own.
        assertTrue(T9.search("2663", book).any { it.entry.name == "Zoé-Anne Petit" })
    }

    @Test fun namesComeBeforeNumbers() {
        // 56 is "Jo" for two names, and appears inside John's number too.
        val found = T9.search("56", book)
        assertTrue(found.first().inName)
        assertTrue(found.filter { it.inName }.map { it.entry.name }.containsAll(listOf("John Smith", "Joëlle Martin")))
    }

    @Test fun findsInsideNumbers() {
        val found = T9.search("6789", book)
        assertEquals("Ahmed", found.single().entry.name)
        assertEquals(false, found.single().inName)
    }

    @Test fun favouritesFirstWithinAGroup() {
        val found = T9.search("2", book).filter { it.inName }.map { it.entry.name }
        assertEquals("Ahmed", found.first())
    }

    @Test fun nothingTypedFindsNothing() {
        assertTrue(T9.search("", book).isEmpty())
        assertTrue(T9.search("+", book).isEmpty())
    }

    @Test fun cleanKeepsALeadingPlusOnly() {
        assertEquals("+33612", T9.clean("+33 6-12"))
        assertEquals("33612", T9.clean("33+612"))
    }
}

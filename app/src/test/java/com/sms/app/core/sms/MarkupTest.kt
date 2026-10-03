package com.sms.app.core.sms

import com.sms.app.core.sms.Markup.Span
import com.sms.app.core.sms.Markup.Style
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkupTest {

    @Test
    fun readsEachStyle() {
        assertEquals(Markup.Read("a big day", listOf(Span(Style.BOLD, 2, 5))), Markup.read("a *big* day"))
        assertEquals(Markup.Read("so nice", listOf(Span(Style.ITALIC, 3, 7))), Markup.read("so _nice_"))
        assertEquals(Markup.Read("read this", listOf(Span(Style.UNDERLINE, 5, 9))), Markup.read("read __this__"))
        assertEquals(Markup.Read("not that", listOf(Span(Style.STRIKE, 0, 3))), Markup.read("~not~ that"))
    }

    @Test
    fun stylesCanBeInsideEachOther() {
        assertEquals(Markup.Read("very big", listOf(Span(Style.ITALIC, 0, 8), Span(Style.BOLD, 0, 8))), Markup.read("*_very big_*"))
    }

    @Test
    fun marksInsideWordsAreNotStyles() {
        assertEquals("file_name_here.txt", Markup.plain("file_name_here.txt"))
        assertEquals("2*3*4", Markup.plain("2*3*4"))
        assertEquals("a * b", Markup.plain("a * b"))
        assertEquals("https://x.org/a_b_c", Markup.plain("https://x.org/a_b_c"))
    }

    @Test
    fun wrapsTheSelectionOrTheWord() {
        assertEquals("a *big* day" to (3..6), Markup.wrap("a big day", 2, 5, Style.BOLD))
        assertEquals("a *big* day" to (3..6), Markup.wrap("a big day", 3, 3, Style.BOLD))
        assertEquals("~a big~ day" to (1..6), Markup.wrap("a big day", 0, 6, Style.STRIKE))
    }
}

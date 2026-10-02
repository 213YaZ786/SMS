package com.sms.app.core.dial

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleTest {

    private fun entry(id: Long, name: String, number: String, starred: Boolean = false) =
        PhoneEntry(id, name, number, T9.clean(number), null, starred)

    private val book = People.of(
        listOf(
            entry(1, "Joëlle Martin", "06 98 76 54 32"),
            entry(1, "Joëlle Martin", "01 23 45 67 89"),
            entry(2, "John Smith", "+33 6 12 34 56 78", starred = true)
        )
    )

    @Test
    fun numbersFoldIntoOnePerson() {
        assertEquals(2, book.size)
        assertEquals(2, book.first { it.id == 1L }.numbers.size)
    }

    @Test
    fun searchIgnoresAccentsAndFindsDigits() {
        assertEquals(listOf(1L), People.search(book, "joel").map { it.id })
        assertEquals(listOf(1L), People.search(book, "mart").map { it.id })
        assertEquals(listOf(2L), People.search(book, "3361").map { it.id })
    }
}

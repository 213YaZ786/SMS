package com.sms.app.core.dial

import java.text.Normalizer

/** A contact with all its numbers, the first one the one called by default. */
data class Person(val id: Long, val name: String, val photo: String?, val starred: Boolean, val numbers: List<PhoneEntry>) {
    val number: String get() = numbers.first().number
}

object People {

    /** The numbers of the phone book folded into one entry per contact, in the book's order. */
    fun of(entries: List<PhoneEntry>): List<Person> =
        entries.groupBy { it.contactId }.map { (id, numbers) ->
            val first = numbers.first()
            Person(id, first.name, numbers.firstNotNullOfOrNull { it.photo }, numbers.any { it.starred }, numbers)
        }

    /** People whose name has a word starting with [query], or whose number holds its digits. */
    fun search(people: List<Person>, query: String): List<Person> {
        val q = plain(query.trim())
        if (q.isEmpty()) return people
        val digits = query.filter(Char::isDigit)
        return people.filter { p ->
            plain(p.name).split(' ', '-', '.').any { it.startsWith(q) } || plain(p.name).startsWith(q) ||
                (digits.length >= 2 && p.numbers.any { it.digits.contains(digits) })
        }
    }

    /** Lower case, accents gone: "Joëlle" is found by "joe". */
    fun plain(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}

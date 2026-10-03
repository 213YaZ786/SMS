package com.yaz.sms.core.dial

import java.text.Normalizer

/** One number of a contact, as the dialpad searches it. */
data class PhoneEntry(
    val contactId: Long,
    val name: String,
    /** The number as saved. */
    val number: String,
    /** The digits only, a leading + kept, for matching what is typed. */
    val digits: String,
    val photo: String?,
    val starred: Boolean,
    /** Their colour, monogram and the rest, as the Contacts app keeps them; null without. */
    val look: ContactLook.Look? = null
)

/** A contact found while typing, with where the typed digits matched. */
data class T9Match(val entry: PhoneEntry, val inName: Boolean)

/**
 * The search of the dialpad: typed digits find a contact by the letters on
 * the keys (5646 finds John) at the start of any word of the name, or
 * anywhere in the number. Names match first, then numbers; within each,
 * favourites first, then alphabetical.
 */
object T9 {

    private val keys = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
    )

    /** The key of each letter of [word], accents dropped; digits stay, the rest goes. */
    fun digitsOf(word: String): String {
        val plain = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return buildString {
            for (c in plain) {
                when {
                    c.isDigit() -> append(c)
                    else -> keys[c]?.let(::append)
                }
            }
        }
    }

    /** What is typed, digits only, a leading + kept. */
    fun clean(typed: String): String = typed.filterIndexed { i, c -> c.isDigit() || (c == '+' && i == 0) }

    fun search(typed: String, entries: List<PhoneEntry>, limit: Int = 20): List<T9Match> {
        val query = clean(typed).removePrefix("+")
        if (query.isEmpty()) return emptyList()
        val byName = ArrayList<T9Match>()
        val byNumber = ArrayList<T9Match>()
        for (e in entries) {
            val words = e.name.split(Regex("[\\s\\-_.]+")).filter { it.isNotEmpty() }
            when {
                words.any { digitsOf(it).startsWith(query) } -> byName += T9Match(e, inName = true)
                e.digits.contains(query) -> byNumber += T9Match(e, inName = false)
            }
        }
        val order = compareByDescending<T9Match> { it.entry.starred }.thenBy { it.entry.name.lowercase() }
        return (byName.sortedWith(order) + byNumber.sortedWith(order)).take(limit)
    }

    /** Equal digits, or the same last nine: 06 12… and +33 6 12… are one number. */
    fun sameDigits(a: String, b: String): Boolean {
        val x = a.removePrefix("+")
        val y = b.removePrefix("+")
        if (x == y) return x.isNotEmpty()
        return x.length >= 9 && y.length >= 9 && x.takeLast(9) == y.takeLast(9)
    }
}

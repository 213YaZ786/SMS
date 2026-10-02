package com.sms.app.data.contacts

import com.sms.app.core.dial.PhoneEntry
import com.sms.app.core.dial.T9

/**
 * The contacts by number, to put a name and a face on a number: the same
 * number written 06… or +33 6… finds the same contact.
 */
class PhoneIndex(entries: List<PhoneEntry>) {

    private val byTail = HashMap<String, PhoneEntry>()
    private val exact = HashMap<String, PhoneEntry>()

    init {
        for (entry in entries) {
            val digits = entry.digits.removePrefix("+")
            exact.putIfAbsent(digits, entry)
            if (digits.length >= 9) byTail.putIfAbsent(digits.takeLast(9), entry)
        }
    }

    fun find(digits: String): PhoneEntry? {
        val d = digits.removePrefix("+")
        if (d.isEmpty()) return null
        return exact[d] ?: if (d.length >= 9) byTail[d.takeLast(9)] else null
    }

    /** All the numbers of one contact match the same way, for the call screen. */
    fun matches(entry: PhoneEntry, digits: String): Boolean = T9.sameDigits(entry.digits, digits)
}

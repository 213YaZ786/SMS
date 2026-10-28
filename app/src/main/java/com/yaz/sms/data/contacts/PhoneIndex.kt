package com.yaz.sms.data.contacts

import com.yaz.sms.core.dial.PhoneEntry
import com.yaz.sms.core.dial.PrivateNames
import com.yaz.sms.core.dial.T9
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The contacts by number, to put a name and a face on a number: the same
 * number written 06… or +33 6… finds the same contact, and +34 6… never
 * the French one. A number found by its last nine digits is accepted only
 * when Android's rule says it is the same number in [region], the phone's
 * country (Numbers.countryIso): PhoneNumberUtils.areSamePhoneNumber, see
 * [T9.sameDigits] (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/PhoneNumberUtils.java).
 */
class PhoneIndex(entries: List<PhoneEntry>, private val region: String = Locale.getDefault().country) {

    private val byTail = HashMap<String, MutableList<PhoneEntry>>()
    private val exact = HashMap<String, PhoneEntry>()
    private val resolved = ConcurrentHashMap<String, Any>()

    init {
        for (entry in entries) {
            val digits = entry.digits.removePrefix("+")
            exact.putIfAbsent(digits, entry)
            // Contacts of several countries can share a tail: all are kept, in the book's order.
            if (digits.length >= 9) byTail.getOrPut(digits.takeLast(9)) { ArrayList(1) } += entry
        }
    }

    /** Android's contacts first, then the Contacts app's private ones (asked in the background, see [PrivateNames.version]). */
    fun find(digits: String): PhoneEntry? {
        val d = digits.removePrefix("+")
        if (d.isEmpty()) return null
        return exact[d] ?: byTail(digits, d) ?: PrivateNames.cached(digits)?.let {
            PhoneEntry(PrivateNames.CONTACT_ID, it.name, digits, digits, null, false, it.look)
        }
    }

    /** The candidates sharing the tail, the first one Android's rule accepts; the verdict is kept, the parse costs. */
    private fun byTail(digits: String, d: String): PhoneEntry? {
        if (d.length < 9) return null
        val verdict = resolved.getOrPut(digits) {
            byTail[d.takeLast(9)]?.firstOrNull { T9.sameDigits(it.digits, digits, region) } ?: NONE
        }
        return verdict as? PhoneEntry
    }

    /** All the numbers of one contact match the same way, for the call screen. */
    fun matches(entry: PhoneEntry, digits: String): Boolean = T9.sameDigits(entry.digits, digits, region)

    private companion object {
        val NONE = Any()
    }
}

package com.sms.app.core.sms

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month

/**
 * What a message carries that can be acted on, read on the phone alone:
 * a parcel's tracking number, an appointment's date and time.
 */
object Finds {

    private val ParcelWords = Regex("\\b(colis|parcel|package|livraison|delivery|suivi|tracking|envoi|shipment|lettre suivie)\\b", RegexOption.IGNORE_CASE)

    /** UPS, international post (S10: two letters, nine digits, two letters), then long codes of letters and digits. */
    private val ParcelCodes = listOf(
        Regex("\\b1Z[0-9A-Z]{16}\\b"),
        Regex("\\b[A-Z]{2}\\d{9}[A-Z]{2}\\b"),
        Regex("\\b(?=[0-9A-Z]*\\d[0-9A-Z]*\\d)(?=[0-9A-Z]*[A-Z])[0-9A-Z]{11,22}\\b"),
        Regex("\\b\\d{12,22}\\b")
    )

    fun parcel(text: String): String? {
        if (!ParcelWords.containsMatchIn(text)) return null
        for (pattern in ParcelCodes) pattern.find(text)?.let { return it.value }
        return null
    }

    private val Months = mapOf(
        "janvier" to 1, "janv" to 1, "january" to 1, "jan" to 1, "février" to 2, "fevrier" to 2, "févr" to 2, "february" to 2, "feb" to 2,
        "mars" to 3, "march" to 3, "mar" to 3, "avril" to 4, "april" to 4, "apr" to 4, "mai" to 5, "may" to 5, "juin" to 6, "june" to 6, "jun" to 6,
        "juillet" to 7, "juil" to 7, "july" to 7, "jul" to 7, "août" to 8, "aout" to 8, "august" to 8, "aug" to 8,
        "septembre" to 9, "sept" to 9, "september" to 9, "sep" to 9, "octobre" to 10, "oct" to 10, "october" to 10,
        "novembre" to 11, "nov" to 11, "november" to 11, "décembre" to 12, "decembre" to 12, "déc" to 12, "december" to 12, "dec" to 12
    )

    private val Time = Regex("\\b(?:à|a|at|vers|@)?\\s*(\\d{1,2})\\s*(?:h|:)\\s*(\\d{2})?\\s*(am|pm)?\\b|\\b(\\d{1,2})\\s*(am|pm)\\b", RegexOption.IGNORE_CASE)
    private val NumericDate = Regex("\\b(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?\\b")
    private val WordDate = Regex("\\b(\\d{1,2})(?:er)?\\s+([a-zéû]+)\\.?(?:\\s+(\\d{4}))?\\b", RegexOption.IGNORE_CASE)
    private val Relative = Regex("\\b(aujourd'hui|today|demain|tomorrow|après-demain)\\b", RegexOption.IGNORE_CASE)

    /** An appointment: a day and an hour in the text, the day not long past. */
    fun appointment(text: String, today: LocalDate = LocalDate.now()): LocalDateTime? {
        val time = Time.find(text)?.let { m ->
            val h = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return@let null
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = (m.groupValues[3].ifEmpty { m.groupValues[5] }).lowercase()
            val hour = when {
                ampm == "pm" && h < 12 -> h + 12
                ampm == "am" && h == 12 -> 0
                else -> h
            }
            if (hour in 0..23 && min in 0..59) LocalTime.of(hour, min) else null
        } ?: return null
        val day = date(text, today) ?: return null
        if (day.isBefore(today.minusDays(1))) return null
        return day.atTime(time)
    }

    private fun date(text: String, today: LocalDate): LocalDate? {
        Relative.find(text)?.let {
            return when (it.value.lowercase()) {
                "aujourd'hui", "today" -> today
                "demain", "tomorrow" -> today.plusDays(1)
                else -> today.plusDays(2)
            }
        }
        NumericDate.find(text)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            if (d in 1..31 && mo in 1..12) {
                val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: yearFor(mo, d, today)
                return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
            }
        }
        WordDate.find(text)?.let { m ->
            val month = Months[m.groupValues[2].lowercase().trimEnd('.')] ?: return null
            val d = m.groupValues[1].toInt()
            val y = m.groupValues[3].toIntOrNull() ?: yearFor(month, d, today)
            return runCatching { LocalDate.of(y, Month.of(month), d) }.getOrNull()
        }
        return null
    }

    /** A date without a year: this year, or next year when it has passed. */
    private fun yearFor(month: Int, day: Int, today: LocalDate): Int =
        runCatching { if (LocalDate.of(today.year, month, day).isBefore(today.minusDays(1))) today.year + 1 else today.year }.getOrDefault(today.year)
}

package com.sms.app.core.sms

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.text.Normalizer
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * What a message carries that can be acted on, read on the phone alone:
 * a parcel's tracking number, an appointment's date and time.
 */
object Finds {

    // Words that say a message is about a parcel, in the languages most texts come in.
    private val ParcelWords = Regex(
        "\\b(colis|parcel|package|livraison|delivery|suivi|tracking|envoi|shipment|lettre suivie|courier|" +
            "paquete|envío|envio|seguimiento|entrega|pedido|" +
            "paket|sendung|sendungsverfolgung|lieferung|zustellung|" +
            "pacco|spedizione|consegna|tracciamento|" +
            "encomenda|pacote|rastreio|rastreamento|" +
            "pakket|zending|bezorging|" +
            "paczka|przesyłka|kargo|gönderi|forsendelse|försändelse|zásilka)\\b",
        setOf(RegexOption.IGNORE_CASE)
    )

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

    /**
     * Month names as every language the phone knows writes them, from its
     * own calendar data: "mars", "March", "marzo", "März", "maart", "Mayıs"…,
     * with and without their accents.
     */
    private val Months: Map<String, Int> by lazy {
        val styles = listOf(TextStyle.FULL, TextStyle.FULL_STANDALONE)
        // Short forms only in languages whose short forms are no everyday words elsewhere ("set", "mar").
        val shortIn = setOf("en", "fr", "es", "de", "it", "pt", "nl")
        val languages = Locale.getAvailableLocales().map { it.language }.filter { it.isNotEmpty() }.toSet()
        buildMap {
            for (language in languages) for (month in Month.entries) {
                val locale = Locale.forLanguageTag(language)
                val forms = styles + if (language in shortIn) listOf(TextStyle.SHORT, TextStyle.SHORT_STANDALONE) else emptyList()
                for (style in forms) {
                    val name = month.getDisplayName(style, locale).lowercase(Locale.ROOT).trimEnd('.')
                    if (name.length < 3 || name.any(Char::isDigit)) continue
                    putIfAbsent(name, month.value)
                    putIfAbsent(plain(name), month.value)
                }
            }
        }
    }

    private fun plain(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    // 18h30, 18:30, 6pm, 14 Uhr, "à 9h", "um 14:30", "alle 18", "às 10h", "a las 9:00".
    private val Time = Regex(
        "\\b(?:à|a|at|vers|@|um|alle|às|as|om|a las|o)?\\s*(\\d{1,2})\\s*(?:h|:|\\.(?=\\d{2}\\s*uhr))\\s*(\\d{2})?\\s*(am|pm|uhr)?\\b|" +
            "\\b(\\d{1,2})\\s*(am|pm|uhr)\\b",
        RegexOption.IGNORE_CASE
    )
    private val IsoDate = Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b")
    private val NumericDate = Regex("\\b(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?\\b")
    // 12 mars, 12. März, 12 de marzo, 1er avril, 3rd of May.
    private val DayMonth = Regex("\\b(\\d{1,2})(?:er|\\.|º|°|st|nd|rd|th)?\\s+(?:de\\s+|of\\s+)?(\\p{L}+)\\.?(?:\\s+(?:de\\s+)?(\\d{4}))?\\b", RegexOption.IGNORE_CASE)
    // March 12, Oct. 3rd, 2026.
    private val MonthDay = Regex("\\b(\\p{L}+)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b,?(?:\\s+(\\d{4}))?", RegexOption.IGNORE_CASE)
    private val Relative = Regex(
        "\\b(aujourd'hui|today|hoy|heute|oggi|hoje|vandaag|dzisiaj|dziś|bugün|idag|i dag|tänään|" +
            "après-demain|apres-demain|day after tomorrow|pasado mañana|übermorgen|dopodomani|depois de amanhã|overmorgen|pojutrze|" +
            "demain|tomorrow|(?<!la )(?<!esta )mañana|(?<!guten )(?<!goede )morgen|domani|amanhã|jutro|yarın|imorgen|i morgen|huomenna)\\b",
        RegexOption.IGNORE_CASE
    )
    private val Today = setOf("aujourd'hui", "today", "hoy", "heute", "oggi", "hoje", "vandaag", "dzisiaj", "dziś", "bugün", "idag", "i dag", "tänään")
    private val AfterTomorrow = setOf("après-demain", "apres-demain", "day after tomorrow", "pasado mañana", "übermorgen", "dopodomani", "depois de amanhã", "overmorgen", "pojutrze")

    /** Where the month comes before the day in 3/10 (the United States and a few others). */
    private fun monthFirstHere(): Boolean = Locale.getDefault().country in setOf("US", "PH", "FM", "MH", "PW", "BZ")

    /** An appointment: a day and an hour in the text, the day not long past. */
    fun appointment(text: String, today: LocalDate = LocalDate.now(), monthFirst: Boolean = monthFirstHere()): LocalDateTime? {
        val time = Time.find(text)?.let { m ->
            val h = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return@let null
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = (m.groupValues[3].ifEmpty { m.groupValues[5] }).lowercase()
            // "18" alone is no hour: a separator, am/pm or Uhr makes it one.
            val hour = when {
                ampm == "pm" && h < 12 -> h + 12
                ampm == "am" && h == 12 -> 0
                else -> h
            }
            if (hour in 0..23 && min in 0..59) LocalTime.of(hour, min) else null
        } ?: return null
        val day = date(text, today, monthFirst) ?: return null
        if (day.isBefore(today.minusDays(1))) return null
        return day.atTime(time)
    }

    private fun date(text: String, today: LocalDate, monthFirst: Boolean): LocalDate? {
        Relative.find(text)?.let {
            val word = it.value.lowercase()
            return when (word) {
                in Today -> today
                in AfterTomorrow -> today.plusDays(2)
                else -> today.plusDays(1)
            }
        }
        IsoDate.find(text)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        NumericDate.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            // The order of the place, unless the numbers leave no doubt (25/12 is day first anywhere).
            val (d, mo) = when {
                a > 12 -> a to b
                b > 12 -> b to a
                monthFirst -> b to a
                else -> a to b
            }
            if (d in 1..31 && mo in 1..12) {
                val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: yearFor(mo, d, today)
                return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
            }
        }
        for (m in DayMonth.findAll(text)) {
            val month = monthOf(m.groupValues[2]) ?: continue
            val d = m.groupValues[1].toInt()
            val y = m.groupValues[3].toIntOrNull() ?: yearFor(month, d, today)
            return runCatching { LocalDate.of(y, Month.of(month), d) }.getOrNull()
        }
        for (m in MonthDay.findAll(text)) {
            val month = monthOf(m.groupValues[1]) ?: continue
            val d = m.groupValues[2].toInt()
            val y = m.groupValues[3].toIntOrNull() ?: yearFor(month, d, today)
            return runCatching { LocalDate.of(y, Month.of(month), d) }.getOrNull()
        }
        return null
    }

    private fun monthOf(word: String): Int? {
        val w = word.lowercase(Locale.ROOT).trimEnd('.')
        return Months[w] ?: Months[plain(w)]
    }

    /** A date without a year: this year, or next year when it has passed. */
    private fun yearFor(month: Int, day: Int, today: LocalDate): Int =
        runCatching { if (LocalDate.of(today.year, month, day).isBefore(today.minusDays(1))) today.year + 1 else today.year }.getOrDefault(today.year)
}

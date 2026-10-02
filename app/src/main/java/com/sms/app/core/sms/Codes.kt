package com.sms.app.core.sms

/**
 * The one-time code in a message, when it carries one: 4 to 8 digits (a
 * space or a dash may split them in two) in a message that says it is a
 * code. Dates, amounts and phone numbers are left alone.
 */
object Codes {

    private val Words = Regex(
        "\\b(code|codes|otp|pin|passcode|password|verification|verify|login|sign[- ]?in|2fa|" +
            "mot de passe|vérification|verification|connexion|código|codice|kode|tan)\\b",
        RegexOption.IGNORE_CASE
    )

    // Not inside a longer number, an amount, a date or an hour: a dot or a
    // colon only counts when a digit stands on its other side.
    private const val START = "(?<![\\d+€$£])(?<!\\d[.,/:-])"
    private const val END = "(?![\\d€$£%])(?![.,/:]\\d)"
    private val Candidate = Regex("$START(\\d{3,4})[ -]?(\\d{3,4})$END|$START(\\d{4,8})$END")

    fun find(text: String): String? {
        if (!Words.containsMatchIn(text)) return null
        for (m in Candidate.findAll(text)) {
            val code = if (m.groups[3] != null) m.groupValues[3] else m.groupValues[1] + m.groupValues[2]
            if (code.length in 4..8) return code
        }
        return null
    }
}

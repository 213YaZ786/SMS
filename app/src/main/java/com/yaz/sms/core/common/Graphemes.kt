package com.yaz.sms.core.common

/**
 * Text cut to a budget of characters (SMS lengths, field limits) without ever splitting what the eye sees as one
 * character: a flag, a skin tone, a family, a keycap stay whole or go whole.
 *
 * Signal's BreakIteratorCompat walks the text by extended grapheme clusters (UAX #29) with Android's ICU
 * android.icu.text.BreakIterator.getCharacterInstance() and setText(CharSequence) on API 29+, and its take(atMost)
 * counts graphemes
 * (https://github.com/signalapp/Signal-Android/blob/main/core/util/src/main/java/org/signal/core/util/BreakIteratorCompat.java,
 * AGPL-3.0). Our limits are budgets of chars, not of graphemes, so this adapts it: [takeWhole] keeps the last
 * boundary at or before n chars, [takeLastWhole] the first boundary at or after length - n. minSdk is 31, so
 * no fallback for older ICU is needed.
 */

/** The longest start of this text that is at most [n] chars and ends on a grapheme boundary. */
fun CharSequence.takeWhole(n: Int): String {
    if (n <= 0) return ""
    if (length <= n) return toString()
    val breaks = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(this@takeWhole) }
    val end = if (breaks.isBoundary(n)) n else breaks.preceding(n).coerceAtLeast(0)
    return substring(0, end)
}

/** The longest end of this text that is at most [n] chars and starts on a grapheme boundary. */
fun CharSequence.takeLastWhole(n: Int): String {
    if (n <= 0) return ""
    if (length <= n) return toString()
    val from = length - n
    val breaks = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(this@takeLastWhole) }
    val start = if (breaks.isBoundary(from)) from else breaks.following(from).let { if (it == android.icu.text.BreakIterator.DONE) length else it }
    return substring(start)
}

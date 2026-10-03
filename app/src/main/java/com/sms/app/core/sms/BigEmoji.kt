package com.sms.app.core.sms

import java.text.BreakIterator

/**
 * A message made only of one to three emoji is shown big, without a
 * bubble. Each emoji is one grapheme (a family, a flag or a skin tone
 * stays whole); its animation, when the app has one, is named by its code
 * points as Noto's animated emoji are.
 */
object BigEmoji {

    const val MAX = 3

    /** The emoji of [text] when it is nothing else (spaces aside), else null. */
    fun read(text: String): List<String>? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 64) return null
        val it = BreakIterator.getCharacterInstance().apply { setText(t) }
        val found = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val g = t.substring(start, end)
            if (!g.isBlank()) {
                if (!isEmoji(g)) return null
                found += g
                if (found.size > MAX) return null
            }
            start = end
            end = it.next()
        }
        return found.ifEmpty { null }
    }

    /** The names an animation of [emoji] may carry, the exact one first. */
    fun names(emoji: String): List<String> {
        val exact = emoji.codePoints().toArray().joinToString("_") { "%x".format(it) }
        val bare = exact.split('_').filter { it != "fe0f" }.joinToString("_")
        return listOf(exact, bare, "${bare}_fe0f").distinct()
    }

    internal fun isEmoji(g: String): Boolean {
        val cps = g.codePoints().toArray()
        val first = cps.first()
        if (0x20E3 in cps) return true                      // keycap: 1️⃣ #️⃣
        if (first in 0x1F000..0x1FAFF) return true          // pictographs, flags, faces
        val styled = 0xFE0F in cps || 0x200D in cps
        if (first in PRESENTED) return true
        // Symbols that are text by default count only when asked as emoji.
        return styled && (first in 0x2190..0x2BFF || first in TEXT_DEFAULT)
    }

    /** Below U+1F000, the emoji shown in colour without any selector. */
    private val PRESENTED: Set<Int> = buildSet {
        addAll(listOf(0x231A, 0x231B, 0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23F0, 0x23F3, 0x25FD, 0x25FE))
        addAll(listOf(0x2614, 0x2615, 0x267F, 0x2693, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26D4, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26FA, 0x26FD))
        addAll(0x2648..0x2653)
        addAll(listOf(0x2705, 0x270A, 0x270B, 0x2728, 0x274C, 0x274E, 0x2753, 0x2754, 0x2755, 0x2757, 0x2795, 0x2796, 0x2797, 0x27B0, 0x27BF))
        addAll(listOf(0x2B1B, 0x2B1C, 0x2B50, 0x2B55))
    }

    private val TEXT_DEFAULT = setOf(0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x3030, 0x303D, 0x3297, 0x3299)
}

package com.yaz.sms.core.sms

/**
 * Text styles written into a message the way people already type them
 * (and other apps show them): *bold*, _italic_, __underline__ and
 * ~strikethrough~. A mark only counts around words, so a file_name or a
 * price like 2*3 stays as it is. Reading gives the text without its marks
 * and where each style runs; writing wraps a part of a text in one.
 */
object Markup {

    enum class Style(val mark: String) { BOLD("*"), ITALIC("_"), UNDERLINE("__"), STRIKE("~") }

    data class Span(val style: Style, val start: Int, val end: Int)

    data class Read(val text: String, val spans: List<Span>)

    // Underline before italic: two marks are one underline, not two italics.
    private val patterns = listOf(
        Style.UNDERLINE to Regex("(?<![\\p{L}\\p{N}_])__(?=\\S)(.+?)(?<=\\S)__(?![\\p{L}\\p{N}_])"),
        Style.BOLD to Regex("(?<![\\p{L}\\p{N}*])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![\\p{L}\\p{N}*])"),
        Style.ITALIC to Regex("(?<![\\p{L}\\p{N}_])_(?=[^\\s_])(.+?)(?<=[^\\s_])_(?![\\p{L}\\p{N}_])"),
        Style.STRIKE to Regex("(?<![\\p{L}\\p{N}~])~(?=\\S)(.+?)(?<=\\S)~(?![\\p{L}\\p{N}~])")
    )

    /** [text] without its marks, and the styles over it. */
    fun read(text: String): Read {
        if (text.none { it == '*' || it == '_' || it == '~' }) return Read(text, emptyList())
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        fun walk(s: String) {
            var rest = s
            while (rest.isNotEmpty()) {
                val first = patterns.mapNotNull { (style, r) -> r.find(rest)?.let { style to it } }.minByOrNull { it.second.range.first }
                if (first == null) {
                    out.append(rest)
                    return
                }
                val (style, m) = first
                out.append(rest, 0, m.range.first)
                val start = out.length
                walk(m.groupValues[1])
                spans += Span(style, start, out.length)
                rest = rest.substring(m.range.last + 1)
            }
        }
        walk(text)
        return Read(out.toString(), spans)
    }

    /** The text without its marks, for a list or a notification. */
    fun plain(text: String): String = read(text).text

    /**
     * [text] with [style] around the part from [start] to [end] (a word when
     * nothing is selected), and where the selection lands after it.
     */
    fun wrap(text: String, start: Int, end: Int, style: Style): Pair<String, IntRange> {
        var a = start.coerceIn(0, text.length)
        var b = end.coerceIn(a, text.length)
        if (a == b) {
            while (a > 0 && !text[a - 1].isWhitespace()) a--
            while (b < text.length && !text[b].isWhitespace()) b++
        }
        // Spaces stay outside the marks, or they would not count.
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (a == b) return text to (start..end)
        val m = style.mark
        return (text.substring(0, a) + m + text.substring(a, b) + m + text.substring(b)) to (a + m.length..b + m.length)
    }
}

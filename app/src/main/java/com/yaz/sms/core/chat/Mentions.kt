package com.yaz.sms.core.chat

/**
 * Mentions in a group, pure: Signal's behaviour (AGPL-3.0, https://github.com/signalapp/Signal-Android)
 * on a message that is plain text. Signal keeps ranges beside the body (database/MentionUtil.java,
 * components/mention/MentionAnnotation.java) and sends them as body ranges; chatmail core, like
 * Delta Chat, has no mention field (no "mention" in deltachat-jsonrpc/src/api.rs or src/chat.rs), so
 * the words "@Name" are the mention on the wire and over SMS, and the ranges are found again in the
 * text by the names of the group's members.
 */
object Mentions {

    /** Signal MentionUtil.MENTION_STARTER. */
    const val STARTER = '@'

    /** Signal ComposeText.MAX_QUERY_LENGTH: a longer word after the @ is no longer a search. */
    const val MAX_QUERY = 64

    /** A mention found in a text: from the @ to the end of the name, and the name as written. */
    data class Found(val start: Int, val end: Int, val name: String)

    /** The word being typed after an @: where the @ is, and what follows it. */
    data class Query(val at: Int, val text: String)

    /**
     * What the cursor is typing after an @, or null. Signal ComposeText.findQueryStart: back from the
     * cursor to the @, stopping at any space; canFilter: not longer than [MAX_QUERY]. Added here: the @
     * opens a mention only at the start or after a space (Signal's also opens in "anna@mail").
     */
    fun queryAt(text: String, cursor: Int): Query? {
        val end = cursor.coerceIn(0, text.length)
        if (end == 0) return null
        var i = end - 1
        while (i >= 0 && text[i] != STARTER && !text[i].isWhitespace()) i--
        if (i < 0 || text[i] != STARTER) return null
        if (i > 0 && !text[i - 1].isWhitespace()) return null
        if (end - (i + 1) > MAX_QUERY) return null
        return Query(i, text.substring(i + 1, end))
    }

    /**
     * The names that answer a query, in order. Signal RecipientTable.queryRecipientsForMentions: the
     * name starts with the query, whatever the case (GLOB "query*" on the sort name); the user is
     * never in the list (the caller gives the others); an empty query gives all, as Signal's picker
     * does on a bare @.
     */
    fun search(names: List<String>, query: String, limit: Int = 8): List<String> =
        names.distinct().filter { it.startsWith(query, ignoreCase = true) }.sortedBy { it.lowercase() }.take(limit)

    /**
     * [text] with the @query before [cursor] replaced by "@Name " and where the cursor lands. Signal
     * ComposeText.replaceTextWithMention / createReplacementToken: the @ and the name, then a space.
     */
    fun insert(text: String, cursor: Int, name: String): Pair<String, Int> {
        val end = cursor.coerceIn(0, text.length)
        val query = queryAt(text, end) ?: return text to end
        val token = "$STARTER$name "
        return (text.substring(0, query.at) + token + text.substring(end)) to (query.at + token.length)
    }

    /**
     * The mentions in [text]: an @ at the start or after a space, then one of [names] (the longest
     * that fits, whatever the case) ending the word. Signal MentionValidatorWatcher: only members of
     * the group count; MentionUtil.update: sorted, none out of the body, none over another.
     */
    fun find(text: String, names: Collection<String>): List<Found> {
        if (text.isEmpty() || names.isEmpty() || STARTER !in text) return emptyList()
        val longest = names.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
        val out = mutableListOf<Found>()
        var i = 0
        while (i < text.length) {
            if (text[i] == STARTER && (i == 0 || !text[i - 1].isLetterOrDigit())) {
                val hit = longest.firstOrNull { n ->
                    text.regionMatches(i + 1, n, 0, n.length, ignoreCase = true) &&
                        (i + 1 + n.length == text.length || !text[i + 1 + n.length].isLetterOrDigit())
                }
                if (hit != null) {
                    out += Found(i, i + 1 + hit.length, text.substring(i + 1, i + 1 + hit.length))
                    i += 1 + hit.length
                    continue
                }
            }
            i++
        }
        return out
    }

    /** Whether the user is named: Signal MessageRecord.hasSelfMention, here by the user's own names. */
    fun mentions(text: String, mine: Collection<String>): Boolean = find(text, mine).isNotEmpty()

    /**
     * Signal MentionUtil.update: [text] with each mention given by [with] in place of its words, and
     * the mentions as they stand in the new text. Ranges are taken in order; one out of the text, or
     * starting inside the one before, is dropped.
     */
    fun replace(text: String?, found: List<Found>, with: (Found) -> String): Pair<String?, List<Found>> {
        if (text == null || found.isEmpty()) return text to emptyList()
        val out = StringBuilder()
        val updated = mutableListOf<Found>()
        var at = 0
        for (m in found.sortedWith(compareBy({ it.start }, { it.end }))) {
            if (m.start < 0 || m.end < m.start || m.end > text.length || at > m.start) continue
            out.append(text, at, m.start)
            val words = with(m)
            updated += Found(out.length, out.length + words.length, words.removePrefix(STARTER.toString()))
            out.append(words)
            at = m.end
        }
        if (at < text.length) out.append(text, at, text.length)
        return out.toString() to updated
    }

    /**
     * Signal MentionDeleter: when an edit takes away a part of a mention, the whole of it goes and only
     * the @ is left. [old] is the text before, [new] after the edit; the text and cursor to show, or
     * null when no mention was cut.
     */
    fun afterEdit(old: String, new: String, names: Collection<String>): Pair<String, Int>? {
        if (new.length >= old.length) return null
        // Where the two texts differ: the common start and end.
        var head = 0
        while (head < new.length && head < old.length && new[head] == old[head]) head++
        var tail = 0
        while (tail < new.length - head && tail < old.length - head && new[new.length - 1 - tail] == old[old.length - 1 - tail]) tail++
        val cutFrom = head
        val cutTo = old.length - tail
        val hit = find(old, names).firstOrNull { it.start < cutFrom && it.end > cutFrom } ?: return null
        // What stays of the mention goes, down to its @, and the rest of the edit stands.
        val kept = old.substring(0, hit.start) + STARTER + old.substring(maxOf(hit.end, cutTo))
        return kept to hit.start + 1
    }
}

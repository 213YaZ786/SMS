package com.yaz.sms.core.sms

import com.yaz.sms.data.sms.Message

/**
 * Reactions that come as SMS, shown under the message they react to, as
 * RCS shows them, instead of as messages of their own.
 *
 * Two senders write them. Google Messages, in any language: the words in
 * the sender's language, the emoji between two U+200B (U+200C when taken
 * back) and the quoted message between two U+200A, the whole wrapped in
 * U+200A. An iPhone: a sentence in its language around the quoted message,
 * Apple's own wording in [Tapbacks]. Either counts only when the quoted
 * text is a message of the conversation sent before it, so a message that
 * merely looks like one stays as it is.
 */
object SmsReactions {

    data class Parsed(val emoji: String, val quoted: String, val removed: Boolean)

    private const val HAIR = '\u200a'
    private val google = Regex("(?s)^\u200a[^\u200b\u200c\u200a]*([\u200b\u200c])([^\u200b\u200c]+)\\1[^\u200a]*\u200a(.*)\u200a[^\u200a]*\u200a\\s*$")

    fun parse(body: String): Parsed? {
        if (body.isEmpty()) return null
        if (body.first() == HAIR) google.find(body)?.let { m ->
            return Parsed(m.groupValues[2].trim(), m.groupValues[3], removed = m.groupValues[1] == "\u200c")
        }
        return Tapbacks.parse(body.trim())
    }

    /** A reaction message as a line of the list or a notification: short, in the app's words. */
    fun plain(body: String): String {
        val p = parse(body) ?: return body
        val quoted = squash(p.quoted).let { if (it.length > 60) it.take(59).trimEnd() + "…" else it }
        return (if (p.removed) "Removed ${p.emoji} from “" else "Reacted ${p.emoji} to “") + quoted + "”"
    }

    /**
     * A reaction of the user's own, as Google Messages writes one: the words
     * are for whoever reads it as text (an iPhone, an older app), the marks
     * carry the emoji and the quoted message, so it shows as a reaction in
     * Google Messages and here, in any language.
     */
    fun write(emoji: String, text: String, removed: Boolean): String {
        val quoted = squash(text).let { if (it.length > 200) it.take(199).trimEnd() + "…" else it }
        val mark = if (removed) '\u200c' else '\u200b'
        return "$HAIR${if (removed) "Removed " else "Reacted "}$mark$emoji$mark${if (removed) " from " else " to "}$HAIR$quoted$HAIR$HAIR"
    }

    /** The conversation without its reaction messages, each message's reactions by its uid, and the user's own among them. */
    data class Folded(val messages: List<Message>, val reactions: Map<String, List<String>>, val mine: Map<String, List<String>> = emptyMap())

    /** [list] oldest first. */
    fun fold(list: List<Message>): Folded {
        if (list.none { it.body.isNotEmpty() && (it.body.first() == HAIR || Tapbacks.mayBe(it.body)) }) return Folded(list, emptyMap())
        val hidden = HashSet<String>()
        // Per message, per person: the emojis they left there, in order.
        val given = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
        list.forEachIndexed { i, m ->
            if (m.mms || m.rich) return@forEachIndexed
            val p = parse(m.body) ?: return@forEachIndexed
            val target = targetOf(list, i, p.quoted) ?: return@forEachIndexed
            hidden += m.uid
            val who = if (m.box == com.yaz.sms.data.sms.Box.RECEIVED) m.address else ""
            val mine = given.getOrPut(target.uid) { LinkedHashMap() }.getOrPut(who) { ArrayList() }
            if (p.removed) {
                // Taken back: that emoji, or with Apple's words for an iOS 18 emoji, the last one.
                if (!mine.remove(p.emoji) && mine.isNotEmpty()) mine.removeAt(mine.lastIndex)
            } else {
                mine.remove(p.emoji)
                mine += p.emoji
            }
        }
        if (hidden.isEmpty()) return Folded(list, emptyMap())
        val reactions = given.mapValues { (_, byWho) -> byWho.values.flatten() }.filterValues { it.isNotEmpty() }
        val mine = given.mapValues { (_, byWho) -> byWho[""].orEmpty().toList() }.filterValues { it.isNotEmpty() }
        return Folded(list.filterNot { it.uid in hidden }, reactions, mine)
    }

    /** The newest message before [index] whose text is [quoted], whole or cut short with an ellipsis. */
    private fun targetOf(list: List<Message>, index: Int, quoted: String): Message? {
        val q = squash(quoted)
        if (q.isEmpty()) return null
        val cut = q.endsWith('…') || q.endsWith("...")
        val head = q.removeSuffix("…").removeSuffix("...").trimEnd()
        for (j in index - 1 downTo maxOf(0, index - 500)) {
            val m = list[j]
            val text = squash(m.body)
            if (text.isEmpty()) continue
            if (text == q || (cut && head.isNotEmpty() && text.startsWith(head))) return m
        }
        return null
    }

    private fun squash(text: String) = text.trim().replace(Regex("\\s+"), " ")
}

package com.yaz.sms.core.sms

/**
 * The reactions under a message, identical ones gathered with a count and the user's own marked.
 *
 * Ported from Element X Android (AGPL-3.0, https://github.com/element-hq/element-x-android):
 * features/messages/impl/.../timeline/model/AggregatedReaction.kt (key, senders, displayKey ellipsized at 16
 * characters, count = number of senders, isHighlighted = the current user is among them) and
 * libraries/core/.../extensions/BasicExtensions.kt (String.ellipsize). Matrix hands Element X the senders of each
 * key; here SMS and MMS reactions come as one entry per person and emoji ([SmsReactions.fold]), and the encrypted
 * chat as a count per emoji (RichRef.counts), so [ofList] and [ofCounts] build the same groups from each.
 */
object ReactionGroups {

    /** Element X's MAX_DISPLAY_CHARS: reactions can be free text, so what is shown is cut. */
    const val MAX_DISPLAY_CHARS = 16

    /** AggregatedReaction: [key] as given, [count] who gave it, [isHighlighted] when the user is one of them. */
    data class Group(val key: String, val count: Int, val isHighlighted: Boolean) {
        val displayKey: String = ellipsize(key, MAX_DISPLAY_CHARS)
    }

    /** Element X's String.ellipsize: cut at [length] characters and end with an ellipsis, whole when it fits. */
    fun ellipsize(text: String, length: Int): String {
        require(length >= 1)
        return if (text.length <= length) text else text.take(length) + "…"
    }

    /**
     * [all] holds one entry for each person who gave each emoji (a heart from two people is two hearts), in the
     * order they came; [mine] the user's own. The groups keep the order of the first of each emoji, as the
     * timeline of Element X lists its reactions in the order they were first sent.
     */
    fun ofList(all: List<String>, mine: List<String>): List<Group> {
        val counts = LinkedHashMap<String, Int>()
        all.filter { it.isNotEmpty() }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val own = mine.toSet()
        // The user's own is a sender of its emoji even when the list of all did not hold it.
        own.filter { it.isNotEmpty() && it !in counts }.forEach { counts[it] = 1 }
        return counts.map { (key, count) -> Group(key, count, key in own) }
    }

    /** The groups of a chat message: [keys] in order, [counts] the number given for each (one when unknown). */
    fun ofCounts(keys: List<String>, counts: Map<String, Int>, mine: List<String>): List<Group> {
        val own = mine.toSet()
        return keys.filter { it.isNotEmpty() }.distinct().map { key -> Group(key, (counts[key] ?: 1).coerceAtLeast(1), key in own) }
    }
}

package com.yaz.sms.core.sms

import com.yaz.sms.data.sms.Conversation

/** Which conversations the list shows. */
enum class Filter { ALL, UNREAD, UNKNOWN, ARCHIVED }

/** How the list of conversations is put together, apart from the screen. */
object Lists {

    private const val HOUR_MS = 60L * 60 * 1000

    /** Archived: put away after its last message. A newer message brings it back. */
    fun isArchived(c: Conversation, archived: Map<Long, Long>): Boolean = archived[c.threadId]?.let { c.date <= it } == true

    /** The conversations of [filter], pinned ones first, then newest first. */
    fun shown(all: List<Conversation>, filter: Filter, pinned: List<Long>, archived: Map<Long, Long>, known: (Conversation) -> Boolean = { true }): List<Conversation> {
        val kept = all.filter { c ->
            when (filter) {
                Filter.ALL -> !isArchived(c, archived)
                Filter.UNREAD -> c.unread > 0 && !isArchived(c, archived)
                // People not in the contacts: no group, no bank or delivery service.
                Filter.UNKNOWN -> !c.group && !isService(c) && !known(c) && !isArchived(c, archived)
                Filter.ARCHIVED -> isArchived(c, archived)
            }
        }
        return kept.sortedWith(compareByDescending<Conversation> { it.threadId in pinned }.thenByDescending { it.date })
    }

    /**
     * Who is waiting for an answer: their message is the last one, it came
     * in the last [hours] hours and was read, so the dot is gone but the
     * reply is still owed. Newest first.
     */
    fun waiting(all: List<Conversation>, archived: Map<Long, Long>, now: Long, hours: Int = 48, limit: Int = 10): List<Conversation> =
        all.filter { !it.fromMe && it.unread == 0 && it.date >= now - hours * HOUR_MS && !isArchived(it, archived) && it.address.any(Char::isDigit) }
            .sortedByDescending { it.date }
            .take(limit)

    /**
     * A service rather than a person: a sender written in letters (a bank,
     * a delivery company) or a short number of 3 to 6 digits.
     */
    fun isService(c: Conversation): Boolean {
        if (c.group) return false
        val address = c.address.trim()
        if (address.any(Char::isLetter)) return true
        val digits = address.count(Char::isDigit)
        return digits in 3..6
    }

    /** The newest code received in the last [minutes] minutes, with its conversation. */
    fun latestCode(all: List<Conversation>, now: Long, minutes: Int = 10): Pair<Conversation, String>? =
        all.asSequence()
            .filter { !it.fromMe && it.date >= now - minutes * 60_000L }
            .sortedByDescending { it.date }
            .firstNotNullOfOrNull { c -> Codes.find(c.snippet)?.let { c to it } }
}

/** What a swipe on a conversation does, chosen for each side in Settings. */
enum class SwipeAction(val label: String) {
    ARCHIVE("Archive"), DELETE("Delete"), READ("Mark as read"), PIN("Pin"), REPLY("Reply"), NONE("Nothing");

    companion object {
        fun of(name: String, fallback: SwipeAction): SwipeAction = entries.firstOrNull { it.name == name } ?: fallback
    }
}

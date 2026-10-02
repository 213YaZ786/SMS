package com.sms.app.core.sms

import com.sms.app.data.sms.Conversation

/** Which conversations the list shows. */
enum class Filter { ALL, UNREAD, ARCHIVED }

/** How the list of conversations is put together, apart from the screen. */
object Lists {

    private const val HOUR_MS = 60L * 60 * 1000

    /** Archived: put away after its last message. A newer message brings it back. */
    fun isArchived(c: Conversation, archived: Map<Long, Long>): Boolean = archived[c.threadId]?.let { c.date <= it } == true

    /** The conversations of [filter], pinned ones first, then newest first. */
    fun shown(all: List<Conversation>, filter: Filter, pinned: List<Long>, archived: Map<Long, Long>): List<Conversation> {
        val kept = all.filter { c ->
            when (filter) {
                Filter.ALL -> !isArchived(c, archived)
                Filter.UNREAD -> c.unread > 0 && !isArchived(c, archived)
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
}

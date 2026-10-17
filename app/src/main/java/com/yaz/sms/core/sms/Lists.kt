package com.yaz.sms.core.sms

import com.yaz.sms.data.settings.Trashed
import com.yaz.sms.data.sms.Conversation

/** Which conversations the list shows. */
enum class Filter { ALL, UNREAD, UNKNOWN, ARCHIVED, TRASH }

/** How the list of conversations is put together, apart from the screen. */
object Lists {

    private const val HOUR_MS = 60L * 60 * 1000

    /** Archived: put away after its last message. A newer message brings it back. */
    fun isArchived(c: Conversation, archived: Map<Long, Long>): Boolean = archived[c.threadId]?.let { c.date <= it } == true

    /**
     * In the Trash: only the messages up to the date of the newest one when it was trashed are
     * trashed, as AOSP Messaging's DeleteConversationAction deletes up to a cutoffTimestamp
     * and leaves newer messages (https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/datamodel/action/DeleteConversationAction.java).
     * A newer message shows the conversation in the inbox again, as Google Messages does.
     */
    fun isTrashed(c: Conversation, trashed: Map<Long, Trashed>): Boolean = trashed[c.threadId]?.let { c.date <= it.cutoff } == true

    /** The conversations of [filter], pinned ones first, then newest first. */
    fun shown(
        all: List<Conversation>, filter: Filter, pinned: List<Long>, archived: Map<Long, Long>,
        known: (Conversation) -> Boolean = { true },
        trashed: Map<Long, Trashed> = emptyMap()
    ): List<Conversation> {
        val kept = all.filter { c ->
            val gone = isTrashed(c, trashed)
            when (filter) {
                Filter.ALL -> !isArchived(c, archived) && !gone
                Filter.UNREAD -> c.unread > 0 && !isArchived(c, archived) && !gone
                // People not in the contacts: no group, no bank or delivery service.
                Filter.UNKNOWN -> !c.group && !isService(c) && !known(c) && !isArchived(c, archived) && !gone
                Filter.ARCHIVED -> isArchived(c, archived) && !gone
                Filter.TRASH -> gone
            }
        }
        return kept.sortedWith(compareByDescending<Conversation> { it.threadId in pinned }.thenByDescending { it.date })
    }

    /**
     * The cards of To answer: conversations with a message not read yet,
     * newest first, each card once: one already shown (its [key] in
     * [seen]) stays away until a newer message makes a new card.
     */
    fun toAnswer(all: List<Conversation>, archived: Map<Long, Long>, seen: Set<String>, limit: Int = 8): List<Conversation> =
        all.filter { it.unread > 0 && !it.fromMe && !isArchived(it, archived) && !isService(it) && key(it) !in seen }
            .sortedByDescending { it.date }
            .take(limit)

    /** A card's key: the conversation and its newest message, so a new message is a new card. */
    fun key(c: Conversation): String = "${c.threadId}:${c.date}"

    /**
     * A service rather than a person: a sender written in letters (a bank,
     * a delivery company) or a short number of 3 to 6 digits.
     */
    fun isService(c: Conversation): Boolean {
        if (c.group) return false
        val address = c.address.trim()
        // A person met without a number, by their chat address: a person, not a service.
        if ('@' in address) return false
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
    ARCHIVE("Archive"), DELETE("Move to trash"), READ("Mark as read"), PIN("Pin"), REPLY("Reply"), NONE("Nothing");

    companion object {
        fun of(name: String, fallback: SwipeAction): SwipeAction = entries.firstOrNull { it.name == name } ?: fallback
    }
}

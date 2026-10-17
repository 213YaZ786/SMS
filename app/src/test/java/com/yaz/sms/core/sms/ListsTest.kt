package com.yaz.sms.core.sms

import com.yaz.sms.data.settings.Trashed
import com.yaz.sms.data.sms.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

class ListsTest {

    private val hour = 60L * 60 * 1000
    private val now = 1000 * hour

    private fun c(id: Long, hoursAgo: Long, unread: Int = 0, fromMe: Boolean = false, address: String = "0612345678") =
        Conversation(id, address, "hi", now - hoursAgo * hour, unread, fromMe, false)

    @Test
    fun pinnedFirstThenNewest() {
        val all = listOf(c(1, 1), c(2, 5), c(3, 3))
        assertEquals(listOf(2L, 1L, 3L), Lists.shown(all, Filter.ALL, pinned = listOf(2), archived = emptyMap()).map { it.threadId })
    }

    @Test
    fun anArchivedConversationComesBackWithANewMessage() {
        val all = listOf(c(1, 1), c(2, 5))
        val archived = mapOf(1L to now - 2 * hour, 2L to now - 2 * hour)
        assertEquals(listOf(1L), Lists.shown(all, Filter.ALL, emptyList(), archived).map { it.threadId })
        assertEquals(listOf(2L), Lists.shown(all, Filter.ARCHIVED, emptyList(), archived).map { it.threadId })
    }

    // The cutoff rule of AOSP Messaging's DeleteConversationAction (messages up to the cutoff are
    // the trashed ones, newer ones stay), shown to the list as Google Messages does.
    @Test
    fun aTrashedConversationLeavesTheInboxAndShowsInTheTrash() {
        val all = listOf(c(1, 1), c(2, 5), c(3, 3))
        val trashed = mapOf(2L to Trashed(now, now - 5 * hour))
        assertEquals(listOf(1L, 3L), Lists.shown(all, Filter.ALL, emptyList(), emptyMap(), trashed = trashed).map { it.threadId })
        assertEquals(listOf(2L), Lists.shown(all, Filter.TRASH, emptyList(), emptyMap(), trashed = trashed).map { it.threadId })
        assertEquals(emptyList<Long>(), Lists.shown(all, Filter.TRASH, emptyList(), emptyMap()).map { it.threadId })
    }

    @Test
    fun aNewMessageBringsATrashedConversationBackToTheInbox() {
        val trashed = mapOf(2L to Trashed(now, now - 5 * hour))
        val newer = c(2, 0)
        assertEquals(listOf(2L), Lists.shown(listOf(newer), Filter.ALL, emptyList(), emptyMap(), trashed = trashed).map { it.threadId })
        assertEquals(emptyList<Long>(), Lists.shown(listOf(newer), Filter.TRASH, emptyList(), emptyMap(), trashed = trashed).map { it.threadId })
        assertEquals(true, Lists.isTrashed(c(2, 5), trashed))
        assertEquals(false, Lists.isTrashed(newer, trashed))
    }

    @Test
    fun aTrashedConversationIsNeitherUnreadNorUnknownNorArchivedNorAnswered() {
        val all = listOf(c(1, 1, unread = 2), c(2, 2, unread = 1, address = "0699999999"))
        val trashed = mapOf(1L to Trashed(now, now - hour), 2L to Trashed(now, now - 2 * hour))
        val archived = mapOf(1L to now - hour)
        assertEquals(emptyList<Long>(), Lists.shown(all, Filter.UNREAD, emptyList(), emptyMap(), trashed = trashed).map { it.threadId })
        assertEquals(emptyList<Long>(), Lists.shown(all, Filter.UNKNOWN, emptyList(), emptyMap(), trashed = trashed).map { it.threadId })
        assertEquals(emptyList<Long>(), Lists.shown(all, Filter.ARCHIVED, emptyList(), archived, trashed = trashed).map { it.threadId })
        // Restored, the archived one is back in the archive.
        assertEquals(listOf(1L), Lists.shown(all, Filter.ARCHIVED, emptyList(), archived).map { it.threadId })
    }

    @Test
    fun toAnswerIsUnreadOnceEachMessage() {
        val all = listOf(c(1, 1), c(2, 2, unread = 1), c(3, 3, fromMe = true), c(4, 4, unread = 2), c(5, 1, address = "BANK", unread = 1))
        assertEquals(listOf(2L, 4L), Lists.toAnswer(all, emptyMap(), emptySet()).map { it.threadId }.sortedBy { it })
        // Shown once: gone, until a newer message of the same conversation.
        val seen = setOf(Lists.key(all[1]))
        assertEquals(listOf(4L), Lists.toAnswer(all, emptyMap(), seen).map { it.threadId })
        val newer = all[1].copy(date = all[1].date + 1)
        assertEquals(true, Lists.toAnswer(listOf(newer), emptyMap(), seen).isNotEmpty())
    }

    @Test
    fun servicesAreLettersOrShortNumbers() {
        assertEquals(true, Lists.isService(c(1, 1, address = "AMAZON")))
        assertEquals(true, Lists.isService(c(2, 1, address = "38015")))
        assertEquals(false, Lists.isService(c(3, 1, address = "+33612345678")))
        assertEquals(false, Lists.isService(c(4, 1, address = "abc123@relay.example")))
    }

    @Test
    fun theLastCodeShowsTenMinutes() {
        val minute = 60_000L
        val code = Conversation(9, "GOOGLE", "Your code is 482913", now - 2 * minute, 0, false, false)
        val old = Conversation(8, "BANK", "Code 111222", now - 30 * minute, 0, false, false)
        assertEquals("482913", Lists.latestCode(listOf(old, code), now)?.second)
        assertEquals(null, Lists.latestCode(listOf(old), now))
    }

    // iOS 26 Messages' Unknown Senders (https://support.apple.com/en-us/125068) over SpamBlocker's
    // checkers (https://github.com/aj3423/SpamBlocker, Checker.kt): the screened conversations leave
    // the main list for Unknown, Transactions and Promotions; a code stays eight hours.
    @Test
    fun screenedConversationsLeaveTheMainList() {
        val all = listOf(c(1, 1), c(2, 2), c(3, 3), c(4, 4), c(5, 20, address = "GOOGLE"))
        val screened = mapOf(
            2L to Verdict(Category.PROMOTIONS, Screening.BY_OPT_OUT),
            3L to Verdict(Category.TRANSACTIONS, Screening.BY_PARCEL),
            4L to Verdict(Category.UNKNOWN, Screening.BY_DEFAULT),
            5L to Verdict(Category.TRANSACTIONS, Screening.BY_CODE)
        )
        fun ids(filter: Filter) = Lists.shown(all, filter, emptyList(), emptyMap(), screened = screened, now = now).map { it.threadId }
        // The code of 20 hours ago has gone with its eight hours.
        assertEquals(listOf(1L), ids(Filter.ALL))
        assertEquals(listOf(2L, 3L, 4L, 5L), ids(Filter.UNKNOWN))
        assertEquals(listOf(3L, 5L), ids(Filter.TRANSACTIONS))
        assertEquals(listOf(2L), ids(Filter.PROMOTIONS))
    }

    @Test
    fun aRecentCodeIsAlsoInTheMainList() {
        val all = listOf(c(1, 2, address = "GOOGLE"), c(2, 1))
        val screened = mapOf(1L to Verdict(Category.TRANSACTIONS, Screening.BY_CODE))
        assertEquals(listOf(2L, 1L), Lists.shown(all, Filter.ALL, emptyList(), emptyMap(), screened = screened, now = now).map { it.threadId })
        assertEquals(listOf(1L), Lists.shown(all, Filter.UNKNOWN, emptyList(), emptyMap(), screened = screened, now = now).map { it.threadId })
        assertEquals(listOf(1L), Lists.shown(all, Filter.TRANSACTIONS, emptyList(), emptyMap(), screened = screened, now = now).map { it.threadId })
    }

    @Test
    fun withScreeningOffEverythingIsAsBefore() {
        val all = listOf(c(1, 1), c(2, 2))
        assertEquals(listOf(1L, 2L), Lists.shown(all, Filter.ALL, emptyList(), emptyMap(), screened = null).map { it.threadId })
        assertEquals(emptyList<Long>(), Lists.shown(all, Filter.PROMOTIONS, emptyList(), emptyMap(), screened = null).map { it.threadId })
    }

    @Test
    fun anArchivedOrTrashedUnknownIsNotInUnknown() {
        val all = listOf(c(1, 1), c(2, 2))
        val screened = mapOf(
            1L to Verdict(Category.UNKNOWN, Screening.BY_DEFAULT),
            2L to Verdict(Category.UNKNOWN, Screening.BY_DEFAULT)
        )
        val archived = mapOf(1L to now)
        val trashed = mapOf(2L to Trashed(now, now))
        assertEquals(emptyList<Long>(), Lists.shown(all, Filter.UNKNOWN, emptyList(), archived, trashed = trashed, screened = screened, now = now).map { it.threadId })
    }

    @Test
    fun unknownIsPeopleNotInTheContacts() {
        val all = listOf(c(1, 1, address = "0611111111"), c(2, 2, address = "0622222222"), c(3, 3, address = "LaPoste"))
        val shown = Lists.shown(all, Filter.UNKNOWN, emptyList(), emptyMap(), known = { it.address == "0611111111" })
        assertEquals(listOf(2L), shown.map { it.threadId })
    }
}

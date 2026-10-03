package com.sms.app.core.sms

import com.sms.app.data.sms.Conversation
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

    @Test
    fun waitingAreReadMessagesNotAnswered() {
        val all = listOf(c(1, 1), c(2, 2, unread = 1), c(3, 3, fromMe = true), c(4, 60), c(5, 1, address = "BANK"))
        assertEquals(listOf(1L), Lists.waiting(all, emptyMap(), now).map { it.threadId })
    }

    @Test
    fun servicesAreLettersOrShortNumbers() {
        assertEquals(true, Lists.isService(c(1, 1, address = "AMAZON")))
        assertEquals(true, Lists.isService(c(2, 1, address = "38015")))
        assertEquals(false, Lists.isService(c(3, 1, address = "+33612345678")))
    }

    @Test
    fun theLastCodeShowsTenMinutes() {
        val minute = 60_000L
        val code = Conversation(9, "GOOGLE", "Your code is 482913", now - 2 * minute, 0, false, false)
        val old = Conversation(8, "BANK", "Code 111222", now - 30 * minute, 0, false, false)
        assertEquals("482913", Lists.latestCode(listOf(old, code), now)?.second)
        assertEquals(null, Lists.latestCode(listOf(old), now))
    }

    @Test
    fun unknownIsPeopleNotInTheContacts() {
        val all = listOf(c(1, 1, address = "0611111111"), c(2, 2, address = "0622222222"), c(3, 3, address = "LaPoste"))
        val shown = Lists.shown(all, Filter.UNKNOWN, emptyList(), emptyMap(), known = { it.address == "0611111111" })
        assertEquals(listOf(2L), shown.map { it.threadId })
    }
}

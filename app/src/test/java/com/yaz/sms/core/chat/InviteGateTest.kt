package com.yaz.sms.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases of Signal-Android's message requests (AGPL-3.0,
 * https://github.com/signalapp/Signal-Android, RecipientUtil.isMessageRequestAccepted and
 * isCallRequestAccepted: accepted for a system contact, for a thread the user has written in;
 * not accepted for anyone else), here for a data SMS invite, plus the limit of one invite sent
 * back per number per day.
 */
class InviteGateTest {

    private val stranger = "+33799887766"

    private fun accepted(
        asked: Boolean = false, contact: Boolean = false, sent: Boolean = false
    ) = InviteGate.accepted(asked, { contact }, { sent })

    @Test
    fun aSystemContactIsAccepted() = assertTrue(accepted(contact = true))

    @Test
    fun aThreadTheUserWroteInIsAccepted() = assertTrue(accepted(sent = true))

    @Test
    fun aNumberWeAskedOurselvesIsAccepted() = assertTrue(accepted(asked = true))

    @Test
    fun aStrangerIsIgnored() {
        assertFalse(accepted())
    }

    @Test
    fun theStoresAreReadOnlyWhenTheCheaperAnswersAreNo() {
        var reads = 0
        assertTrue(InviteGate.accepted(true, { reads++; false }, { reads++; false }))
        assertEquals(0, reads)
        assertTrue(InviteGate.accepted(false, { reads++; true }, { reads++; false }))
        assertEquals(1, reads)
    }

    @Test
    fun aProvenOrInvitedNumberIsOneWeAsked() {
        assertTrue(InviteGate.asked(ChatLink(stranger, proven = true)))
        assertTrue(InviteGate.asked(ChatLink(stranger, myNonce = "0a0b")))
        assertFalse(InviteGate.asked(ChatLink(stranger, address = "a@b.c", chatId = 4)))
        assertFalse(InviteGate.asked(null))
    }

    @Test
    fun anInviteGoesBackOncePerNumberPerDay() {
        val now = 1_000_000_000_000L
        assertTrue(InviteGate.mayAnswer(null, now))
        assertTrue(InviteGate.mayAnswer(ChatLink(stranger), now))
        assertFalse(InviteGate.mayAnswer(ChatLink(stranger, askedAt = now - 1_000), now))
        assertFalse(InviteGate.mayAnswer(ChatLink(stranger, askedAt = now - InviteGate.ANSWER_EVERY_MS + 1), now))
        assertTrue(InviteGate.mayAnswer(ChatLink(stranger, askedAt = now - InviteGate.ANSWER_EVERY_MS), now))
    }

    @Test
    fun aClockSetBackDoesNotBlockTheAnswerForever() {
        val now = 1_000_000_000_000L
        assertTrue(InviteGate.mayAnswer(ChatLink(stranger, askedAt = now + 5 * InviteGate.ANSWER_EVERY_MS), now))
    }
}

package com.yaz.sms.core.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases of AOSP Messaging's PduPersister.checkAndLoadToCcRecipients (oneself left out of a received message's people)
 * and of BugleDatabaseOperations.sanitizeConversationParticipants (the same number once; every own number out when
 * others remain; the list kept when all are oneself), https://android.googlesource.com/platform/packages/apps/Messaging/
 * (src/com/android/messaging/mmslib/pdu/PduPersister.java and src/com/android/messaging/datamodel/BugleDatabaseOperations.java).
 * AOSP ships no test of either; the cases follow each branch of the code and the threads that split in the issues
 * qksms #1443 (https://github.com/moezbhatti/qksms/issues/1443), Fossify Messages #699
 * (https://github.com/FossifyOrg/Messages/issues/699) and Quik #505 (https://github.com/quik-sms/quik/issues/505).
 */
class OwnNumbersTest {

    private val sender = "+33611112222"

    @Test fun ownNumberInNationalAndInternationalForm() {
        assertTrue(OwnNumbers.isOwn("0698765432", listOf("+33698765432")))
        assertTrue(OwnNumbers.isOwn("+33698765432", listOf("0698765432")))
        assertTrue(OwnNumbers.isOwn("+33 6 98 76 54 32", listOf("0698765432")))
        assertFalse(OwnNumbers.isOwn("0698765433", listOf("0698765432")))
    }

    @Test fun messageToOneselfAloneIsOneToOne() {
        assertEquals(listOf(sender), OwnNumbers.peopleOf(sender, listOf("0698765432"), listOf("+33698765432"), true))
        assertEquals(listOf(sender), OwnNumbers.peopleOf(sender, listOf("+33698765432"), listOf("0698765432"), true))
    }

    @Test fun severalSimsEachOneLeftOut() {
        val own = listOf("+33698765432", "+33755443322")
        assertEquals(listOf(sender), OwnNumbers.peopleOf(sender, listOf("0755443322"), own, true))
        assertEquals(listOf(sender), OwnNumbers.peopleOf(sender, listOf("0698765432"), own, true))
    }

    @Test fun realGroupKeepsItsOthers() {
        val others = listOf("+33600000002", "0698765432", "+33600000003")
        assertEquals(
            listOf(sender, "+33600000002", "+33600000003"),
            OwnNumbers.peopleOf(sender, others, listOf("+33698765432"), true)
        )
    }

    @Test fun copyAddressesCountToo() {
        assertEquals(
            listOf(sender, "+33600000002"),
            OwnNumbers.peopleOf(sender, listOf("0698765432", "+33600000002"), listOf("+33698765432"), true)
        )
    }

    @Test fun ownNumberUnknownAndOneAddressIsOneself() {
        // AOSP: no number for the SIM and a single TO or CC address, that address is oneself.
        assertEquals(listOf(sender), OwnNumbers.peopleOf(sender, listOf("0698765432"), emptyList(), false))
    }

    @Test fun ownNumberUnknownAndSeveralAddressesChangesNothing() {
        assertEquals(
            listOf(sender, "0698765432", "+33600000002"),
            OwnNumbers.peopleOf(sender, listOf("0698765432", "+33600000002"), emptyList(), false)
        )
    }

    @Test fun theNumberWrittenByHandSplitsNoGroupAnyMore() {
        // The SIM gives nothing: the group of two others whose TO holds the user is, with the number written in
        // Settings, the group of the two others; without it, a thread of three that the next reply splits.
        val others = listOf("0698765432", "+33600000002")
        assertEquals(listOf(sender, "0698765432", "+33600000002"), OwnNumbers.peopleOf(sender, others, emptyList(), false))
        assertEquals(listOf(sender, "+33600000002"), OwnNumbers.peopleOf(sender, others, listOf("+33698765432"), true))
    }

    @Test fun messageToNobodyIsTheSenders() {
        assertEquals(listOf(sender), OwnNumbers.peopleOf(sender, emptyList(), listOf("+33698765432"), true))
    }

    @Test fun theSameNumberWrittenTwiceIsKeptOnce() {
        val own = listOf("+33698765432")
        // The sender again in CC, and a number in two forms.
        assertEquals(listOf(sender, "+33600000002"), OwnNumbers.peopleOf(sender, listOf("+33600000002", "0611112222", "06 00 00 00 02"), own, true))
        assertEquals(listOf("alerts@example.org", sender), OwnNumbers.sanitize(listOf("alerts@example.org", sender, "alerts@example.org"), own))
    }

    @Test fun aMessageSentByOneselfToOthersIsTheirConversation() {
        // Seen from another device: From is the user, To the others. Oneself leaves once others remain.
        val own = listOf("+33698765432")
        assertEquals(listOf("+33600000002", "+33600000003"), OwnNumbers.peopleOf("0698765432", listOf("+33600000002", "+33600000003"), own, true))
    }

    @Test fun everyoneBeingOneselfLeavesTheListAsItIs() {
        val own = listOf("+33698765432", "+33755443322")
        assertEquals(listOf("0698765432", "0755443322"), OwnNumbers.sanitize(listOf("0698765432", "0755443322"), own))
        assertEquals(listOf("0698765432"), OwnNumbers.peopleOf("0698765432", listOf("+33698765432"), own, true))
    }

    @Test fun existingThreadShownWithoutOneself() {
        val own = listOf("+33698765432")
        assertEquals(listOf(sender), OwnNumbers.withoutOwn(listOf(sender, "0698765432"), own))
        assertEquals(listOf(sender, "+33600000002"), OwnNumbers.withoutOwn(listOf(sender, "0698765432", "+33600000002"), own))
    }

    @Test fun threadWithOneselfAloneOrNoOwnNumberStaysAsItIs() {
        assertEquals(listOf("0698765432"), OwnNumbers.withoutOwn(listOf("0698765432"), listOf("+33698765432")))
        assertEquals(listOf(sender, "0698765432"), OwnNumbers.withoutOwn(listOf(sender, "0698765432"), emptyList()))
        // The same number twice is one person, and that person is oneself.
        assertEquals(listOf("0698765432"), OwnNumbers.withoutOwn(listOf("0698765432", "+33698765432"), listOf("+33698765432")))
    }

    @Test fun hostileListsStayBounded() {
        val own = listOf("+33698765432")
        assertEquals(emptyList<String>(), OwnNumbers.sanitize(emptyList(), own))
        assertEquals(listOf(sender), OwnNumbers.sanitize(listOf("", " ", sender), own))
        val many = List(5000) { "+3361111" + (1000 + it % 10) }
        assertEquals(10, OwnNumbers.sanitize(many, own).size)
    }
}

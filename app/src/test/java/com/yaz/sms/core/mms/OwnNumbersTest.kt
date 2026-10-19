package com.yaz.sms.core.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases of AOSP Messaging's PduPersister.checkAndLoadToCcRecipients: oneself left out of a received message's people. */
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

    @Test fun messageToNobodyIsTheSenders() {
        assertEquals(listOf(sender), OwnNumbers.peopleOf(sender, emptyList(), listOf("+33698765432"), true))
    }

    @Test fun existingThreadShownWithoutOneself() {
        val own = listOf("+33698765432")
        assertEquals(listOf(sender), OwnNumbers.withoutOwn(listOf(sender, "0698765432"), own))
        assertEquals(listOf(sender, "+33600000002"), OwnNumbers.withoutOwn(listOf(sender, "0698765432", "+33600000002"), own))
    }

    @Test fun threadWithOneselfAloneOrNoOwnNumberStaysAsItIs() {
        assertEquals(listOf("0698765432"), OwnNumbers.withoutOwn(listOf("0698765432"), listOf("+33698765432")))
        assertEquals(listOf(sender, "0698765432"), OwnNumbers.withoutOwn(listOf(sender, "0698765432"), emptyList()))
        assertEquals(listOf("0698765432", "+33698765432"), OwnNumbers.withoutOwn(listOf("0698765432", "+33698765432"), listOf("+33698765432")))
    }
}

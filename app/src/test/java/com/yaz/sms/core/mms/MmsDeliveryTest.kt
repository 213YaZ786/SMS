package com.yaz.sms.core.mms

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.sms.core.mms.MmsDelivery.State
import com.yaz.sms.core.mms.pdu.DeliveryInd
import com.yaz.sms.core.mms.pdu.PduHeaders
import com.yaz.sms.core.mms.pdu.PduParser
import com.yaz.sms.core.mms.pdu.SendConf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Delivery reports of picture messages. AOSP ships no test of its PushReceiver, DeliveryReportActivity or of
 * mmslib's PduParser for an M-Delivery.ind (platform/packages/apps/Mms and Messaging), so the cases follow the
 * code paths of MmsDelivery: the PDUs are written byte by byte as OMA-TS-MMS-ENC section 6.1.4 (M-Delivery.ind) and
 * 6.1.2 (M-Send.conf) lay their headers out (header octet, then value; text strings end with 0), the status
 * mapping is the table of X-Mms-Status, the match is PushReceiver.findThreadId's and the group rule is
 * DeliveryReportActivity's one status per recipient.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [36])
class MmsDeliveryTest {

    private fun text(s: String) = s.toByteArray(Charsets.ISO_8859_1) + 0

    /** X-Mms-Message-Type, version 1.0, Message-ID, To, Date 0x5F000000, X-Mms-Status. */
    private fun deliveryInd(messageId: String, to: String, status: Int): ByteArray =
        byteArrayOf(0x8C.toByte(), 0x86.toByte(), 0x8D.toByte(), 0x90.toByte(), 0x8B.toByte()) + text(messageId) +
            byteArrayOf(0x97.toByte()) + text(to) +
            byteArrayOf(0x85.toByte(), 0x04, 0x5F, 0x00, 0x00, 0x00, 0x95.toByte(), status.toByte())

    @Test fun parsesAnM_Delivery_ind() {
        val pdu = PduParser(deliveryInd("abc123", "+33612345678/TYPE=PLMN", PduHeaders.STATUS_RETRIEVED), true).parse()
        assertTrue(pdu is DeliveryInd)
        pdu as DeliveryInd
        assertEquals("abc123", MmsStore.isoString(pdu.messageId))
        assertEquals(PduHeaders.STATUS_RETRIEVED, pdu.status)
        // The parser takes the "/TYPE=PLMN" off a phone number (PduParser.parseEncodedStringValue).
        assertEquals(listOf("+33612345678"), pdu.to.map { it.string })
        assertEquals(0x5F000000L, pdu.date)
    }

    @Test fun parsesTheMessageIdOfAnM_Send_conf() {
        // Message-Type 0x81, Transaction-Id, version, Response-Status OK, Message-ID.
        val bytes = byteArrayOf(0x8C.toByte(), 0x81.toByte(), 0x98.toByte()) + text("t1") +
            byteArrayOf(0x8D.toByte(), 0x90.toByte(), 0x92.toByte(), PduHeaders.RESPONSE_STATUS_OK.toByte(), 0x8B.toByte()) + text("<id@mmsc>")
        val conf = PduParser(bytes, true).parse()
        assertTrue(conf is SendConf)
        conf as SendConf
        assertEquals(PduHeaders.RESPONSE_STATUS_OK, conf.responseStatus)
        assertEquals("<id@mmsc>", MmsStore.isoString(conf.messageId))
    }

    @Test fun hostilePushesGiveNoDeliveryInd() {
        val whole = deliveryInd("abc123", "+33612345678", PduHeaders.STATUS_RETRIEVED)
        // Empty, cut short at every length, and noise: never an exception, never a report without its Message-ID.
        assertNull(PduParser(ByteArray(0), true).parse())
        for (n in 1 until whole.size) {
            val pdu = runCatching { PduParser(whole.copyOf(n), true).parse() }.getOrNull()
            if (pdu is DeliveryInd) assertTrue(pdu.messageId != null && pdu.to != null)
        }
        val noise = ByteArray(64) { (it * 37 + 11).toByte() }
        runCatching { PduParser(noise, true).parse() }
        // A report without its Message-ID is refused by the parser's mandatory headers (checkMandatoryHeader).
        val noId = byteArrayOf(0x8C.toByte(), 0x86.toByte(), 0x8D.toByte(), 0x90.toByte(), 0x97.toByte()) + text("+33612345678") +
            byteArrayOf(0x85.toByte(), 0x04, 0x5F, 0x00, 0x00, 0x00, 0x95.toByte(), 0x81.toByte())
        assertNull(PduParser(noId, true).parse())
    }

    @Test fun statusReadAsOmaDefinesIt() {
        assertEquals(State.DELIVERED, MmsDelivery.stateOf(PduHeaders.STATUS_RETRIEVED))
        for (s in listOf(PduHeaders.STATUS_REJECTED, PduHeaders.STATUS_EXPIRED, PduHeaders.STATUS_UNREACHABLE, PduHeaders.STATUS_UNRECOGNIZED)) {
            assertEquals(State.FAILED, MmsDelivery.stateOf(s))
        }
        for (s in listOf(PduHeaders.STATUS_DEFERRED, PduHeaders.STATUS_FORWARDED, PduHeaders.STATUS_INDETERMINATE, 0, 0xFF)) {
            assertEquals(State.PENDING, MmsDelivery.stateOf(s))
        }
    }

    @Test fun addressesCompareAsNumbers() {
        assertTrue(MmsDelivery.same("+33612345678/TYPE=PLMN", "0612345678"))
        assertTrue(MmsDelivery.same("+33612345678", "+33 6 12 34 56 78"))
        assertTrue(MmsDelivery.same("a@b.com", "a@b.com"))
        assertFalse(MmsDelivery.same("+33612345678", "+33612345679"))
        assertFalse(MmsDelivery.same("a@b.com", "c@d.com"))
        assertFalse(MmsDelivery.same("", ""))
        assertFalse(MmsDelivery.same("a@b.com", ""))
    }

    @Test fun reportNamesTheRecipientItIsAbout() {
        val group = listOf("0611111111", "0622222222", "0633333333")
        assertEquals("0622222222", MmsDelivery.recipientFor(listOf("+33622222222/TYPE=PLMN"), group))
        // Someone the message did not go to: nothing is taken.
        assertNull(MmsDelivery.recipientFor(listOf("+33699999999"), group))
        assertNull(MmsDelivery.recipientFor(emptyList(), group))
        // One recipient: the report is about them, however the carrier wrote the address.
        assertEquals("0611111111", MmsDelivery.recipientFor(listOf("tel:0611111111"), listOf("0611111111")))
        assertEquals("0611111111", MmsDelivery.recipientFor(listOf("anything"), listOf("0611111111")))
        assertNull(MmsDelivery.recipientFor(listOf("+33611111111"), emptyList()))
    }

    @Test fun groupIsDeliveredWhenEveryRecipientIs() {
        val group = listOf("a", "b", "c")
        assertTrue(MmsDelivery.allDelivered(group, mapOf("a" to State.DELIVERED, "b" to State.DELIVERED, "c" to State.DELIVERED)))
        assertFalse(MmsDelivery.allDelivered(group, mapOf("a" to State.DELIVERED, "b" to State.DELIVERED)))
        assertFalse(MmsDelivery.allDelivered(group, mapOf("a" to State.DELIVERED, "b" to State.PENDING, "c" to State.DELIVERED)))
        assertFalse(MmsDelivery.allDelivered(group, mapOf("a" to State.DELIVERED, "b" to State.FAILED, "c" to State.DELIVERED)))
        assertFalse(MmsDelivery.allDelivered(group, emptyMap()))
        assertFalse(MmsDelivery.allDelivered(emptyList(), mapOf("a" to State.DELIVERED)))
        assertTrue(MmsDelivery.allDelivered(listOf("a"), mapOf("a" to State.DELIVERED, "z" to State.FAILED)))
    }
}

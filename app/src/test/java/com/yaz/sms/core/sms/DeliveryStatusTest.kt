package com.yaz.sms.core.sms

import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases of 3GPP TS 23.040 section 9.2.3.15 and of AOSP Messaging's SendStatusReceiver. */
class DeliveryStatusTest {

    private val complete = Telephony.Sms.STATUS_COMPLETE
    private val pending = Telephony.Sms.STATUS_PENDING
    private val failed = Telephony.Sms.STATUS_FAILED

    /** A 3gpp2 status: error class in bits 25-24, code in bits 23-16. */
    private fun cdma(errorClass: Int, code: Int) = (errorClass shl 24) or (code shl 16)

    @Test
    fun belowThirtyTwoIsDelivered() {
        for (s in listOf(0, 1, 2, 31)) assertEquals(complete, DeliveryStatus.of(s, "3gpp"))
    }

    @Test
    fun thirtyTwoToSixtyThreeStillPending() {
        for (s in listOf(32, 48, 63)) assertEquals(pending, DeliveryStatus.of(s, "3gpp"))
    }

    @Test
    fun sixtyFourAndUpFailed() {
        for (s in listOf(64, 100, 127)) assertEquals(failed, DeliveryStatus.of(s, "3gpp"))
    }

    @Test
    fun noFormatIsGsm() {
        assertEquals(complete, DeliveryStatus.of(0, null))
        assertEquals(failed, DeliveryStatus.of(64, null))
    }

    @Test
    fun cdmaDeliveredIsClassZeroCodeTwo() {
        assertEquals(complete, DeliveryStatus.of(cdma(0, 0x02), "3gpp2"))
        assertEquals(pending, DeliveryStatus.of(cdma(0, 0x00), "3gpp2"))
        assertEquals(pending, DeliveryStatus.of(cdma(0, 0x03), "3gpp2"))
    }

    @Test
    fun cdmaTemporaryPendingPermanentFailed() {
        assertEquals(pending, DeliveryStatus.of(cdma(2, 0x04), "3gpp2"))
        assertEquals(failed, DeliveryStatus.of(cdma(3, 0x04), "3gpp2"))
        assertEquals(pending, DeliveryStatus.of(cdma(1, 0x02), "3gpp2"))
    }

    @Test
    fun cdmaIgnoresTheGsmBands() {
        // 64 would be a failure in GSM; as 3gpp2 it is class 0, code 0: not delivered, still pending.
        assertEquals(pending, DeliveryStatus.of(64, "3gpp2"))
    }
}

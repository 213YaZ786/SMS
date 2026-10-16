package com.yaz.sms.core.sms

import android.provider.Telephony

/**
 * What a status report says, as the value kept in the row's STATUS column.
 *
 * GSM: AOSP SmsDispatchersController.handleSmsStatusReport
 * (https://android.googlesource.com/platform/frameworks/opt/telephony/+/refs/heads/main/src/java/com/android/internal/telephony/SmsDispatchersController.java)
 * counts a report final when tpStatus >= STATUS_FAILED (64) or < STATUS_PENDING (32),
 * after 3GPP TS 23.040 section 9.2.3.15: below 32 the message reached the
 * recipient, 32 to 63 the network still tries, 64 and up it gave up.
 *
 * 3gpp2: AOSP Messaging SendStatusReceiver
 * (https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/receiver/SendStatusReceiver.java):
 * error class in bits 25-24, status code in bits 23-16; class 0 with code
 * 0x02 is delivered, class 3 permanent failure, any other still pending.
 */
object DeliveryStatus {

    private const val ERROR_NONE = 0
    private const val ERROR_PERMANENT = 3
    private const val CODE_DELIVERED = 0x02

    /** The Telephony.Sms.STATUS_* value for the status of a report read from its PDU. */
    fun of(status: Int, format: String?): Int =
        if ("3gpp2" == format) cdma(status) else gsm(status)

    private fun gsm(tpStatus: Int): Int = when {
        tpStatus >= Telephony.Sms.STATUS_FAILED -> Telephony.Sms.STATUS_FAILED
        tpStatus < Telephony.Sms.STATUS_PENDING -> Telephony.Sms.STATUS_COMPLETE
        else -> Telephony.Sms.STATUS_PENDING
    }

    private fun cdma(status: Int): Int {
        val errorClass = (status shr 24) and 0x03
        val code = (status shr 16) and 0x3f
        return when (errorClass) {
            ERROR_NONE -> if (code == CODE_DELIVERED) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_PENDING
            ERROR_PERMANENT -> Telephony.Sms.STATUS_FAILED
            else -> Telephony.Sms.STATUS_PENDING
        }
    }
}

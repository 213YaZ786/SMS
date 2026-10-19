package com.yaz.sms.core.chat

import android.content.Context
import android.net.Uri
import android.provider.Telephony

/**
 * Who may make the phone act on a data SMS invite. Signal-Android's message requests
 * (AGPL-3.0, https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/recipients/RecipientUtil.java,
 * isMessageRequestAccepted and isCallRequestAccepted): a sender is accepted when it is a system
 * contact, when profile sharing is on, or when the user has sent a message in the thread. Here:
 * a contact, a number we asked ourselves (our invite sent, or its chat
 * proven), or one the user has sent an SMS or MMS to. Anyone else is ignored: nothing started,
 * nothing sent. The chat still comes when the user writes to that number: ThreadScreen sends
 * our invite, the other side answers with its own, and the sender is then accepted.
 */
object InviteGate {

    /** Our invite goes back to one number at most once a day, so a faked sender cannot make us send again and again. */
    const val ANSWER_EVERY_MS = 24L * 60 * 60 * 1000

    /**
     * Whether the invite of [address] is acted on. [asked]: the number is proven or our own invite
     * went to it. [contact] and [sent] are asked only when the cheaper answers are no (they read
     * Android's stores).
     */
    fun accepted(asked: Boolean, contact: () -> Boolean, sent: () -> Boolean): Boolean =
        asked || contact() || sent()

    /** Our invite may go back to this number now: none went in the last day. */
    fun mayAnswer(link: ChatLink?, now: Long): Boolean =
        link == null || link.askedAt <= 0 || now - link.askedAt >= ANSWER_EVERY_MS || link.askedAt > now

    /** We asked this number ourselves, or its chat is proven. */
    fun asked(link: ChatLink?): Boolean = link != null && (link.proven || link.myNonce.isNotEmpty())

    /**
     * The user has sent an SMS or an MMS to [number]: the messages of the number from Android's
     * store, sent ones only (AOSP MmsSmsProvider's messages/byphone, matching the number with
     * PHONE_NUMBERS_EQUAL; Telephony.Sms.MESSAGE_TYPE_SENT and Telephony.Mms.MESSAGE_BOX_SENT).
     * The selection is checked per table by the provider, hence one query per kind.
     */
    fun sentTo(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        val uri = Uri.withAppendedPath(Telephony.MmsSms.CONTENT_FILTER_BYPHONE_URI, Uri.encode(number))
        val columns = arrayOf(Telephony.Sms._ID, Telephony.Sms.TYPE, Telephony.Mms.MESSAGE_BOX)
        return listOf(
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_SENT}",
            "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_SENT}"
        ).any { where ->
            runCatching {
                context.contentResolver.query(uri, columns, where, null, null)?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
        }
    }
}

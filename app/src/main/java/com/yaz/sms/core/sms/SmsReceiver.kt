package com.yaz.sms.core.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import java.util.GregorianCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A new SMS, given by Android to the messaging app alone: the parts joined,
 * kept in the store (only this app may), then shown. Numbers the user
 * blocked never reach here, Android drops them first. The steps are AOSP Messaging's.
 */
// not ported: updateSmsReceiveHandler (a secondary user's old SMS_RECEIVED path; this app takes SMS_DELIVER alone)
// not ported: postNewMessageSecondaryUserNotification (the secondary user's notification; MessageNotifier shows ours)
// not ported: cancelSecondaryUserNotification (same, no such notification)
// not ported: getNotificationTag (same, no such notification)
// not ported: getMessagesFromIntent (the intent's messages are read at the top of deliverSmsIntent)
// not ported: shouldIgnoreMessage (nothing is ignored here, Android drops blocked numbers first)
// not ported: ReceiveSmsMessageAction (no local database to sync; the insert and the notification end deliverSmsMessages)
class SmsReceiver : BroadcastReceiver() {

    /** Worked off the main thread; a bad PDU or a refused store never kills the process. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deliverSmsIntent(app, intent)
            } catch (e: Throwable) {
                // The class only, nothing of the message.
                Log.w(TAG, "SMS not delivered: " + e.javaClass.simpleName)
            } finally {
                done.finish()
            }
        }
    }

    /** https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/receiver/SmsReceiver.java#L100-L141 */
    private fun deliverSmsIntent(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty().toTypedArray()

        // Check messages for validity
        if (messages.isEmpty()) {
            Log.e(TAG, "processReceivedSms: null or zero or ignored message")
            return
        }

        val errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, NO_ERROR_CODE)
        // Always convert negative subIds into -1
        var subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (subId < 0) subId = intent.getIntExtra(EXTRA_SUB_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (subId < 0) subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        deliverSmsMessages(context, subId, errorCode, messages)
    }

    /** https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/receiver/SmsReceiver.java#L100-L141 */
    private fun deliverSmsMessages(context: Context, subId: Int, errorCode: Int, messages: Array<SmsMessage>) {
        val messageValues = parseReceivedSmsMessage(messages, errorCode)

        val nowInMillis = System.currentTimeMillis()
        val receivedTimestampMs = getMessageDate(messages[0], nowInMillis)

        messageValues.put(Telephony.Sms.DATE, receivedTimestampMs)
        // Default to unread and unseen
        messageValues.put(Telephony.Sms.READ, 0)
        messageValues.put(Telephony.Sms.SEEN, 0)
        messageValues.put(Telephony.Sms.SUBSCRIPTION_ID, subId)

        val address = messageValues.getAsString(Telephony.Sms.ADDRESS).orEmpty()
        if (messages[0].messageClass == SmsMessage.MessageClass.CLASS_0) {
            // A flash message is shown, never kept (AOSP: launchClassZeroActivity).
            MessageNotifier(context).postNewMessageNotification(address, messageValues.getAsString(Telephony.Sms.BODY).orEmpty())
            return
        }
        val thread = Telephony.Threads.getOrCreateThreadId(context, address)
        messageValues.put(Telephony.Sms.THREAD_ID, thread)
        val inserted = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, messageValues)
        if (inserted == null) {
            // The store refused it: nothing to show, nothing to notify.
            Log.w(TAG, "deliverSmsMessages: the store kept no row")
            return
        }
        // Quiet sales senders and spam are left out by the notifier itself.
        MessageNotifier(context).show(thread, subId)
    }

    /**
     * Parse values from a received sms message. A message without an address comes from an unknown
     * sender (ProcessDownloadedMmsAction does the same for a message without a sender), not dropped.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsUtils.java#L979-L1049
     */
    private fun parseReceivedSmsMessage(msgs: Array<SmsMessage>, error: Int): ContentValues {
        val sms = msgs[0]
        val values = ContentValues()

        values.put(Telephony.Sms.ADDRESS, sms.displayOriginatingAddress?.takeIf { it.isNotBlank() } ?: UNKNOWN_SENDER)
        values.put(Telephony.Sms.BODY, buildMessageBodyFromPdus(msgs))
        values.put(Telephony.Sms.DATE_SENT, sms.timestampMillis)
        values.put(Telephony.Sms.PROTOCOL, sms.protocolIdentifier)
        if (sms.pseudoSubject.orEmpty().isNotEmpty()) {
            values.put(Telephony.Sms.SUBJECT, sms.pseudoSubject)
        }
        values.put(Telephony.Sms.REPLY_PATH_PRESENT, if (sms.isReplyPathPresent) 1 else 0)
        values.put(Telephony.Sms.SERVICE_CENTER, sms.serviceCenterAddress)
        // Error code
        values.put(Telephony.Sms.ERROR_CODE, error)

        return values
    }

    /**
     * Some providers send formfeeds in their messages. Convert those formfeeds to newlines.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsUtils.java#L979-L1049
     */
    private fun replaceFormFeeds(s: String?): String = s?.replace('\u000C', '\n') ?: ""

    /**
     * Parse the message body from message PDUs.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsUtils.java#L979-L1049
     */
    private fun buildMessageBodyFromPdus(msgs: Array<SmsMessage>): String {
        if (msgs.size == 1) {
            // There is only one part, so grab the body directly.
            return replaceFormFeeds(runCatching { msgs[0].displayMessageBody }.getOrNull())
        }
        // Build up the body from the parts.
        val body = StringBuilder()
        for (msg in msgs) {
            // getDisplayMessageBody() can NPE if mWrappedMessage inside is null: that part is left out.
            runCatching { msg.displayMessageBody }.getOrNull()?.let { body.append(it) }
        }
        return replaceFormFeeds(body.toString())
    }

    /**
     * Parse the message date.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsUtils.java#L979-L1049
     */
    private fun getMessageDate(sms: SmsMessage, now: Long): Long {
        // Use now for the timestamp to avoid confusion with clock
        // drift between the handset and the SMSC.
        // Check to make sure the system is giving us a non-bogus time.
        val buildDate = GregorianCalendar(2011, 8, 18) // 18 Sep 2011
        val nowDate = GregorianCalendar()
        nowDate.timeInMillis = now
        if (nowDate.before(buildDate)) {
            // The system clock is not set yet: the time the message was sent stands instead.
            return sms.timestampMillis
        }
        return now
    }

    private companion object {
        const val TAG = "SmsReceiver"
        const val EXTRA_ERROR_CODE = "errorCode"
        const val EXTRA_SUB_ID = "subscription"

        /** SendStatusReceiver.NO_ERROR_CODE. */
        const val NO_ERROR_CODE = -1

        /** The sender of a message that came without an address (AOSP's ParticipantData unknown sender). */
        const val UNKNOWN_SENDER = "Unknown sender"
    }
}

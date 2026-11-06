package com.yaz.sms.core.sms

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

// not ported: SendResult (ported in SmsStatusReceiver.kt, where the reports arrive)
// not ported: result (the SendResult tally, in SmsStatusReceiver.kt)
// not ported: setPartResult (SendResult.setPartResult, in SmsStatusReceiver.kt)
// not ported: hasPending (SendResult.hasPending, in SmsStatusReceiver.kt)
// not ported: getHighestFailureLevel (SendResult.highestFailureLevel, in SmsStatusReceiver.kt)
// not ported: getFailureLevel (SendResult.getFailureLevel, in SmsStatusReceiver.kt)
// not ported: setHighestFailureLevel (SendResult.setHighestFailureLevel, in SmsStatusReceiver.kt)
// not ported: setResult (SmsStatusReceiver.setResult)
// not ported: getSendErrorToastMessage (SmsStatusReceiver.getSendErrorToastMessage)
// not ported: toString (SendResult.toString, in SmsStatusReceiver.kt)
// not ported: sendMessage (its blocking wait with a timeout; here the row stays in the outbox until the reports arrive)

/**
 * Sends a text as the messaging app: written to the store first as going
 * out, so it shows at once, then handed to the network part by part; the
 * reports (SmsStatusReceiver) turn it to sent, delivered or failed.
 */
object SmsSender {

    const val ACTION_SENT = "com.yaz.sms.SENT"
    const val ACTION_DELIVERED = "com.yaz.sms.DELIVERED"
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"
    const val EXTRA_SUB = "subId"
    private const val TAG = "SmsSender"

    /**
     * Request codes of the reports: one for every part of every message and every kind of report, so
     * that two parts (or two messages) never share a PendingIntent whatever their number.
     */
    private val requestCodes = AtomicInteger(Random.nextInt())

    /** The message's place in the store, or null when it could not even be written. */
    fun send(context: Context, address: String, text: String, subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID, inThread: Long? = null): Uri? {
        // Ordinary spaces only: a reaction's hair spaces (U+200A) are its structure.
        val body = text.trim(' ', '\n', '\t', '\r')
        if (body.isEmpty() || address.isBlank()) return null
        val app = context.applicationContext
        // [inThread]: the conversation open, which may list oneself beside the person (OwnNumbers):
        // the answer stays in it, as AOSP Messaging answers in the conversation it shows.
        val thread = inThread ?: runCatching { Telephony.Threads.getOrCreateThreadId(app, address) }.getOrNull() ?: return null
        val sub = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) subId else SubscriptionManager.getDefaultSmsSubscriptionId()
        val uri = runCatching {
            app.contentResolver.insert(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                    // A delivery report is asked for, so the row waits for it: as AOSP
                    // SMSDispatcher.SmsTracker.persistSentMessageIfRequired
                    // (frameworks/opt/telephony, googlesource) writes STATUS_PENDING.
                    put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                    put(Telephony.Sms.THREAD_ID, thread)
                    put(Telephony.Sms.SUBSCRIPTION_ID, sub)
                }
            )
        }.getOrNull() ?: return null
        val smsManager = app.getSystemService(SmsManager::class.java).let {
            if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(sub) else it
        }
        if (!sendInternal(app, sub, address, smsManager.divideMessage(body), null, true, uri)) mark(app, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
        return uri
    }

    /**
     * Actually sending the message using SmsManager: one sent report for every part, a delivery report for the
     * last part only; false when Android refused it. The request codes come from a counter, never from
     * the part alone (AOSP's partId is the same for every message), so no two PendingIntents collide.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/SmsSender.java#sendInternal
     * Not ported: getSendMultipartSmsAsSeparateMessages (a carrier setting no current network needs).
     */
    private fun sendInternal(
        context: Context, subId: Int, dest: String, messages: ArrayList<String>, serviceCenter: String?,
        requireDeliveryReport: Boolean, messageUri: Uri
    ): Boolean {
        val smsManager = context.getSystemService(SmsManager::class.java).let {
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(subId) else it
        }
        val messageCount = messages.size
        val deliveryIntents = ArrayList<PendingIntent?>(messageCount)
        val sentIntents = ArrayList<PendingIntent?>(messageCount)
        for (i in 0 until messageCount) {
            // Make pending intents different for each message part
            val partId = if (messageCount <= 1) 0 else i + 1
            if (requireDeliveryReport && i == messageCount - 1) {
                deliveryIntents.add(
                    PendingIntent.getBroadcast(
                        context, requestCodes.incrementAndGet(),
                        getSendStatusIntent(context, ACTION_DELIVERED, messageUri, partId, messageCount, subId),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                deliveryIntents.add(null)
            }
            // Mutable: the network adds the carrier's errorCode to the sent report.
            sentIntents.add(
                PendingIntent.getBroadcast(
                    context, requestCodes.incrementAndGet(),
                    getSendStatusIntent(context, ACTION_SENT, messageUri, partId, messageCount, subId),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        return try {
            smsManager.sendMultipartTextMessage(dest, serviceCenter, messages, sentIntents, deliveryIntents)
            true
        } catch (e: Exception) {
            Log.e(TAG, "SmsSender: caught exception in sending ${e.javaClass.simpleName}")
            false
        }
    }

    /** Sends again a message that failed; true when it is on its way (see [executeAction]). */
    fun retry(context: Context, id: Long, address: String, body: String, subId: Int): Boolean = executeAction(context, id, address, body, subId)

    /**
     * Sends again a message that failed, as AOSP Messaging's ResendMessageAction.executeAction: only a message
     * that can be resent (failed or still going out), put back to going out with a new date and sent again as
     * the same row, in the same conversation; never deleted first. If the row cannot be put back, the new row is
     * written first and the old one removed only once the new one is on its way.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/action/ResendMessageAction.java#executeAction
     * Not ported: ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction (no queue here, the send is immediate).
     */
    private fun executeAction(context: Context, messageId: Long, address: String, body: String, subId: Int): Boolean {
        val app = context.applicationContext
        val messageUri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, messageId.toString())
        val text = body.trim(' ', '\n', '\t', '\r')
        if (text.isEmpty() || address.isBlank()) return false
        val message = runCatching {
            app.contentResolver.query(messageUri, arrayOf(Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else null
            }
        }.getOrNull()
        // Check message can be resent
        if (message != null && (message.first == Telephony.Sms.MESSAGE_TYPE_FAILED || message.first == Telephony.Sms.MESSAGE_TYPE_OUTBOX)) {
            val timestamp = System.currentTimeMillis()
            Log.i(TAG, "SmsSender: Resending message $messageId; changed timestamp to $timestamp")
            val sub = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) subId else SubscriptionManager.getDefaultSmsSubscriptionId()
            val values = ContentValues().apply {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
                put(Telephony.Sms.DATE, timestamp)
            }
            val updated = runCatching { app.contentResolver.update(messageUri, values, null, null) }.getOrDefault(0)
            if (updated > 0) {
                SmsStatusReceiver.forget(messageUri)
                val smsManager = app.getSystemService(SmsManager::class.java).let {
                    if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(sub) else it
                }
                if (sendInternal(app, sub, address, smsManager.divideMessage(text), null, true, messageUri)) return true
                mark(app, messageUri, Telephony.Sms.MESSAGE_TYPE_FAILED)
                return false
            }
            // The row could not be put back: the new one first, the old one gone only on success.
            val fresh = send(app, address, text, sub, message.second.takeIf { it > 0 }) ?: return false
            val freshType = runCatching {
                app.contentResolver.query(fresh, arrayOf(Telephony.Sms.TYPE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
            }.getOrNull()
            if (freshType == null || freshType == Telephony.Sms.MESSAGE_TYPE_FAILED) {
                runCatching { app.contentResolver.delete(fresh, null, null) }
                return false
            }
            runCatching { app.contentResolver.delete(messageUri, null, null) }
            return true
        }
        var error = "SmsSender: Cannot resend message $messageId; "
        error += if (message != null) "type = ${message.first}" else "not found in the store"
        Log.e(TAG, error)
        return false
    }

    /** Turns the row to [type]; with [unlessFailed], a row that failed stays failed. */
    fun mark(context: Context, uri: Uri, type: Int, unlessFailed: Boolean = false) {
        runCatching {
            context.contentResolver.update(
                uri, ContentValues().apply { put(Telephony.Sms.TYPE, type) },
                if (unlessFailed) "${Telephony.Sms.TYPE} != ?" else null,
                if (unlessFailed) arrayOf(Telephony.Sms.MESSAGE_TYPE_FAILED.toString()) else null
            )
        }
    }

    /**
     * The intent of the report Android sends back for one part, as AOSP Messaging's SmsSender.getSendStatusIntent:
     * the message's place in the store as the intent's data, the part and the SIM as extras; explicit, so only
     * this app gets it.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/SmsSender.java#getSendStatusIntent
     */
    private fun getSendStatusIntent(context: Context, action: String, requestUri: Uri, partId: Int, parts: Int, subId: Int): Intent {
        // Encode requestId in intent data
        val intent = Intent(action, requestUri, context, SmsStatusReceiver::class.java)
        intent.putExtra(EXTRA_PART, partId)
        intent.putExtra(EXTRA_PARTS, parts)
        intent.putExtra(EXTRA_SUB, subId)
        return intent
    }
}

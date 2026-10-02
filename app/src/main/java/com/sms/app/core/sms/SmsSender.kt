package com.sms.app.core.sms

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * Sends a text as the messaging app: written to the store first as going
 * out, so it shows at once, then handed to the network part by part; the
 * reports (SmsStatusReceiver) turn it to sent, delivered or failed.
 */
object SmsSender {

    const val ACTION_SENT = "com.sms.app.SENT"
    const val ACTION_DELIVERED = "com.sms.app.DELIVERED"
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"

    /** The message's place in the store, or null when it could not even be written. */
    fun send(context: Context, address: String, text: String, subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID): Uri? {
        val body = text.trim()
        if (body.isEmpty() || address.isBlank()) return null
        val app = context.applicationContext
        val thread = runCatching { Telephony.Threads.getOrCreateThreadId(app, address) }.getOrNull() ?: return null
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
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                    put(Telephony.Sms.THREAD_ID, thread)
                    put(Telephony.Sms.SUBSCRIPTION_ID, sub)
                }
            )
        }.getOrNull() ?: return null
        val sent = runCatching {
            val manager = app.getSystemService(SmsManager::class.java).let {
                if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(sub) else it
            }
            val parts = manager.divideMessage(body)
            val id = uri.lastPathSegment?.toIntOrNull() ?: 0
            val sentIntents = ArrayList(parts.indices.map { report(app, ACTION_SENT, uri, id * 16 + it, it, parts.size) })
            val deliveries = ArrayList(parts.indices.map { if (it == parts.lastIndex) report(app, ACTION_DELIVERED, uri, id * 16 + 15, it, parts.size) else null })
            manager.sendMultipartTextMessage(address, null, parts, sentIntents, deliveries)
        }.isSuccess
        if (!sent) mark(app, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
        return uri
    }

    /** Sends again a message that failed, as a new one, the old one removed. */
    fun retry(context: Context, id: Long, address: String, body: String, subId: Int) {
        runCatching { context.contentResolver.delete(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()), null, null) }
        send(context, address, body, subId)
    }

    fun mark(context: Context, uri: Uri, type: Int) {
        runCatching { context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null) }
    }

    /**
     * The report Android sends back for one part. Mutable because the
     * network adds its details to it; explicit, so only this app gets it.
     */
    private fun report(context: Context, action: String, uri: Uri, code: Int, part: Int, parts: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, SmsStatusReceiver::class.java).setAction(action).setData(uri)
                .putExtra(EXTRA_PART, part).putExtra(EXTRA_PARTS, parts),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

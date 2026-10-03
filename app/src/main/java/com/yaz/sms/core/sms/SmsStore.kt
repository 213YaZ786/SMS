package com.yaz.sms.core.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony

/** Text messages written to Android's store for what came or went over the rich chat. */
object SmsStore {

    /** Kept in the inbox, unread: its thread and its id. */
    fun saveReceived(context: Context, phone: String, text: String): Pair<Long, Long>? = runCatching {
        val thread = Telephony.Threads.getOrCreateThreadId(context, phone)
        val uri = context.contentResolver.insert(
            Telephony.Sms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, phone)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.THREAD_ID, thread)
            }
        ) ?: return null
        thread to ContentUris.parseId(uri)
    }.getOrNull()

    /** Kept as going out, until the chat says sent or failed: its id. */
    fun saveSending(context: Context, phone: String, text: String): Long? = runCatching {
        val thread = Telephony.Threads.getOrCreateThreadId(context, phone)
        context.contentResolver.insert(
            Telephony.Sms.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, phone)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                put(Telephony.Sms.THREAD_ID, thread)
            }
        )?.let { ContentUris.parseId(it) }
    }.getOrNull()
}

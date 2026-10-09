package com.yaz.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Debug builds only: fills a conversation with many messages, to see how
 * the app holds up with years of them.
 * adb shell am broadcast -n com.yaz.sms.debug/com.yaz.sms.DebugBulkReceiver --es from +33611112222 --ei count 3000
 * With --es body "…", one received message with that text; --es drop "5056,5057" deletes those rows.
 * With --es picture files/x.jpg (a file in the app's own folder), one received picture message.
 */
class DebugBulkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("drop")?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.forEach {
            context.contentResolver.delete(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, it), null, null)
        }
        val from = intent.getStringExtra("from") ?: return
        intent.getStringExtra("picture")?.let { name ->
            val file = java.io.File(context.dataDir, name)
            val saved = com.yaz.sms.core.mms.MmsStore.saveReceivedParts(context, from, intent.getStringExtra("body").orEmpty(), "image/jpeg", file)
            android.util.Log.d("DebugBulk", "picture message $saved")
            return
        }
        val body = intent.getStringExtra("body")
        val count = intent.getIntExtra("count", if (body != null) 1 else 1000)
        val done = goAsync()
        Thread {
            try {
                val thread = Telephony.Threads.getOrCreateThreadId(context, from)
                val now = System.currentTimeMillis()
                val rows = Array(count) { i ->
                    ContentValues().apply {
                        put(Telephony.Sms.THREAD_ID, thread)
                        put(Telephony.Sms.ADDRESS, from)
                        put(Telephony.Sms.BODY, body ?: "Message $i, to see a long conversation")
                        put(Telephony.Sms.DATE, now - (count - i) * 60_000L)
                        put(Telephony.Sms.TYPE, if (body != null || i % 2 == 0) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT)
                        put(Telephony.Sms.READ, 1)
                    }
                }
                // The SMS store takes them one by one (no bulk insert).
                val n = rows.count { context.contentResolver.insert(Telephony.Sms.CONTENT_URI, it) != null }
                android.util.Log.d("DebugBulk", "inserted $n into $thread")
            } catch (e: Exception) {
                android.util.Log.d("DebugBulk", "failed $e")
            } finally {
                done.finish()
            }
        }.start()
    }
}

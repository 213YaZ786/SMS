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
 * With --es picture files/x.jpg (a file in the app's own folder), one received picture message;
 * --es video files/x.mp4 the same with a film.
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
        intent.getStringExtra("video")?.let { name ->
            val file = java.io.File(context.dataDir, name)
            val saved = com.yaz.sms.core.mms.MmsStore.saveReceivedParts(context, from, intent.getStringExtra("body").orEmpty(), "video/mp4", file)
            android.util.Log.d("DebugBulk", "video message $saved")
            return
        }
        // --es file files/x.gif --es mime image/gif: any part, with the type its sender wrote.
        intent.getStringExtra("file")?.let { name ->
            val saved = com.yaz.sms.core.mms.MmsStore.saveReceivedParts(context, from, intent.getStringExtra("body").orEmpty(), intent.getStringExtra("mime"), java.io.File(context.dataDir, name))
            android.util.Log.d("DebugBulk", "file message $saved")
            return
        }
        // --ez waiting true: a picture message announced, not downloaded (the tap-to-download tile).
        if (intent.getBooleanExtra("waiting", false)) {
            val ind = com.yaz.sms.core.mms.pdu.NotificationInd().apply {
                contentLocation = "http://mmsc.example/xyz${System.nanoTime()}".toByteArray()
                transactionId = "T${System.nanoTime()}".toByteArray()
                setFrom(com.yaz.sms.core.mms.pdu.EncodedStringValue(from))
            }
            android.util.Log.d("DebugBulk", "waiting ${com.yaz.sms.core.mms.MmsStore.saveNotification(context, ind, -1)}")
            return
        }
        // --ez failmms true: one of the user's own picture messages left as failed.
        if (intent.getBooleanExtra("failmms", false)) {
            val uri = com.yaz.sms.core.mms.MmsStore.saveSendingParts(context, listOf(from), "Echec MMS test", null, null)
            uri?.let {
                com.yaz.sms.core.mms.MmsStore.setBox(context, android.content.ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, it), Telephony.Mms.MESSAGE_BOX_FAILED)
            }
            android.util.Log.d("DebugBulk", "failed mms $uri")
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

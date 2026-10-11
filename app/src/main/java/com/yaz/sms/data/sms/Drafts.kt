package com.yaz.sms.data.sms

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony

/**
 * The text left in a conversation's composer, kept as Android's own
 * draft rows (Telephony.Sms, MESSAGE_TYPE_DRAFT), one per conversation,
 * the way AOSP Mms keeps them (WorkingMessage.asyncSaveDraft /
 * updateDraftSmsMessage): another SMS app taking over finds them too.
 * The manners are AOSP Messaging's (DraftMessageData): read when the
 * conversation opens, replaced on leaving it, cleared by sending.
 */
object Drafts {

    /** The draft of [threadId], or null. */
    fun of(context: Context, threadId: Long): String? = runCatching {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.BODY),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_DRAFT}",
            arrayOf(threadId.toString()), "${Telephony.Sms.DATE} DESC"
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }.getOrNull()

    /** Every conversation's draft, for the list. */
    fun all(context: Context): Map<Long, String> = runCatching {
        val out = HashMap<Long, String>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.BODY),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_DRAFT}", null, "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(1).orEmpty()
                if (body.isNotBlank()) out[c.getLong(0)] = body
            }
        }
        out
    }.getOrDefault(emptyMap())

    /** Keeps [text] as the one draft of [threadId]; blank removes it. */
    fun keep(context: Context, threadId: Long, text: String) {
        runCatching {
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_DRAFT}",
                arrayOf(threadId.toString())
            )
            if (text.isNotBlank()) {
                context.contentResolver.insert(
                    Telephony.Sms.CONTENT_URI,
                    ContentValues().apply {
                        put(Telephony.Sms.THREAD_ID, threadId)
                        put(Telephony.Sms.BODY, text)
                        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_DRAFT)
                        put(Telephony.Sms.DATE, System.currentTimeMillis())
                        put(Telephony.Sms.READ, 1)
                    }
                )
            }
        }
    }
}

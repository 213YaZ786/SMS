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

    /** Every conversation's draft for the list: its text and when it was left. */
    fun all(context: Context): Map<Long, Pair<String, Long>> = runCatching {
        val out = HashMap<Long, Pair<String, Long>>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_DRAFT}", null, "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(1).orEmpty()
                if (body.isNotBlank()) out[c.getLong(0)] = body to c.getLong(2)
            }
        }
        out
    }.getOrDefault(emptyMap())

    /**
     * Keeps [text] as the one draft of the conversation with [people]; blank removes it.
     * In AOSP Mms's order (WorkingMessage.asyncUpdateDraftSmsMessage): the thread asked
     * again first (Conversation.ensureThreadId), since Android drops a thread left with no
     * message; the new draft written, only then the older ones removed, so the thread is
     * never empty in between.
     */
    fun keep(context: Context, people: Set<String>, text: String) {
        runCatching {
            if (people.isEmpty()) return
            val thread = Telephony.Threads.getOrCreateThreadId(context, people)
            val draftOf = "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_DRAFT}"
            if (text.isBlank()) {
                context.contentResolver.delete(Telephony.Sms.CONTENT_URI, draftOf, arrayOf(thread.toString()))
                return
            }
            val kept = context.contentResolver.insert(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Sms.THREAD_ID, thread)
                    put(Telephony.Sms.BODY, text)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_DRAFT)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                }
            ) ?: return
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI, "$draftOf AND ${Telephony.Sms._ID} != ?",
                arrayOf(thread.toString(), android.content.ContentUris.parseId(kept).toString())
            )
        }
    }
}

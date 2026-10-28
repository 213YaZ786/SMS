package com.yaz.sms.data.sms

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.yaz.sms.core.mms.MmsDelivery
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.core.mms.MmsStore
import com.yaz.sms.core.mms.pdu.PduHeaders
import com.yaz.sms.core.mms.mediaWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a message stands. */
enum class Box { RECEIVED, SENT, SENDING, FAILED }

data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    /** When it was received or written, epoch milliseconds. */
    val date: Long,
    val box: Box,
    val read: Boolean,
    /** The network said it reached the phone it was sent to. */
    val delivered: Boolean,
    val subId: Int,
    /** A picture message: its pictures, videos and sounds; the text is in [body]. */
    val mms: Boolean = false,
    val parts: List<MmsPart> = emptyList(),
    /** Announced by the network, not downloaded yet: a tap fetches it. */
    val waiting: Boolean = false,
    /** Went over the rich chat. */
    val rich: Boolean = false,
    /** The other side read it (rich chat). */
    val seen: Boolean = false,
    val reactions: List<String> = emptyList(),
    /** A picture message's subject, cleansed as AOSP Messaging does (MmsSubject); null when there is none to show. */
    val subject: String? = null
) {
    /** Unique among SMS and picture messages, whose numbers overlap. */
    val uid: String get() = (if (mms) "mms/" else "sms/") + id
}

/** One conversation: who with, its last message, how many are unread. */
data class Conversation(
    val threadId: Long,
    /** The first person of the conversation; a group has [addresses]. */
    val address: String,
    val snippet: String,
    val date: Long,
    val unread: Int,
    /** The last message is one the user sent (or tried to). */
    val fromMe: Boolean,
    val failed: Boolean,
    val addresses: List<String> = listOf(address),
    /** A text left in the composer, shown over the snippet as Bugle shows its drafts. */
    val draft: String? = null,
    /** How many messages the user sent in it: three make a sender known to the spam classifier (SpamJudge). */
    val sent: Int = 0,
    /** The newest message's subject when it is a picture message with one (AOSP Messaging lists it over the snippet). */
    val subject: String? = null,
    /**
     * The newest picture message's parts (pictures, films, sounds, cards, files) and its words apart from them,
     * already read for the snippet by MmsStore.parts; kept for the list's last-message bubble. Source: this
     * file's own readConversations (MmsStore.parts for the newest message of each conversation).
     */
    val parts: List<MmsPart> = emptyList(),
    val caption: String = ""
) {
    val group: Boolean get() = addresses.size > 1
}

/**
 * The messages on the phone, read from Android's own store, which this app
 * writes as the messaging app. The conversations follow every change.
 */
class Messages(private val context: Context, private val scope: CoroutineScope) {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Bumped on every change of the store, for open conversations to read again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private var pending: Job? = null
    private var watching = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // A burst of changes (a long message, a whole thread deleted) reads once.
            pending?.cancel()
            pending = scope.launch {
                delay(150)
                _changes.value++
                load()
            }
        }
    }

    fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        if (!canRead()) return
        watch()
        scope.launch { load() }
    }

    /** Follows the store, for a conversation opened alone (a bubble) to see what comes and goes. */
    fun watch() {
        if (watching || !canRead()) return
        runCatching { context.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer) }
        watching = true
    }

    private suspend fun load() {
        _conversations.value = withContext(Dispatchers.IO) { readConversations() }
        _loaded.value = true
    }

    /**
     * Each conversation's newest message as Android picks it (one row per
     * conversation), and the unread ones counted: never every message of the
     * phone, which took seconds and ran again at each change of the store.
     */
    private fun readConversations(): List<Conversation> = runCatching {
        val people = recipients()
        val drafts = Drafts.all(context)
        // Announcements still waiting for their download (kept NotificationInd rows).
        val waiting = HashSet<Long>()
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.MESSAGE_TYPE} = 130", null, null
        )?.use { c -> while (c.moveToNext()) waiting += c.getLong(0) }
        val unread = HashMap<Long, Int>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.READ} = 0", null, null
        )?.use { c -> while (c.moveToNext()) c.getLong(0).let { unread[it] = (unread[it] ?: 0) + 1 } }
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID),
            "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX} AND ${Telephony.Mms.READ} = 0", null, null
        )?.use { c -> while (c.moveToNext()) c.getLong(0).let { unread[it] = (unread[it] ?: 0) + 1 } }
        // The messages the user sent, per conversation, in one read (SpamJudge.sentCounts).
        val sentCounts = com.yaz.sms.core.sms.SpamJudge.sentCounts(context)
        // The newest of each conversation, an SMS (type) or a picture message (msg_box).
        class Last(val thread: Long, val id: Long, val date: Long, val body: String, val address: String, val sms: Boolean, val box: Int)
        val last = ArrayList<Last>()
        context.contentResolver.query(
            Uri.parse("content://mms-sms/conversations"),
            arrayOf("tid", "_id", "normalized_date", Telephony.Sms.BODY, Telephony.Sms.ADDRESS, Telephony.Sms.TYPE, Telephony.Mms.MESSAGE_BOX),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val sms = !c.isNull(5)
                last += Last(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3).orEmpty(), c.getString(4).orEmpty(), sms, if (sms) c.getInt(5) else c.getInt(6))
            }
        }
        val parts = MmsStore.parts(context, last.filterNot { it.sms }.map { it.id })
        val subjects = MmsStore.subjects(context, last.filterNot { it.sms }.map { it.id })
        last.map { m ->
            val addresses = people[m.thread] ?: listOf(m.address)
            if (m.sms) Conversation(
                threadId = m.thread,
                address = addresses.firstOrNull() ?: m.address,
                snippet = m.body,
                date = m.date,
                unread = unread[m.thread] ?: 0,
                fromMe = m.box != Telephony.Sms.MESSAGE_TYPE_INBOX,
                failed = m.box == Telephony.Sms.MESSAGE_TYPE_FAILED,
                addresses = addresses,
                draft = drafts[m.thread]?.first,
                sent = sentCounts[m.thread] ?: 0
            ) else {
                val (text, media) = parts[m.id] ?: ("" to emptyList())
                Conversation(
                    threadId = m.thread,
                    address = addresses.firstOrNull().orEmpty(),
                    // A message of a subject alone has the subject over an empty snippet, as in AOSP's list.
                    snippet = if (m.id in waiting) "Picture message" else text.ifBlank { if (media.isEmpty() && subjects[m.id] != null) "" else mediaWord(media.firstOrNull()?.contentType) },
                    date = m.date,
                    unread = unread[m.thread] ?: 0,
                    fromMe = m.box != Telephony.Mms.MESSAGE_BOX_INBOX,
                    failed = m.box == Telephony.Mms.MESSAGE_BOX_FAILED,
                    addresses = addresses.ifEmpty { listOf("") },
                    draft = drafts[m.thread]?.first,
                    sent = sentCounts[m.thread] ?: 0,
                    subject = if (m.id in waiting) null else subjects[m.id],
                    parts = if (m.id in waiting) emptyList() else media,
                    caption = if (m.id in waiting) "" else text
                )
            }
        }.let { listed ->
            // A conversation holding only a draft has no message for Android's conversations
            // view to return; AOSP Mms lists it from the threads table (Conversation.java,
            // sAllThreadsUri "simple=true"), marked as a draft. Its people come from there too.
            val shown = listed.map { it.threadId }.toSet()
            listed + drafts.filterKeys { it !in shown && !people[it].isNullOrEmpty() }.map { (thread, d) ->
                val addresses = people[thread].orEmpty()
                Conversation(
                    threadId = thread, address = addresses.first(), snippet = d.first, date = d.second,
                    unread = 0, fromMe = true, failed = false, addresses = addresses, draft = d.first
                )
            }
        }.sortedByDescending { it.date }
    }.getOrDefault(emptyList())

    /** Who is in each conversation, from Android's threads: one number, or a group's. */
    private fun recipients(): Map<Long, List<String>> = runCatching {
        val canonical = HashMap<String, String>()
        context.contentResolver.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) canonical[c.getString(0)] = c.getString(1).orEmpty()
        }
        val out = HashMap<Long, List<String>>()
        // A thread Android made of the sender and oneself reads as the sender's (AOSP Messaging
        // PduPersister leaves one's own number out of a received message's people).
        val own = com.yaz.sms.core.mms.OwnNumbers.of(context)
        context.contentResolver.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id", "recipient_ids"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val ids = c.getString(1).orEmpty().split(' ').filter { it.isNotBlank() }
                out[c.getLong(0)] = com.yaz.sms.core.mms.OwnNumbers.withoutOwn(ids.mapNotNull { canonical[it] }.filter { it.isNotBlank() }, own)
            }
        }
        out
    }.getOrDefault(emptyMap())

    /** The people of one conversation. */
    suspend fun addressesOf(threadId: Long): List<String> = withContext(Dispatchers.IO) { recipients()[threadId].orEmpty() }

    /** The messages of one conversation, oldest first. */
    /**
     * The newest [limit] messages of [threadId], oldest first: a
     * conversation of years opens as fast as a new one, and more is read
     * as the user goes back in it.
     */
    suspend fun thread(threadId: Long, limit: Int = PAGE): List<Message> = withContext(Dispatchers.IO) {
        runCatching {
            rowsWhere(
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}", arrayOf(threadId.toString()),
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}", arrayOf(threadId.toString()),
                limit
            )
        }.getOrDefault(emptyList())
    }

    /**
     * The messages named by [uids] ("sms/7", "mms/45"), oldest first, each with its own thread
     * id; null when the store cannot be read, so that nothing is taken for deleted. Rows asked
     * by _id in batches, under SQLite's limit of variables (developer.android.com, Telephony.Sms
     * and Telephony.Mms; the starred messages view of Zulip, zulip-flutter lib/model/narrow.dart
     * StarredMessagesNarrow, which lists exactly the messages carrying the flag).
     */
    suspend fun byUid(uids: Collection<String>): List<Message>? = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext null
        runCatching {
            val sms = uids.filter { it.startsWith("sms/") }.mapNotNull { it.substringAfter('/').toLongOrNull() }
            val mms = uids.filter { it.startsWith("mms/") }.mapNotNull { it.substringAfter('/').toLongOrNull() }
            val out = ArrayList<Message>()
            val batches = maxOf(sms.chunked(BATCH).size, mms.chunked(BATCH).size)
            for (i in 0 until batches) {
                val s = sms.chunked(BATCH).getOrElse(i) { emptyList() }
                val m = mms.chunked(BATCH).getOrElse(i) { emptyList() }
                out += rowsWhere(
                    if (s.isEmpty()) "0" else "${Telephony.Sms._ID} IN (${s.joinToString(",") { "?" }})", s.map { it.toString() }.toTypedArray(),
                    if (m.isEmpty()) "0" else "${Telephony.Mms._ID} IN (${m.joinToString(",") { "?" }})", m.map { it.toString() }.toTypedArray(),
                    Int.MAX_VALUE
                )
            }
            out.sortedBy { it.date }
        }.getOrNull()
    }

    /** The SMS and the picture messages matching the two selections, oldest first, the newest [limit] of them. */
    private fun rowsWhere(smsWhere: String, smsArgs: Array<String>, mmsWhere: String, mmsArgs: Array<String>, limit: Int): List<Message> {
        run {
            val out = ArrayList<Message>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE,
                    Telephony.Sms.READ, Telephony.Sms.STATUS, Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.THREAD_ID
                ),
                smsWhere,
                smsArgs, "${Telephony.Sms.DATE} DESC" + if (limit < Int.MAX_VALUE) " LIMIT $limit" else ""
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Message(
                        id = c.getLong(0),
                        threadId = c.getLong(8),
                        address = c.getString(1).orEmpty(),
                        body = c.getString(2).orEmpty(),
                        date = c.getLong(3),
                        box = boxOf(c.getInt(4)),
                        read = c.getInt(5) != 0,
                        delivered = c.getInt(6) == Telephony.Sms.STATUS_COMPLETE,
                        subId = c.getInt(7)
                    )
                }
            }
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ, Telephony.Mms.SUBSCRIPTION_ID, Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.THREAD_ID, Telephony.Mms.SUBJECT, Telephony.Mms.MESSAGE_ID, Telephony.Mms.DELIVERY_REPORT),
                // The delivery reports kept beside the messages (m_type 134, 136) are no message: the provider's own
                // conversation views take only types 128, 130 and 132 (MmsSmsProvider, "m_type=132 OR m_type=130 OR m_type=128").
                "($mmsWhere) AND ${Telephony.Mms.MESSAGE_TYPE} NOT IN (${PduHeaders.MESSAGE_TYPE_DELIVERY_IND}, ${PduHeaders.MESSAGE_TYPE_READ_ORIG_IND})",
                mmsArgs, "${Telephony.Mms.DATE} DESC" + if (limit < Int.MAX_VALUE) " LIMIT $limit" else ""
            )?.use { c ->
                // The rows first, then all their parts in one query.
                val rows = ArrayList<LongArray>()
                val subjects = HashMap<Long, String?>()
                // Sent messages that asked for a delivery report: row to Message-ID (MmsDelivery).
                val reportable = HashMap<Long, String>()
                while (c.moveToNext()) {
                    rows += longArrayOf(c.getLong(0), c.getLong(1), c.getInt(2).toLong(), c.getInt(3).toLong(), c.getInt(4).toLong(), c.getInt(5).toLong(), c.getLong(6))
                    subjects[c.getLong(0)] = c.getString(7)
                    if (c.getInt(2) == Telephony.Mms.MESSAGE_BOX_SENT && c.getInt(9) == PduHeaders.VALUE_YES) {
                        c.getString(8)?.takeIf { it.isNotBlank() }?.let { reportable[c.getLong(0)] = it }
                    }
                }
                val delivered = MmsDelivery.delivered(context, reportable)
                val parts = MmsStore.parts(context, rows.map { it[0] })
                rows.forEach { r ->
                    val (id, date, box, read, sub) = r
                    val (text, media) = parts[id] ?: ("" to emptyList())
                    // An announcement not downloaded yet (a kept NotificationInd) waits for a tap.
                    val waiting = r[5].toInt() == 130
                    val sender = if (box.toInt() == Telephony.Mms.MESSAGE_BOX_INBOX) senderOf(id, date) else ""
                    // A received message's parts remember where they came from, so that Save can keep who sent them.
                    val origin = if (sender.isNotBlank()) com.yaz.sms.core.mms.SavedOrigin(r[6], "mms/$id", sender) else null
                    out += Message(
                        id = id,
                        threadId = r[6],
                        address = sender,
                        body = text,
                        date = date * 1000,
                        box = when (box.toInt()) {
                            Telephony.Mms.MESSAGE_BOX_INBOX -> Box.RECEIVED
                            Telephony.Mms.MESSAGE_BOX_SENT -> Box.SENT
                            Telephony.Mms.MESSAGE_BOX_FAILED -> Box.FAILED
                            else -> Box.SENDING
                        },
                        read = read != 0L,
                        delivered = id in delivered,
                        subId = sub.toInt(),
                        mms = true,
                        parts = if (origin == null) media else media.map { it.copy(origin = origin) },
                        waiting = waiting,
                        subject = if (waiting) null else com.yaz.sms.core.mms.MmsSubject.cleanse(subjects[id])
                    )
                }
            }
            return out.sortedBy { it.date }.takeLast(limit)
        }
    }

    // Who sent a picture message never changes: read once per message (by its date too, as Android reuses a deleted row's number).
    private val senders = android.util.LruCache<String, String>(4000)

    private fun senderOf(id: Long, date: Long): String =
        senders.get("$id/$date") ?: MmsStore.sender(context, id).orEmpty().also { if (it.isNotEmpty()) senders.put("$id/$date", it) }

    /** Everything in [threadId] read and seen, so its dot and notification go. */
    fun markRead(threadId: Long) {
        scope.launch(Dispatchers.IO) { markRead(context, threadId) }
    }

    /**
     * Deletes the messages of [threadId] sent or received up to [cutoffMillis], the newer ones stay:
     * AOSP Messaging's DeleteConversationAction (cutoffTimestamp, "to prevent us deleting new
     * messages") and MmsUtils.deleteThread(threadId, cutOffTimestampInMillis), which deletes
     * "date <= cutoff" in the thread
     * (https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/datamodel/action/DeleteConversationAction.java
     * and .../sms/MmsUtils.java). AOSP hands one selection to the threads URI, and a picture
     * message keeps its date in seconds (Telephony.Mms.DATE), so SMS and MMS are deleted each
     * with the cutoff in its own unit. True when both deletions went through, false when the store
     * refused or this app is not the default SMS app (Android then ignores a write silently).
     */
    suspend fun deleteUpTo(threadId: Long, cutoffMillis: Long): Boolean = withContext(Dispatchers.IO) {
        if (Telephony.Sms.getDefaultSmsPackage(context) != context.packageName) return@withContext false
        runCatching {
            val thread = threadId.toString()
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.DATE} <= ?",
                arrayOf(thread, cutoffMillis.toString())
            )
            context.contentResolver.delete(
                Telephony.Mms.CONTENT_URI, "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.DATE} <= ?",
                arrayOf(thread, (cutoffMillis / 1000).toString())
            )
            true
        }.getOrDefault(false)
    }

    fun deleteMessage(message: Message) = run {
        val base = if (message.mms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
        scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.delete(Uri.withAppendedPath(base, message.id.toString()), null, null) }
        }
    }

    companion object {
        /** How many messages a conversation reads at a time. */
        const val PAGE = 200

        /** Ids asked at once by [byUid], under SQLite's limit of 999 variables. */
        private const val BATCH = 400

        fun boxOf(type: Int): Box = when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> Box.RECEIVED
            Telephony.Sms.MESSAGE_TYPE_SENT -> Box.SENT
            Telephony.Sms.MESSAGE_TYPE_FAILED -> Box.FAILED
            else -> Box.SENDING
        }

        fun markRead(context: Context, threadId: Long) {
            runCatching {
                context.contentResolver.update(
                    Telephony.Mms.CONTENT_URI,
                    ContentValues().apply {
                        put(Telephony.Mms.READ, 1)
                        put(Telephony.Mms.SEEN, 1)
                    },
                    "${Telephony.Mms.THREAD_ID} = ? AND (${Telephony.Mms.READ} = 0 OR ${Telephony.Mms.SEEN} = 0)",
                    arrayOf(threadId.toString())
                )
            }
            runCatching {
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    ContentValues().apply {
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.SEEN, 1)
                    },
                    "${Telephony.Sms.THREAD_ID} = ? AND (${Telephony.Sms.READ} = 0 OR ${Telephony.Sms.SEEN} = 0)",
                    arrayOf(threadId.toString())
                )
            }
        }
    }
}

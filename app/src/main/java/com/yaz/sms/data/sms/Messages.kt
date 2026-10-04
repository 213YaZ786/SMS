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
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.core.mms.MmsStore
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
    /** Went over the rich chat. */
    val rich: Boolean = false,
    /** The other side read it (rich chat). */
    val seen: Boolean = false,
    val reactions: List<String> = emptyList()
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
    val addresses: List<String> = listOf(address)
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
        if (!watching) {
            runCatching { context.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer) }
            watching = true
        }
        scope.launch { load() }
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
        val unread = HashMap<Long, Int>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.READ} = 0", null, null
        )?.use { c -> while (c.moveToNext()) c.getLong(0).let { unread[it] = (unread[it] ?: 0) + 1 } }
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID),
            "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX} AND ${Telephony.Mms.READ} = 0", null, null
        )?.use { c -> while (c.moveToNext()) c.getLong(0).let { unread[it] = (unread[it] ?: 0) + 1 } }
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
                addresses = addresses
            ) else {
                val (text, media) = parts[m.id] ?: ("" to emptyList())
                Conversation(
                    threadId = m.thread,
                    address = addresses.firstOrNull().orEmpty(),
                    snippet = text.ifBlank { mediaWord(media.firstOrNull()?.contentType) },
                    date = m.date,
                    unread = unread[m.thread] ?: 0,
                    fromMe = m.box != Telephony.Mms.MESSAGE_BOX_INBOX,
                    failed = m.box == Telephony.Mms.MESSAGE_BOX_FAILED,
                    addresses = addresses.ifEmpty { listOf("") }
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
        context.contentResolver.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id", "recipient_ids"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val ids = c.getString(1).orEmpty().split(' ').filter { it.isNotBlank() }
                out[c.getLong(0)] = ids.mapNotNull { canonical[it] }.filter { it.isNotBlank() }
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
            val out = ArrayList<Message>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE,
                    Telephony.Sms.READ, Telephony.Sms.STATUS, Telephony.Sms.SUBSCRIPTION_ID
                ),
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Message(
                        id = c.getLong(0),
                        threadId = threadId,
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
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ, Telephony.Mms.SUBSCRIPTION_ID),
                "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Mms.DATE} DESC LIMIT $limit"
            )?.use { c ->
                // The rows first, then all their parts in one query.
                val rows = ArrayList<LongArray>()
                while (c.moveToNext()) rows += longArrayOf(c.getLong(0), c.getLong(1), c.getInt(2).toLong(), c.getInt(3).toLong(), c.getInt(4).toLong())
                val parts = MmsStore.parts(context, rows.map { it[0] })
                rows.forEach { (id, date, box, read, sub) ->
                    val (text, media) = parts[id] ?: ("" to emptyList())
                    out += Message(
                        id = id,
                        threadId = threadId,
                        address = if (box.toInt() == Telephony.Mms.MESSAGE_BOX_INBOX) senderOf(id, date) else "",
                        body = text,
                        date = date * 1000,
                        box = when (box.toInt()) {
                            Telephony.Mms.MESSAGE_BOX_INBOX -> Box.RECEIVED
                            Telephony.Mms.MESSAGE_BOX_SENT -> Box.SENT
                            Telephony.Mms.MESSAGE_BOX_FAILED -> Box.FAILED
                            else -> Box.SENDING
                        },
                        read = read != 0L,
                        delivered = false,
                        subId = sub.toInt(),
                        mms = true,
                        parts = media
                    )
                }
            }
            out.sortedBy { it.date }.takeLast(limit)
        }.getOrDefault(emptyList())
    }

    // Who sent a picture message never changes: read once per message (by its date too, as Android reuses a deleted row's number).
    private val senders = android.util.LruCache<String, String>(4000)

    private fun senderOf(id: Long, date: Long): String =
        senders.get("$id/$date") ?: MmsStore.sender(context, id).orEmpty().also { if (it.isNotEmpty()) senders.put("$id/$date", it) }

    /** Everything in [threadId] read and seen, so its dot and notification go. */
    fun markRead(threadId: Long) {
        scope.launch(Dispatchers.IO) { markRead(context, threadId) }
    }

    fun delete(threadId: Long) = run {
        scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.delete(Uri.withAppendedPath(Telephony.Threads.CONTENT_URI, threadId.toString()), null, null) }
        }
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

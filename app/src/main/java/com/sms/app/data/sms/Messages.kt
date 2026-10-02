package com.sms.app.data.sms

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
import com.sms.app.core.mms.MmsPart
import com.sms.app.core.mms.MmsStore
import com.sms.app.core.mms.mediaWord
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
)

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

    private fun readConversations(): List<Conversation> = runCatching {
        val people = recipients()
        val byThread = LinkedHashMap<Long, Conversation>()
        val unread = HashMap<Long, Int>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
            null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val thread = c.getLong(0)
                val type = c.getInt(4)
                if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(5) == 0) unread[thread] = (unread[thread] ?: 0) + 1
                if (thread in byThread) continue
                val address = c.getString(1).orEmpty()
                byThread[thread] = Conversation(
                    threadId = thread,
                    address = people[thread]?.firstOrNull() ?: address,
                    snippet = c.getString(2).orEmpty(),
                    date = c.getLong(3),
                    unread = 0,
                    fromMe = type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                    failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED,
                    addresses = people[thread] ?: listOf(address)
                )
            }
        }
        // Picture messages: the newest of a thread wins over its SMS when later.
        val seenMms = HashSet<Long>()
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ),
            null, null, "${Telephony.Mms.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val thread = c.getLong(1)
                val box = c.getInt(3)
                if (box == Telephony.Mms.MESSAGE_BOX_INBOX && c.getInt(4) == 0) unread[thread] = (unread[thread] ?: 0) + 1
                if (!seenMms.add(thread)) continue
                val date = c.getLong(2) * 1000
                val known = byThread[thread]
                if (known != null && known.date >= date) continue
                val (text, media) = MmsStore.parts(context, c.getLong(0))
                val addresses = people[thread] ?: known?.addresses ?: emptyList()
                byThread[thread] = Conversation(
                    threadId = thread,
                    address = addresses.firstOrNull().orEmpty(),
                    snippet = text.ifBlank { mediaWord(media.firstOrNull()?.contentType) },
                    date = date,
                    unread = 0,
                    fromMe = box != Telephony.Mms.MESSAGE_BOX_INBOX,
                    failed = box == Telephony.Mms.MESSAGE_BOX_FAILED,
                    addresses = addresses.ifEmpty { listOf("") }
                )
            }
        }
        byThread.values.map { it.copy(unread = unread[it.threadId] ?: 0) }.sortedByDescending { it.date }
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
    suspend fun thread(threadId: Long): List<Message> = withContext(Dispatchers.IO) {
        runCatching {
            val out = ArrayList<Message>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE,
                    Telephony.Sms.READ, Telephony.Sms.STATUS, Telephony.Sms.SUBSCRIPTION_ID
                ),
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} ASC"
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
                "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val box = c.getInt(2)
                    val (text, media) = MmsStore.parts(context, id)
                    out += Message(
                        id = id,
                        threadId = threadId,
                        address = if (box == Telephony.Mms.MESSAGE_BOX_INBOX) MmsStore.sender(context, id).orEmpty() else "",
                        body = text,
                        date = c.getLong(1) * 1000,
                        box = when (box) {
                            Telephony.Mms.MESSAGE_BOX_INBOX -> Box.RECEIVED
                            Telephony.Mms.MESSAGE_BOX_SENT -> Box.SENT
                            Telephony.Mms.MESSAGE_BOX_FAILED -> Box.FAILED
                            else -> Box.SENDING
                        },
                        read = c.getInt(3) != 0,
                        delivered = false,
                        subId = c.getInt(4),
                        mms = true,
                        parts = media
                    )
                }
            }
            out.sortedBy { it.date }
        }.getOrDefault(emptyList())
    }

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

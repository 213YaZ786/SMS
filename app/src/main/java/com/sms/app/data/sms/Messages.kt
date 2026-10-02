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
    val subId: Int
)

/** One conversation: who with, its last message, how many are unread. */
data class Conversation(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val unread: Int,
    /** The last message is one the user sent (or tried to). */
    val fromMe: Boolean,
    val failed: Boolean
)

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
                byThread[thread] = Conversation(
                    threadId = thread,
                    address = c.getString(1).orEmpty(),
                    snippet = c.getString(2).orEmpty(),
                    date = c.getLong(3),
                    unread = 0,
                    fromMe = type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                    failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED
                )
            }
        }
        byThread.values.map { it.copy(unread = unread[it.threadId] ?: 0) }
    }.getOrDefault(emptyList())

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
            out
        }.getOrDefault(emptyList())
    }

    /** Everything in [threadId] read and seen, so its dot and notification go. */
    fun markRead(threadId: Long) {
        scope.launch(Dispatchers.IO) { markRead(context, threadId) }
    }

    fun delete(threadId: Long) {
        scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.delete(Uri.withAppendedPath(Telephony.Threads.CONTENT_URI, threadId.toString()), null, null) }
        }
    }

    fun deleteMessage(id: Long) {
        scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.delete(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()), null, null) }
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

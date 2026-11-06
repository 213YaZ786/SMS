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
    val caption: String = "",
    /**
     * The newest message's row (its table: [lastMms]) and where it stands, for the list's delivery mark when it is
     * the user's own (Signal's DeliveryStatusView, AOSP Messaging's failed icon). 0 for a conversation of a draft alone.
     */
    val lastId: Long = 0L,
    val lastMms: Boolean = false,
    /** The user's newest message is still leaving (outbox or queued). */
    val sending: Boolean = false,
    /** The network said it reached the phone it was sent to. */
    val delivered: Boolean = false
) {
    val group: Boolean get() = addresses.size > 1
}

/**
 * A message found by the text search: its conversation, its uid ("sms/7", "mms/45") and the words it holds.
 * One per conversation, as the provider's search groups its answer.
 */
data class Found(val threadId: Long, val uid: String, val body: String)

/**
 * The messages on the phone, read from Android's own store, which this app
 * writes as the messaging app. The conversations follow every change.
 */
class Messages(private val context: Context, private val scope: CoroutineScope) {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _failed = MutableStateFlow(false)

    /** The last read of the store failed: [conversations] is the previous list, not an empty one. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

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

    // not ported: roomListContentState (a Compose presenter: the list screen picks Empty, Skeleton or Rooms from loaded, failed and conversations)
    /**
     * Element X's RoomListPresenter.roomListContentState: the list is empty only once loaded with zero
     * rooms, a skeleton while it is not loaded. A read that fails keeps the list shown and says so in
     * [failed], never replaces it by an empty one.
     * https://raw.githubusercontent.com/element-hq/element-x-android/develop/features/home/impl/src/main/kotlin/io/element/android/features/home/impl/roomlist/RoomListPresenter.kt#L251-L295
     */
    private suspend fun load() {
        val roomSummaries = withContext(Dispatchers.IO) { readConversations() }
        if (roomSummaries == null) {
            _failed.value = true
            return
        }
        _conversations.value = roomSummaries
        _failed.value = false
        _loaded.value = true
    }

    /**
     * Each conversation's newest message as Android picks it (one row per
     * conversation), and the unread ones counted: never every message of the
     * phone, which took seconds and ran again at each change of the store.
     */
    private fun readConversations(): List<Conversation>? = runCatching {
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
        // Read only when spam protection is on: nothing else uses them.
        val spamProtection = runCatching { org.koin.core.context.GlobalContext.get().get<com.yaz.sms.data.settings.SettingsStore>().current.spamProtection }.getOrDefault(true)
        val sentCounts = if (spamProtection) com.yaz.sms.core.sms.SpamJudge.sentCounts(context) else emptyMap()
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
        } ?: throw IllegalStateException("conversations view gave no cursor")
        val parts = MmsStore.parts(context, last.filterNot { it.sms }.map { it.id })
        val subjects = MmsStore.subjects(context, last.filterNot { it.sms }.map { it.id })
        // Whether the newest message the user sent reached the phone it went to: one read for all the conversations.
        val deliveredSms = deliveredSms(last.filter { it.sms && it.box == Telephony.Sms.MESSAGE_TYPE_SENT }.map { it.id })
        val deliveredMms = deliveredMms(last.filter { !it.sms && it.box == Telephony.Mms.MESSAGE_BOX_SENT }.map { it.id })
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
                sent = sentCounts[m.thread] ?: 0,
                lastId = m.id,
                sending = m.box == Telephony.Sms.MESSAGE_TYPE_OUTBOX || m.box == Telephony.Sms.MESSAGE_TYPE_QUEUED,
                delivered = m.id in deliveredSms
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
                    caption = if (m.id in waiting) "" else text,
                    lastId = m.id,
                    lastMms = true,
                    // As rowsWhere's boxes: anything but inbox, sent and failed is still leaving.
                    sending = m.box != Telephony.Mms.MESSAGE_BOX_INBOX && m.box != Telephony.Mms.MESSAGE_BOX_SENT &&
                        m.box != Telephony.Mms.MESSAGE_BOX_FAILED && m.box != Telephony.Mms.MESSAGE_BOX_DRAFTS,
                    delivered = m.id in deliveredMms
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
    }.onFailure { android.util.Log.w("Messages", "conversations not read: " + it.javaClass.simpleName) }.getOrNull()

    /**
     * Of the sent SMS [ids], those the network reported delivered (status complete): the reading of rowsWhere
     * (Telephony.Sms.STATUS) for many rows in one query per few hundred, for the list's newest messages.
     */
    private fun deliveredSms(ids: Collection<Long>): Set<Long> {
        val out = HashSet<Long>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                    "${Telephony.Sms._ID} IN (${chunk.joinToString(",") { "?" }}) AND ${Telephony.Sms.STATUS} = ${Telephony.Sms.STATUS_COMPLETE}",
                    chunk.map { it.toString() }.toTypedArray(), null
                )?.use { c -> while (c.moveToNext()) out += c.getLong(0) }
            }
        }
        return out
    }

    /**
     * Of the sent picture messages [ids], those every recipient reported delivered: rowsWhere's reading (a message
     * that asked for a report, its Message-ID, then MmsDelivery) for the list's newest messages, in one query.
     */
    private fun deliveredMms(ids: Collection<Long>): Set<Long> {
        val reportable = HashMap<Long, String>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            runCatching {
                context.contentResolver.query(
                    Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.MESSAGE_ID),
                    "${Telephony.Mms._ID} IN (${chunk.joinToString(",") { "?" }}) AND ${Telephony.Mms.DELIVERY_REPORT} = ${PduHeaders.VALUE_YES}",
                    chunk.map { it.toString() }.toTypedArray(), null
                )?.use { c -> while (c.moveToNext()) c.getString(1)?.takeIf { it.isNotBlank() }?.let { reportable[c.getLong(0)] = it } }
            }
        }
        return runCatching { MmsDelivery.delivered(context, reportable) }.getOrDefault(emptySet())
    }

    /**
     * The messages holding [pattern], one per conversation: Android's own text search, the provider's
     * `content://mms-sms/search?pattern=` that AOSP Messaging's SearchActivity queries
     * (https://android.googlesource.com/platform/packages/apps/Mms/+/refs/heads/main/src/com/android/mms/ui/SearchActivity.java,
     * Telephony.MmsSms.SEARCH_URI) and TelephonyProvider's MmsSmsProvider answers from its full text `words` table
     * (URI_SEARCH, getTextSearchQuery, https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/refs/heads/main/src/com/android/providers/telephony/MmsSmsProvider.java):
     * the words of a message beginning with the pattern, SMS and picture messages' texts together, grouped by
     * conversation. Columns by their place in getTextSearchQuery: message row, thread, address, body, date. The
     * provider does not say which table the row is of; an SMS keeps its date in milliseconds, a picture message in
     * seconds (as rowsWhere reads them), which tells them apart. Nothing is given to the provider but the pattern
     * (it refuses a selection). Messages sent over the encrypted chat are rows of the same store, so they are found too.
     */
    suspend fun search(pattern: String): List<Found> = withContext(Dispatchers.IO) {
        if (!canRead() || pattern.isBlank()) return@withContext emptyList()
        runCatching {
            val uri = Uri.parse("content://mms-sms/search").buildUpon().appendQueryParameter("pattern", pattern.trim()).build()
            val out = ArrayList<Found>()
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val sms = c.getLong(4) >= SMS_MILLIS
                    out += Found(c.getLong(1), (if (sms) "sms/" else "mms/") + c.getLong(0), c.getString(3).orEmpty())
                }
            }
            out
        }.getOrDefault(emptyList())
    }

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

    // not ported: getMessages (its blocked-number filter and contact lookups; rowsWhere reads the rows, Android drops blocked numbers)
    /**
     * The same read as [thread], null when the store cannot be read: a screen then keeps what it shows
     * and says so, instead of showing an empty conversation. The query of Fossify Messages' getMessages
     * (the thread's selection, the newest [limit] first), its error not hidden as an empty list.
     * https://raw.githubusercontent.com/FossifyOrg/Messages/main/app/src/main/kotlin/org/fossify/messages/extensions/Context.kt#L114-L217
     */
    suspend fun threadOrNull(threadId: Long, limit: Int = PAGE): List<Message>? = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext null
        val selection = "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}"
        val mmsSelection = "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}"
        val selectionArgs = arrayOf(threadId.toString())
        val messages = runCatching { rowsWhere(selection, selectionArgs, mmsSelection, selectionArgs, limit) }.getOrNull()
        messages
    }

    /** How many messages of [threadId] are not read yet, SMS and picture messages together (see [getUnreadCountsByThread]). */
    suspend fun unreadCount(threadId: Long): Int = withContext(Dispatchers.IO) { getUnreadCountsByThread(context, threadId) }

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

    /** The messages of [threadId] up to [upToDate] read and seen; the notification goes when nothing unread remains. */
    fun markRead(threadId: Long, upToDate: Long) {
        scope.launch(Dispatchers.IO) { markRead(context, threadId, upToDate) }
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

    /**
     * Deletes one message from the store, off the main thread (the caller's). True only when the provider
     * deleted a row: DeleteConversationAction.deleteConversationMessagesFromTelephony counts what
     * MmsUtils.deleteMessage(messageUri) deleted and logs "Could not delete telephony message" when it is not above 0.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/action/DeleteConversationAction.java#L121-L160
     */
    fun deleteMessage(message: Message): Boolean {
        val base = if (message.mms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
        val messageUri = Uri.withAppendedPath(base, message.id.toString())
        val count = runCatching { context.contentResolver.delete(messageUri, null, null) }.getOrDefault(0)
        if (count > 0) return true
        android.util.Log.w("Messages", "deleteMessage: Could not delete telephony message")
        return false
    }

    companion object {
        /** How many messages a conversation reads at a time. */
        const val PAGE = 200

        /** Ids asked at once by [byUid], under SQLite's limit of 999 variables. */
        private const val BATCH = 400

        /** Dates from here are milliseconds (an SMS), under it seconds (a picture message): 1973 in milliseconds. */
        private const val SMS_MILLIS = 100_000_000_000L

        fun boxOf(type: Int): Box = when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> Box.RECEIVED
            Telephony.Sms.MESSAGE_TYPE_SENT -> Box.SENT
            Telephony.Sms.MESSAGE_TYPE_FAILED -> Box.FAILED
            else -> Box.SENDING
        }

        /**
         * MarkReadReceiver.onReceive for one conversation: its message notification is cancelled
         * (NotificationCancellationHelper.cancel(context, notificationId)), then everything in it is marked
         * read and seen (threads().setRead(thread)), then process() is told what was marked.
         * Not ported: process's scheduleDeletion (no disappearing timer on SMS), MultiDeviceReadUpdateJob (no linked
         * device), SendReadReceiptJob (the chat sends its own receipts); processCallEvents (calls are Dialer's);
         * removeStickyThread and updateNotification (the notifier keeps no state, a read thread has nothing left to show).
         * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/notifications/MarkReadReceiver.java#process
         */
        fun markRead(context: Context, threadId: Long) {
            // The notification's id is the thread's (MessageNotifier.show).
            val notificationId = threadId.toInt()
            androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationId)
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

        // not ported: messagesDB.markThreadRead and conversationsDB.markRead (Fossify's own Room copy of the store; there is none here)
        /**
         * Fossify Messages' markThreadMessagesRead, the received messages of [threadId] read and seen up to
         * [upToDate] only (an SMS date is in milliseconds, a picture message's in seconds); the newer ones stay.
         * https://raw.githubusercontent.com/FossifyOrg/Messages/main/app/src/main/kotlin/org/fossify/messages/extensions/Context.kt#L1008-L1029
         */
        private fun markThreadMessagesRead(context: Context, threadId: Long, upToDate: Long) {
            val id = threadId.toString()

            val smsValues = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            val smsSelection = "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.TYPE}=? AND (${Telephony.Sms.READ}=? OR ${Telephony.Sms.SEEN}=?) AND ${Telephony.Sms.DATE}<=?"
            val smsArgs = arrayOf(id, Telephony.Sms.MESSAGE_TYPE_INBOX.toString(), "0", "0", upToDate.toString())
            runCatching { context.contentResolver.update(Telephony.Sms.CONTENT_URI, smsValues, smsSelection, smsArgs) }

            val mmsValues = ContentValues().apply {
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
            }
            val mmsSelection = "${Telephony.Mms.THREAD_ID}=? AND ${Telephony.Mms.MESSAGE_BOX}=? AND (${Telephony.Mms.READ}=? OR ${Telephony.Mms.SEEN}=?) AND ${Telephony.Mms.DATE}<=?"
            val mmsArgs = arrayOf(id, Telephony.Mms.MESSAGE_BOX_INBOX.toString(), "0", "0", (upToDate / 1000).toString())
            runCatching { context.contentResolver.update(Telephony.Mms.CONTENT_URI, mmsValues, mmsSelection, mmsArgs) }
        }

        // not ported: getUnreadCountsByThread's map of every thread (the list's readConversations reads them in one pass; this is one thread)
        /**
         * Fossify Messages' getUnreadCountsByThread for one conversation: unread SMS, then unread MMS, each bumped
         * into the result.
         * https://raw.githubusercontent.com/FossifyOrg/Messages/main/app/src/main/kotlin/org/fossify/messages/extensions/Context.kt#L323-L349
         */
        fun getUnreadCountsByThread(context: Context, threadId: Long): Int {
            var result = 0
            fun bump(count: Int) {
                result += count
            }

            // Unread SMS
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.THREAD_ID),
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}",
                    arrayOf(threadId.toString()), null
                )?.use { bump(it.count) }
            }

            // Unread MMS
            runCatching {
                context.contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms.THREAD_ID),
                    "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0 AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}",
                    arrayOf(threadId.toString()), null
                )?.use { bump(it.count) }
            }

            return result
        }

        // not ported: process (its read receipts, expiry timers and linked-device sync have no SMS counterpart, see the form above)
        /**
         * MarkReadReceiver.onReceive for the messages of [threadId] up to [upToDate] only: they are marked read, and
         * the notification goes only when no unread message is left (updateNotification then finds nothing to show).
         * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/notifications/MarkReadReceiver.java#process
         */
        fun markRead(context: Context, threadId: Long, upToDate: Long) {
            val notificationId = threadId.toInt()
            markThreadMessagesRead(context, threadId, upToDate)
            if (getUnreadCountsByThread(context, threadId) == 0) {
                androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationId)
            }
        }
    }
}

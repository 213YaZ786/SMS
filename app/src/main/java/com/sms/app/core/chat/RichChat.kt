package com.sms.app.core.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.telephony.SmsManager
import com.sms.app.core.common.writeTextAtomically
import com.sms.app.core.dial.T9
import com.sms.app.core.mms.Attachment
import com.sms.app.core.mms.ContentType
import android.provider.Telephony
import com.sms.app.core.mms.MmsStore
import com.sms.app.core.sms.SmsSender
import com.sms.app.core.sms.SmsStore
import com.sms.app.core.sms.MessageNotifier
import com.sms.app.data.settings.SettingsStore
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A phone number known to have SMS too, and its encrypted chat. */
@Serializable
data class ChatLink(
    val phone: String,
    val address: String = "",
    val chatId: Int = 0,
    /** Our nonce sent to that number, waiting to come back over the chat. */
    val myNonce: String = "",
    /** The chat proved to be the person holding the number. */
    val proven: Boolean = false,
    /** When the invite was last sent, not to ask again and again. */
    val askedAt: Long = 0
)

/** An encrypted group of several proven numbers, and its chat. */
@Serializable
data class ChatGroup(val chatId: Int, val phones: List<String>)

/** A message of Android's store that went over the rich chat: where it is, what the chat says of it. */
@Serializable
data class RichRef(
    val mms: Boolean,
    val id: Long,
    val seen: Boolean = false,
    val reactions: List<String> = emptyList(),
    val edited: Boolean = false,
    val pinned: Boolean = false,
    /** Ours: we may edit it or delete it for everyone. */
    val mine: Boolean = false
)

/**
 * The rich chat between SMS users, over chatmail: end-to-end encrypted,
 * read receipts, reactions, full quality pictures. Found by phone number:
 * an unseen data SMS carries the invite and a nonce, the nonce comes back
 * over the chat, and from then on messages to that number go this way;
 * SMS and MMS stay for everyone else and when there is no connection.
 */
class RichChat(private val context: Context, private val scope: CoroutineScope, private val settings: SettingsStore) {

    private val engine = Engine(context, scope)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(context.filesDir, "chat-links.json")
    private val lock = Mutex()
    private var account = 0
    private val random = SecureRandom()

    /** Nonces to send back once a joined chat can carry encrypted messages, by chat. */
    private val proofs = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()

    private val _links = MutableStateFlow(load())
    val links: StateFlow<Map<String, ChatLink>> = _links.asStateFlow()

    private val refsFile = File(context.filesDir, "chat-messages.json")
    private val groupsFile = File(context.filesDir, "chat-groups.json")

    /** The encrypted groups, by their members' numbers. */
    private val _groups = MutableStateFlow(loadGroups())

    /** The rich chat's messages in Android's store, by the chat's message id. */
    private val _refs = MutableStateFlow(loadRefs())
    val refs: StateFlow<Map<Int, RichRef>> = _refs.asStateFlow()

    /** The chat side of a message of the store, when it went over the chat. */
    fun refOf(mms: Boolean, id: Long): RichRef? = _refs.value.values.firstOrNull { it.mms == mms && it.id == id }

    /** The chat's own id of a message of the store, when it went over the chat. */
    fun chatIdOf(mms: Boolean, id: Long): Int? = _refs.value.entries.firstOrNull { it.value.mms == mms && it.value.id == id }?.key

    /** Bumped on every change of the chats, for open screens to read again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Where the chat stands, for Settings: off, connecting, connected or failed. */
    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val enabled: Boolean get() = settings.current.richChat

    init {
        scope.launch { engine.events.collect { onEvent(it) } }
    }

    /** The profile on the relay made and connected; false when off or unreachable. */
    suspend fun start(): Boolean = lock.withLock {
        if (!enabled) return false
        if (_ready.value && engine.running) return true
        _status.value = "Connecting"
        runCatching {
            val ids = engine.call("get_all_account_ids").jsonArray
            account = ids.firstOrNull()?.jsonPrimitive?.int ?: engine.call("add_account").jsonPrimitive.int
            if (!engine.call("is_configured", account).jsonPrimitive.boolean()) {
                engine.call("add_transport_from_qr", account, "dcaccount:" + settings.current.relay)
            }
            engine.call("start_io", account)
            _ready.value = true
            _status.value = "Connected"
        }.onFailure { error ->
            _status.value = "Not connected"
            // Debug builds keep the engine's last error in the app's own files, never in a log.
            if (com.sms.app.BuildConfig.DEBUG) runCatching { File(context.filesDir, "chat-error.txt").writeText(error.toString()) }
        }.isSuccess
    }

    fun stop() {
        engine.stop()
        _ready.value = false
    }

    /** The proven chat of [phone], or null: then SMS. */
    fun linkFor(phone: String): ChatLink? = _links.value[key(phone)]?.takeIf { it.proven && it.chatId > 0 }

    /** Every member proven: the group goes over the encrypted chat. */
    fun groupReady(phones: List<String>): Boolean = phones.size > 1 && phones.all { linkFor(it) != null }

    /** The chat of one number or of a group, made for the group the first time. */
    private suspend fun chatOf(phones: List<String>): Int? {
        if (phones.size == 1) return linkFor(phones[0])?.chatId
        if (!groupReady(phones)) return null
        _groups.value[groupKey(phones)]?.let { return it.chatId }
        return runCatching {
            val chat = engine.call("create_group_chat", account, "SMS", false).jsonPrimitive.int
            phones.forEach { phone ->
                val one = linkFor(phone)!!.chatId
                val contact = engine.call("get_chat_contacts", account, one).jsonArray.map { it.jsonPrimitive.int }.first { it != SELF }
                engine.call("add_contact_to_chat", account, chat, contact)
            }
            saveGroup(ChatGroup(chat, phones))
            chat
        }.getOrNull()
    }

    /**
     * A group someone else made: known when each of its other members is a
     * proven number, so its messages join that group's conversation.
     */
    private suspend fun adopt(chat: Int): ChatGroup? = runCatching {
        val contacts = engine.call("get_chat_contacts", account, chat).jsonArray.map { it.jsonPrimitive.int }.filter { it != SELF }
        val phones = contacts.map { id ->
            val address = engine.call("get_contact", account, id).jsonObject["address"]?.jsonPrimitive?.contentOrNull
            _links.value.values.firstOrNull { it.proven && it.address == address }?.phone ?: return@runCatching null
        }
        if (phones.size < 2) null else ChatGroup(chat, phones).also(::saveGroup)
    }.getOrNull()

    /**
     * Asks [phone] whether it has SMS too, at most once a month (unless
     * [force]d): a data SMS with our invite and a fresh nonce. A phone
     * without SMS drops it unseen.
     */
    suspend fun hello(phone: String, force: Boolean = false) {
        if (!enabled || T9.clean(phone).count(Char::isDigit) < 7) return
        val known = _links.value[key(phone)]
        if (known?.proven == true && !force) return
        if (!force && known != null && System.currentTimeMillis() - known.askedAt < ASK_AGAIN_MS) return
        if (!start()) return
        val nonce = ByteArray(Hello.NONCE_BYTES).also(random::nextBytes)
        val link = runCatching { engine.call("get_chat_securejoin_qr_code", account, null).jsonPrimitive.content }.getOrNull() ?: return
        val hello = Hello.fromLink(link, nonce) ?: return
        val sent = runCatching {
            context.getSystemService(SmsManager::class.java).sendDataMessage(phone, null, PORT, hello.encode(), null, null)
        }.isSuccess
        if (sent) save(phone) { (it ?: ChatLink(phone)).copy(myNonce = hex(nonce), askedAt = System.currentTimeMillis()) }
    }

    /** An invite from [from]: joined, and its nonce sent back over the chat as proof. */
    suspend fun onHello(from: String, hello: Hello) {
        if (!enabled || !start()) return
        val chat = runCatching { engine.call("secure_join", account, hello.link()).jsonPrimitive.int }.getOrNull() ?: return
        // The proof waits until the keys are exchanged: relays take only encrypted mail.
        proofs[chat] = hello.nonce
        val known = _links.value[key(from)]
        if (known?.proven == true && known.address != hello.address) {
            // An SMS sender can be faked: a proven number never moves on an
            // invite alone. Our own nonce goes to the number; whoever really
            // holds it proves it, and only then does the chat change.
            hello(from, force = true)
            return
        }
        save(from) { (it ?: ChatLink(from)).copy(address = hello.address, chatId = chat) }
        // Our own invite back, so they can prove this side too.
        if (known == null || known.myNonce.isEmpty()) hello(from, force = true)
    }

    private suspend fun onEvent(event: ChatEvent) {
        val msgId = event.data["msgId"]?.jsonPrimitive?.intOrNull
        when (event.kind) {
            "IncomingMsg" -> if (msgId != null) onIncoming(msgId)
            "SecurejoinJoinerProgress" -> {
                val progress = event.data["progress"]?.jsonPrimitive?.intOrNull ?: 0
                val contact = event.data["contactId"]?.jsonPrimitive?.intOrNull
                if (progress >= 400 && contact != null) {
                    val chat = runCatching { engine.call("get_chat_id_by_contact_id", account, contact).jsonPrimitive.intOrNull }.getOrNull()
                    val nonce = chat?.let { proofs.remove(it) }
                    if (nonce != null) runCatching { engine.call("misc_send_text_message", account, chat, Hello.proofText(nonce)) }
                }
            }
            "MsgDelivered" -> if (msgId != null) _refs.value[msgId]?.let { mark(it, failed = false) }
            "MsgFailed" -> if (msgId != null) _refs.value[msgId]?.let { mark(it, failed = true) }
            "MsgRead" -> if (msgId != null) _refs.value[msgId]?.let { saveRef(msgId, it.copy(seen = true)) }
            // Edited or pinned on the other side, or a vanishing message gone.
            "MsgsChanged" -> if (msgId != null) _refs.value[msgId]?.let { refresh(msgId, it) }
            "MsgDeleted" -> if (msgId != null) _refs.value[msgId]?.let { forget(msgId, it) }
            "ReactionsChanged", "IncomingReaction" -> if (msgId != null) _refs.value[msgId]?.let { ref ->
                val emojis = runCatching {
                    engine.call("get_message_reactions", account, msgId).jsonObject["reactions"]?.jsonArray
                        ?.mapNotNull { (it as? JsonObject)?.get("emoji")?.jsonPrimitive?.contentOrNull }
                }.getOrNull().orEmpty()
                saveRef(msgId, ref.copy(reactions = emojis))
            }
        }
        _changes.value++
    }

    /**
     * A message in: a proof binds a number; anything else is kept in
     * Android's store with the number's conversation and shown as usual.
     */
    private suspend fun onIncoming(msgId: Int) {
        val message = runCatching { engine.call("get_message", account, msgId).jsonObject }.getOrNull() ?: return
        val text = message["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val chat = message["chatId"]?.jsonPrimitive?.int ?: return
        val proof = Hello.proofOf(text)
        if (proof != null) {
            // Whoever proves our nonce holds the number we sent it to.
            val from = (message["sender"] as? JsonObject)?.get("address")?.jsonPrimitive?.contentOrNull.orEmpty()
            val match = _links.value.values.firstOrNull { it.myNonce == hex(proof) } ?: return
            save(match.phone) { it!!.copy(address = from, chatId = chat, proven = true, myNonce = "") }
            runCatching { engine.call("delete_messages", account, listOf(msgId)) }
            return
        }
        if (message["isInfo"]?.jsonPrimitive?.booleanOrNull == true) return
        val file = message["file"]?.jsonPrimitive?.contentOrNull
        val mime = message["fileMime"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
        val link = _links.value.values.firstOrNull { it.proven && it.chatId == chat }
        val stored = if (link != null) withContext(Dispatchers.IO) {
            if (file != null) {
                val data = runCatching { File(file).readBytes() }.getOrNull() ?: return@withContext null
                MmsStore.saveReceivedParts(context, link.phone, text, mime, data)?.let { RichRef(true, it.second) to it.first }
            } else {
                SmsStore.saveReceived(context, link.phone, text)?.let { RichRef(false, it.second) to it.first }
            }
        } else {
            // A group: kept as a group message from its sender, to the others.
            val group = _groups.value.values.firstOrNull { it.chatId == chat } ?: adopt(chat) ?: return
            val from = (message["sender"] as? JsonObject)?.get("address")?.jsonPrimitive?.contentOrNull
            val sender = _links.value.values.firstOrNull { it.proven && it.address == from }?.phone ?: return
            val others = group.phones.filter { key(it) != key(sender) }
            withContext(Dispatchers.IO) {
                val data = file?.let { runCatching { File(it).readBytes() }.getOrNull() }
                MmsStore.saveReceivedParts(context, sender, text, if (data != null) mime else null, data, others)?.let { RichRef(true, it.second) to it.first }
            }
        } ?: return
        saveRef(msgId, stored.first)
        MessageNotifier(context).show(stored.second)
    }

    private fun mark(ref: RichRef, failed: Boolean) {
        if (ref.mms) {
            MmsStore.setBox(context, Uri.parse("content://mms/${ref.id}"), if (failed) Telephony.Mms.MESSAGE_BOX_FAILED else Telephony.Mms.MESSAGE_BOX_SENT)
        } else {
            SmsSender.mark(context, Uri.parse("content://sms/${ref.id}"), if (failed) Telephony.Sms.MESSAGE_TYPE_FAILED else Telephony.Sms.MESSAGE_TYPE_SENT)
        }
    }

    /**
     * Sends over the chat, the text and each attachment in full quality,
     * each kept in Android's store as going out; the chat's reports turn
     * it sent, read or failed.
     */
    suspend fun send(phones: List<String>, text: String, attachments: List<Attachment>, quote: String?): Boolean {
        if (phones.isEmpty() || (phones.size == 1 && linkFor(phones[0]) == null) || (phones.size > 1 && !groupReady(phones))) return false
        if (!start()) return false
        val chatId = chatOf(phones) ?: return false
        val phone = phones[0]
        return runCatching {
            if (attachments.isEmpty()) {
                val id = engine.call("send_msg", account, chatId, mapOf("text" to text, "quotedText" to quote)).jsonPrimitive.int
                if (phones.size == 1) {
                    SmsStore.saveSending(context, phone, text)?.let { saveRef(id, RichRef(false, it, mine = true)) }
                } else {
                    MmsStore.saveSendingParts(context, phones, text, null, null)?.let { saveRef(id, RichRef(true, it, mine = true)) }
                }
            } else {
                attachments.forEachIndexed { i, a ->
                    val copy = copyIn(a) ?: return@forEachIndexed
                    val viewtype = when {
                        ContentType.isImageType(a.contentType) -> "Image"
                        ContentType.isVideoType(a.contentType) -> "Video"
                        a.contentType.contains("vcard") -> "Vcard"
                        // Recorded in the composer: a voice message; any other sound stays audio.
                        ContentType.isAudioType(a.contentType) -> if (copy.name.startsWith("voice-")) "Voice" else "Audio"
                        else -> "File"
                    }
                    val caption = if (i == 0) text else ""
                    val id = engine.call(
                        "send_msg", account, chatId,
                        mapOf("text" to caption.ifBlank { null }, "file" to copy.path, "viewtype" to viewtype, "quotedText" to if (i == 0) quote else null)
                    ).jsonPrimitive.int
                    MmsStore.saveSendingParts(context, phones, caption, a.contentType, copy.readBytes())?.let { saveRef(id, RichRef(true, it, mine = true)) }
                    // The engine keeps its own copy.
                    copy.parentFile?.deleteRecursively()
                }
            }
            _changes.value++
            true
        }.getOrDefault(false)
    }

    /** What the user has seen, so the other side gets its read receipt. */
    suspend fun markSeen(phones: List<String>) {
        val chatId = when {
            phones.size == 1 -> linkFor(phones[0])?.chatId
            groupReady(phones) -> _groups.value[groupKey(phones)]?.chatId
            else -> null
        } ?: return
        if (!start()) return
        runCatching {
            val ids = engine.call("get_message_ids", account, chatId, false, false).jsonArray.map { it.jsonPrimitive.int }
            if (ids.isNotEmpty()) engine.call("markseen_msgs", account, ids)
        }
    }

    suspend fun react(messageId: Long, emoji: String?) {
        if (!start()) return
        runCatching { engine.call("send_reaction", account, messageId.toInt(), listOfNotNull(emoji)) }
        _changes.value++
    }

    /** Our own message, changed for both sides. */
    suspend fun edit(msgId: Int, text: String): Boolean {
        val ref = _refs.value[msgId]?.takeIf { it.mine } ?: return false
        if (text.isBlank() || !start()) return false
        val done = runCatching { engine.call("send_edit_request", account, msgId, text) }.isSuccess
        if (done) {
            withContext(Dispatchers.IO) { rewrite(ref, text) }
            saveRef(msgId, ref.copy(edited = true))
            _changes.value++
        }
        return done
    }

    /** Our own message, deleted here and on the other side. */
    suspend fun deleteForAll(msgId: Int): Boolean {
        val ref = _refs.value[msgId]?.takeIf { it.mine } ?: return false
        if (!start()) return false
        val done = runCatching { engine.call("delete_messages_for_all", account, listOf(msgId)) }.isSuccess
        if (done) forget(msgId, ref)
        return done
    }

    /** Pinned for both sides, to find it again at the top of the conversation. */
    suspend fun pin(msgId: Int, on: Boolean) {
        val ref = _refs.value[msgId] ?: return
        if (!start()) return
        if (runCatching { engine.call("set_pinned_message_state", account, msgId, on) }.isSuccess) {
            saveRef(msgId, ref.copy(pinned = on))
            _changes.value++
        }
    }

    /** How long messages with [phones] last before they vanish, in seconds; 0 for always. */
    suspend fun timer(phones: List<String>): Int {
        if (!start()) return 0
        val chatId = chatOf(phones) ?: return 0
        return runCatching { engine.call("get_chat_ephemeral_timer", account, chatId).jsonPrimitive.int }.getOrDefault(0)
    }

    /** Messages with [phones] vanish [seconds] after they are read, on both sides; 0 turns it off. */
    suspend fun setTimer(phones: List<String>, seconds: Int): Boolean {
        if (!start()) return false
        val chatId = chatOf(phones) ?: return false
        return runCatching { engine.call("set_chat_ephemeral_timer", account, chatId, seconds) }.isSuccess.also { if (it) _changes.value++ }
    }

    /** What the chat now says of a message: its text after an edit, its pin. */
    private suspend fun refresh(msgId: Int, ref: RichRef) {
        val message = runCatching { engine.call("get_message", account, msgId).jsonObject }.getOrNull() ?: return
        val edited = message["isEdited"]?.jsonPrimitive?.booleanOrNull == true
        val pinned = message["isPinned"]?.jsonPrimitive?.booleanOrNull == true
        if (edited) {
            message["text"]?.jsonPrimitive?.contentOrNull?.let { text -> withContext(Dispatchers.IO) { rewrite(ref, text) } }
        }
        if (edited != ref.edited || pinned != ref.pinned) saveRef(msgId, ref.copy(edited = edited || ref.edited, pinned = pinned))
    }

    /** A message gone from the chat leaves Android's store too. */
    private suspend fun forget(msgId: Int, ref: RichRef) {
        withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.delete(Uri.parse(if (ref.mms) "content://mms/${ref.id}" else "content://sms/${ref.id}"), null, null) }
        }
        val updated = _refs.value - msgId
        _refs.value = updated
        runCatching { refsFile.writeTextAtomically(json.encodeToString(updated)) }
        _changes.value++
    }

    /** The text of a message in Android's store replaced, after an edit. */
    private fun rewrite(ref: RichRef, text: String) {
        runCatching {
            if (ref.mms) {
                val values = android.content.ContentValues().apply { put(Telephony.Mms.Part.TEXT, text) }
                context.contentResolver.update(
                    Uri.parse("content://mms/part"), values,
                    "${Telephony.Mms.Part.MSG_ID} = ? AND ${Telephony.Mms.Part.CONTENT_TYPE} = ?", arrayOf(ref.id.toString(), "text/plain")
                )
            } else {
                val values = android.content.ContentValues().apply { put(Telephony.Sms.BODY, text) }
                context.contentResolver.update(Uri.parse("content://sms/${ref.id}"), values, null, null)
            }
        }
    }

    /** A picked file copied where the engine may read it. */
    private suspend fun copyIn(a: Attachment): File? = withContext(Dispatchers.IO) {
        runCatching {
            val name = context.contentResolver.query(a.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: if (a.contentType.contains("vcard")) "contact.vcf" else "file"
            // A folder of its own, so the file keeps its name as the other side sees it.
            val dir = File(File(context.cacheDir, "outgoing"), System.nanoTime().toString()).apply { mkdirs() }
            File(dir, name.replace('/', '_')).also { out ->
                context.contentResolver.openInputStream(a.uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }.getOrNull()
    }

    private fun save(phone: String, change: (ChatLink?) -> ChatLink) {
        val k = key(phone)
        val updated = _links.value + (k to change(_links.value[k]).copy(phone = phone))
        _links.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(updated)) }
    }

    private fun saveRef(msgId: Int, ref: RichRef) {
        val updated = _refs.value + (msgId to ref)
        _refs.value = updated
        runCatching { refsFile.writeTextAtomically(json.encodeToString(updated)) }
    }

    private fun saveGroup(group: ChatGroup) {
        val updated = _groups.value + (groupKey(group.phones) to group)
        _groups.value = updated
        runCatching { groupsFile.writeTextAtomically(json.encodeToString(updated)) }
    }

    private fun loadGroups(): Map<String, ChatGroup> =
        runCatching { json.decodeFromString<Map<String, ChatGroup>>(groupsFile.readText()) }.getOrDefault(emptyMap())

    private fun groupKey(phones: List<String>) = phones.map(::key).sorted().joinToString(",")

    private fun loadRefs(): Map<Int, RichRef> =
        runCatching { json.decodeFromString<Map<Int, RichRef>>(refsFile.readText()) }.getOrDefault(emptyMap())

    private fun load(): Map<String, ChatLink> =
        runCatching { json.decodeFromString<Map<String, ChatLink>>(file.readText()) }.getOrDefault(emptyMap())

    private fun key(phone: String) = T9.clean(phone).removePrefix("+").takeLast(9)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        /** The port the data SMS carrying an invite go to. */
        const val PORT: Short = 18471
        /** The engine's id for the user themselves among a chat's contacts. */
        private const val SELF = 1
        /** A number without SMS is asked again only a month later. */
        private const val ASK_AGAIN_MS = 30L * 24 * 60 * 60 * 1000
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.boolean(): Boolean = booleanOrNull ?: false

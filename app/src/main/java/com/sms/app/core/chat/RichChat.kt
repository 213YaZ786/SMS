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

/** A message of Android's store that went over the rich chat: where it is, what the chat says of it. */
@Serializable
data class RichRef(val mms: Boolean, val id: Long, val seen: Boolean = false, val reactions: List<String> = emptyList())

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

    /** The rich chat's messages in Android's store, by the chat's message id. */
    private val _refs = MutableStateFlow(loadRefs())
    val refs: StateFlow<Map<Int, RichRef>> = _refs.asStateFlow()

    /** The chat side of a message of the store, when it went over the chat. */
    fun refOf(mms: Boolean, id: Long): RichRef? = _refs.value.values.firstOrNull { it.mms == mms && it.id == id }

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
        val link = _links.value.values.firstOrNull { it.proven && it.chatId == chat } ?: return
        val file = message["file"]?.jsonPrimitive?.contentOrNull
        val mime = message["fileMime"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
        val stored = withContext(Dispatchers.IO) {
            if (file != null) {
                val data = runCatching { File(file).readBytes() }.getOrNull() ?: return@withContext null
                MmsStore.saveReceivedParts(context, link.phone, text, mime, data)?.let { RichRef(true, it.second) to it.first }
            } else {
                SmsStore.saveReceived(context, link.phone, text)?.let { RichRef(false, it.second) to it.first }
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
    suspend fun send(phone: String, text: String, attachments: List<Attachment>, quote: String?): Boolean {
        val link = linkFor(phone) ?: return false
        if (!start()) return false
        return runCatching {
            if (attachments.isEmpty()) {
                val id = engine.call("send_msg", account, link.chatId, mapOf("text" to text, "quotedText" to quote)).jsonPrimitive.int
                SmsStore.saveSending(context, phone, text)?.let { saveRef(id, RichRef(false, it)) }
            } else {
                attachments.forEachIndexed { i, a ->
                    val copy = copyIn(a) ?: return@forEachIndexed
                    val viewtype = when {
                        ContentType.isImageType(a.contentType) -> "Image"
                        ContentType.isVideoType(a.contentType) -> "Video"
                        else -> "File"
                    }
                    val caption = if (i == 0) text else ""
                    val id = engine.call(
                        "send_msg", account, link.chatId,
                        mapOf("text" to caption.ifBlank { null }, "file" to copy.path, "viewtype" to viewtype, "quotedText" to if (i == 0) quote else null)
                    ).jsonPrimitive.int
                    MmsStore.saveSendingParts(context, phone, caption, a.contentType, copy.readBytes())?.let { saveRef(id, RichRef(true, it)) }
                }
            }
            _changes.value++
            true
        }.getOrDefault(false)
    }

    /** What the user has seen, so the other side gets its read receipt. */
    suspend fun markSeen(phone: String) {
        val link = linkFor(phone) ?: return
        if (!start()) return
        runCatching {
            val ids = engine.call("get_message_ids", account, link.chatId, false, false).jsonArray.map { it.jsonPrimitive.int }
            if (ids.isNotEmpty()) engine.call("markseen_msgs", account, ids)
        }
    }

    suspend fun react(messageId: Long, emoji: String?) {
        if (!start()) return
        runCatching { engine.call("send_reaction", account, messageId.toInt(), listOfNotNull(emoji)) }
        _changes.value++
    }

    /** A picked file copied where the engine may read it. */
    private suspend fun copyIn(a: Attachment): File? = withContext(Dispatchers.IO) {
        runCatching {
            val name = context.contentResolver.query(a.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "file"
            val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
            File(dir, "${System.nanoTime()}-${name.replace('/', '_')}").also { out ->
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

    private fun loadRefs(): Map<Int, RichRef> =
        runCatching { json.decodeFromString<Map<Int, RichRef>>(refsFile.readText()) }.getOrDefault(emptyMap())

    private fun load(): Map<String, ChatLink> =
        runCatching { json.decodeFromString<Map<String, ChatLink>>(file.readText()) }.getOrDefault(emptyMap())

    private fun key(phone: String) = T9.clean(phone).removePrefix("+").takeLast(9)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        /** The port the data SMS carrying an invite go to. */
        const val PORT: Short = 18471
        /** A number without SMS is asked again only a month later. */
        private const val ASK_AGAIN_MS = 30L * 24 * 60 * 60 * 1000
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.boolean(): Boolean = booleanOrNull ?: false

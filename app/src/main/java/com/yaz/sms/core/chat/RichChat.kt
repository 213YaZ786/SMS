package com.yaz.sms.core.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.telephony.SmsManager
import com.yaz.sms.core.common.writeTextAtomically
import com.yaz.sms.core.dial.T9
import com.yaz.sms.core.mms.Attachment
import com.yaz.sms.core.mms.ContentType
import android.provider.Telephony
import com.yaz.sms.core.mms.MmsStore
import com.yaz.sms.core.sms.SmsSender
import com.yaz.sms.core.sms.SmsStore
import com.yaz.sms.core.sms.MessageNotifier
import com.yaz.sms.data.settings.SettingsStore
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

/**
 * A phone number known to have SMS too, and its encrypted chat; or a person
 * with no number, met by an invite link or code: then [phone] is their chat
 * address, which is also the address of their conversation.
 */
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
    val askedAt: Long = 0,
    /** For a person met without a number (an invite link or code): the name they go by. */
    val name: String = ""
)

/** A person's card as the chat brought it: their chosen name, their photo's file, their status. */
data class SharedCard(val name: String, val photo: String?, val status: String)

/** When the relays were last looked at, and how many days each has not answered. */
@Serializable
data class RelayHealth(val checkedAt: Long = 0, val fails: Map<String, Int> = emptyMap())

class HealthFile(private val file: File, private val json: Json) {
    fun load(): RelayHealth = runCatching { json.decodeFromString<RelayHealth>(file.readText()) }.getOrDefault(RelayHealth())
    fun save(health: RelayHealth) {
        runCatching { file.writeTextAtomically(json.encodeToString(health)) }
    }
}

/** What the engine says of a call: one rings here, ours was taken, one ended. */
sealed class CallSignal {
    data class Incoming(val msgId: Int, val phone: String, val offer: String, val video: Boolean) : CallSignal()
    data class Accepted(val msgId: Int, val answer: String) : CallSignal()
    data class Ended(val msgId: Int) : CallSignal()
    /** Taken on another of the user's devices: this one stops ringing. */
    data class TakenElsewhere(val msgId: Int) : CallSignal()
}

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
    val mine: Boolean = false,
    /** The chat it belongs to. */
    val chat: Int = 0,
    /** When it vanishes, in milliseconds, and how long it was given; 0 when it stays. */
    val vanishAt: Long = 0,
    val vanishFor: Int = 0,
    /** How many gave each reaction, and the user's own: a poll's votes. */
    val counts: Map<String, Int> = emptyMap(),
    val myReactions: List<String> = emptyList()
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
    /** Invites received in person, by the chat they open: the number they bind once the keys are exchanged. */
    private val inPerson = java.util.concurrent.ConcurrentHashMap<Int, String>()
    // Chats joined by an invite link or code, with no number: chat id to when.
    private val noNumber = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private val _links = MutableStateFlow(load())
    val links: StateFlow<Map<String, ChatLink>> = _links.asStateFlow()

    private val refsFile = File(context.filesDir, "chat-messages.json")
    private val groupsFile = File(context.filesDir, "chat-groups.json")

    /** The encrypted groups, by their members' numbers. */
    private val _groups = MutableStateFlow(loadGroups())

    /** The rich chat's messages in Android's store, by the chat's message id. */
    private val refStore = RefStore(context)
    private val _refs = MutableStateFlow(loadRefs())
    val refs: StateFlow<Map<Int, RichRef>> = _refs.asStateFlow()

    /** The chat's id of each row of the store it holds, to find one at once. */
    @Volatile private var byRow: Map<Pair<Boolean, Long>, Int> = index(_refs.value)

    private fun index(refs: Map<Int, RichRef>) = HashMap<Pair<Boolean, Long>, Int>(refs.size * 2).apply {
        refs.forEach { (msg, r) -> put(r.mms to r.id, msg) }
    }

    /** The in-memory list and its index after a change. */
    private fun publish(updated: Map<Int, RichRef>) {
        byRow = index(updated)
        _refs.value = updated
    }

    /** The chat side of a message of the store, when it went over the chat. */
    fun refOf(mms: Boolean, id: Long): RichRef? = byRow[mms to id]?.let { _refs.value[it] }

    /** The chat's own id of a message of the store, when it went over the chat. */
    fun chatIdOf(mms: Boolean, id: Long): Int? = byRow[mms to id]

    /** How long messages last in each chat, in seconds, as last heard. */
    private val timers = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /**
     * When the engine will delete a message, as it says itself: its timer
     * and, once the countdown has started (sent, or read when received),
     * the moment it ends.
     */
    private suspend fun withExpiry(msgId: Int, ref: RichRef): RichRef {
        val info = runCatching { engine.call("get_message_info_object", account, msgId).jsonObject }.getOrNull() ?: return ref
        val duration = (info["ephemeralTimer"] as? JsonObject)?.get("duration")?.jsonPrimitive?.intOrNull ?: return ref.copy(vanishAt = 0, vanishFor = 0)
        val at = info["ephemeralTimestamp"]?.jsonPrimitive?.longOrNull ?: 0L
        return ref.copy(vanishAt = at * 1000, vanishFor = duration)
    }

    /** Messages whose countdown was asked of the engine in this run. */
    private val expiryKnown = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())

    private val _calls = kotlinx.coroutines.flow.MutableSharedFlow<CallSignal>(extraBufferCapacity = 16)
    /** The calls' signals, for the call machinery. */
    val calls: kotlinx.coroutines.flow.SharedFlow<CallSignal> = _calls

    /** Bumped on every change of the chats, for open screens to read again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Where the chat stands, for Settings: off, connecting, connected or failed. */
    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _outgoing = MutableStateFlow<Set<Int>>(emptySet())
    /** Messages handed to the engine and not yet sent or failed. */
    val outgoing: StateFlow<Set<Int>> = _outgoing.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val enabled: Boolean get() = settings.current.richChat

    init {
        scope.launch { engine.events.collect { onEvent(it) } }
        // A call coming in rings through Telecom; when it cannot (another call), it is missed and ended.
        scope.launch {
            calls.collect { signal ->
                if (signal is CallSignal.Incoming && !com.yaz.sms.core.call.CallBook.ring(context, signal.msgId, signal.phone, signal.offer, signal.video)) {
                    com.yaz.sms.core.call.CallNotices.missed(context, signal.phone, signal.video)
                    endCall(signal.msgId)
                }
            }
        }
    }

    /** The profile on the relay made and connected; false when off or unreachable. */
    suspend fun start(): Boolean = lock.withLock {
        if (!enabled) return false
        // Started off screen (a reply from a notification, an invite): it stops again once done.
        ChatLife.settle()
        if (_ready.value && engine.running) return true
        _status.value = "Connecting"
        runCatching {
            val ids = engine.call("get_all_account_ids").jsonArray
            account = ids.firstOrNull()?.jsonPrimitive?.int ?: engine.call("add_account").jsonPrimitive.int
            if (!engine.call("is_configured", account).jsonPrimitive.boolean()) {
                val chosen = settings.current.relay
                // Automatic: the relay that answers fastest, then backups added in the background.
                if (chosen.isBlank()) engine.call("init_transports", account, null)
                else engine.call("add_transport_from_qr", account, "dcaccount:$chosen")
            }
            engine.call("start_io", account)
            _ready.value = true
            scope.launch {
                trimRelays()
                tend()
            }
            scope.launch { prune() }
            _status.value = "Connected"
        }.onFailure { error ->
            _status.value = "Not connected"
            // Debug builds keep the engine's last error in the app's own files, never in a log.
            if (com.yaz.sms.BuildConfig.DEBUG) runCatching { File(context.filesDir, "chat-error.txt").writeText(error.toString()) }
        }.isSuccess
    }

    /** The relays the profile receives through, the one it sends through first. */
    suspend fun relays(): List<String> {
        if (!_ready.value) return emptyList()
        return runCatching {
            engine.call("list_transports", account).jsonArray.mapNotNull { (it as? JsonObject)?.get("addr")?.jsonPrimitive?.contentOrNull?.substringAfter('@') }
        }.getOrDefault(emptyList())
    }

    /**
     * Keeps the relays alive over the years, once a day: the list of
     * public relays is read again each week; a relay of the profile that
     * has not answered for three days while another one did is let
     * go (the engine then sends through another), and in automatic mode
     * the profile always keeps two, the newcomer being the known relay
     * that answers fastest.
     */
    suspend fun tend() = withContext(Dispatchers.IO) { tending.withLock { tendNow() } }

    private val tending = Mutex()

    private suspend fun tendNow() {
        if (!_ready.value) return
        val state = health.load()
        if (System.currentTimeMillis() - state.checkedAt < 20 * 60 * 60 * 1000L) return
        Relays.refresh(context)
        val transports = runCatching {
            engine.call("list_transports", account).jsonArray.mapNotNull { (it as? JsonObject)?.get("addr")?.jsonPrimitive?.contentOrNull }
        }.getOrDefault(emptyList())
        val answers = transports.associate { addr -> addr.substringAfter('@') to Relays.answerTime(addr.substringAfter('@')) }
        // A silent day counts against a relay only when the network was
        // working: another of them, or a known relay, answered that day.
        val networkWorked = answers.values.any { it != null } ||
            Relays.known(context).filter { it !in answers.keys }.take(3).any { Relays.answerTime(it) != null }
        if (!networkWorked) return
        val fails = answers.mapValues { (host, time) -> if (time == null) (state.fails[host] ?: 0) + 1 else 0 }
        // Written now: adding a relay below may take minutes.
        health.save(RelayHealth(System.currentTimeMillis(), fails))
        val alive = transports.toMutableList()
        transports.filter { (fails[it.substringAfter('@')] ?: 0) >= 3 }.forEach { addr ->
            if (alive.size > 1 && runCatching { engine.call("delete_transport", account, addr) }.isSuccess) alive -= addr
        }
        if (settings.current.relay.isBlank() && alive.size < 2) {
            val have = alive.map { it.substringAfter('@') }.toSet()
            val ranked = coroutineScope {
                Relays.known(context).filter { it !in have }.map { host -> async { host to Relays.answerTime(host) } }
                    .mapNotNull { it.await().let { (h, t) -> t?.let { h to it } } }.sortedBy { it.second }.map { it.first }
            }
            for (host in ranked.take(3)) {
                val ok = runCatching { engine.call("add_transport_from_qr", account, "dcaccount:$host") }
                if (ok.isSuccess) break
            }
        }
        _changes.value++
    }

    /**
     * Two relays at most: the one the profile sends through and one backup.
     * Every message comes to each relay of the profile and the engine keeps
     * a connection open to each, renewed every five minutes: more relays are
     * more of the phone's radio woken for the same messages. The engine's
     * own additions (up to three) are turned off; removed relays are told to
     * the contacts by the engine, who then stop writing there.
     */
    private suspend fun trimRelays() = withContext(Dispatchers.IO) {
        tending.withLock {
            runCatching { engine.call("set_config", account, "autorelay", "0") }
            val transports = runCatching {
                engine.call("list_transports", account).jsonArray.mapNotNull { (it as? JsonObject)?.get("addr")?.jsonPrimitive?.contentOrNull }
            }.getOrDefault(emptyList())
            if (transports.size <= MAX_RELAYS) return@withLock
            val sending = runCatching { engine.call("get_config", account, "configured_addr").jsonPrimitive.contentOrNull }.getOrNull()
            // The backup kept: the one answering fastest now, else the first listed.
            val backups = transports.filter { it != sending }
            val times = coroutineScope { backups.map { addr -> async { addr to Relays.answerTime(addr.substringAfter('@')) } }.map { it.await() } }
            val keep = setOfNotNull(sending ?: transports.first()) +
                (times.filter { it.second != null }.minByOrNull { it.second!! }?.first ?: backups.first())
            transports.filter { it !in keep }.forEach { addr -> runCatching { engine.call("delete_transport", account, addr) } }
            _changes.value++
        }
    }

    private val health = HealthFile(File(context.filesDir, "relay-health.json"), json)

    /**
     * Forgets the chat side of messages no longer in Android's store
     * (deleted here), before their number is given to another message.
     */
    suspend fun prune() = withContext(Dispatchers.IO) {
        val gone = _refs.value.filter { (_, r) ->
            val uri = Uri.parse(if (r.mms) "content://mms/${r.id}" else "content://sms/${r.id}")
            runCatching { context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.count == 0 } }.getOrNull() ?: false
        }.keys
        if (gone.isEmpty()) return@withContext
        publish(_refs.value - gone)
        runCatching { refStore.remove(gone) }
    }

    /** The relays' meeting points for calls (TURN), as the engine gives them, in WebRTC's JSON. */
    suspend fun iceServers(): String {
        if (!start()) return "[]"
        return runCatching { engine.call("ice_servers", account).jsonPrimitive.content }.getOrDefault("[]")
    }

    /** Calls [phone] with our [offer]; the call's id, or null when it cannot go. */
    suspend fun placeCall(phone: String, offer: String, video: Boolean): Int? {
        val link = linkFor(phone) ?: return null
        if (!start()) return null
        return runCatching { engine.call("place_outgoing_call", account, link.chatId, offer, video).jsonPrimitive.int }.getOrNull()
    }

    suspend fun acceptCall(msgId: Int, answer: String): Boolean {
        if (!start()) return false
        return runCatching { engine.call("accept_incoming_call", account, msgId, answer) }.isSuccess
    }

    suspend fun endCall(msgId: Int) {
        if (!start()) return
        runCatching { engine.call("end_call", account, msgId) }
    }

    /** One more relay for the profile, chosen by hand. */
    suspend fun addRelay(host: String): Boolean {
        if (!start()) return false
        return runCatching { engine.call("add_transport_from_qr", account, "dcaccount:$host") }.isSuccess.also { if (it) _changes.value++ }
    }

    fun stop() {
        engine.stop()
        _ready.value = false
    }

    /** The proven chat of [phone], or null: then SMS. */
    fun linkFor(phone: String): ChatLink? = _links.value[key(phone)]?.takeIf { it.proven && it.chatId > 0 }

    /**
     * How the chat with [phone] is protected, as the engine tells it: both
     * keys' fingerprints, to compare on the two phones, and the relays.
     */
    suspend fun encryptionInfo(phone: String): EncryptionInfo? {
        val link = linkFor(phone) ?: return null
        return runCatching {
            val contact = engine.call("get_chat_contacts", account, link.chatId).jsonArray.map { it.jsonPrimitive.int }.first { it != SELF }
            EncryptionInfo.parse(engine.call("get_contact_encryption_info", account, contact).jsonPrimitive.content)
        }.getOrNull()
    }

    /**
     * The user's own card for the people of the chat: the engine carries the
     * name, the photo and the status with the messages, end-to-end encrypted.
     * Null and empty take them back.
     */
    suspend fun shareCard(name: String?, avatar: String?, status: String) {
        if (!start()) return
        runCatching {
            engine.call("set_config", account, "displayname", name)
            engine.call("set_config", account, "selfavatar", avatar)
            engine.call("set_config", account, "selfstatus", status)
        }
    }

    /** What a proven person shares of themselves: the name they chose, their photo's file, their status. */
    suspend fun cardOf(phone: String): SharedCard? {
        val link = linkFor(phone) ?: return null
        if (!start()) return null
        return runCatching {
            val contact = engine.call("get_chat_contacts", account, link.chatId).jsonArray.map { it.jsonPrimitive.int }.first { it != SELF }
            val o = engine.call("get_contact", account, contact).jsonObject
            SharedCard(
                name = o["authName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                photo = o["profileImage"]?.jsonPrimitive?.contentOrNull,
                status = o["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.getOrNull()
    }

    /** Every member proven: the group goes over the encrypted chat. */
    fun groupReady(phones: List<String>): Boolean = phones.size > 1 && phones.all { linkFor(it) != null }

    /** The chat of one number or of a group, made for the group the first time. */
    private suspend fun chatOf(phones: List<String>): Int? {
        if (phones.size == 1) return linkFor(phones[0])?.chatId
        if (!groupReady(phones)) return null
        _groups.value[groupKey(phones)]?.let { return it.chatId }
        return runCatching {
            val named = runCatching { Telephony.Threads.getOrCreateThreadId(context, phones.toSet()) }.getOrNull()?.let { settings.current.groupNames[it] }
            val chat = engine.call("create_group_chat", account, named ?: DEFAULT_GROUP_NAME, false).jsonPrimitive.int
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
    /**
     * A chat of one person whose keys were just checked by an invite (theirs
     * joined by us, or ours joined by them), with no number bound: kept under
     * their chat address with the name they chose. Called only at the end of
     * such a handshake, never for someone merely writing. Their address.
     */
    private suspend fun bindNoNumber(chat: Int, contactId: Int?): String? = runCatching {
        val id = contactId ?: engine.call("get_chat_contacts", account, chat).jsonArray.map { it.jsonPrimitive.int }.single { it != SELF }
        val contact = engine.call("get_contact", account, id).jsonObject
        val address = contact["address"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val name = contact["displayName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != address }.orEmpty()
        // Kept under the address the conversation was opened with (a person with
        // several relays has several addresses): one conversation per person.
        val kept = _links.value.values.firstOrNull { '@' in it.phone && it.chatId == chat }?.phone ?: address
        _links.value.values.filter { '@' in it.phone && it.chatId == chat && it.phone != kept }.forEach { drop(it.phone) }
        save(kept) { (it ?: ChatLink(kept)).copy(address = address, chatId = chat, proven = true, myNonce = "", name = name.ifBlank { it?.name.orEmpty() }) }
        kept
    }.getOrNull()

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
        // The key exchange takes a few round trips over the relay.
        ChatLife.hold(2 * 60 * 1000L)
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

    /** Our invite link, for the Contacts app to hand over in person (phones touched); null when the chat is off. */
    suspend fun inviteLink(): String? {
        if (!enabled || !start()) return null
        return runCatching { engine.call("get_chat_securejoin_qr_code", account, null).jsonPrimitive.content }.getOrNull()
    }

    /**
     * An invite link or code from someone, with no number involved: joined,
     * and once the keys are exchanged (and checked: the link carries the
     * key's fingerprint) the person has a conversation of their own, under
     * their chat address. Their address, or null when it cannot be joined.
     */
    suspend fun joinByLink(link: String): String? {
        if (!enabled) return null
        // Rebuilt from its checked parts: nothing else in the link reaches the engine.
        val hello = Hello.fromLink(link.trim(), ByteArray(Hello.NONCE_BYTES)) ?: return null
        if (!start()) return null
        val chat = runCatching { engine.call("secure_join", account, hello.link()).jsonPrimitive.int }.getOrNull() ?: return null
        noNumber[chat] = System.currentTimeMillis()
        // A conversation at once, so the user can write while the keys are exchanged.
        save(hello.address) { (it ?: ChatLink(hello.address)).copy(address = hello.address, chatId = chat, proven = it?.proven == true) }
        return hello.address
    }

    /** The name a person without a number goes by, or null. */
    fun nameFor(address: String): String? = if ('@' !in address) null else _links.value[key(address)]?.name?.takeIf { it.isNotBlank() }

    /**
     * An invite received in person: the phones touched and the user saved the
     * card in the Contacts app. Joined, and [phone] bound to that chat once the
     * keys are exchanged, proven as by an SMS. A number proven to someone else
     * stays theirs: it changes only through the SMS proof.
     */
    suspend fun joinInPerson(link: String, phone: String): Boolean {
        if (!enabled || T9.clean(phone).count(Char::isDigit) < 7) return false
        // Rebuilt from its checked parts: nothing in the card adds to the link the engine joins.
        val hello = Hello.fromLink(link, ByteArray(Hello.NONCE_BYTES)) ?: return false
        val known = _links.value[key(phone)]
        if (known?.proven == true && known.address != hello.address) return false
        if (!start()) return false
        val chat = runCatching { engine.call("secure_join", account, hello.link()).jsonPrimitive.int }.getOrNull() ?: return false
        inPerson[chat] = phone
        return true
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
                    // Joined by a link with no number: the keys are checked, the chat theirs.
                    if (progress >= 1000 && chat != null && noNumber.remove(chat) != null) bindNoNumber(chat, contact)
                    // Met in person: the number is theirs, and the Contacts app may say so.
                    val phone = if (progress >= 1000) chat?.let { inPerson.remove(it) } else null
                    if (phone != null && chat != null) {
                        val address = runCatching { engine.call("get_contact", account, contact).jsonObject["address"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                        if (address != null) {
                            save(phone) { (it ?: ChatLink(phone)).copy(address = address, chatId = chat, proven = true, myNonce = "") }
                            withContext(Dispatchers.IO) { ContactsCards.verified(context, phone, true) }
                        }
                    }
                }
            }
            // Someone joined our invite (our code or link, no number): the keys are checked.
            "SecurejoinInviterProgress" -> {
                val progress = event.data["progress"]?.jsonPrimitive?.intOrNull ?: 0
                val contact = event.data["contactId"]?.jsonPrimitive?.intOrNull
                if (progress >= 1000 && contact != null) {
                    val chat = runCatching { engine.call("get_chat_id_by_contact_id", account, contact).jsonPrimitive.intOrNull }.getOrNull()
                    // Not when a number's proof by SMS is under way for this chat: that one binds the number.
                    // A number's proof follows its join within seconds: kept by address only if none came.
                    if (chat != null) scope.launch {
                        kotlinx.coroutines.delay(15_000)
                        if (_links.value.values.none { it.chatId == chat && it.proven }) { bindNoNumber(chat, contact); _changes.value++ }
                    }
                }
            }
            "MsgDelivered", "MsgFailed" -> if (msgId != null) {
                _outgoing.update { it - msgId }
                val ref = _refs.value[msgId]
                if (ref != null) {
                    mark(ref, failed = event.kind == "MsgFailed")
                    // Sent, its media is in Android's store: the engine's copy goes.
                    if (ref.mms && event.kind == "MsgDelivered") runCatching { engine.call("get_message", account, msgId).jsonObject["file"]?.jsonPrimitive?.contentOrNull?.let { f -> File(f).delete() } }
                }
            }
            "MsgRead" -> if (msgId != null) _refs.value[msgId]?.let { saveRef(msgId, it.copy(seen = true)) }
            // Edited or pinned on the other side, or a vanishing message gone.
            "MsgsChanged" -> if (msgId != null) _refs.value[msgId]?.let { refresh(msgId, it) }
            "MsgDeleted" -> if (msgId != null) _refs.value[msgId]?.let { forget(msgId, it) }
            // Calls: the engine's fields here are in snake case.
            "IncomingCall" -> {
                val id = event.data["msg_id"]?.jsonPrimitive?.intOrNull
                val chat = event.data["chat_id"]?.jsonPrimitive?.intOrNull
                val offer = event.data["place_call_info"]?.jsonPrimitive?.contentOrNull
                val video = event.data["has_video"]?.jsonPrimitive?.booleanOrNull == true
                // Only from a proven number: the call shows who it is.
                val phone = _links.value.values.firstOrNull { it.proven && it.chatId == chat }?.phone
                if (id != null && offer != null && phone != null) _calls.tryEmit(CallSignal.Incoming(id, phone, offer, video))
            }
            "OutgoingCallAccepted" -> {
                val id = event.data["msg_id"]?.jsonPrimitive?.intOrNull
                val answer = event.data["accept_call_info"]?.jsonPrimitive?.contentOrNull
                if (id != null && answer != null) _calls.tryEmit(CallSignal.Accepted(id, answer))
            }
            "IncomingCallAccepted" -> {
                val id = event.data["msg_id"]?.jsonPrimitive?.intOrNull
                if (id != null && event.data["from_this_device"]?.jsonPrimitive?.booleanOrNull != true) _calls.tryEmit(CallSignal.TakenElsewhere(id))
            }
            "CallEnded" -> {
                event.data["msg_id"]?.jsonPrimitive?.intOrNull?.let { _calls.tryEmit(CallSignal.Ended(it)) }
            }
            // A group renamed by one of its members: the name here follows.
            "ChatModified" -> event.data["chatId"]?.jsonPrimitive?.intOrNull?.let { chat ->
                val group = _groups.value.values.firstOrNull { it.chatId == chat } ?: return@let
                val name = runCatching { engine.call("get_basic_chat_info", account, chat).jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                if (!name.isNullOrBlank() && name != DEFAULT_GROUP_NAME) {
                    val thread = runCatching { Telephony.Threads.getOrCreateThreadId(context, group.phones.toSet()) }.getOrNull() ?: return@let
                    if (settings.current.groupNames[thread] != name) settings.update { it.copy(groupNames = it.groupNames + (thread to name)) }
                }
            }
            "ChatEphemeralTimerModified" -> {
                val chat = event.data["chatId"]?.jsonPrimitive?.intOrNull
                val timer = event.data["timer"]?.jsonPrimitive?.intOrNull
                if (chat != null && timer != null) timers[chat] = timer
            }
            "ReactionsChanged", "IncomingReaction" -> if (msgId != null) readReactions(msgId)
        }
        // Only what a screen shows: the engine's own logs and connections redrew every open conversation.
        if (event.kind in SHOWN) _changes.value++
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
        // A number bound to the chat wins over the same person kept by their address.
        val link = _links.value.values.filter { it.proven && it.chatId == chat }.minByOrNull { if ('@' in it.phone) 1 else 0 }
        val stored = (if (link != null) withContext(Dispatchers.IO) {
            if (file != null) {
                val data = File(file).takeIf { it.canRead() } ?: return@withContext null
                MmsStore.saveReceivedParts(context, link.phone, text, mime, data)?.let { RichRef(true, it.second, chat = chat) to it.first }
            } else {
                SmsStore.saveReceived(context, link.phone, text)?.let { RichRef(false, it.second, chat = chat) to it.first }
            }
        } else {
            // A group: kept as a group message from its sender, to the others.
            val group = _groups.value.values.firstOrNull { it.chatId == chat } ?: adopt(chat) ?: return
            val from = (message["sender"] as? JsonObject)?.get("address")?.jsonPrimitive?.contentOrNull
            val sender = _links.value.values.firstOrNull { it.proven && it.address == from }?.phone ?: return
            val others = group.phones.filter { key(it) != key(sender) }
            withContext(Dispatchers.IO) {
                val data = file?.let { File(it).takeIf { f -> f.canRead() } }
                MmsStore.saveReceivedParts(context, sender, text, if (data != null) mime else null, data, others)?.let { RichRef(true, it.second, chat = chat) to it.first }
            }
        }) ?: return
        saveRef(msgId, stored.first)
        // Kept in Android's store now, the media need not stay twice on the phone.
        if (file != null) runCatching { File(file).delete() }
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
                _outgoing.update { it + id }
                if (phones.size == 1) {
                    SmsStore.saveSending(context, phone, text)?.let { saveRef(id, withExpiry(id, RichRef(false, it, mine = true, chat = chatId))) }
                } else {
                    MmsStore.saveSendingParts(context, phones, text, null, null)?.let { saveRef(id, withExpiry(id, RichRef(true, it, mine = true, chat = chatId))) }
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
                    _outgoing.update { it + id }
                    MmsStore.saveSendingParts(context, phones, caption, a.contentType, copy)?.let { saveRef(id, withExpiry(id, RichRef(true, it, mine = true, chat = chatId))) }
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
            // Read now: the received ones of a vanishing chat start their
            // countdown; the engine says when each one ends.
            ids.takeLast(60).forEach { id ->
                val ref = _refs.value[id] ?: return@forEach
                if (ref.vanishAt > 0 && id in expiryKnown) return@forEach
                val now = withExpiry(id, ref.copy(chat = chatId))
                if (now.vanishAt > 0 || ref.vanishAt == 0L) expiryKnown += id
                if (now != ref) saveRef(id, now)
            }
        }
    }

    suspend fun react(messageId: Long, emoji: String?) {
        if (!start()) return
        runCatching { engine.call("send_reaction", account, messageId.toInt(), listOfNotNull(emoji)) }
        // The user's own reaction counts at once, a vote included.
        readReactions(messageId.toInt())
        _changes.value++
    }

    /** A poll shown: its votes read again from the engine, which keeps them whatever happened here. */
    suspend fun recount(mms: Boolean, id: Long) {
        val msgId = byRow[mms to id] ?: return
        if (!start()) return
        readReactions(msgId)
        _changes.value++
    }

    /** The reactions on [msgId] as the engine has them now: which, how many, and the user's own. */
    private suspend fun readReactions(msgId: Int) {
        val ref = _refs.value[msgId] ?: return
        val all = runCatching { engine.call("get_message_reactions", account, msgId).jsonObject["reactions"]?.jsonArray }
            .getOrNull()?.mapNotNull { it as? JsonObject }.orEmpty()
        val emojis = all.mapNotNull { it["emoji"]?.jsonPrimitive?.contentOrNull }
        val counts = all.mapNotNull { r -> r["emoji"]?.jsonPrimitive?.contentOrNull?.let { it to (r["count"]?.jsonPrimitive?.intOrNull ?: 1) } }.toMap()
        val mine = all.filter { it["isFromSelf"]?.jsonPrimitive?.booleanOrNull == true }.mapNotNull { it["emoji"]?.jsonPrimitive?.contentOrNull }
        saveRef(msgId, ref.copy(reactions = emojis, counts = counts, myReactions = mine))
    }

    /** A group's name, for all its members when it goes over the encrypted chat. */
    suspend fun nameGroup(phones: List<String>, name: String) {
        if (!groupReady(phones) || !start()) return
        val chat = chatOf(phones) ?: return
        runCatching { engine.call("set_chat_name", account, chat, name.take(80)) }
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
        return runCatching { engine.call("get_chat_ephemeral_timer", account, chatId).jsonPrimitive.int }.getOrDefault(0).also { timers[chatId] = it }
    }

    /** Messages with [phones] vanish [seconds] after they are read, on both sides; 0 turns it off. */
    suspend fun setTimer(phones: List<String>, seconds: Int): Boolean {
        if (!start()) return false
        val chatId = chatOf(phones) ?: return false
        return runCatching { engine.call("set_chat_ephemeral_timer", account, chatId, seconds) }.isSuccess.also {
            if (it) {
                timers[chatId] = seconds
                _changes.value++
            }
        }
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
        publish(_refs.value - msgId)
        runCatching { refStore.remove(listOf(msgId)) }
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
        val link = change(_links.value[k]).copy(phone = phone)
        // A number proven for a chat takes the place of the same person kept without one.
        val others = if ('@' !in phone && link.proven) _links.value.filterValues { '@' !in it.phone || it.chatId != link.chatId } else _links.value
        val updated = others + (k to link)
        _links.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(updated)) }
    }

    private fun drop(phone: String) {
        val updated = _links.value - key(phone)
        _links.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(updated)) }
    }

    private fun saveRef(msgId: Int, ref: RichRef) {
        // Android reuses a deleted message's number: the newest message holding it wins.
        val stale = byRow[ref.mms to ref.id]?.takeIf { it != msgId }
        publish((if (stale != null) _refs.value - stale else _refs.value) + (msgId to ref))
        runCatching { refStore.put(msgId, ref) }
    }

    private fun saveGroup(group: ChatGroup) {
        val updated = _groups.value + (groupKey(group.phones) to group)
        _groups.value = updated
        runCatching { groupsFile.writeTextAtomically(json.encodeToString(updated)) }
    }

    private fun loadGroups(): Map<String, ChatGroup> =
        runCatching { json.decodeFromString<Map<String, ChatGroup>>(groupsFile.readText()) }.getOrDefault(emptyMap())

    private fun groupKey(phones: List<String>) = phones.map(::key).sorted().joinToString(",")

    /** From the database; the JSON file of older versions is moved into it once. */
    private fun loadRefs(): Map<Int, RichRef> {
        if (refsFile.exists()) {
            runCatching { json.decodeFromString<Map<Int, RichRef>>(refsFile.readText()) }.getOrNull()?.let { old ->
                runCatching { refStore.putAll(old) }.onSuccess { refsFile.delete() }
            }
        }
        return runCatching { refStore.all() }.getOrDefault(emptyMap())
    }

    private fun load(): Map<String, ChatLink> =
        runCatching { json.decodeFromString<Map<String, ChatLink>>(file.readText()) }.getOrDefault(emptyMap())

    /** A number by its last nine digits; a person without a number by their chat address. */
    private fun key(phone: String) = if ('@' in phone) phone.trim().lowercase() else T9.clean(phone).removePrefix("+").takeLast(9)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        /** The port the data SMS carrying an invite go to. */
        const val PORT: Short = 18471
        /** What a group is called in the chat until someone names it. */
        private const val DEFAULT_GROUP_NAME = "SMS"
        /** The engine's id for the user themselves among a chat's contacts. */
        private const val SELF = 1
        /** The relays a profile keeps: the sending one and one backup. */
        private const val MAX_RELAYS = 2
        /** A number without SMS is asked again only a month later. */
        private const val ASK_AGAIN_MS = 30L * 24 * 60 * 60 * 1000
        /** The engine's events that change what a screen shows. */
        private val SHOWN = setOf(
            "IncomingMsg", "MsgDelivered", "MsgFailed", "MsgRead", "MsgsChanged", "MsgDeleted",
            "ReactionsChanged", "IncomingReaction", "ChatModified", "ChatEphemeralTimerModified",
            "SecurejoinJoinerProgress", "SecurejoinInviterProgress", "TransportsModified"
        )
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.boolean(): Boolean = booleanOrNull ?: false

/** What the engine says of a chat's encryption, without its random addresses. */
data class EncryptionInfo(val encrypted: Boolean, val mine: String?, val theirs: String?, val relays: List<String>) {
    companion object {
        fun parse(text: String): EncryptionInfo {
            val lines = text.lines().map { it.trim() }
            val fingerprints = mutableListOf<Pair<Boolean, String>>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.removeSuffix(":").endsWith(")") && line.contains("(") && i + 1 < lines.size && lines[i + 1].matches(Regex("[0-9A-F ]{4,}"))) {
                    val print = lines.drop(i + 1).takeWhile { it.matches(Regex("[0-9A-F ]{4,}")) }.joinToString(" ")
                    fingerprints += line.startsWith("Me ") to print
                }
                i++
            }
            val relays = lines.dropWhile { it != "Relays:" }.drop(1).takeWhile { it.isNotEmpty() }
                .map { it.substringAfter('@') }.distinct()
            return EncryptionInfo(
                encrypted = text.contains("end-to-end encrypted", ignoreCase = true),
                mine = fingerprints.firstOrNull { it.first }?.second,
                theirs = fingerprints.firstOrNull { !it.first }?.second,
                relays = relays
            )
        }
    }
}

package com.yaz.sms.core.sms

import android.app.role.RoleManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.yaz.sms.core.common.takeWhole
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.core.mms.Attachment
import com.yaz.sms.core.mms.MediaPrivacy
import com.yaz.sms.core.mms.MmsTransport
import com.yaz.sms.data.settings.BroadcastStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Messages waiting their few seconds before they go (Gmail's Undo send): the countdown belongs
 * to the app, not to the conversation's screen, so leaving the conversation never cancels it and
 * the message leaves at the end of its delay.
 *
 * The scope is the application-scoped CoroutineScope injected into the class, Android's recipe for
 * work that must not be cancelled with a screen:
 * https://github.com/android/nowinandroid/blob/main/core/common/src/main/kotlin/com/google/samples/apps/nowinandroid/core/network/di/CoroutineScopesModule.kt
 * (`@ApplicationScope`: CoroutineScope(SupervisorJob() + dispatcher)) and
 * https://medium.com/androiddevelopers/coroutines-patterns-for-work-that-shouldnt-be-cancelled-e26c40f142ad
 * (the class takes an external scope and launches the work in it). The waiting list as a StateFlow
 * the screen reads is the state-holder pattern of
 * https://github.com/android/nowinandroid core/data repositories (flows read by the UI).
 * The sending itself is what ThreadScreen's sendNow did, moved here unchanged.
 */
class PendingSends(context: Context, private val scope: CoroutineScope, private val chat: RichChat) {

    private val context = context.applicationContext

    /** A message in its delay, with what sending it needs. [dueAt] is on SystemClock.elapsedRealtime's clock. */
    data class Waiting(
        val key: Long,
        val text: String,
        val sub: Int,
        val attachments: List<Attachment>,
        val quoted: String?,
        val effect: Effects.Effect?,
        val people: List<String>,
        val thread: Long?,
        /** The words as the chat's members read them (mentions renamed), or null. */
        val wire: String?,
        val seconds: Int,
        val dueAt: Long,
        /** False for a message only spaced out (Send again with Undo send off): no countdown look, no taking back. */
        val undo: Boolean = true
    ) {
        /** Milliseconds left of the delay. */
        fun remaining(): Long = (dueAt - SystemClock.elapsedRealtime()).coerceIn(0L, seconds * 1000L)
    }

    /** What did not leave, to go back into the field of its conversation: the words and the attachments with them. */
    data class Returned(val people: List<String>, val text: String, val attachments: List<Attachment> = emptyList())

    private val lock = Any()
    private val jobs = HashMap<Long, Job>()
    private val _waiting = MutableStateFlow<List<Waiting>>(emptyList())

    /** Every message in its delay, all conversations; the screen keeps those of its people. */
    val waiting: StateFlow<List<Waiting>> = _waiting.asStateFlow()

    private val _sent = MutableSharedFlow<Waiting>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A message whose delay ended and that is leaving: the screen of its conversation, if open, plays its haptic. */
    val sent: SharedFlow<Waiting> = _sent.asSharedFlow()

    private val _returned = MutableStateFlow<List<Returned>>(emptyList())

    /** What a refused send gives back, until a screen of that conversation takes it. */
    val returned: StateFlow<List<Returned>> = _returned.asStateFlow()

    /** Puts [p] in its delay ([p].seconds, above 0), then sends it, whatever happens to the screen. */
    fun queue(p: Waiting) {
        keepCopy(p)
        synchronized(lock) {
            _waiting.update { it + p }
            jobs[p.key] = scope.launch {
                delay(p.seconds * 1000L)
                // Taken back meanwhile: nothing to send.
                val mine = take(p.key)
                synchronized(lock) { jobs.remove(p.key) }
                if (mine == null) return@launch
                // The copy kept for a dead app goes first; already taken by the alarm (the app was
                // frozen past its time): that send is the one, never a second.
                if (covered(mine) && !dropCopy(mine, mustFind = true)) return@launch
                _sent.tryEmit(mine)
                deliver(mine)
            }
        }
    }

    /**
     * If the app dies during the countdown the message is not lost: a copy sits in send later
     * (Timed, Fossify Messages' scheduled messages with Android's alarm recipe), one minute after
     * the delay ends, so the alarm only ever fires when this process is gone. Pictures and chat
     * addresses ("@", no number to send an SMS to) are not covered.
     */
    private fun covered(p: Waiting) = p.attachments.isEmpty() && p.people.none { '@' in it }

    private val timedLock = Any()

    private fun keepCopy(p: Waiting) {
        if (!covered(p)) return
        // A quote is folded into the words as an SMS carries it (the alarm cannot send a quote).
        val text = p.quoted?.let { "«${excerpt(it)}»\n${p.text}" } ?: p.text
        synchronized(timedLock) {
            Timed.schedule(context, Scheduled(p.key, p.people, text, System.currentTimeMillis() + p.seconds * 1000L + 60_000L, p.sub, backup = true))
        }
    }

    /** Removes the copy and its alarm; with [mustFind], false when the alarm had already taken it. */
    private fun dropCopy(p: Waiting, mustFind: Boolean = false): Boolean = synchronized(timedLock) {
        if (mustFind && Timed.take(context, p.key) == null) return false
        Timed.cancel(context, p.key)
        true
    }

    /** Sends [p] at once (no delay set). */
    fun sendNow(p: Waiting) {
        scope.launch { deliver(p) }
    }

    /** Takes [key] back before it goes; the message, or null when it already left. */
    fun cancel(key: Long): Waiting? {
        val p = take(key)
        synchronized(lock) { jobs.remove(key) }?.cancel()
        if (p != null && covered(p)) dropCopy(p)
        return p
    }

    /** The texts given back for [people], once: the screen puts them in its field. */
    fun takeReturned(people: List<String>): List<String> {
        val set = people.toSet()
        var mine = emptyList<Returned>()
        _returned.update { all ->
            mine = all.filter { it.people.toSet() == set }
            all - mine.toSet()
        }
        return mine.map { it.text }
    }

    private fun take(key: Long): Waiting? = synchronized(lock) {
        val p = _waiting.value.firstOrNull { it.key == key } ?: return null
        _waiting.update { all -> all.filterNot { it.key == key } }
        p
    }

    // A message that did not leave is given back whole, words and attachments, as ProcessSentMessageAction.processResult
    // keeps a failed message with all its parts (https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/action/ProcessSentMessageAction.java#L201-L289).
    private fun giveBack(p: Waiting) {
        _returned.update { it + Returned(p.people, p.text, p.attachments) }
    }

    /** What ThreadScreen.sendNow did, with the people, thread and sub carried by [p]. */
    private suspend fun deliver(p: Waiting) {
        // What did not leave is said on the screen, whatever thread this runs on, with its reason.
        val notSent = { reason: String ->
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, "Not sent: $reason", Toast.LENGTH_LONG).show() }
        }
        try {
            withContext(Dispatchers.IO) {
                // Photos leave without their place, camera and dates.
                val attachments = p.attachments.map { MediaPrivacy.clean(context, it) }
                val people = p.people
                val group = people.size > 1
                val to = people.firstOrNull().orEmpty()
                // A group the user left sends nothing, not even by SMS to the same people.
                if (group && chat.groupOf(people)?.left == true) return@withContext
                val linked = if (group || to.isBlank()) null else chat.linkFor(to)
                val encrypted = linked != null || (group && chat.groupReady(people))
                // Over the rich chat when the number has it, quote included;
                // else a group or a picture as a picture message, the rest as SMS.
                // The effect travels over the encrypted chat; an SMS stays plain (and cheap).
                val wire = p.wire?.let { w -> p.effect?.let { Effects.mark(w, it) } ?: w }
                if (encrypted) {
                    if (chat.send(people, p.effect?.let { Effects.mark(p.text, it) } ?: p.text, attachments, p.quoted, wire)) return@withContext
                    // The link is proven (or the group is ready): a failed chat send never falls back to a plain
                    // SMS or MMS, it goes back to its field.
                    giveBack(p)
                    notSent("the encrypted chat is not available")
                    return@withContext
                }
                // Someone with no number has the chat only: never an SMS to an address.
                if (people.any { '@' in it }) {
                    giveBack(p)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Not sent yet: the keys are still being exchanged. Try again in a moment.", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@withContext
                }
                // Sending by SMS or MMS needs the SMS role, as ConversationFragment.isReadyForAction asks
                // (https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/ui/conversation/ConversationFragment.java#L1043-L1045).
                if (context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) != true) {
                    giveBack(p)
                    notSent("SMS is not your messaging app")
                    return@withContext
                }
                val text = p.quoted?.let { "«${excerpt(it)}»\n${p.text}" } ?: p.text
                // A group marked "One SMS each": words alone go as AOSP's broadcast; pictures stay a group MMS.
                if (group && attachments.isEmpty()) {
                    val thread = p.thread ?: runCatching { android.provider.Telephony.Threads.getOrCreateThreadId(context, people.toSet()) }.getOrNull()
                    if (thread != null && BroadcastStore.of(context).contains(thread)) {
                        if (!broadcast(thread, people, text, p.sub)) {
                            giveBack(p)
                            notSent("no message could be written to the phone's messages")
                        }
                        return@withContext
                    }
                }
                val sent = if (group || attachments.isNotEmpty()) MmsTransport.send(context, people, text, attachments, p.sub, p.thread)
                else SmsSender.send(context, to, text, p.sub, p.thread)
                if (sent == null) {
                    // Never silent: the whole message goes back, words and attachments, and the user is told.
                    giveBack(p)
                    notSent("the message could not be written to the phone's messages")
                    return@withContext
                }
                // The first message to a number asks, unseen, whether it has SMS too; the message has left,
                // so a failure here never gives it back.
                if (!group) try { chat.hello(to) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A send that failed unexpectedly is given back, never lost, and said.
            giveBack(p)
            notSent("something went wrong while sending")
        }
    }

    /**
     * One SMS to each person, as AOSP Messaging's broadcast SMS (InsertNewMessageAction.executeAction and
     * insertBroadcastSmsMessage, https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/datamodel/action/InsertNewMessageAction.java):
     * first one sent record in the group's thread, its address the people joined by a space, status complete,
     * dated one millisecond later; then insertSendingSmsMessage with no conversation, so SmsSender gives each
     * message the thread of that person alone (getOrCreateSmsThreadId) and it is sent and reported there.
     */
    internal fun broadcast(thread: Long, people: List<String>, text: String, sub: Int): Boolean {
        val own = com.yaz.sms.core.mms.OwnNumbers.of(context)
        val others = people.filterNot { com.yaz.sms.core.mms.OwnNumbers.isOwn(it, own) }.distinct()
        val body = text.trim(' ', '\n', '\t', '\r')
        if (others.isEmpty() || body.isEmpty()) return false
        val now = System.currentTimeMillis()
        val subId = if (sub != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) sub else android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()
        runCatching {
            context.contentResolver.insert(
                android.provider.Telephony.Sms.Sent.CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.Telephony.Sms.ADDRESS, others.joinToString(" "))
                    put(android.provider.Telephony.Sms.BODY, body)
                    put(android.provider.Telephony.Sms.DATE, now + 1)
                    put(android.provider.Telephony.Sms.READ, 1)
                    put(android.provider.Telephony.Sms.SEEN, 1)
                    put(android.provider.Telephony.Sms.STATUS, android.provider.Telephony.Sms.STATUS_COMPLETE)
                    put(android.provider.Telephony.Sms.THREAD_ID, thread)
                    put(android.provider.Telephony.Sms.SUBSCRIPTION_ID, subId)
                }
            )
        }
        // True when at least one of the messages was written; each is sent and reported in its own thread.
        return others.map { SmsSender.send(context, it, body, sub) }.any { it != null }
    }

    /** The start of a quoted message, short enough for the first line. */
    // Cut on a whole grapheme, never inside an emoji: Signal's BreakIteratorCompat
    // (https://github.com/signalapp/Signal-Android/blob/main/core/util/src/main/java/org/signal/core/util/BreakIteratorCompat.java), core/common/Graphemes.kt.
    private fun excerpt(text: String): String = text.replace('\n', ' ').let { if (it.length > 60) it.takeWhole(58).trimEnd() + "…" else it }
}

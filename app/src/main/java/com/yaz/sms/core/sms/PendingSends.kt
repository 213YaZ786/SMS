package com.yaz.sms.core.sms

import android.content.Context
import android.os.SystemClock
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.core.mms.Attachment
import com.yaz.sms.core.mms.MediaPrivacy
import com.yaz.sms.core.mms.MmsTransport
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
        val dueAt: Long
    ) {
        /** Milliseconds left of the delay. */
        fun remaining(): Long = (dueAt - SystemClock.elapsedRealtime()).coerceIn(0L, seconds * 1000L)
    }

    /** Text that did not leave, to go back into the field of its conversation. */
    data class Returned(val people: List<String>, val text: String)

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

    private fun giveBack(p: Waiting) {
        _returned.update { it + Returned(p.people, p.text) }
    }

    /** What ThreadScreen.sendNow did, with the people, thread and sub carried by [p]. */
    private suspend fun deliver(p: Waiting) {
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
                if (encrypted && chat.send(people, p.effect?.let { Effects.mark(p.text, it) } ?: p.text, attachments, p.quoted, wire)) return@withContext
                // Someone with no number has the chat only: never an SMS to an address.
                if (people.any { '@' in it }) {
                    giveBack(p)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Not sent yet: the keys are still being exchanged. Try again in a moment.", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@withContext
                }
                val text = p.quoted?.let { "«${excerpt(it)}»\n${p.text}" } ?: p.text
                if (group || attachments.isNotEmpty()) MmsTransport.send(context, people, text, attachments, p.sub, p.thread)
                else SmsSender.send(context, to, text, p.sub, p.thread)
                // The first message to a number asks, unseen, whether it has SMS too.
                if (!group) chat.hello(to)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A send that failed unexpectedly is given back, never lost.
            giveBack(p)
        }
    }

    /** The start of a quoted message, short enough for the first line. */
    private fun excerpt(text: String): String = text.replace('\n', ' ').let { if (it.length > 60) it.take(58).trimEnd() + "…" else it }
}

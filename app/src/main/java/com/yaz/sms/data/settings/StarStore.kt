package com.yaz.sms.data.settings

import android.content.Context
import com.yaz.sms.core.common.writeTextAtomically
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the file holds: the starred messages' uids ("sms/7", "mms/45") with their thread id. */
@Serializable
data class Starred(val items: Map<String, Long> = emptyMap())

/**
 * The rules of the starred set, pure. Android's SMS/MMS store has no starred
 * column, so the set lives in the app. The model is Zulip's (Apache-2.0,
 * https://github.com/zulip/zulip-flutter/blob/main/lib/model/message.dart):
 * a message store holding the set of starred message ids
 * (MessageStoreImpl.starredMessages), a flag added or removed by the star
 * action, an id dropped when its message is deleted. The thread id kept with
 * each uid is this app's addition: Android reuses a deleted row's _id, so a uid
 * found in another thread is not the message that was starred.
 */
object Stars {
    /** The most messages kept starred; the file stays small. */
    const val MAX = 5000

    private val uidShape = Regex("(sms|mms)/[0-9]{1,18}")

    /**
     * The flag added when absent, removed when present: zulip-flutter
     * handleUpdateMessageFlagsEvent (UpdateMessageFlagsAddEvent / RemoveEvent,
     * starredMessages.addAll / removeAll) in
     * https://github.com/zulip/zulip-flutter/blob/main/lib/model/message.dart
     * and StarButton (flips between star and unstar) in
     * https://github.com/zulip/zulip-flutter/blob/main/lib/widgets/action_sheet.dart
     */
    fun toggle(stars: Map<String, Long>, uid: String, thread: Long): Map<String, Long> =
        when {
            uid in stars -> stars - uid
            !uidShape.matches(uid) || stars.size >= MAX -> stars
            else -> stars + (uid to thread)
        }

    /**
     * A message that is gone loses its star: zulip-flutter handleDeleteMessageEvent
     * (starredMessages.remove) in
     * https://github.com/zulip/zulip-flutter/blob/main/lib/model/message.dart
     */
    fun remove(stars: Map<String, Long>, uid: String): Map<String, Long> = stars - uid

    /**
     * Only the stars whose message still exists, in the same thread: [alive] is
     * what the store answered for the uids asked (uid to thread id). The same
     * removal as [remove], done when the list is read, since Android's store
     * does not tell this app of a deletion made elsewhere.
     */
    fun alive(stars: Map<String, Long>, alive: Map<String, Long>): Map<String, Long> =
        stars.filter { (uid, thread) -> alive[uid] == thread }

    /**
     * The starred messages of one conversation: zulip-flutter StarredMessagesNarrow
     * (containsMessage: the message carries the flag) narrowed by a conversation, in
     * https://github.com/zulip/zulip-flutter/blob/main/lib/model/narrow.dart
     */
    fun inThread(stars: Map<String, Long>, thread: Long): Set<String> =
        stars.filterValues { it == thread }.keys

    /** A file read back: what is not a uid with a thread id is dropped, the count capped. */
    fun parse(text: String, json: Json): Map<String, Long> = runCatching {
        json.decodeFromString<Starred>(text).items
            .filter { (uid, thread) -> uidShape.matches(uid) && thread >= 0 }
            .entries.take(MAX).associate { it.key to it.value }
    }.getOrDefault(emptyMap())
}

/**
 * The starred messages, kept in a small file of their own and written
 * atomically, the way this app's SettingsStore keeps its preferences
 * (data/settings/SettingsStore.kt and core/common/AtomicWrite.kt: a StateFlow
 * over the loaded file, the write made beside it then moved over it), the
 * store of zulip-flutter (https://github.com/zulip/zulip-flutter/blob/main/lib/model/message.dart,
 * MessageStoreImpl) being the model of what it holds. Nothing here leaves the device.
 */
class StarStore(context: Context) {

    private val file = File(context.filesDir, "starred.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _stars = MutableStateFlow(load())

    /** Starred uid to its thread id. */
    val stars: StateFlow<Map<String, Long>> = _stars.asStateFlow()

    private fun load(): Map<String, Long> =
        if (!file.exists()) emptyMap() else Stars.parse(runCatching { file.readText() }.getOrDefault(""), json)

    /** The write of SettingsStore.update: the new value in the flow, then the file replaced at once. */
    @Synchronized
    private fun update(transform: (Map<String, Long>) -> Map<String, Long>) {
        val updated = transform(_stars.value)
        if (updated == _stars.value) return
        _stars.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(Starred.serializer(), Starred(updated))) }
    }

    /** Star the message, or take the star off. */
    fun toggle(uid: String, thread: Long) = update { Stars.toggle(it, uid, thread) }

    fun remove(uid: String) = update { Stars.remove(it, uid) }

    /** Keep only the stars of messages the store still holds (see [Stars.alive]). */
    fun keepAlive(alive: Map<String, Long>) = update { Stars.alive(it, alive) }
}

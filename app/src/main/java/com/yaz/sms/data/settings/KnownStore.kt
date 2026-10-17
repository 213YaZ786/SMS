package com.yaz.sms.data.settings

import android.content.Context
import com.yaz.sms.core.common.writeTextAtomically
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the file holds: the senders marked known, by Screening.senderKey. */
@Serializable
data class KnownFile(val items: Set<String> = emptySet())

/**
 * The rules of the set of senders marked known, pure. The model is Fossify Messages' set of
 * strings kept in its config (GPL-3.0, https://github.com/FossifyOrg/Messages,
 * app/src/main/kotlin/org/fossify/messages/helpers/Config.kt: blockedKeywords, with
 * addBlockedKeyword and removeBlockedKeyword adding to the set and taking from it), here a set
 * of senders; the count capped and a read file filtered as StarStore's Stars.parse does.
 */
object Knowns {
    /** The most senders kept; the file stays small. */
    const val MAX = 5000

    /** A key is a name or the last nine digits of a number: short, and never empty. */
    private const val KEY_MAX = 64

    /** Fossify addBlockedKeyword: the set with the sender added (a key that is no key leaves it as it is). */
    fun add(known: Set<String>, key: String): Set<String> =
        if (key.isBlank() || key.length > KEY_MAX || known.size >= MAX) known else known + key

    /** Fossify removeBlockedKeyword: the set without the sender. */
    fun remove(known: Set<String>, key: String): Set<String> = known - key

    /** A file read back: what is no key is dropped, the count capped. */
    fun parse(text: String, json: Json): Set<String> = runCatching {
        json.decodeFromString<KnownFile>(text).items.filter { it.isNotBlank() && it.length <= KEY_MAX }.take(MAX).toSet()
    }.getOrDefault(emptySet())
}

/**
 * The senders marked known, kept in a small file of their own and written atomically, the way
 * StarStore keeps the starred messages (data/settings/StarStore.kt: a StateFlow over the loaded
 * file, the write made beside it then moved over it by core/common/AtomicWrite.kt). Nothing
 * here leaves the device.
 */
class KnownStore(context: Context) {

    private val file = File(context.filesDir, "known.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _known = MutableStateFlow(load())

    /** The senders marked known, as Screening.senderKey writes them. */
    val known: StateFlow<Set<String>> = _known.asStateFlow()

    private fun load(): Set<String> =
        if (!file.exists()) emptySet() else Knowns.parse(runCatching { file.readText() }.getOrDefault(""), json)

    /** The write of StarStore.update: the new value in the flow, then the file replaced at once. */
    @Synchronized
    private fun update(transform: (Set<String>) -> Set<String>) {
        val updated = transform(_known.value)
        if (updated == _known.value) return
        _known.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(KnownFile.serializer(), KnownFile(updated))) }
    }

    /** Mark the sender known. */
    fun mark(key: String) = update { Knowns.add(it, key) }

    /** Take the mark off. */
    fun unmark(key: String) = update { Knowns.remove(it, key) }
}

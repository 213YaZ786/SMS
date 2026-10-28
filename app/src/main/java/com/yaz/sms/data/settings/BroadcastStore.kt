package com.yaz.sms.data.settings

import android.content.Context
import com.yaz.sms.core.common.writeTextAtomically
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the file holds: the group threads whose messages go as one SMS to each person. */
@Serializable
data class BroadcastFile(val threads: Set<Long> = emptySet())

/**
 * The group threads whose text messages go as one SMS to each person instead of one group MMS.
 * AOSP Messaging makes that choice for the whole app (its "Group messaging" setting, off: a message
 * to several people is a broadcast SMS, InsertNewMessageAction.executeAction,
 * https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/datamodel/action/InsertNewMessageAction.java);
 * here the choice is the user's per thread. The file is kept the way this app's TrashStore keeps
 * trash.json (data/settings/TrashStore.kt: a StateFlow over the loaded file, the write made beside it
 * then moved over it, core/common/AtomicWrite.kt). Nothing here leaves the device.
 */
class BroadcastStore private constructor(context: Context) {

    private val file = File(context.filesDir, "broadcast.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _threads = MutableStateFlow(load())

    /** The marked thread ids. */
    val threads: StateFlow<Set<Long>> = _threads.asStateFlow()

    /** TrashStore.load: a file unreadable or damaged is an empty set; ids that are not threads are dropped. */
    private fun load(): Set<Long> {
        val text = if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
        return runCatching { json.decodeFromString<BroadcastFile>(text).threads }.getOrDefault(emptySet())
            .filter { it >= 0 }.take(MAX).toSet()
    }

    fun contains(thread: Long): Boolean = thread in _threads.value

    /** TrashStore.update: the new value in the flow, then the file replaced at once. */
    @Synchronized
    private fun update(transform: (Set<Long>) -> Set<Long>) {
        val updated = transform(_threads.value)
        if (updated == _threads.value) return
        _threads.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(BroadcastFile.serializer(), BroadcastFile(updated))) }
    }

    /** Marks [thread]: its text messages go as one SMS to each. */
    fun mark(thread: Long) {
        if (thread >= 0 && thread !in _threads.value && _threads.value.size < MAX) update { it + thread }
    }

    /** Back to the group MMS. */
    fun unmark(thread: Long) = update { it - thread }

    companion object {
        /** The most threads kept; the file stays small. */
        const val MAX = 2000

        @Volatile
        private var instance: BroadcastStore? = null

        /** One store per process, so a screen and PendingSends see the same marks. */
        fun of(context: Context): BroadcastStore =
            instance ?: synchronized(this) { instance ?: BroadcastStore(context.applicationContext).also { instance = it } }
    }
}

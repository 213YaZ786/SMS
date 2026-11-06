package com.yaz.sms.data.settings

import android.content.Context
import android.util.Log
import com.yaz.sms.core.common.writeTextAtomically
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One conversation in the Trash: when it was put there, and the date of its
 * newest message at that moment. Messages up to [cutoff] are the trashed ones;
 * a newer message belongs to a conversation that is back in the inbox.
 */
@Serializable
data class Trashed(val at: Long, val cutoff: Long)

/** What the file holds: the trashed conversations by thread id, and when expired ones were last swept. */
@Serializable
data class TrashFile(val items: Map<Long, Trashed> = emptyMap(), val swept: Long = 0)

/**
 * The rules of the Trash, pure. Android's SMS/MMS store has no trash, so the
 * trashed conversations are listed in the app and nothing is deleted until the
 * 30 days are over. The sources:
 * - the keep duration, 30 days: AOSP MediaProvider FileUtils.DEFAULT_DURATION_TRASHED
 *   (https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/util/FileUtils.java)
 *   and Aves TrashMixin.binKeepDuration
 *   (https://github.com/deckerst/aves/blob/develop/lib/model/source/trash.dart);
 * - expired once the keep duration is before now, and the days left rounded to the
 *   nearest day: Aves AvesEntry.isExpiredTrash and trashDaysLeft
 *   (https://github.com/deckerst/aves/blob/develop/lib/model/entry/extensions/props.dart),
 *   with Duration.inHumanDays (https://github.com/deckerst/aves/blob/develop/lib/utils/time_utils.dart);
 * - items flagged with an expiry and deleted once it has passed: AOSP MediaProvider
 *   deleteOrExtendExpiredItems (DATE_EXPIRES < now), here without its one-week extension;
 * - Fossify Messages' recycle bin (GPL-3.0, ported as SMS is GPL-3.0,
 *   https://github.com/FossifyOrg/Messages): models/RecycleBinMessage.kt (an entry is an id and
 *   its deleted_ts) becomes [Trashed] per conversation with the cutoff Google Messages' whole
 *   conversation trash needs; interfaces/MessagesDao.kt getOldRecycleBinMessages (deleted_ts
 *   before now minus a month) becomes [expired]; extensions/Context.kt moveMessageToRecycleBin,
 *   restoreMessageFromRecycleBin, emptyMessagesRecycleBin and
 *   checkAndDeleteOldRecycleBinMessages (a check at most once a day, 30 days) become [add],
 *   [remove], the whole-trash deletion and [dueForSweep] / TrashStore.sweep. Fossify stamps its
 *   last check before it deletes; here a check whose deletion failed is not stamped.
 */
object Trash {
    /** AOSP FileUtils.DEFAULT_DURATION_TRASHED: 30 days. */
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** The most conversations kept; the file stays small. */
    const val MAX = 5000

    /** A newest-message date further ahead than this is not a date; such an entry is dropped when read. */
    private const val FUTURE_MS = 366L * DAY_MS

    /** Aves isExpiredTrash: the keep duration added to the day it was trashed is before now. */
    fun isExpired(t: Trashed, now: Long): Boolean = t.at + KEEP_MS < now

    /**
     * Aves trashDaysLeft: what remains of the keep duration, in days rounded to the nearest
     * (Duration.inHumanDays), never below 0.
     */
    fun daysLeft(t: Trashed, now: Long): Int =
        Math.round((t.at + KEEP_MS - now).toDouble() / DAY_MS).toInt().coerceAtLeast(0)

    /** The words under a trashed conversation. */
    fun daysLabel(days: Int): String = when {
        days <= 0 -> "Less than a day left"
        days == 1 -> "1 day left"
        else -> "$days days left"
    }

    /**
     * Conversations put in the Trash now: [cutoffs] is thread id to the date of its newest message.
     * One already there is trashed again from this moment (AOSP MediaProvider sets DATE_EXPIRES
     * to now plus the keep duration each time IS_TRASHED is set).
     */
    fun add(items: Map<Long, Trashed>, cutoffs: Map<Long, Long>, now: Long): Map<Long, Trashed> {
        val room = Math.max(0, MAX - items.keys.count { it !in cutoffs })
        return items + cutoffs.entries.take(room).filter { it.key >= 0 && it.value >= 0 }.associate { it.key to Trashed(now, it.value) }
    }

    /** Out of the Trash (restored, or deleted for good): the same removal. */
    fun remove(items: Map<Long, Trashed>, threads: Collection<Long>): Map<Long, Trashed> = items - threads.toSet()

    /** Aves deleteExpiredTrash: the trashed entries that are expired. */
    fun expired(items: Map<Long, Trashed>, now: Long): Map<Long, Trashed> = items.filterValues { isExpired(it, now) }

    /**
     * Only the conversations Android's store still holds: [existing] is every thread id it
     * answered. Done when the list is read, since the store does not tell this app of a
     * conversation deleted elsewhere.
     */
    fun alive(items: Map<Long, Trashed>, existing: Set<Long>): Map<Long, Trashed> = items.filterKeys { it in existing }

    /**
     * Expired items are looked for at most once a day, when the app opens: Fossify Messages'
     * checkAndDeleteOldRecycleBinMessages (lastRecycleBinCheck older than a day, called from
     * MainActivity.onCreate; extensions/Context.kt) and the cleanup of Aves
     * MediaStoreSource.loadEntries (once, at load, no background work). A clock set back checks again.
     */
    fun dueForSweep(swept: Long, now: Long): Boolean = swept > now || now - swept >= DAY_MS

    /** A file read back: what is not a thread with a plausible date pair is dropped, the count capped. */
    fun parse(text: String, json: Json, now: Long): TrashFile = runCatching {
        val file = json.decodeFromString<TrashFile>(text)
        TrashFile(
            items = file.items
                .filter { (thread, t) -> thread >= 0 && t.at > 0 && t.cutoff >= 0 && t.cutoff <= now + FUTURE_MS }
                .entries.take(MAX).associate { (thread, t) -> thread to Trashed(minOf(t.at, now), t.cutoff) },
            swept = file.swept.coerceIn(0, now)
        )
    }.getOrDefault(TrashFile())
}

/**
 * The trashed conversations, kept in a small file of their own and written
 * atomically, the way this app's SettingsStore keeps its preferences
 * (data/settings/SettingsStore.kt and core/common/AtomicWrite.kt: a StateFlow
 * over the loaded file, the write made beside it then moved over it), as StarStore
 * keeps the starred messages. Nothing here leaves the device.
 */
class TrashStore(context: Context) {

    private val file = File(context.filesDir, "trash.json")
    private val json = Json { ignoreUnknownKeys = true }

    private var swept = 0L
    private val _items = MutableStateFlow(loadFromDisk())

    /** Thread id to when it was trashed and its cutoff. */
    val items: StateFlow<Map<Long, Trashed>> = _items.asStateFlow()

    // not ported: startReloadIfChangedUnexpectedly (only this process writes the file, nothing changes it behind the store)
    /**
     * SharedPreferencesImpl.loadFromDisk: a file that cannot be read is logged ("Cannot read") and the map
     * stays empty, but the file is not lost: it is kept aside as makeBackupFile keeps ".bak", here ".bad",
     * and never read as empty then overwritten. Not ported: the .bak rename on start (the write below moves a
     * whole file in one step), the stat timestamps and the waiting threads (mLock, one reader).
     * https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r1/core/java/android/app/SharedPreferencesImpl.java#L153-L225
     */
    private fun loadFromDisk(): Map<Long, Trashed> {
        val fileExists = file.exists()
        var map: String? = null
        var thrown: Throwable? = null
        if (fileExists) {
            try {
                map = file.readText()
                json.decodeFromString<TrashFile>(map)
            } catch (e: Exception) {
                thrown = e
                Log.w(TAG, "Cannot read " + file.name + ": " + e.javaClass.simpleName)
            }
        }
        if (thrown != null && map != null) makeBackupFile(file)
        return Trash.parse(if (thrown == null) map.orEmpty() else "", json, System.currentTimeMillis()).also { swept = it.swept }.items
    }

    /** makeBackupFile: the corrupt file's new name, ".bad". A failed rename is logged; the next write replaces the file. */
    private fun makeBackupFile(prefsFile: File) {
        val mBackupFile = File(prefsFile.path + ".bad")
        if (!prefsFile.renameTo(mBackupFile)) Log.e(TAG, "Couldn't keep " + prefsFile.name + " aside")
    }

    /** The write of SettingsStore.update: the new value in the flow, then the file replaced at once. False if the file was not written. */
    @Synchronized
    private fun update(sweptNow: Long = swept, transform: (Map<Long, Trashed>) -> Map<Long, Trashed>): Boolean {
        val updated = transform(_items.value)
        if (updated == _items.value && sweptNow == swept) return true
        _items.value = updated
        swept = sweptNow
        return writeToFile(TrashFile(updated, swept))
    }

    /**
     * SharedPreferencesImpl.writeToFile: an exception is logged ("writeToFile: Got exception"), the partly
     * written file cleaned up, and the result is false (mcr.setDiskWriteResult(false, false)).
     * https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r1/core/java/android/app/SharedPreferencesImpl.java#L731-L880
     */
    private fun writeToFile(mcr: TrashFile): Boolean {
        try {
            file.writeTextAtomically(json.encodeToString(TrashFile.serializer(), mcr))
            return true
        } catch (e: Exception) {
            Log.w(TAG, "writeToFile: Got exception: " + e.javaClass.simpleName)
        }

        // Clean up an unsuccessfully written file
        val mFile = File(file.parentFile, file.name + ".tmp")
        if (mFile.exists()) {
            if (!mFile.delete()) {
                Log.e(TAG, "Couldn't clean up partially-written file " + mFile.name)
            }
        }
        return false
    }

    /**
     * Put conversations in the Trash: thread id to the date of its newest message. Fossify
     * Messages' moveMessageToRecycleBin (insertRecycleBinEntry with the time of now), for a whole
     * conversation (https://github.com/FossifyOrg/Messages, extensions/Context.kt).
     */
    fun trash(cutoffs: Map<Long, Long>, now: Long = System.currentTimeMillis()) = update { Trash.add(it, cutoffs, now) }

    /**
     * Take conversations out of the Trash, back in the inbox: Fossify Messages'
     * restoreMessageFromRecycleBin and restoreAllMessagesFromRecycleBinForConversation
     * (deleteFromRecycleBin; https://github.com/FossifyOrg/Messages, extensions/Context.kt).
     */
    fun restore(threads: Collection<Long>) = update { Trash.remove(it, threads) }

    /** Forget conversations whose messages were deleted for good. */
    fun forget(threads: Collection<Long>) = update { Trash.remove(it, threads) }

    /** Keep only the conversations the store still holds (see [Trash.alive]). */
    fun keepThreads(existing: Set<Long>) = update { Trash.alive(it, existing) }

    /**
     * Fossify Messages' checkAndDeleteOldRecycleBinMessages: getOldRecycleBinMessages, then
     * deleteMessage for each (https://github.com/FossifyOrg/Messages, extensions/Context.kt and
     * interfaces/MessagesDao.kt), here per conversation up to its cutoff.
     * Deletes the expired conversations through [delete] (thread id and cutoff, true when the
     * messages are gone), at most once a day. An entry whose deletion failed stays, and the
     * check is tried again the next time the app opens. Returns the threads deleted.
     */
    suspend fun sweep(now: Long, delete: suspend (Long, Long) -> Boolean): List<Long> {
        if (!Trash.dueForSweep(swept, now)) return emptyList()
        val gone = ArrayList<Long>()
        var failed = false
        for ((thread, t) in Trash.expired(_items.value, now)) {
            if (delete(thread, t.cutoff)) gone += thread else failed = true
        }
        update(if (failed) swept else now) { Trash.remove(it, gone) }
        return gone
    }

    private companion object {
        const val TAG = "TrashStore"
    }
}

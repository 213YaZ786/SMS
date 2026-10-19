package com.yaz.sms.core.mms

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import com.yaz.sms.core.common.writeTextAtomically
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a part came from: its conversation, its message (Message.uid) and the number that sent it. */
data class SavedOrigin(val thread: Long, val uid: String, val sender: String)

/** One media saved to the phone: the media store's row id and where it came from. */
@Serializable
data class SavedEntry(val id: Long, val thread: Long, val uid: String, val sender: String)

/** What the file holds, oldest first. */
@Serializable
data class SavedFile(val items: List<SavedEntry> = emptyList())

/**
 * The rules of the list of saved media, pure. The list is kept like Fossify Messages keeps its
 * blocked keywords and like StarStore keeps the starred messages (data/settings/StarStore.kt: a
 * capped set, a read file filtered as StarStore's Stars.parse does); the oldest go first when
 * the cap is passed, as MediaProvider drops its oldest trashed items first.
 */
object SavedEntries {
    /** The most entries kept; the file stays small. */
    const val MAX = 5000

    private const val UID_MAX = 24
    private const val SENDER_MAX = 64
    private val UID = Regex("(sms|mms)/[0-9]{1,18}")

    /**
     * Whether [uid] is a message uid as Message.uid writes it, "sms/7" or "mms/45": a table of the
     * Telephony provider and its _id, a long (developer.android.com, Telephony.Sms and Telephony.Mms),
     * as Messages.byUid reads them back.
     */
    fun isUid(uid: String?): Boolean = uid != null && uid.length <= UID_MAX && UID.matches(uid)

    /** Whether an entry can be kept: a real row, a real thread, a message uid, a sender. */
    fun valid(e: SavedEntry): Boolean =
        e.id > 0 && e.thread >= 0 && isUid(e.uid) && e.sender.isNotBlank() && e.sender.length <= SENDER_MAX

    /** [list] with [entry] added at the end (a row saved again replaces its old entry), capped to [MAX], the oldest dropped. */
    fun add(list: List<SavedEntry>, entry: SavedEntry): List<SavedEntry> =
        if (!valid(entry)) list else (list.filter { it.id != entry.id } + entry).takeLast(MAX)

    /** A file read back: what is not an entry is dropped, the count capped, a row kept once (its last entry). */
    fun parse(text: String, json: Json): List<SavedEntry> = runCatching {
        json.decodeFromString<SavedFile>(text).items.filter(::valid).asReversed().distinctBy { it.id }.asReversed().takeLast(MAX)
    }.getOrDefault(emptyList())
}

/**
 * The media this app saved to the phone, with the message each came from, for the Files app to
 * say who sent a saved attachment (SavedDoor). Kept in the app's no-backup folder in a small file
 * of its own, written atomically, the way StarStore keeps its set (core/common/AtomicWrite.kt).
 * Nothing here leaves the device except through SavedDoor, behind a signature permission.
 */
class SavedMedia(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private var items: List<SavedEntry> = load()

    private fun load(): List<SavedEntry> =
        if (!file.exists()) emptyList() else SavedEntries.parse(runCatching { file.readText() }.getOrDefault(""), json)

    private fun write() {
        runCatching { file.writeTextAtomically(json.encodeToString(SavedFile.serializer(), SavedFile(items))) }
    }

    /** Remembers a saved media. */
    @Synchronized
    fun add(entry: SavedEntry) {
        val updated = SavedEntries.add(items, entry)
        if (updated == items) return
        items = updated
        write()
    }

    /** Everything kept, oldest first. */
    @Synchronized
    fun all(): List<SavedEntry> = items

    /**
     * The entries of [ids], in the order asked, each row once. [exists] says which of the rows
     * asked are still in the media store; the entries of the others are dropped for good.
     */
    @Synchronized
    fun origins(ids: List<Long>, exists: (List<Long>) -> Set<Long>): List<SavedEntry> {
        val byId = items.associateBy { it.id }
        val asked = ids.distinct().filter { it in byId }
        if (asked.isEmpty()) return emptyList()
        val alive = exists(asked)
        val gone = asked.filter { it !in alive }.toSet()
        if (gone.isNotEmpty()) {
            items = items.filter { it.id !in gone }
            write()
        }
        return asked.filter { it in alive }.mapNotNull { byId[it] }
    }

    companion object {
        @Volatile private var instance: SavedMedia? = null

        /** The one list of the app, in the folder Android keeps out of backups. */
        fun of(context: Context): SavedMedia =
            instance ?: synchronized(this) {
                instance ?: SavedMedia(File(context.applicationContext.noBackupFilesDir, "saved-media.json")).also { instance = it }
            }

        /**
         * Which of [ids] are still rows of the media store. The app owns the rows it saved, so it
         * sees them without a permission (developer.android.com, MediaStore: an app reads its own
         * files); rows asked in a query of `_id IN (...)` as Messages.byUid asks Android's store.
         * When the store cannot be read nothing is taken for gone.
         */
        fun existing(resolver: ContentResolver, ids: List<Long>): Set<Long> = runCatching {
            val found = HashSet<Long>()
            for (chunk in ids.chunked(400)) {
                resolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    arrayOf(MediaStore.Files.FileColumns._ID),
                    "${MediaStore.Files.FileColumns._ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map { it.toString() }.toTypedArray(),
                    null
                )?.use { c -> while (c.moveToNext()) found += c.getLong(0) } ?: return@runCatching ids.toSet()
            }
            found
        }.getOrElse { ids.toSet() }
    }
}

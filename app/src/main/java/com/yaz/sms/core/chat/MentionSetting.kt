package com.yaz.sms.core.chat

import android.content.Context
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.yaz.sms.core.common.writeTextAtomically
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the file holds: the conversations whose mentions do not break through silence. */
@Serializable
data class MentionsOffFile(val threads: Set<Long> = emptySet())

/**
 * "Mentions: Notify me", per conversation, on by default. Signal keeps it as the recipient's
 * mentionSetting (AGPL-3.0, https://github.com/signalapp/Signal-Android, RecipientTable.NotificationSetting
 * ALWAYS_NOTIFY / DO_NOT_NOTIFY, read in notifications/v2/NotificationStateProvider.kt breaksThroughMute:
 * a message that mentions the user breaks through a muted conversation when the setting is ALWAYS_NOTIFY).
 * Kept here in a small file of its own, written atomically, the way KnownStore and StarStore keep theirs
 * (data/settings/KnownStore.kt). Only the conversations switched off are written.
 */
class MentionSetting private constructor(context: Context) {

    private val file = File(context.filesDir, "mentions-off.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _off = MutableStateFlow(load())

    /** The conversations (thread ids) whose mentions stay silent when the conversation is silenced. */
    val off: StateFlow<Set<Long>> = _off.asStateFlow()

    private fun load(): Set<Long> =
        runCatching { json.decodeFromString<MentionsOffFile>(file.readText()).threads }.getOrDefault(emptySet())

    /** Whether a mention of the user breaks through the silence of [thread]. */
    fun notifies(thread: Long): Boolean = thread !in _off.value

    /** Switches it on or off for [thread]. */
    @Synchronized
    fun set(thread: Long, on: Boolean) {
        val updated = if (on) _off.value - thread else _off.value + thread
        if (updated == _off.value) return
        _off.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(MentionsOffFile.serializer(), MentionsOffFile(updated))) }
    }

    companion object {
        @Volatile private var instance: MentionSetting? = null

        /** The one setting of the app. */
        fun of(context: Context): MentionSetting =
            instance ?: synchronized(this) { instance ?: MentionSetting(context.applicationContext).also { instance = it } }

        /**
         * The names the user goes by, to know when they are named: the Me contact's name, which is also
         * what the chat shows the others (Cards.profileName / Cards.publish set it as the chat's name).
         */
        fun myNames(context: Context): List<String> {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
            return runCatching {
                context.contentResolver.query(ContactsContract.Profile.CONTENT_URI, arrayOf(ContactsContract.Profile.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) listOfNotNull(c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }) else emptyList() }
            }.getOrNull().orEmpty()
        }
    }
}

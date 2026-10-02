package com.sms.app.data.settings

import android.content.Context
import com.sms.app.core.common.writeTextAtomically
import com.sms.app.core.update.UpdateMode
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Settings(
    /**
     * What happens when a newer version is out, checked once when the app
     * opens. Installing by default: the first launch page says so, and that
     * this one request is the app's only use of the internet.
     */
    val updates: UpdateMode = UpdateMode.INSTALL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** True black instead of dark grey in dark mode. */
    val pureBlack: Boolean = false,
    /** Zones and floating controls in liquid glass, over a soft light in the wallpaper's colours. */
    val glass: Boolean = true,
    /** Multiplier on every text style, one of the steps in ui.theme.TEXT_SCALES. */
    val textScale: Float = 1f,
    /** The first launch page was closed. */
    val welcomeSeen: Boolean = false,
    /** The app's picture in the recent apps screen stays blank: no messages to be seen there. */
    val hideInRecents: Boolean = true,
    /** Where the user left the new message button, fractions of its room; below 0, its usual place. */
    val composeX: Float = -1f,
    val composeY: Float = -1f,
    /** The little show of the new message button moving was seen. */
    val composeHintSeen: Boolean = false,
    /** Conversations set aside until a time: thread id to when they come back. */
    val later: Map<Long, Long> = emptyMap(),
    /** A vibration of their own for some people, by the last nine digits of their number. */
    val signatures: Map<String, String> = emptyMap(),
    /** Seconds a message waits before it goes, to take it back with a tap; 0 sends at once. */
    val undoSeconds: Int = 4,
    /** Encrypted chat with other SMS users, over a chatmail relay. */
    val richChat: Boolean = true,
    /** The chatmail relay the chat profile lives on. */
    val relay: String = "nine.testrun.org",
    /** Messages from the ranges kept for sales are kept apart, without a notification. */
    val quietSales: Boolean = false,
    /** Conversations pinned on top, by thread id. */
    val pinned: List<Long> = emptyList(),
    /** Conversations put away, thread id to when: out of the list until a newer message comes. */
    val archived: Map<Long, Long> = emptyMap()
)

/**
 * Small preference file, plain JSON written atomically, like the other apps.
 * Nothing here is a secret, and none of it leaves the device.
 */
class SettingsStore(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    private fun load(): Settings {
        if (!file.exists()) return Settings()
        return runCatching { json.decodeFromString<Settings>(file.readText()) }
            .getOrDefault(Settings())
    }

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(updated)) }
    }
}

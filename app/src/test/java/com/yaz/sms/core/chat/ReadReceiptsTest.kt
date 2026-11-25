package com.yaz.sms.core.chat

import com.yaz.sms.data.settings.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The read receipts switch maps to the engine's mdns_enabled as Delta Chat Android's
 * ChatsPreferenceFragment.ReadReceiptToggleListener does (setConfigInt 1 or 0). Off by default
 * since 2026-11-25; the engine still defaults to on, so RichChat.start sets mdns_enabled from the setting.
 */
class ReadReceiptsTest {

    @Test
    fun theSwitchIsOneOrZero() {
        assertEquals("mdns_enabled", ReadReceipts.KEY)
        assertEquals("1", ReadReceipts.value(true))
        assertEquals("0", ReadReceipts.value(false))
    }

    // Ported from https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/master/docs/basic-serialization.md#optional-properties
    @Test
    fun offByDefaultAndKeptInTheSettingsFile() {
        assertFalse(Settings().readReceipts)
        // A missing key takes the default (kotlinx.serialization basic-serialization.md, optional properties).
        assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString<Settings>("{}").readReceipts)
        val on = Json.decodeFromString<Settings>(Json.encodeToString(Settings(readReceipts = true)))
        assertEquals("1", ReadReceipts.value(on.readReceipts))
    }
}

package com.yaz.sms.core.chat

import com.yaz.sms.data.settings.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read receipts switch maps to the engine's mdns_enabled as Delta Chat Android's
 * ChatsPreferenceFragment.ReadReceiptToggleListener does (setConfigInt 1 or 0, on by default).
 */
class ReadReceiptsTest {

    @Test
    fun theSwitchIsOneOrZero() {
        assertEquals("mdns_enabled", ReadReceipts.KEY)
        assertEquals("1", ReadReceipts.value(true))
        assertEquals("0", ReadReceipts.value(false))
    }

    @Test
    fun onByDefaultAndKeptInTheSettingsFile() {
        assertTrue(Settings().readReceipts)
        // A settings file written before the switch existed still reads, with receipts on.
        assertTrue(Json { ignoreUnknownKeys = true }.decodeFromString<Settings>("{}").readReceipts)
        val off = Json.decodeFromString<Settings>(Json.encodeToString(Settings(readReceipts = false)))
        assertEquals("0", ReadReceipts.value(off.readReceipts))
    }
}

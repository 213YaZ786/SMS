package com.yaz.sms.core.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Signal's "Mentions: Notify me" per conversation, on unless switched off
 * (https://github.com/signalapp/Signal-Android, RecipientTable.NotificationSetting ALWAYS_NOTIFY / DO_NOT_NOTIFY).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MentionSettingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun onUntilSwitchedOffAndKeptInItsFile() {
        val setting = MentionSetting.of(context)
        assertTrue(setting.notifies(9001))
        setting.set(9001, false)
        assertFalse(setting.notifies(9001))
        assertTrue(setting.notifies(9002))
        val written = Json.decodeFromString<MentionsOffFile>(File(context.filesDir, "mentions-off.json").readText())
        assertTrue(9001L in written.threads)
        setting.set(9001, true)
        assertTrue(setting.notifies(9001))
        assertEquals(emptySet<Long>(), Json.decodeFromString<MentionsOffFile>(File(context.filesDir, "mentions-off.json").readText()).threads)
    }

    @Test
    fun withoutPermissionTheUserHasNoName() {
        // Robolectric starts with READ_CONTACTS refused: nobody is named.
        assertTrue(MentionSetting.myNames(context).isEmpty())
    }
}

package com.yaz.sms.data.settings

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Secure defaults: a fresh store holds them, an old file is switched once, later choices stay.
 * Checks the once-only step after Signal's ApplicationMigrations and the optional properties of
 * https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/master/docs/basic-serialization.md#optional-properties
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [36])
class SecureDefaultsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val file = File(context.filesDir, "settings.json")

    private class Case(val key: String, val unsafe: String, val safe: Any?, val read: (Settings) -> Any?, val other: String, val otherValue: Any?)

    private val cases = listOf(
        Case("blockScreenshots", "false", true, { it.blockScreenshots }, "false", false),
        Case("autoMmsDownload", "true", false, { it.autoMmsDownload }, "true", true),
        Case("shareCard", "true", false, { it.shareCard }, "true", true),
        Case("readReceipts", "true", false, { it.readReceipts }, "true", true),
        Case("showStrangerMedia", "true", false, { it.showStrangerMedia }, "true", true),
        Case("vanishDefault", "0", 2_419_200, { it.vanishDefault }, "3600", 3600)
    )

    @Test
    fun `a fresh store has each secure value`() {
        for (c in cases) {
            file.delete()
            val s = SettingsStore(context).current
            assertEquals(c.key, c.safe, c.read(s))
            assertEquals(1, s.defaultsVersion)
        }
    }

    @Test
    fun `an old file is switched`() {
        for (c in cases) {
            file.writeText("{\"${c.key}\": ${c.unsafe}}")
            val s = SettingsStore(context).current
            assertEquals(c.key, c.safe, c.read(s))
            assertEquals(1, s.defaultsVersion)
        }
    }

    @Test
    fun `a file at version 1 keeps the user's choice`() {
        for (c in cases) {
            file.writeText("{\"${c.key}\": ${c.other}, \"defaultsVersion\": 1}")
            assertEquals(c.key, c.otherValue, c.read(SettingsStore(context).current))
        }
    }

    @Test
    fun `the step runs once`() {
        file.writeText("""{"blockScreenshots": false}""")
        SettingsStore(context).update { it.copy(blockScreenshots = false) }
        assertEquals(false, SettingsStore(context).current.blockScreenshots)
    }
}

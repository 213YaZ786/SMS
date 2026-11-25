package com.yaz.sms.data.settings

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Strangers' media is off in a fresh store and in a file without the key; a stored choice stays.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [36])
class StrangerMediaTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val file = File(context.filesDir, "settings.json")

    @Test
    fun `a fresh store holds strangers' media`() {
        file.delete()
        assertFalse(SettingsStore(context).current.showStrangerMedia)
    }

    @Test
    fun `a file without the key holds strangers' media`() {
        file.writeText("""{"themeMode": "DARK"}""")
        assertFalse(SettingsStore(context).current.showStrangerMedia)
    }

    @Test
    fun `a stored true stays true`() {
        file.writeText("""{"showStrangerMedia": true}""")
        assertTrue(SettingsStore(context).current.showStrangerMedia)
    }

    @Test
    fun `a stored false stays false`() {
        file.writeText("""{"showStrangerMedia": false}""")
        assertFalse(SettingsStore(context).current.showStrangerMedia)
    }

    @Test
    fun `a choice saved by the store survives a reopening`() {
        file.delete()
        SettingsStore(context).update { it.copy(showStrangerMedia = true) }
        assertTrue(SettingsStore(context).current.showStrangerMedia)
    }
}

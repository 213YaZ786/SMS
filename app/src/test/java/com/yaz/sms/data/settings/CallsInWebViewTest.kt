package com.yaz.sms.data.settings

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The encrypted call's media runs in the system WebView by default: a file without the key decodes to the
 * property's default (https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/master/docs/basic-serialization.md#optional-properties).
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [36])
class CallsInWebViewTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val file = File(context.filesDir, "settings.json")

    @Test
    fun `a file without the key runs calls in the WebView`() {
        file.writeText("""{"themeMode": "DARK"}""")
        assertTrue(SettingsStore(context).current.callsInWebView)
    }
}

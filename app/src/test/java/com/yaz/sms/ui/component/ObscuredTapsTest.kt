package com.yaz.sms.ui.component

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A dialog asked to refuse obscured taps has a decorView that filters touches while another app's window covers it, as
 * Super Productivity sets it on each dialog of WebViewBlockActivity (`dialog.window?.decorView?.filterTouchesWhenObscured`).
 * https://raw.githubusercontent.com/super-productivity/super-productivity/master/android/app/src/main/java/com/superproductivity/superproductivity/webview/WebViewBlockActivity.kt#WebViewBlockActivity
 * not ported: onCreate's layout and texts, showOpenWebViewConfirmation, showOpenWebViewAppInfoConfirmation,
 * showOverrideConfirmation, relaunchApp, present (the screen itself, not the dialog's flag)
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [36])
class ObscuredTapsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showDialog(refuse: Boolean): View? {
        var dialog: View? = null
        compose.activity.setContent {
            ZoneAlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                refuseObscuredTaps = refuse,
                text = {
                    dialog = LocalView.current
                    Text("Delete this message?")
                }
            )
        }
        compose.waitForIdle()
        return dialog
    }

    @Test
    fun aDangerousDialogsWindowFiltersObscuredTouches() {
        val dialog = showDialog(refuse = true)
        assertNotNull(dialog)
        assertTrue(dialog!!.rootView.filterTouchesWhenObscured)
    }

    @Test
    fun anOrdinaryDialogKeepsTheDefault() {
        val dialog = showDialog(refuse = false)
        assertNotNull(dialog)
        assertFalse(dialog!!.rootView.filterTouchesWhenObscured)
    }
}

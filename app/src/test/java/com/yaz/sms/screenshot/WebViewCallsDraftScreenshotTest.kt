package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingFrame
import com.yaz.sms.ui.component.FloatingTop
import com.yaz.sms.ui.component.LargeTitle
import com.yaz.sms.ui.component.SettingsHeaderSpacer
import com.yaz.sms.ui.component.SettingsSectionTitle
import com.yaz.sms.ui.component.SettingsSwitchTile
import com.yaz.sms.ui.component.SettingsTile
import com.yaz.sms.ui.component.SettingsTileGrid
import com.yaz.sms.ui.glass.LocalGlass
import com.yaz.sms.ui.glass.glassGround
import com.yaz.sms.ui.glass.rememberGlassLook
import com.yaz.sms.ui.icon.AppIcons
import com.yaz.sms.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DRAFT for the user's yes: nothing of app/src/main changes. The Chat section of SettingsScreen.kt (Encrypted chat on,
 * Share your name and photo, Send read receipts, the real SettingsSwitchTile and SettingsTileGrid) with a new switch
 * tile "Calls in the system WebView" right after "Send read receipts", off, enabled while the encrypted chat is on.
 * Pictures: webview_calls_draft_phone_{light,dark}, webview_calls_draft_tablet_light.
 *
 * Ports:
 * https://raw.githubusercontent.com/android/nowinandroid/main/core/screenshot-testing/src/main/kotlin/com/google/samples/apps/nowinandroid/core/testing/util/ScreenshotHelper.kt#captureForDevice
 * (device spec, dark mode, RuntimeEnvironment.setQualifiers, setContent with LocalInspectionMode, captureRoboImage)
 * not ported: the accessibility checks (checkRoboAccessibility, ATF options), extractSpecs (the qualifiers are given
 * whole), DeviceConfigurationOverride (AppTheme takes the dark flag)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class WebViewCallsDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    // ScreenshotHelper.kt#captureForDevice
    private fun captureForDevice(screenshotName: String, body: @Composable () -> Unit) {
        listOf(
            Triple("phone", "w411dp-h914dp-420dpi", listOf(false, true)),
            Triple("tablet", "w1280dp-h800dp-240dpi", listOf(false))
        ).forEach { (device, qualifiers, nights) ->
            RuntimeEnvironment.setQualifiers(qualifiers)
            nights.forEach { night ->
                compose.activity.setContent {
                    CompositionLocalProvider(LocalInspectionMode provides true) {
                        AppTheme(darkTheme = night, dynamicColor = false) {
                            val look = rememberGlassLook(MaterialTheme.colorScheme, true, quiet = true)
                            CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                                Box(Modifier.fillMaxSize().glassGround(look, MaterialTheme.colorScheme.background)) { body() }
                            }
                        }
                    }
                }
                compose.waitForIdle()
                compose.onRoot().captureRoboImage(
                    "src/test/screenshots/${screenshotName}_${device}_${if (night) "dark" else "light"}.png",
                    roborazziOptions = options
                )
            }
        }
    }

    // The Chat tiles of SettingsScreen.kt, the new one after "Send read receipts".
    private val chatTiles: List<SettingsTile> = listOf(
        SettingsSwitchTile(
            id = "chat", name = "Encrypted chat", title = "Encrypted chat with SMS users", icon = AppIcons.Lock,
            summary = "Connected", checked = true, stateText = "On, connected", onChange = {}
        ),
        SettingsSwitchTile(
            id = "card", name = "Name and photo", title = "Share your name and photo", icon = AppIcons.Person,
            summary = "Your card in Android's contacts (Me) goes with your encrypted messages.",
            checked = true, enabled = true, onChange = {}
        ),
        SettingsSwitchTile(
            id = "receipts", name = "Read receipts", title = "Send read receipts", icon = AppIcons.DoneAll,
            summary = "Off: you stop seeing others' receipts too.", checked = true, enabled = true, onChange = {}
        ),
        SettingsSwitchTile(
            id = "webcalls", name = "Calls in WebView", title = "Calls in the system WebView", icon = AppIcons.Call,
            summary = "Encrypted calls run in Android's WebView, kept up to date with the system.",
            checked = false, enabled = true, onChange = {}
        )
    )

    @Test
    fun webViewCalls() = captureForDevice("webview_calls_draft") {
        FloatingFrame(
            bottom = 0.dp,
            scroll = null,
            top = { FloatingTop("Settings", leading = { FloatingAction(AppIcons.ArrowBack, "Back", {}) }) },
            largeTitle = LargeTitle("Settings")
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsHeaderSpacer(padding)
                Spacer(Modifier.height(24.dp))
                SettingsSectionTitle("Chat")
                SettingsTileGrid(chatTiles) {}
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

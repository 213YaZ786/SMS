package com.yaz.sms.screenshot

import android.view.Window
import android.view.WindowManager
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
 * DRAFT for the user's yes: nothing of app/src/main changes. The Appearance section of SettingsScreen.kt (Pure black,
 * Hide in recent apps, Glass effects, the real SettingsSwitchTile and SettingsTileGrid) with a new switch tile
 * "Block screenshots" right after "Hide in recent apps", off, its state text On / Off like its neighbours. Pictures:
 * screen_security_draft_phone_{light,dark}. The switch will set FLAG_SECURE on the window, as Signal's Screen security.
 *
 * Ports:
 * https://raw.githubusercontent.com/android/nowinandroid/main/core/screenshot-testing/src/main/kotlin/com/google/samples/apps/nowinandroid/core/testing/util/ScreenshotHelper.kt#captureForDevice
 * (device spec, dark mode, RuntimeEnvironment.setQualifiers, setContent with LocalInspectionMode, captureRoboImage)
 * not ported: the accessibility checks (checkRoboAccessibility, ATF options), extractSpecs (the qualifiers are given
 * whole), DeviceConfigurationOverride (AppTheme takes the dark flag)
 * https://raw.githubusercontent.com/signalapp/Signal-Android/main/core/ui/src/main/java/org/signal/core/ui/WindowExtensions.kt#initializeScreenshotSecurity
 * (below, the flag the tile's switch stands for)
 * not ported: the edge-to-edge dialog helper of that file (no dialog in this picture)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class ScreenSecurityDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    // WindowExtensions.kt#initializeScreenshotSecurity: the flag follows the setting (not applied in the picture).
    @Suppress("unused")
    private fun Window.initializeScreenshotSecurity(isScreenSecurityEnabled: Boolean) {
        if (isScreenSecurityEnabled) {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // ScreenshotHelper.kt#captureForDevice
    private fun captureForDevice(screenshotName: String, body: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w411dp-h914dp-420dpi")
        listOf(false, true).forEach { night ->
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
                "src/test/screenshots/${screenshotName}_phone_${if (night) "dark" else "light"}.png",
                roborazziOptions = options
            )
        }
    }

    // The Appearance tiles of SettingsScreen.kt, the new one after "Hide in recent apps".
    private val appearanceTiles: List<SettingsTile> = listOf(
        SettingsSwitchTile(
            id = "black", name = "Pure black", title = "Pure black", icon = AppIcons.Night,
            summary = "Black background in dark mode.", checked = false, onChange = {}
        ),
        SettingsSwitchTile(
            id = "recents", name = "Hide in recents", title = "Hide in recent apps", icon = AppIcons.VisibilityOff,
            summary = "The app's preview stays blank in recent apps.", checked = false, onChange = {}
        ),
        SettingsSwitchTile(
            id = "screenshots", name = "Block screenshots", title = "Block screenshots", icon = AppIcons.Block,
            summary = "Screenshots and screen recordings show nothing of the app.", checked = false, onChange = {}
        ),
        SettingsSwitchTile(
            id = "glass", name = "Glass effects", title = "Glass effects", icon = AppIcons.AutoAwesome,
            summary = "Buttons and panes in liquid glass.", checked = true, onChange = {}
        )
    )

    @Test
    fun screenSecurity() = captureForDevice("screen_security_draft") {
        FloatingFrame(
            bottom = 0.dp,
            scroll = null,
            top = { FloatingTop("Settings", leading = { FloatingAction(AppIcons.ArrowBack, "Back", {}) }) },
            largeTitle = LargeTitle("Settings")
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsHeaderSpacer(padding)
                Spacer(Modifier.height(24.dp))
                SettingsSectionTitle("Appearance")
                SettingsTileGrid(appearanceTiles) {}
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

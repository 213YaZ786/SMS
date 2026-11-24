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
import com.yaz.sms.ui.component.SettingsActionTile
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
 * DRAFT for the user's yes (requests.md, Settings without the line under the title, choice A): nothing of app/src/main
 * changes. SettingsScreen.kt:389 gives LargeTitle("Settings", status = ...); here the large title stands alone, as
 * Material 3's LargeTopAppBar and Apple Messages' large title do (a title, no second line), and the chat's state
 * ("Encrypted chat, connected") moves into the Encrypted chat tile's own state line. The frame, the title block and the
 * tiles are the app's own (FloatingFrame, LargeTitle, SettingsTileGrid); only the tile data is fixed.
 *
 * Ports:
 * https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AppBar.kt#LargeTopAppBar
 * (the app's FloatingFrame already ports LargeTopAppBar's collapsing title, ui/component/FloatingPane.kt#LargeTitleBlock;
 * this test only calls it with a title and no status)
 * not ported: LargeTopAppBar's own container, navigationIcon, actions slots and windowInsets (FloatingTop and
 * FloatingAction draw the top row), TwoRowsTopAppBar (the app's TitleCollapse does the measuring), SingleRowTopAppBar,
 * MediumTopAppBar, CenterAlignedTopAppBar, TopAppBar, BottomAppBar (no other bar is drawn here)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class SettingsTitleDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    private fun capture(name: String, body: @Composable () -> Unit) {
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
                "src/test/screenshots/${name}_phone_${if (night) "dark" else "light"}.png",
                roborazziOptions = options
            )
        }
    }

    // The chat's state lives on its own tile now: a value under the name, as every tile that opens something.
    private val chatTiles: List<SettingsTile> = listOf(
        SettingsActionTile(
            id = "chat", name = "Encrypted chat", title = "Encrypted chat with SMS users", icon = AppIcons.Lock,
            value = "On, connected", summary = "On, connected"
        ),
        SettingsSwitchTile(
            id = "card", name = "Name and photo", title = "Share your name and photo", icon = AppIcons.Person,
            summary = "", checked = true, onChange = {}
        ),
        SettingsSwitchTile(
            id = "receipts", name = "Read receipts", title = "Send read receipts", icon = AppIcons.DoneAll,
            summary = "", checked = false, onChange = {}
        ),
        SettingsActionTile(
            id = "vanish", name = "Vanishing messages", title = "Vanishing messages", icon = AppIcons.Timer,
            value = "4 weeks", summary = "4 weeks"
        )
    )

    private val messageTiles: List<SettingsTile> = listOf(
        SettingsSwitchTile(
            id = "spam", name = "Spam protection", title = "Spam protection", icon = AppIcons.Report,
            summary = "", checked = true, onChange = {}
        ),
        SettingsActionTile(
            id = "undo", name = "Undo send", title = "Undo send", icon = AppIcons.Undo,
            value = "5 s", summary = "5 s"
        )
    )

    @Test
    fun title() = capture("settings_title_draft") {
        FloatingFrame(
            bottom = 0.dp,
            scroll = null,
            top = { FloatingTop("Settings", leading = { FloatingAction(AppIcons.ArrowBack, "Back", {}) }) },
            // No status line: the title alone.
            largeTitle = LargeTitle("Settings")
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsHeaderSpacer(padding)
                Spacer(Modifier.height(24.dp))
                SettingsSectionTitle("Encrypted chat")
                SettingsTileGrid(chatTiles) {}
                Spacer(Modifier.height(24.dp))
                SettingsSectionTitle("Messages")
                SettingsTileGrid(messageTiles) {}
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

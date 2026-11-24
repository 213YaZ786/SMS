package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.sms.Filter
import com.yaz.sms.core.sms.SwipeAction
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.feature.conversations.ConversationLine
import com.yaz.sms.feature.conversations.FilterCapsule
import com.yaz.sms.feature.conversations.HomeHeaderLayoutMobile
import com.yaz.sms.feature.conversations.LINE_WIDTH
import com.yaz.sms.ui.component.DropdownMenuGroup
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingFrame
import com.yaz.sms.ui.component.FloatingTop
import com.yaz.sms.ui.component.SelectableDropdownMenuItem
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
 * The list's top (requests.md point 6), drawn with the app's own composables: TabFrame's top row (FloatingTop, Settings
 * at its end), HomeHeaderLayoutMobile and FilterCapsule (the filter pill and the Search part in one glass capsule).
 * Drawn closed (list_top_draft) and open (list_top_menu_draft). Robolectric draws no Popup, so the open menu is the
 * panes kit's own groups (DropdownMenuGroup, SelectableDropdownMenuItem) laid in the frame's overlay, in the place
 * GlassMenu's window gives them under the pill.
 *
 * Ports:
 * https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Menu.kt#DropdownMenuGroup
 * (the menu's groups, one column of them; the kit's GlassMenu.kt draws their glass)
 * not ported: DropdownMenuGroup itself (the panes kit's GlassMenu.kt is its port, called here), MenuGroupShapes and the
 * hover state
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class ListTopDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    private fun capture(name: String, body: @Composable () -> Unit) {
        listOf(Triple("phone", "w411dp-h914dp-420dpi", false), Triple("phone", "w411dp-h914dp-420dpi", true), Triple("tablet", "w1280dp-h800dp-320dpi", false))
            .forEach { (device, qualifiers, night) ->
                RuntimeEnvironment.setQualifiers(qualifiers)
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
                    "src/test/screenshots/${name}_${device}_${if (night) "dark" else "light"}.png",
                    roborazziOptions = options
                )
            }
    }

    private fun talk(id: Long, snippet: String, address: String, unread: Int = 0, fromMe: Boolean = false) =
        Conversation(id, address, snippet, 1_600_000_000_000L + id * 86_400_000L, unread, fromMe, failed = false)

    private val people: List<Pair<Conversation, String>> = listOf(
        talk(1, "See you at eight", "+212661234567") to "Ahmed Benali",
        talk(2, "The documents are ready, I left them at the reception desk, please come and take them before noon", "+33612345678", unread = 3) to "Joëlle Martin",
        talk(3, "Thanks a lot", "+7916123456", unread = 1) to "Valentina Ivanova",
        talk(4, "On my way", "+81901234567", fromMe = true) to "Akira Tanaka",
        talk(5, "Call me back when you can, it is about the contract and the signature", "+2348031234567", unread = 12) to "Marie-Christine Delacroix-Beaumont and her whole family",
        talk(6, "Dinner is booked for seven", "+966501234567") to "فاطمة الزهراء",
        talk(7, "Meeting moved to Friday", "+34612345678") to "Sofía García",
        talk(8, "Your code is 482913", "+353851234567", unread = 1) to "Liam O'Connor",
        talk(9, "Happy birthday, many returns of the day", "+4915123456789") to "Jürgen Weber",
        talk(10, "I will send the pictures tonight", "+5511912345678") to "Camila Souza"
    )

    // The menu's two groups as GlassMenu lays them (All and Unread, then Archive, Spam and Trash), All checked.
    @Composable
    private fun DropdownMenuGroups() {
        Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DropdownMenuGroup(index = 0, count = 2) {
                SelectableDropdownMenuItem(selected = true, onClick = {}, text = "All")
                SelectableDropdownMenuItem(selected = false, onClick = {}, text = "Unread", count = 3)
            }
            DropdownMenuGroup(index = 1, count = 2) {
                SelectableDropdownMenuItem(selected = false, onClick = {}, text = "Archive")
                SelectableDropdownMenuItem(selected = false, onClick = {}, text = "Spam", count = 2)
                SelectableDropdownMenuItem(selected = false, onClick = {}, text = "Trash")
            }
        }
    }

    @Composable
    private fun Top(open: Boolean) {
        FloatingFrame(
            bottom = 0.dp,
            scroll = null,
            top = {
                FloatingTop(
                    title = null,
                    trailing = { FloatingAction(AppIcons.Settings, "Settings", {}) },
                    center = {
                        HomeHeaderLayoutMobile { widthStyle ->
                            FilterCapsule(
                                filter = Filter.ALL, unreadCount = 3, spamCount = 2, spamOn = true,
                                onFilter = {}, onSearch = {}, modifier = widthStyle
                            )
                        }
                    }
                )
            },
            overlay = {
                if (open) Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().padding(horizontal = 12.dp)) { DropdownMenuGroups() }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(padding.calculateTopPadding()))
                Column(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.forEach { (c, name) ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            ConversationLine(
                                c, name = name, holdMedia = false, photo = null, look = null, pinned = false,
                                onOpen = {}, onPeek = {}, encrypted = false,
                                left = SwipeAction.NONE, right = SwipeAction.NONE, archived = false,
                                note = null, onSwipe = {},
                                modifier = Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun closed() = capture("list_top_draft") { Top(open = false) }

    @Test
    fun menu() = capture("list_top_menu_draft") { Top(open = true) }
}

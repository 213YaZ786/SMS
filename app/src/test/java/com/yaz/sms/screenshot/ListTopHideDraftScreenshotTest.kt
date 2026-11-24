package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingFrame
import com.yaz.sms.ui.component.FloatingTop
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
 * DRAFT for the user's yes (requests.md point 17): the list's top (the capsule and Settings) slides away when the list
 * goes down and comes back at the first move up, as the readers' Home, Google Messages and iOS Messages do. Two frames
 * of the same list scrolled the same: list_top_hide_draft_down (the top gone, the list under the status bar) and
 * list_top_hide_draft_up (a small move up: the top back over the list). The mechanics are Material 3's
 * enterAlwaysScrollBehavior: a TopAppBarState whose heightOffset runs from 0 (shown) to heightOffsetLimit (gone, minus
 * the top's own height), and the top drawn translated by that offset, as TopAppBar's own layout does. A swipe cannot be
 * played in a picture: the state is set to the value a swipe leaves (gone), and to 0 after the move up. The build
 * passes canScroll (true only where the list can scroll, so a list that fits keeps its top).
 *
 * Ports:
 * https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AppBar.kt#enterAlwaysScrollBehavior
 * (TopAppBarDefaults.enterAlwaysScrollBehavior(state, canScroll): its nestedScrollConnection is armed on the frame,
 * the offset is the one it leaves)
 * not ported: EnterAlwaysScrollBehavior's snap and fling animation specs (a picture has no motion), the deprecated
 * overloads, the reverseLayout variant (the list is not reversed)
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class ListTopHideDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    private fun capture(name: String, body: @Composable () -> Unit) {
        listOf(Triple("phone", "w411dp-h914dp-420dpi", false), Triple("phone", "w411dp-h914dp-420dpi", true), Triple("tablet", "w1280dp-h800dp-320dpi", false)).forEach { (device, qualifiers, night) ->
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
        talk(10, "I will send the pictures tonight", "+5511912345678") to "Camila Souza",
        talk(11, "Ok", "+919876543210") to "Priya Nair",
        talk(12, "See you tomorrow", "+447911123456") to "Oliver Smith"
    )

    // [gone]: the top as a swipe down leaves it; else as the first move up leaves it.
    @Composable
    private fun Frame(gone: Boolean) {
        val density = LocalDensity.current
        val headerPx = remember { mutableIntStateOf(0) }
        val statusPx = WindowInsets.statusBars.getTop(density)
        val state = rememberTopAppBarState(initialHeightOffsetLimit = -1f, initialHeightOffset = 0f, initialContentOffset = 0f)
        val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior(state, canScroll = { true })
        // The limit is the top's own height with the status bar, as the bar would measure it.
        val header = headerPx.intValue
        SideEffect {
            if (header > 0) {
                state.heightOffsetLimit = -(header + statusPx).toFloat()
                state.heightOffset = if (gone) state.heightOffsetLimit else 0f
            }
        }
        FloatingFrame(
            bottom = 0.dp,
            scroll = null,
            softEdge = !gone,
            top = {
                Box(
                    Modifier
                        .onSizeChanged { headerPx.intValue = it.height }
                        .graphicsLayer { translationY = state.heightOffset }
                ) {
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
                }
            }
        ) {
            // The list, scrolled the same in both frames, running under the status bar.
            Column(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(behavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState(initial = 700)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
    fun down() = capture("list_top_hide_draft_down") { Frame(gone = true) }

    @Test
    fun up() = capture("list_top_hide_draft_up") { Frame(gone = false) }
}

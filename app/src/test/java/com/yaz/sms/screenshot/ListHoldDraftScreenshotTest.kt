package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.sms.SwipeAction
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.feature.conversations.ConversationLine
import com.yaz.sms.feature.conversations.LINE_WIDTH
import com.yaz.sms.feature.conversations.RoomListState
import com.yaz.sms.ui.component.BalancedFlow
import com.yaz.sms.ui.component.HoldAction
import com.yaz.sms.ui.component.TileLabel
import com.yaz.sms.ui.component.ZoneSurface
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
import kotlin.math.roundToInt

/**
 * DRAFT for the user's yes (requests.md, long press on a conversation card): nothing of app/src/main changes. A long
 * press on a card opens the panes kit's HoldMenu in place of Peek (a centred Dialog): the card lifts where it stands
 * over the blurred list, its last two lines under the name, and the round glass actions sit under it, or over it when
 * the card is near the bottom (RoomListContextMenu's bottom sheet lists the same actions as rows, Element X; here they
 * are SMS's round glass tiles). Robolectric cannot draw a Dialog window nor its blur behind, so the three layers of
 * HoldMenu (blurred list, lifted copy, palette) are laid out here with the same numbers (scale 1.05, 4 dp up, a 10 dp
 * gap, the pane's padding and the tiles of HoldPalette); HoldAction is the app's own class.
 * Actions: Pin, Archive, Read or Unread, Select, Delete in red, Report spam.
 *
 * Ports:
 * https://raw.githubusercontent.com/element-hq/element-x-android/develop/features/home/impl/src/main/kotlin/io/element/android/features/home/impl/roomlist/RoomListContextMenu.kt#RoomListModalBottomSheetContent
 * (its rows: Mark as read or unread, Favourite, Settings, Report room, Leave room in red, become the six actions below;
 * hasNewContent chooses Read or Unread as here, canReportRoom is the spam setting)
 * not ported: RoomListContextMenu (the ModalBottomSheet is HoldMenu's Dialog, kept in the app), the eventSink and
 * RoomListEvent calls (no action runs in a picture), onRoomSettingsClick and onFavoriteChange's Switch row (SMS has no
 * room settings page and Pin is a plain action), ListItemStyle.Destructive (the danger flag of HoldAction)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class ListHoldDraftScreenshotTest {

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

    // Names of several countries, one long, one Arabic; the held card (a two line message) is marked in each list.
    private val people: List<Pair<Conversation, String>> = listOf(
        talk(1, "See you at eight", "+212661234567") to "Ahmed Benali",
        talk(2, "The documents are ready, I left them at the reception desk, please come and take them before noon", "+33612345678", unread = 3) to "Joëlle Martin",
        talk(3, "Thanks a lot", "+7916123456", unread = 1) to "Valentina Ivanova",
        talk(4, "On my way", "+81901234567", fromMe = true) to "Akira Tanaka",
        talk(5, "Call me back when you can, it is about the contract and the signature, I need it today", "+2348031234567", unread = 12) to "Marie-Christine Delacroix-Beaumont and her whole family",
        talk(6, "Dinner is booked for seven, bring the children and the cake", "+966501234567") to "فاطمة الزهراء",
        talk(7, "Meeting moved to Friday", "+34612345678") to "Sofía García",
        talk(8, "Your code is 482913", "+353851234567", unread = 1) to "Liam O'Connor",
        talk(9, "Happy birthday, many returns of the day", "+4915123456789") to "Jürgen Weber",
        talk(10, "I will send the pictures tonight", "+5511912345678") to "Camila Souza",
        talk(11, "Ok", "+919876543210") to "Priya Nair"
    )

    // As ConversationCardScreenshotTest's Card: a row as tall as its card, the card filling it.
    @Composable
    private fun Card(c: Conversation, name: String, modifier: Modifier = Modifier) {
        Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ConversationLine(
                c, name = name, holdMedia = false, photo = null, look = null, pinned = false,
                onOpen = {}, onPeek = {}, encrypted = false,
                left = SwipeAction.NONE, right = SwipeAction.NONE, archived = false,
                note = null, onSwipe = {},
                modifier = Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().fillMaxHeight()
            )
        }
    }

    // The app's own rows, the very list HoldMenu gets (ConversationsScreen.kt's RoomListModalBottomSheetContent).
    private fun actions(hasNewContent: Boolean, canReportRoom: Boolean = true): List<HoldAction> =
        com.yaz.sms.feature.conversations.RoomListModalBottomSheetContent(
            contextMenu = RoomListState.ContextMenu.Shown(
                roomName = null, hasNewContent = hasNewContent, isFavorite = false, isArchived = false, isSpam = false
            ),
            canReportRoom = canReportRoom,
            onRoomMarkReadClick = {}, onRoomMarkUnreadClick = {}, onFavoriteChange = {}, onArchiveChange = {},
            onRoomSelectClick = {}, onLeaveRoomClick = {}, onReportRoomClick = {}, onNotSpamClick = {}
        )

    /**
     * RoomListModalBottomSheetContent's rows as HoldPalette's pane: a glass pane, a round glass drop with the icon in the
     * accent (red when it takes something away) and its word under it, balanced in rows.
     */
    @Composable
    private fun RoomListModalBottomSheetContent(contextMenu: List<HoldAction>, modifier: Modifier) {
        ZoneSurface(shape = ShapeDefaults.ExtraLarge, shadowElevation = 6.dp, modifier = modifier) {
            Box(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                BalancedFlow(
                    labels = contextMenu.map { it.label },
                    style = MaterialTheme.typography.labelLarge,
                    floor = 78.dp, gap = 6.dp, rowGap = 10.dp
                ) { i, tileModifier ->
                    val action = contextMenu[i]
                    val tint = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = tileModifier.padding(vertical = 4.dp)) {
                        ZoneSurface(shape = CircleShape, modifier = Modifier.size(56.dp)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        TileLabel(
                            action.label, MaterialTheme.typography.labelLarge,
                            if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    /**
     * The list blurred and dimmed, the held card lifted where it stands, the palette under it (over it when the
     * card sits near the bottom, HoldMenu's fitsBelow). [held] is the index of the card; [bottom] packs the list
     * to the bottom of the window.
     */
    @Composable
    private fun Held(held: Int, bottom: Boolean, hasNewContent: Boolean) {
        val density = LocalDensity.current
        var bounds by remember { mutableStateOf<Rect?>(null) }
        var pane by remember { mutableStateOf(IntSize.Zero) }
        var window by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(density) { 10.dp.roundToPx() }
        val shown = if (bottom) people.subList(6, 11) + people[5] else people.take(7)
        val index = if (bottom) shown.lastIndex else held
        Box(Modifier.fillMaxSize().onSizeChanged { window = it }) {
            Box(Modifier.fillMaxSize().blur(16.dp), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = LINE_WIDTH).fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = if (bottom) Arrangement.Bottom else Arrangement.Top
                ) {
                    shown.forEachIndexed { i, (c, name) ->
                        Card(
                            c, name,
                            Modifier.padding(vertical = 4.dp).onGloballyPositioned { if (i == index) bounds = it.boundsInRoot() }
                        )
                    }
                    if (bottom) Spacer(Modifier.height(24.dp))
                }
            }
            // The veil: HoldMenu's BlurBehind dims the window as the blur rises.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            bounds?.let { b ->
                val width = with(density) { b.width.toDp() }
                val height = with(density) { b.height.toDp() }
                // The lifted copy: scale 1.05 around its centre, 4 dp up (HoldMenu's graphicsLayer).
                Box(
                    Modifier
                        .offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
                        .size(width, height)
                        .graphicsLayer {
                            scaleX = 1.05f
                            scaleY = 1.05f
                            translationY = -4.dp.toPx()
                        }
                ) { Card(shown[index].first, shown[index].second) }
                val x = (b.center.x - pane.width / 2f).roundToInt().coerceIn(0, maxOf(0, window.width - pane.width))
                val y = if (bottom) b.top.roundToInt() - gap - pane.height else b.bottom.roundToInt() + gap
                RoomListModalBottomSheetContent(
                    actions(hasNewContent),
                    Modifier.offset { IntOffset(x, y) }.onSizeChanged { pane = it }
                )
            }
        }
    }

    @Test
    fun middle() = capture("list_hold_draft") { Held(held = 1, bottom = false, hasNewContent = true) }

    @Test
    fun bottom() = capture("list_hold_bottom_draft") { Held(held = 0, bottom = true, hasNewContent = false) }
}

package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.security.MediaCheck
import com.yaz.sms.feature.thread.Cluster
import com.yaz.sms.feature.thread.Clusters
import com.yaz.sms.feature.thread.Delivery
import com.yaz.sms.feature.thread.MenuPlacement
import com.yaz.sms.feature.thread.MessageAction
import com.yaz.sms.feature.thread.MessageBody
import com.yaz.sms.feature.thread.MessageFooter
import com.yaz.sms.feature.thread.Palette
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
 * DRAFT for the user's yes (requests.md point 11): a message held, its reactions bar above, the bubble, the actions
 * pane below, all three hung on the bubble's side (start for a received message, end for a sent one) on ONE shared
 * edge, the same 12 dp between them, the bubble drawn where it is with no scale (today it is drawn 5 % larger around
 * its centre, so a sent bubble ends nearer the window's edge than the bar and the pane, which are centred then held
 * 12 dp inside the window). The placement is the app's own MenuPlacement.of (Signal's ReactionOverlayPlacement, in
 * feature/thread/MenuPlacement.kt, its cases in MenuPlacementTest), the one MessageMenu uses: the bar over the bubble,
 * the pane under it; no room under (a message near the bottom): pane and bar above; neither: the message slides up;
 * the 200 % font: the bar at the top, the message cut at a quarter of the window, the pane takes the rest and scrolls.
 * Real parts: MessageBody and MessageFooter in a ZoneSurface (the thread's bubble), Palette (the real pane, real
 * Palette colours), the bar drawn as MessageMenu draws it. Robolectric draws no Dialog nor window blur, so MessageMenu
 * itself cannot be drawn: the list is blurred and veiled inline as ListHoldDraftScreenshotTest does.
 * Pictures: message_menu_align_draft (a sent message), message_menu_align_received_draft (a received one),
 * message_menu_align_bottom_draft (near the bottom), message_menu_align_font200_draft (fontScale 2).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class MessageMenuAlignDraftScreenshotTest {

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

    // The thread: who wrote it, its words, its status.
    private class Line(val words: String, val mine: Boolean, val delivery: Delivery?)

    private val thread = listOf(
        Line("Tu es là ?", false, null),
        Line("Oui, j'arrive dans dix minutes", true, Delivery.Read),
        Line("Ok pour demain", false, null),
        Line("Ok pour demain", true, Delivery.Delivered),
        Line("On se voit à 14h devant la gare, ne sois pas en retard cette fois ci", false, null),
        Line("Parfait, à tout à l'heure", true, Delivery.Sent),
        Line("Ok", false, null),
        Line("Merci", true, Delivery.Sent)
    )

    @Composable
    private fun BubbleOf(line: Line, modifier: Modifier = Modifier) {
        ZoneSurface(shape = Clusters.corners(Cluster.SINGLE, line.mine), accent = line.mine, modifier = modifier) {
            MessageBody(
                words = AnnotatedString(line.words), plain = line.words, blurred = false, poll = null,
                visual = emptyList(), kinds = emptyList<MediaCheck.Kind?>(), held = true,
                footer = { mismatched, onMedia -> MessageFooter("10:42", line.delivery, false, onMedia = onMedia, mismatched = mismatched) },
                onMore = null, interactive = false, mine = line.mine
            )
        }
    }

    private val actions = listOf(
        MessageAction(AppIcons.Reply, "Reply") {},
        MessageAction(AppIcons.Copy, "Copy") {},
        MessageAction(AppIcons.SelectAll, "Select") {},
        MessageAction(AppIcons.Forward, "Forward") {},
        MessageAction(AppIcons.Translate, "Translate") {},
        MessageAction(AppIcons.Send, "Send again") {},
        MessageAction(AppIcons.Star, "Star") {},
        MessageAction(AppIcons.Info, "Details") {},
        MessageAction(AppIcons.Delete, "Delete", danger = true) {}
    )

    private val reactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    // The reactions bar as MessageMenu draws it: a glass capsule, 44 dp round places, the chosen one washed, a more button.
    @Composable
    private fun ReactionsBar(modifier: Modifier) {
        ZoneSurface(shape = CircleShape, shadowElevation = 6.dp, modifier = modifier) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                reactions.forEachIndexed { i, emoji ->
                    Box(
                        Modifier.size(44.dp).background(if (i == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Add, contentDescription = "More reactions", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    /**
     * The overlay: the list blurred and veiled, the held bubble drawn where it is, the bar and the pane on its edge by
     * [ReactionOverlayPlacement.of]. [held] is the index in [thread]; [bottom] packs the list to the window's bottom.
     */
    @Composable
    private fun Held(held: Int, bottom: Boolean, fontScale: Float = 1f) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            val density = LocalDensity.current
            var bounds by remember { mutableStateOf<Rect?>(null) }
            var bar by remember { mutableStateOf(IntSize.Zero) }
            var pane by remember { mutableStateOf(IntSize.Zero) }
            var natural by remember { mutableStateOf(0) }
            var window by remember { mutableStateOf(IntSize.Zero) }
            val status = WindowInsets.statusBars.getTop(density)
            val navigation = WindowInsets.navigationBars.getBottom(density)
            val shown = if (bottom) thread else thread.take(6)
            val index = if (bottom) shown.lastIndex else held
            Box(Modifier.fillMaxSize().onSizeChanged { window = it }) {
                Box(Modifier.fillMaxSize().blur(16.dp)) {
                    Column(
                        Modifier.fillMaxSize().padding(top = 56.dp, start = 12.dp, end = 12.dp, bottom = 24.dp),
                        verticalArrangement = if (bottom) Arrangement.spacedBy(6.dp, Alignment.Bottom) else Arrangement.spacedBy(6.dp)
                    ) {
                        shown.forEachIndexed { i, line ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = if (line.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                                Box(Modifier.fillMaxWidth(0.82f), contentAlignment = if (line.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                                    BubbleOf(line, Modifier.onGloballyPositioned { if (i == index) bounds = it.boundsInRoot() })
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                val b = bounds
                if (b != null && natural > 0 && bar.width > 0) {
                    val line = shown[index]
                    val place = MenuPlacement.of(
                        MenuPlacement.Metrics(
                            overlayWidth = window.width, overlayHeight = window.height,
                            statusBarHeight = status, navigationBarHeight = navigation,
                            bubbleX = b.left, bubbleY = b.top, bubbleWidth = b.width.roundToInt(),
                            snapshotHeight = b.height.roundToInt(),
                            reactionBarWidth = bar.width, reactionBarHeight = bar.height,
                            menuMaxWidth = pane.width, menuMaxHeight = natural,
                            isMessageOnLeft = !line.mine
                        )
                    ) { with(density) { it.dp.toPx() } }
                    // The held bubble, where it stands, no scale.
                    Box(
                        Modifier
                            .offset { IntOffset(b.left.roundToInt(), place.snapshotY.roundToInt()) }
                            .size(with(density) { b.width.toDp() }, with(density) { place.snapshotHeight.toDp() })
                    ) { BubbleOf(line) }
                    ReactionsBar(Modifier.offset { IntOffset(place.reactionBarX.roundToInt(), place.reactionBarY.roundToInt()) }.onSizeChanged { bar = it })
                    Palette(
                        actions, {},
                        Modifier.offset { IntOffset(place.menuOffsetX.roundToInt(), place.menuOffsetY.roundToInt()) }.onSizeChanged { pane = it },
                        maxHeight = place.menuHeight?.let { with(density) { it.toDp() } } ?: Dp.Unspecified,
                        onNatural = { natural = it }
                    )
                } else {
                    // First pass: measure the bar and the pane where nobody sees them.
                    ReactionsBar(Modifier.offset { IntOffset(-4000, 0) }.onSizeChanged { bar = it })
                    Palette(actions, {}, Modifier.offset { IntOffset(-4000, 0) }.onSizeChanged { pane = it }, onNatural = { natural = it })
                }
            }
        }
    }

    @Test
    fun sent() = capture("message_menu_align_draft") { Held(held = 3, bottom = false) }

    @Test
    fun received() = capture("message_menu_align_received_draft") { Held(held = 2, bottom = false) }

    @Test
    fun bottom() = capture("message_menu_align_bottom_draft") { Held(held = 0, bottom = true) }

    @Test
    fun font200() = capture("message_menu_align_font200_draft") { Held(held = 3, bottom = false, fontScale = 2f) }

    // Signal's ReactionOverlayPlacementTest cases live in feature/thread/MenuPlacementTest, on the same MenuPlacement.
}

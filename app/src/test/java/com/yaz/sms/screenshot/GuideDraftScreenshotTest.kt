package com.yaz.sms.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.yaz.sms.feature.main.WelcomeContent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.sms.SwipeAction
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.feature.conversations.ConversationLine
import com.yaz.sms.feature.conversations.LINE_WIDTH
import com.yaz.sms.ui.component.LoadingMark
import com.yaz.sms.ui.component.SetupTile
import com.yaz.sms.ui.component.SetupTileGrid
import com.yaz.sms.ui.component.StartRevealOverlay
import com.yaz.sms.ui.component.StartRevealState
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.component.startRevealHole
import com.yaz.sms.ui.glass.LocalGlass
import com.yaz.sms.ui.glass.glassGround
import com.yaz.sms.ui.glass.rememberGlassLook
import com.yaz.sms.ui.icon.AppIcons
import com.yaz.sms.ui.theme.AppTheme
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * DRAFT for the user's yes (requests.md, the guide with the round Start): nothing of app/src/main changes. WelcomeScreen.kt's
 * WelcomeContent (lines 192 to 234) becomes: the animated LoadingMark on top (stopped here: a picture), one centred
 * bodyLarge sentence in place of "Welcome to SMS", no "N left" and no "Still needed" line (a refused step shows its
 * refusal on its own tile: Notifications below), the tiles, then Start as the panes kit's round cookie (Cookie9Sided,
 * FabMedium's 80 dp, an arrow), the whole block centred in height. WhatsNewView's order (a title block, the features,
 * one primary button at the foot) is the model. Second picture: a filmstrip of StartReveal opening from Start at 0,
 * 350, 900 and 1750 ms, drawn with the real RuntimeShader (the app's StartRevealState, startRevealHole and
 * StartRevealOverlay, called as MainTabs will call them) on a stopped test clock, Robolectric NATIVE, sdk 36.
 *
 * Ports:
 * https://raw.githubusercontent.com/SvenTiigi/WhatsNewKit/main/Sources/View/WhatsNewView.swift#WhatsNewView
 * https://github.com/android/snippets/blob/main/compose/snippets/src/main/java/com/example/compose/snippets/graphics/ShapesSnippets.kt#MorphPolygonShape
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MaterialShapes.kt#cookie9
 * not ported: WhatsNewView's WhatsNew.Layout, Feature rows with their images, primaryAction's Button style and
 * secondaryAction, the SwiftUI environment (the app's SetupTileGrid and WelcomeContent hold the tiles and the order)
 * not ported: a feed under the guide (a list of conversation cards stands under it), the other shapes of MaterialShapes
 */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class GuideDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    private val times = listOf(0, 350, 900, 1750)

    private fun tile(
        id: String, name: String, icon: ImageVector, state: String, done: Boolean, required: Boolean = false, error: Boolean = false
    ) = SetupTile(
        id = id, name = name, title = name, icon = icon, state = state, summary = "", done = done,
        required = required, error = error, onClick = {}
    )

    // Notifications was refused for good: its refusal stands on its own tile, nowhere else.
    private val tiles = listOf(
        tile("role", "Messaging app", AppIcons.Message, "SMS", done = true, required = true),
        tile("notifications", "Notifications", AppIcons.NotificationsOn, "Refused, open settings", done = false, error = true),
        tile("chat", "Encrypted chat", AppIcons.Lock, "On", done = true),
        tile("card", "Name and photo", AppIcons.Person, "Shared", done = true),
        tile("updates", "Updates", AppIcons.Update, "Allowed", done = true)
    )

    private fun talk(id: Long, snippet: String, address: String, unread: Int = 0) =
        Conversation(id, address, snippet, 1_600_000_000_000L + id * 86_400_000L, unread, false, failed = false)

    private val people: List<Pair<Conversation, String>> = listOf(
        talk(1, "See you at eight", "+212661234567") to "Ahmed Benali",
        talk(2, "The documents are ready, I left them at the reception desk", "+33612345678", unread = 3) to "Joëlle Martin",
        talk(3, "Thanks a lot", "+7916123456", unread = 1) to "Valentina Ivanova",
        talk(4, "On my way", "+81901234567") to "Akira Tanaka",
        talk(5, "Call me back when you can", "+2348031234567", unread = 12) to "Marie-Christine Delacroix-Beaumont",
        talk(6, "Dinner is booked for seven", "+966501234567") to "فاطمة الزهراء",
        talk(7, "Meeting moved to Friday", "+34612345678") to "Sofía García"
    )

    private fun themed(night: Boolean, body: @Composable () -> Unit) {
        compose.activity.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(darkTheme = night, dynamicColor = false) {
                    val look = rememberGlassLook(MaterialTheme.colorScheme, true, quiet = true)
                    CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        Box(Modifier.testTag("stage").fillMaxSize().clipToBounds().glassGround(look, MaterialTheme.colorScheme.background)) { body() }
                    }
                }
            }
        }
    }

    @Test
    fun guide() {
        // The mark moves on its own: the clock is stopped at a fixed instant so the picture is the same each time.
        compose.mainClock.autoAdvance = false
        listOf(Triple("phone", "w411dp-h914dp-420dpi", false), Triple("phone", "w411dp-h914dp-420dpi", true), Triple("tablet", "w1280dp-h800dp-320dpi", false))
            .forEach { (device, qualifiers, night) ->
                RuntimeEnvironment.setQualifiers(qualifiers)
                themed(night) { WelcomeContent(tiles, reopened = false, onStart = {}) }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(500)
                compose.onRoot().captureRoboImage(
                    "src/test/screenshots/guide_draft_${device}_${if (night) "dark" else "light"}.png",
                    roborazziOptions = options
                )
            }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun revealLight() = filmstrip(false)

    @Test
    fun revealDark() = filmstrip(true)

    // The clock stopped, advanced to each instant, a frame captured at each.
    private fun filmstrip(night: Boolean) {
        RuntimeEnvironment.setQualifiers("w411dp-h914dp-420dpi")
        val go = mutableStateOf(false)
        val state = StartRevealState()
        compose.mainClock.autoAdvance = false
        themed(night) { Reveal(go.value, state) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        // Start's centre, where the reveal opens from (the real button gives it at a tap).
        state.origin = compose.onNodeWithContentDescription("Start").fetchSemanticsNode().boundsInRoot.center
        compose.runOnIdle { go.value = true }
        val frames = mutableListOf<Bitmap>()
        val played = mutableListOf<Int>()
        var elapsed = 0
        times.forEach { t ->
            compose.mainClock.advanceTimeBy((t - elapsed).toLong())
            elapsed = t
            played += compose.runOnIdle { state.currentPlayTime.roundToInt() }
            frames += compose.onNodeWithTag("stage").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        }
        compose.mainClock.autoAdvance = true
        sheet(night, frames, played)
    }

    // The frames joined, the ms under each.
    private fun sheet(night: Boolean, frames: List<Bitmap>, played: List<Int>) {
        val scale = 0.6f
        val w = (frames[0].width * scale).toInt()
        val h = (frames[0].height * scale).toInt()
        val ms = 40
        val gap = 12
        val sheet = Bitmap.createBitmap((w + gap) * times.size + gap, h + ms + gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(); textSize = 26f }
        val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.forEachIndexed { i, frame ->
            val left = gap + i * (w + gap)
            canvas.drawBitmap(frame, null, AndroidRect(left, gap, left + w, gap + h), smooth)
            canvas.drawText("${played[i]} ms", left + 4f, gap + h + 28f, text)
        }
        sheet.captureRoboImage(
            "src/test/screenshots/guide_reveal_draft_phone_${if (night) "dark" else "light"}.png",
            roborazziOptions = options
        )
    }

    // The list under, the guide over it with the ripple's hole, the ripple above both.
    @Composable
    private fun Reveal(go: Boolean, state: StartRevealState) {
        LaunchedEffect(go) {
            if (go) state.startRipple {}
        }
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
            Box(Modifier.fillMaxSize().startRevealHole(state).glassGround(LocalGlass.current, MaterialTheme.colorScheme.background)) {
                WelcomeContent(tiles, reopened = false, onStart = {})
            }
            StartRevealOverlay(state)
        }
    }
}


package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.sms.SwipeAction
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.feature.conversations.ConversationLine
import com.yaz.sms.feature.conversations.LINE_WIDTH
import com.yaz.sms.ui.glass.LocalGlass
import com.yaz.sms.ui.glass.glassGround
import com.yaz.sms.ui.glass.rememberGlassLook
import com.yaz.sms.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Requests.md point 14: the unread count on the message's first baseline. The cards are the app's own ConversationLine:
 * the count (UnreadBadge, a Text with Modifier.alignByBaseline()) and the message block (FirstLine, which hands its
 * first baseline up) are children of one Row aligned by their first baseline, whatever the font size, the script or a
 * subject above the text. Pictures: list_edge_draft (right to left, 99+ asked as 120, a very long name, a subject)
 * and list_edge_font200_draft (the same cards at the 200 % font). The case of a picture lead is not drawn: its part is
 * read from a content Uri that Robolectric has none of.
 *
 * Ports:
 * https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/Row.kt#alignByBaseline
 * (used as is, in app/src/main: ConversationsScreen.kt's ConversationLine; this test draws it)
 * not ported: RowScope.alignBy(alignmentLine) and alignBy(alignmentLineBlock) (no other line is aligned),
 * RowScope.weight and align (called, not redeclared), Row itself (the library's)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class ListEdgeDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    private fun capture(name: String, body: @Composable () -> Unit) {
        listOf("phone" to "w411dp-h914dp-420dpi").forEach { (device, qualifiers) ->
            RuntimeEnvironment.setQualifiers(qualifiers)
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
                    "src/test/screenshots/${name}_${device}_${if (night) "dark" else "light"}.png",
                    roborazziOptions = options
                )
            }
        }
    }

    private var next = 200L

    /** One card, the app's own ConversationLine. */
    @Composable
    private fun Card(name: String, message: String, unread: Int, address: String, subject: String? = null) {
        val id = remember(name) { next++ }
        val talk = Conversation(id, address, message, 1_600_000_000_000L + id * 86_400_000L, unread, fromMe = false, failed = false, subject = subject)
        Row(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().height(IntrinsicSize.Min)) {
            ConversationLine(
                talk, name = name, holdMedia = false, photo = null, look = null, pinned = false,
                onOpen = {}, onPeek = {}, encrypted = false,
                left = SwipeAction.NONE, right = SwipeAction.NONE, archived = false,
                note = null, onSwipe = {},
                modifier = Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().fillMaxHeight()
            )
        }
    }

    @Composable
    private fun Cases(fontScale: Float) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            Column(
                Modifier.fillMaxSize().padding(top = 56.dp, start = 8.dp, end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card("Ahmed Benali", "See you at eight", 1, "+212661234567")
                Card("فاطمة الزهراء", "العشاء محجوز على الساعة السابعة، أحضري الأطفال والكعكة", 3, "+966501234567")
                Card("Marie-Christine Delacroix-Beaumont and her whole family", "Call me back when you can, it is about the contract and the signature, I need it today", 120, "+2348031234567")
                Card("Joëlle Martin", "On se voit demain à 14h devant la gare", 2, "+33612345678", subject = "Rendez-vous")
            }
        }
    }

    @Test
    fun edge() = capture("list_edge_draft") { Cases(1f) }

    @Test
    fun font200() = capture("list_edge_font200_draft") { Cases(2f) }
}

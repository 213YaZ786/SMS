package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.sms.SwipeAction
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.feature.conversations.ConversationLine
import com.yaz.sms.feature.conversations.LINE_WIDTH
import com.yaz.sms.feature.conversations.ServicesStack
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
 * DRAFT for the user's yes (requests.md point 10): no "People" heading. The Services card, then the people's cards,
 * the two set apart by a larger gap (Google Messages, iOS Messages and Signal show no label there). Same data as
 * PeopleHeadingDraftScreenshotTest, drawn with the app's own ServicesStack and ConversationLine; nothing of
 * app/src/main changes in the picture (the build removes the Heading call).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class ListNoHeadingDraftScreenshotTest {

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

    private val services = listOf(
        talk(21, "Your parcel is out for delivery", "AMAZON", unread = 2),
        talk(22, "Your code is 482913", "38600", unread = 1),
        talk(23, "Balance: 1 204,50 EUR", "BNPPARIBAS")
    )

    private val people: List<Pair<Conversation, String>> = listOf(
        talk(1, "See you at eight", "+212661234567") to "Ahmed Benali",
        talk(2, "The documents are ready, I left them at the reception desk, please come and take them before noon", "+33612345678", unread = 3) to "Joëlle Martin",
        talk(3, "Thanks a lot", "+7916123456", unread = 1) to "Valentina Ivanova",
        talk(4, "On my way", "+81901234567", fromMe = true) to "Akira Tanaka",
        talk(5, "Call me back when you can, it is about the contract and the signature", "+2348031234567", unread = 12) to "Marie-Christine Delacroix-Beaumont and her whole family",
        talk(6, "Dinner is booked for seven", "+966501234567") to "فاطمة الزهراء",
        talk(7, "Meeting moved to Friday", "+34612345678") to "Sofía García"
    )

    @Test
    fun noHeading() = capture("list_no_heading_draft") {
        Column(
            Modifier.fillMaxSize().padding(top = 56.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // The list's order: the people's cards, then the Services card, the two set apart by a gap and no label.
            people.forEach { (c, name) ->
                Row(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().height(IntrinsicSize.Min)) {
                    ConversationLine(
                        c, name = name, holdMedia = false, photo = null, look = null, pinned = false,
                        onOpen = {}, onPeek = {}, encrypted = false,
                        left = SwipeAction.NONE, right = SwipeAction.NONE, archived = false,
                        note = null, onSwipe = {},
                        modifier = Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().fillMaxHeight()
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ServicesStack(services, open = false, onToggle = {})
        }
    }
}

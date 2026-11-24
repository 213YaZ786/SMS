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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.sms.SwipeAction
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.feature.conversations.ConversationLine
import com.yaz.sms.feature.conversations.Heading
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
 * The "People" heading (requests.md point 3), drawn with the app's own composables: HomeParts.kt's Heading (bold,
 * centred, titleMedium, the style of the "Services" card's title) between the Services stack and the people's cards.
 *
 * Ports:
 * https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Typography.kt#Typography
 * (the scale's titleMedium and titleSmall as MaterialTheme.typography gives them; Bold is a FontWeight on top, as
 * ServicesStack's own title does when something is unread)
 * not ported: Typography's copy, fromToken, the other styles of the scale (displayLarge to labelSmall and the emphasized
 * set: no other style is drawn here), the deprecated constructor
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class PeopleHeadingDraftScreenshotTest {

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

    // The heading is the app's own: HomeParts.kt's Heading, titleMedium, bold, centred, onSurface.
    @Test
    fun heading() = capture("list_people_heading_draft") {
        Column(
            Modifier.fillMaxSize().padding(top = 56.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ServicesStack(services, open = false, onToggle = {})
            Heading("People")
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

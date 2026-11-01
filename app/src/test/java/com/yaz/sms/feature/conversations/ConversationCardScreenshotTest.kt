package com.yaz.sms.feature.conversations

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.yaz.sms.core.sms.SwipeAction
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.feature.thread.Delivery
import com.yaz.sms.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The conversation card as drawn on a phone and a tablet, by day and by night, compared with the pictures kept in
 * src/test/screenshots: the time centred on the name's line, the unread count at the end of the message's first
 * line, the face centred on the two lines. Same harness as PersonPageScreenshotTest, taken from Now in Android's
 * captureForDevice (https://github.com/android/nowinandroid, core/screenshot-testing/.../ScreenshotHelper.kt).
 * Dates are fixed years back so the time reads "d MMM yyyy" whatever the day the test runs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the app's own starts the chat engine, none of the screen's business.
@Config(application = android.app.Application::class, sdk = [36])
class ConversationCardScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Now in Android's DefaultTestDevices, phone and tablet.
    private val devices = listOf(
        "phone" to "w411dp-h914dp-420dpi",
        "tablet" to "w1280dp-h800dp-320dpi"
    )

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    private fun capture(name: String, body: @Composable () -> Unit) {
        devices.forEach { (device, qualifiers) ->
            RuntimeEnvironment.setQualifiers(qualifiers)
            listOf(false, true).forEach { night ->
                compose.activity.setContent {
                    CompositionLocalProvider(LocalInspectionMode provides true) {
                        AppTheme(darkTheme = night, dynamicColor = false) { body() }
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

    private fun talk(
        id: Long, snippet: String, unread: Int = 0, fromMe: Boolean = false, address: String = "+33612345678", failed: Boolean = false
    ) = Conversation(id, address, snippet, 1_600_000_000_000L + id * 86_400_000L, unread, fromMe, failed = failed)

    // As CardRow lays the cards of the list: a row as tall as its card, the card filling it.
    @Composable
    private fun Card(
        c: Conversation, name: String?, note: String? = null, pinned: Boolean = false, encrypted: Boolean = false,
        delivery: Delivery? = null, silenced: Boolean = false
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ConversationLine(
                c, name = name, holdMedia = false, photo = null, look = null, pinned = pinned,
                onOpen = {}, onPeek = {}, encrypted = encrypted,
                left = SwipeAction.NONE, right = SwipeAction.NONE, archived = false,
                note = note, onSwipe = {}, delivery = delivery, silenced = silenced,
                modifier = Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().fillMaxHeight()
            )
        }
    }

    @Test
    fun cards() = capture("list_cards") {
        Column(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // A one line message, read.
            Card(talk(1, "See you at eight"), "Ahmed")
            // A two line message, unread, count 3, the lock on the face.
            Card(
                talk(2, "The documents are ready, I left them at the reception desk, please come and take them before noon", unread = 3),
                "Joëlle Martin", encrypted = true
            )
            // A long name with a time (the note stands where the time is).
            Card(talk(3, "Thanks a lot", unread = 1), "Marie-Christine Delacroix-Beaumont and her whole family", note = "12:40", pinned = true)
            // A count past nine.
            Card(talk(4, "Call me back when you can, it is about the contract and the signature", unread = 12), "Valentina Ivanova")
            // A message the user sent.
            Card(talk(5, "On my way", fromMe = true), "Bob")
            // A number alone, no name.
            Card(talk(6, "Your code is 482913", unread = 2, address = "+33799887766"), null)
            // The user's own message, delivered: the two ticks before the time.
            Card(talk(7, "Dinner is booked", fromMe = true), "Leila", delivery = Delivery.Delivered)
            // The user's own message that did not leave: the error mark before the time.
            Card(talk(8, "Are you there?", fromMe = true, failed = true), "Karim", delivery = Delivery.Failed)
            // A silenced conversation: the bell after the name.
            Card(talk(9, "Meeting moved to Friday"), "Team Atlas", silenced = true)
        }
    }
}

package com.yaz.sms.feature.thread

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.data.sms.Box
import com.yaz.sms.data.sms.Message
import com.yaz.sms.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The person page as drawn on a phone and a tablet, by day and by night,
 * compared with the pictures kept in src/test/screenshots: a block moved
 * or cut in two fails the build. Taken from Now in Android's
 * captureForDevice (https://github.com/android/nowinandroid,
 * core/screenshot-testing/.../ScreenshotHelper.kt): the screen's size set
 * through Robolectric's qualifiers, inspection mode on, one picture each.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the app's own starts the chat engine, none of the screen's business.
@Config(application = android.app.Application::class, sdk = [36])
class PersonPageScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Now in Android's DefaultTestDevices, phone and tablet.
    private val devices = listOf(
        "phone" to "w411dp-h914dp-420dpi",
        "tablet" to "w1280dp-h800dp-320dpi"
    )

    private val options = RoborazziOptions(
        // Pixel-perfect, as Now in Android's DefaultRoborazziOptions: a word changed is a change.
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    // As captureForDevice: the qualifiers first, then the content set on the activity again.
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

    private fun said(id: Long, body: String, box: Box = Box.RECEIVED) =
        Message(id, 1, "+33612345678", body, 1_791_000_000_000L + id * 60_000, box, read = true, delivered = true, subId = 1)

    private val talk = listOf(
        said(1, "Le code est 482913"),
        said(2, "Regarde https://www.example.com/?utm_source=x&id=3"),
        said(3, "On se voit demain à 14h", Box.SENT)
    )

    @Test
    fun contact() = capture("person_contact") {
        PersonPage(
            name = "Marie-Christine Delacroix-Beaumont", number = "0612345678", address = "+33612345678",
            photo = null, contactId = 12, group = false, encrypted = false,
            messages = talk, stranger = { false },
            calls = CallChoices(phone = {}, encrypted = null, video = null),
            onBackground = {}, onEncryption = {}, onName = {}, onClose = {}
        )
    }

    // A group over the encrypted chat and one by SMS: the picture, the members, leaving, mentions
    // (Delta Chat Android's group profile, https://github.com/deltachat/deltachat-android, ProfileFragment).
    private fun group(chat: Boolean, left: Boolean = false) = GroupTools(
        members = listOf(
            GroupMember("+33612345678", "Marie-Christine Delacroix-Beaumont"),
            GroupMember("+33699887766", "Ahmed"),
            GroupMember("+33611223344", "Joëlle Martin")
        ),
        chat = chat, left = left, picture = null, mentionsOn = true,
        onMentions = {}, onPicture = {}, onRemove = {}, onLeave = {}
    )

    @Test
    fun groupChat() = capture("person_group_chat") {
        PersonPage(
            name = "Marie-Christine, Ahmed, Joëlle", number = null, address = "+33612345678",
            photo = null, contactId = null, group = true, encrypted = true,
            messages = talk, stranger = { false },
            calls = CallChoices(phone = null, encrypted = null, video = null),
            onBackground = {}, onEncryption = {}, onName = {},
            groupTools = group(chat = true), onClose = {}
        )
    }

    @Test
    fun groupSms() = capture("person_group_sms") {
        PersonPage(
            name = "Marie-Christine, Ahmed, Joëlle", number = null, address = "+33612345678",
            photo = null, contactId = null, group = true, encrypted = false,
            messages = talk, stranger = { false },
            calls = CallChoices(phone = null, encrypted = null, video = null),
            onBackground = {}, onEncryption = {}, onName = {},
            groupTools = group(chat = false), onClose = {}
        )
    }

    @Test
    fun stranger() = capture("person_stranger") {
        PersonPage(
            name = "+33 7 99 88 77 66", number = null, address = "+33799887766",
            photo = null, contactId = null, group = false, encrypted = false,
            messages = talk, stranger = { true },
            calls = CallChoices(phone = {}, encrypted = null, video = null),
            onBackground = null, onEncryption = {}, onName = {}, onClose = {}
        )
    }
}

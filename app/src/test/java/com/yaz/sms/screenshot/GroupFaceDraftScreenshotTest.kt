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
import com.yaz.sms.feature.conversations.PinnedFace
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
 * Requests.md point 13: a group's face in the list as QKSMS's GroupAvatarView draws it (group_avatar_view.xml): two
 * frames of 0.65 of the card's face (48 dp), the second member at the top start with no ring, the first at the bottom
 * end in a circle of the card's colour (the ring). The cards are the app's own ConversationLine and its GroupAvatarView
 * (feature/conversations/GroupFace.kt), no copy of them here; the members are monograms, a photo being loaded from a
 * content Uri that Robolectric has none of. A group of one person shows one face whole; more than two members: the
 * first two, the card says the count. Pictures: group_face_draft and group_face_now (the same cards: the face of
 * today is the new one).
 *
 * Ports:
 * https://raw.githubusercontent.com/moezbhatti/qksms/master/presentation/src/main/java/com/moez/QKSMS/common/widget/GroupAvatarView.kt#GroupAvatarView
 * (the layout itself is in app/src/main, GroupFace.kt; this test draws it)
 * not ported: onFinishInflate and isInEditMode (a View's inflate hooks), the recipients' sort by lookupKey,
 * setRecipient and setBackgroundTint (the app's own ContactAvatar and a ring colour passed in)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class GroupFaceDraftScreenshotTest {

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

    private class Group(val name: String, val message: String, val unread: Int, val members: List<String>)

    // Three groups: 2, 3 and 6 members.
    private val groups = listOf(
        Group("Ahmed and Joëlle", "Are we still on for dinner?", 2, listOf("Ahmed Benali", "Joëlle Martin")),
        Group("Family trip", "The tickets are booked, I sent them to everyone", 0, listOf("Valentina Ivanova", "Akira Tanaka", "Sofía García")),
        Group(
            "Marie-Christine Delacroix-Beaumont and the whole football club", "Training moved to Friday evening, bring the new shirts", 12,
            listOf("Marie-Christine Delacroix-Beaumont", "فاطمة الزهراء", "Liam O'Connor", "Jürgen Weber", "Camila Souza", "Priya Nair")
        )
    )

    // The app's own ConversationLine and GroupAvatarView, the same groups and names.
    @Composable
    private fun Cards() {
        Column(
            Modifier.fillMaxSize().padding(top = 56.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groups.forEachIndexed { i, g ->
                Row(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().height(IntrinsicSize.Min)) {
                    ConversationLine(
                        talk(100L + i, g.message, "+3361234567$i", unread = g.unread), name = g.name,
                        holdMedia = false, photo = null, look = null, pinned = false,
                        onOpen = {}, onPeek = {}, encrypted = false,
                        left = SwipeAction.NONE, right = SwipeAction.NONE, archived = false,
                        note = null, onSwipe = {},
                        members = g.members.map { PinnedFace(it, null, null) },
                        modifier = Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().fillMaxHeight()
                    )
                }
            }
        }
    }

    @Test
    fun draft() = capture("group_face_draft") { Cards() }
}

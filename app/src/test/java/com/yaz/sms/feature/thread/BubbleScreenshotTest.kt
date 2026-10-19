package com.yaz.sms.feature.thread

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.core.security.MediaCheck
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.theme.AppTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The message bubbles' footers as drawn on a phone and a tablet, by day and by night: the time on the
 * last line when it fits, under the words when it does not or when the words run both ways, with the
 * ticks of each status, and over a held picture or under a grid of them. Same protocol as
 * PersonPageScreenshotTest (Now in Android's captureForDevice,
 * https://github.com/android/nowinandroid, core/screenshot-testing/.../ScreenshotHelper.kt).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class BubbleScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
                        AppTheme(darkTheme = night, dynamicColor = false) {
                            // The page's ground and text colour, as the app's screens give them (https://github.com/android/nowinandroid screenshot tests wrap their content in a Surface).
                            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { body() }
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

    // A bubble as the conversation draws it: its width a share of the list's, its side by who wrote it.
    @Composable
    private fun Bubble(
        words: String,
        mine: Boolean,
        delivery: Delivery?,
        share: Float = 0.82f,
        visual: List<MmsPart> = emptyList(),
        starred: Boolean = false,
        cluster: Cluster = Cluster.SINGLE,
        tiles: List<MmsPart> = emptyList()
    ) {
        Box(
            Modifier.fillMaxWidth(share).padding(top = cluster.top, bottom = cluster.bottom),
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            ZoneSurface(shape = Clusters.corners(cluster, mine), accent = mine) {
                MessageBody(
                    words = AnnotatedString(words), plain = words, blurred = false, poll = null,
                    visual = visual, kinds = visual.map { MediaCheck.Kind.IMAGE }, held = true,
                    footer = { mismatched, onMedia -> MessageFooter("10:42", delivery, starred, onMedia = onMedia, mismatched = mismatched) },
                    onMore = null,
                    tiles = tiles, mine = mine
                )
            }
        }
    }

    // Fixed parts as Element X's file previews (TimelineItemFileContentPreviewParam, element-hq/element-x-android): a PDF and a card.
    private fun files() = listOf(
        MmsPart(Uri.parse("content://t/f1"), "application/pdf", "Facture octobre.pdf"),
        MmsPart(Uri.parse("content://t/f2"), "text/x-vcard", "contact.vcf")
    )

    private fun pictures(n: Int) = List(n) { MmsPart(Uri.parse("content://t/$it"), "image/jpeg", null) }

    @Test
    fun words() = capture("bubble_words") {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Bubble("Ok", false, null)
            Bubble("Ok", true, Delivery.Sending)
            Bubble("On se voit demain à 14h", true, Delivery.Sent)
            Bubble("On se voit demain à 14h devant la gare, ne sois pas en retard", true, Delivery.Delivered)
            Bubble("On se voit demain à 14h devant la gare, ne sois pas en retard cette fois ci, merci beaucoup", true, Delivery.Read, starred = true)
            Bubble("Pas envoyé", true, Delivery.Failed)
        }
    }

    @Test
    fun both_directions() = capture("bubble_directions") {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Bubble("مرحبا بك", false, null)
            Bubble("مرحبا بك في تطبيق الرسائل الجديد الذي يجمع كل ما تحتاجه للتواصل مع أصدقائك", true, Delivery.Read)
            Bubble("Rendez-vous au café مقهى النجوم demain", true, Delivery.Delivered)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Bubble("مرحبا بك", false, null)
                Bubble("مرحبا بك في تطبيق الرسائل الجديد الذي يجمع كل ما تحتاجه للتواصل مع أصدقائك", true, Delivery.Sent)
                Bubble("Ok", true, Delivery.Sent)
            }
        }
    }

    // Runs of messages of one sender: the corners and the margins by the place in the run (Signal's V2ConversationItemShape).
    @Test
    fun clusters() = capture("bubble_clusters") {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Bubble("Salut", false, null, cluster = Cluster.START)
            Bubble("Tu es là ?", false, null, cluster = Cluster.MIDDLE)
            Bubble("J'arrive dans dix minutes, devant la gare", false, null, cluster = Cluster.END)
            Bubble("Ok", true, Delivery.Read, cluster = Cluster.START)
            Bubble("À tout de suite, je suis déjà au café", true, Delivery.Read, cluster = Cluster.MIDDLE)
            Bubble("Ok", true, Delivery.Delivered, cluster = Cluster.END)
            Bubble("Seul", true, Delivery.Sent, cluster = Cluster.SINGLE)
        }
    }

    // A file, a card: inside the one bubble above the words, as Element X's file view with its caption
    // (https://github.com/element-hq/element-x-android, TimelineItemEventContentView.kt, TimelineItemFileView.kt).
    @Test
    fun fileTiles() = capture("bubble_files") {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Bubble("", true, Delivery.Delivered, 0.78f, tiles = files().take(1))
            Bubble("La facture dont je te parlais", false, null, 0.78f, tiles = files().take(1))
            Bubble("", false, null, 0.78f, cluster = Cluster.START, tiles = files().drop(1))
            Bubble("Son contact", false, null, 0.78f, cluster = Cluster.END, tiles = files())
        }
    }

    @Test
    fun media() = capture("bubble_media") {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Bubble("", true, Delivery.Delivered, 0.78f, pictures(1))
            Bubble("Regarde la vue d'ici", false, null, 0.78f, pictures(1))
            Bubble("", true, Delivery.Sent, 0.78f, pictures(3))
            Bubble("Les photos de samedi, toutes en vrac", true, Delivery.Read, 0.78f, pictures(6))
        }
    }
}

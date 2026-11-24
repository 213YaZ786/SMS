package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.security.MediaCheck
import com.yaz.sms.feature.thread.Cluster
import com.yaz.sms.feature.thread.Clusters
import com.yaz.sms.feature.thread.Delivery
import com.yaz.sms.feature.thread.MessageBody
import com.yaz.sms.feature.thread.MessageFooter
import com.yaz.sms.ui.component.ZoneSurface
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
 * Locks today's code (requests.md point 15), no change of app code: the thread's real bubbles (MessageBody,
 * MessageFooter, Clusters.corners, and through them ContentAvoidingLayout) at the 200 % font on a phone
 * (bubble_edge_draft), on a 320 dp wide window (bubble_edge_narrow_draft) and on both (bubble_edge_narrow_font200_draft).
 * Cases: a short word, a long word with no space, a link, Arabic, mixed Arabic and Latin, a sentence long enough to wrap
 * twice; the time beside the words when the last line has room, under them when it has not. The Bubble is
 * BubbleScreenshotTest's. The device is set by qualifiers and drawn light then dark, as Now in Android's
 * captureForDevice and captureMultiTheme.
 *
 * Ports:
 * https://raw.githubusercontent.com/android/nowinandroid/main/core/screenshot-testing/src/main/kotlin/com/google/samples/apps/nowinandroid/core/testing/util/ScreenshotHelper.kt#captureForDevice
 * (deviceSpec to qualifiers, LocalInspectionMode provided, the body drawn, one picture per theme)
 * not ported: DeviceConfigurationOverride (the dark mode is AppTheme's darkTheme), the accessibility checks
 * (accessibilitySuppressions: not run on a draft), captureMultiDevice, extractSpecs, generateDescription (the
 * qualifiers are written whole here)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class BubbleEdgeDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    private fun captureForDevice(deviceName: String, deviceSpec: String, screenshotName: String, body: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(deviceSpec)
        listOf(false, true).forEach { darkMode ->
            compose.activity.setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AppTheme(darkTheme = darkMode, dynamicColor = false) {
                        val look = rememberGlassLook(MaterialTheme.colorScheme, true, quiet = true)
                        CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                            Box(Modifier.fillMaxSize().glassGround(look, MaterialTheme.colorScheme.background)) { body() }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(
                "src/test/screenshots/${screenshotName}_${deviceName}_${if (darkMode) "dark" else "light"}.png",
                roborazziOptions = roborazziOptions
            )
        }
    }

    // A bubble as the conversation draws it (BubbleScreenshotTest's).
    @Composable
    private fun Bubble(words: String, mine: Boolean, delivery: Delivery?, share: Float = 0.82f, cluster: Cluster = Cluster.SINGLE) {
        Box(
            Modifier.fillMaxWidth(share).padding(top = cluster.top, bottom = cluster.bottom),
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            ZoneSurface(shape = Clusters.corners(cluster, mine), accent = mine) {
                MessageBody(
                    words = AnnotatedString(words), plain = words, blurred = false, poll = null,
                    visual = emptyList(), kinds = emptyList<MediaCheck.Kind?>(), held = true,
                    footer = { mismatched, onMedia -> MessageFooter("10:42", delivery, false, onMedia = onMedia, mismatched = mismatched) },
                    onMore = null,
                    mine = mine
                )
            }
        }
    }

    @Composable
    private fun Cases(fontScale: Float) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            Column(Modifier.fillMaxWidth().padding(top = 40.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Bubble("Ok", false, null)
                Bubble("Ok", true, Delivery.Read)
                Bubble("Supercalifragilisticexpialidocious et anticonstitutionnellement", false, null)
                Bubble("https://example.org/a/very/long/path/that/never/breaks/at/a/space?query=1234567890", true, Delivery.Delivered)
                Bubble("مرحبا بك في تطبيق الرسائل الجديد الذي يجمع كل ما تحتاجه", false, null)
                Bubble("Rendez-vous au café مقهى النجوم demain à 14h", true, Delivery.Sent)
                Bubble("On se voit demain à 14h devant la gare, ne sois pas en retard cette fois ci, merci beaucoup", true, Delivery.Sent)
            }
        }
    }

    @Test
    fun font200() = captureForDevice("phone", "w411dp-h914dp-420dpi", "bubble_edge_draft") { Cases(2f) }

    @Test
    fun narrow() = captureForDevice("phone", "w320dp-h640dp-420dpi", "bubble_edge_narrow_draft") { Cases(1f) }

    @Test
    fun narrowFont200() = captureForDevice("phone", "w320dp-h900dp-420dpi", "bubble_edge_narrow_font200_draft") { Cases(2f) }
}

package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.core.security.MediaCheck
import com.yaz.sms.core.sms.Effects
import com.yaz.sms.feature.thread.Cluster
import com.yaz.sms.feature.thread.Clusters
import com.yaz.sms.feature.thread.Delivery
import com.yaz.sms.feature.thread.MessageBody
import com.yaz.sms.feature.thread.MessageFooter
import com.yaz.sms.feature.thread.ShowEffectSelector
import com.yaz.sms.feature.thread.effectFaces
import com.yaz.sms.ui.component.BalancedFlow
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.QuietButton
import com.yaz.sms.ui.component.TileLabel
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

/**
 * DRAFT for the user's yes (requests.md point 12): the effects picker as iOS's Send with effect and Telegram's
 * MessageSendPreview. Over the blurred thread, the message the user typed lifted as a bubble at the top, playing the
 * effect picked (here "Slam" frozen mid-way: the bubble scaled up from its end, about to land at 1), under it the
 * effects as tiles in two groups, Bubble and Screen, one chosen (accented), then a round Send action and a quiet
 * "Send later". Choosing a tile only plays it on the bubble; Send sends (the action does what its name says).
 * Robolectric draws no Dialog: the three layers (thread, veil, content) are laid inline, as ListHoldDraftScreenshotTest
 * does. The real parts: the thread's bubble (MessageBody and MessageFooter), the app's effectFaces for the tiles,
 * BalancedFlow, TileLabel, FloatingPane and QuietButton of the glass kit. Emoji may draw as boxes under Robolectric.
 * Pictures: effect_picker_draft (encrypted chat: the other side sees it) and effect_picker_sms_draft (SMS: the line
 * saying only the user sees it, unless the words bring one).
 *
 * Ports:
 * https://raw.githubusercontent.com/DrKLO/Telegram/master/TMessagesProj/src/main/java/org/telegram/ui/MessageSendPreview.java#MessageSendPreview
 * (the message cell lifted over a blurred, dimmed chat at openProgress, effectSelector under it with the effect chosen
 * playing on the message, the send button anchored at the end (anchorSendButton), effectsView above the rest)
 * not ported: the open and close transitions (openInProgress, firstOpenFrame, the cell's move from the composer),
 * the spoiler and camera paths (spoilerEffect2, cameraRect), the chat list's cells and groups drawn again
 * (drawingGroups, scrimGroup), the animated emoji overlay (EmojiAnimationsOverlay: the screen effects are the effects
 * kit's own and play on the thread), the keyboard focus handling (focusable, editText), allowEffectSelector's
 * ReactionsContainerLayout (the tiles are BalancedFlow's)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class EffectPickerDraftScreenshotTest {

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

    // The thread behind: a few messages, blurred.
    private val behind = listOf(
        "Tu es là ?" to false, "Oui, j'arrive dans dix minutes" to true, "Ok pour demain" to false,
        "À tout de suite" to true, "On se voit à 14h devant la gare" to false, "Parfait" to true, "Merci" to false
    )

    @Composable
    private fun Bubble(words: String, mine: Boolean, delivery: Delivery?) {
        ZoneSurface(shape = Clusters.corners(Cluster.SINGLE, mine), accent = mine) {
            MessageBody(
                words = AnnotatedString(words), plain = words, blurred = false, poll = null,
                visual = emptyList(), kinds = emptyList<MediaCheck.Kind?>(), held = true,
                footer = { mismatched, onMedia -> MessageFooter("10:42", delivery, false, onMedia = onMedia, mismatched = mismatched) },
                onMore = null, interactive = false, mine = mine
            )
        }
    }

    /**
     * MessageSendPreview: the thread blurred and dimmed (openProgress at 1), the message at the top playing [chosen]
     * ("Slam": scaled from its end, frozen at 1.3 on its way down to 1), the selector, Send and Send later.
     */
    @Composable
    private fun MessageSendPreview(carried: Boolean, chosen: Effects.Effect) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().blur(16.dp)) {
                Column(Modifier.fillMaxSize().padding(top = 56.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    behind.forEach { (words, mine) ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                            Box(Modifier.fillMaxWidth(0.82f), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                                Bubble(words, mine, if (mine) Delivery.Read else null)
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            Column(
                Modifier.fillMaxSize().padding(top = 56.dp, start = 12.dp, end = 12.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // The typed message, lifted: Slam mid-way, scaled around the bubble's end.
                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.82f)
                            .graphicsLayer {
                                scaleX = 1.3f
                                scaleY = 1.3f
                                transformOrigin = TransformOrigin(1f, 0f)
                            },
                        contentAlignment = Alignment.CenterEnd
                    ) { Bubble("Bonne fête ! On se voit demain à 14h", true, Delivery.Sending) }
                }
                Spacer(Modifier.height(36.dp))
                // effectSelector: the app's own, the two groups and the SMS line (EffectSheet's ShowEffectSelector).
                ShowEffectSelector(carried, chosen) {}
                Spacer(Modifier.weight(1f))
                // anchorSendButton: the round Send, then the quiet Send later.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietButton(onClick = {}) {
                        Icon(AppIcons.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Send later", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(16.dp))
                    FloatingPane(shape = CircleShape, accent = true, onClick = {}) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun chat() = capture("effect_picker_draft") { MessageSendPreview(carried = true, chosen = Effects.Effect.SLAM) }

    @Test
    fun sms() = capture("effect_picker_sms_draft") { MessageSendPreview(carried = false, chosen = Effects.Effect.SLAM) }
}

package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.materialkolor.blend.Blend
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot
import com.yaz.sms.ui.glass.LocalGlass
import com.yaz.sms.ui.glass.glassGround
import com.yaz.sms.ui.glass.rememberGlassLook
import com.yaz.sms.ui.icon.AppIcons
import com.yaz.sms.ui.theme.CustomColor
import com.yaz.sms.ui.theme.customColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DRAFT for the user's yes: the call button of feature/thread/CallButtons.kt today (the green harmonized with the
 * primary, a white icon) beside the Material custom colour group of the same green (color / onColor, tones 40 and 100
 * by day, 80 and 20 by night), with its WCAG contrast ratio under each, and the container pair. Teal and orange seeds,
 * light and dark. Pictures: call_colour_draft_{teal,orange}_phone_{light,dark}.
 *
 * Ports:
 * https://raw.githubusercontent.com/android/nowinandroid/main/core/screenshot-testing/src/main/kotlin/com/google/samples/apps/nowinandroid/core/testing/util/ScreenshotHelper.kt#captureForDevice
 * (device name, spec, dark mode, RuntimeEnvironment.setQualifiers, setContent with LocalInspectionMode, captureRoboImage)
 * not ported: the accessibility checks (checkRoboAccessibility, ATF options), extractSpecs, DeviceConfigurationOverride
 * https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/typescript/utils/theme_utils.ts#customColor
 * (CustomColor, CustomColorGroup and customColor below; CorePalette.of(value).a1 is TonalPalette.fromHueAndChroma of the
 * value's hue and max(48, chroma), CorePalette's definition of a1; the 6.0.0 jar has no CorePalette class, only CorePalettes)
 * not ported: themeFromSourceColor, themeFromImage, Theme, the typescript Scheme and palettes (the draft reads one
 * group only; the scheme comes from SchemeTonalSpot as in ColourDraftScreenshotTest)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class CallColourDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    private val callGreen = Color(0xFF1E9E4A)

    // customColor, CustomColor and CustomColorGroup now live in ui/theme/Harmonize.kt.

    // DynamicColorScheme.kt#toColorScheme as in ColourDraftScreenshotTest: the roles this draft draws.
    private fun DynamicScheme.toColorScheme(): ColorScheme {
        val colors = MaterialDynamicColors()
        fun role(c: com.materialkolor.dynamiccolor.DynamicColor) = Color(c.getArgb(this))
        return if (isDark) darkColorScheme(
            background = role(colors.background), onBackground = role(colors.onBackground),
            primary = role(colors.primary), onPrimary = role(colors.onPrimary),
            surface = role(colors.surface), onSurface = role(colors.onSurface),
            onSurfaceVariant = role(colors.onSurfaceVariant),
            surfaceContainer = role(colors.surfaceContainer)
        ) else lightColorScheme(
            background = role(colors.background), onBackground = role(colors.onBackground),
            primary = role(colors.primary), onPrimary = role(colors.onPrimary),
            surface = role(colors.surface), onSurface = role(colors.onSurface),
            onSurfaceVariant = role(colors.onSurfaceVariant),
            surfaceContainer = role(colors.surfaceContainer)
        )
    }

    private fun schemeOf(seed: Int, night: Boolean): ColorScheme =
        SchemeTonalSpot(Hct.fromInt(seed), night, 0.0).toColorScheme()

    private fun harmonize(designColor: Color, sourceColor: Color): Color =
        Color(Blend.harmonize(designColor.toArgb(), sourceColor.toArgb()))

    // WCAG relative luminance ratio, androidx.core.graphics.ColorUtils.calculateContrast.
    private fun ratio(foreground: Color, background: Color): String =
        "%.1f:1".format(ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb()))

    // ScreenshotHelper.kt#captureForDevice
    private fun captureForDevice(
        deviceName: String,
        deviceSpec: String,
        screenshotName: String,
        scheme: ColorScheme,
        darkMode: Boolean,
        body: @Composable () -> Unit
    ) {
        RuntimeEnvironment.setQualifiers(deviceSpec)
        compose.activity.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MaterialTheme(colorScheme = scheme) {
                    val look = rememberGlassLook(MaterialTheme.colorScheme, true, quiet = true)
                    CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        Box(Modifier.fillMaxSize().glassGround(look, MaterialTheme.colorScheme.background)) { body() }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/${screenshotName}.png",
            roborazziOptions = options
        )
    }

    private fun capture(seedName: String, seed: Int) {
        listOf(false, true).forEach { night ->
            captureForDevice(
                deviceName = "phone",
                deviceSpec = "w411dp-h914dp-420dpi",
                screenshotName = "call_colour_draft_${seedName}_phone_${if (night) "dark" else "light"}",
                scheme = schemeOf(seed, night),
                darkMode = night
            ) { Page(seed, night) }
        }
    }

    @Composable
    private fun Label(words: String) {
        Text(
            words,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)
        )
    }

    @Composable
    private fun Sides(now: @Composable () -> Unit, proposed: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { now() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { proposed() }
        }
    }

    @Composable
    private fun Round(fill: Color, icon: Color, contrast: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Call, contentDescription = null, tint = icon, modifier = Modifier.size(28.dp))
            }
            Text(
                contrast,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    @Composable
    private fun Page(seed: Int, night: Boolean) {
        val primary = MaterialTheme.colorScheme.primary
        val group = customColor(seed, CustomColor(callGreen.toArgb(), "Call", true)).let { if (night) it.dark else it.light }
        val nowFill = harmonize(callGreen, primary)
        val color = Color(group.color)
        val onColor = Color(group.onColor)
        val container = Color(group.colorContainer)
        val onContainer = Color(group.onColorContainer)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 48.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("Now", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text("Proposed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
            Label("Call button")
            Sides(
                now = { Round(nowFill, Color.White, ratio(Color.White, nowFill)) },
                proposed = { Round(color, onColor, ratio(onColor, color)) }
            )
            Label("Container")
            Sides(
                now = { Round(nowFill, Color.White, ratio(Color.White, nowFill)) },
                proposed = { Round(container, onContainer, ratio(onContainer, container)) }
            )
        }
    }

    @Test fun teal() = capture("teal", 0xFF006A6A.toInt())

    @Test fun orange() = capture("orange", 0xFFE8710A.toInt())
}

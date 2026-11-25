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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DRAFT for the user's yes: the app's fixed colours (call green, caution, effect hues, glass tint, scene ground) beside
 * the same colours harmonized with the primary of a real wallpaper's scheme (Material You), on a teal and an orange seed,
 * light and dark. Left column "Today", right column "Proposed". Pictures: colour_draft_{teal,orange}_phone_{light,dark}.
 *
 * Ports:
 * https://raw.githubusercontent.com/android/nowinandroid/main/core/screenshot-testing/src/main/kotlin/com/google/samples/apps/nowinandroid/core/testing/util/ScreenshotHelper.kt#captureForDevice
 * (device name, spec, dark mode, RuntimeEnvironment.setQualifiers, setContent with LocalInspectionMode, captureRoboImage)
 * not ported: the accessibility checks (checkRoboAccessibility, ATF options), extractSpecs (the qualifiers are given whole),
 * DeviceConfigurationOverride (the scheme is passed to MaterialTheme directly)
 * https://raw.githubusercontent.com/jordond/MaterialKolor/main/material-kolor-material3/src/commonMain/kotlin/com/materialkolor/material3/DynamicColorScheme.kt#toColorScheme
 * (the roles of a DynamicScheme read through MaterialDynamicColors into a Compose ColorScheme; lightColorScheme and
 * darkColorScheme take the roles by name, the fixed and Fixed-dim roles, which this draft does not use, are left to
 * their defaults)
 * not ported: isAmoled and modifyColorScheme (no pure black here), rememberDynamicColorScheme and its overloads
 * (the scheme is built once per seed), the Fixed roles
 * https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/blend/Blend.java#harmonize
 * (through com.materialkolor.blend.Blend.harmonize, as core/link/LinkSheet.kt harmonize does)
 * not ported: hctHue, cam16Ucs (the library's own, not used here)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class ColourDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    // DynamicColorScheme.kt#toColorScheme: the roles of the DynamicScheme, one by one.
    private fun DynamicScheme.toColorScheme(): ColorScheme {
        val colors = MaterialDynamicColors()
        fun role(c: com.materialkolor.dynamiccolor.DynamicColor) = Color(c.getArgb(this))
        return if (isDark) darkColorScheme(
            background = role(colors.background),
            error = role(colors.error),
            errorContainer = role(colors.errorContainer),
            inverseOnSurface = role(colors.inverseOnSurface),
            inversePrimary = role(colors.inversePrimary),
            inverseSurface = role(colors.inverseSurface),
            onBackground = role(colors.onBackground),
            onError = role(colors.onError),
            onErrorContainer = role(colors.onErrorContainer),
            onPrimary = role(colors.onPrimary),
            onPrimaryContainer = role(colors.onPrimaryContainer),
            onSecondary = role(colors.onSecondary),
            onSecondaryContainer = role(colors.onSecondaryContainer),
            onSurface = role(colors.onSurface),
            onSurfaceVariant = role(colors.onSurfaceVariant),
            onTertiary = role(colors.onTertiary),
            onTertiaryContainer = role(colors.onTertiaryContainer),
            outline = role(colors.outline),
            outlineVariant = role(colors.outlineVariant),
            primary = role(colors.primary),
            primaryContainer = role(colors.primaryContainer),
            scrim = role(colors.scrim),
            secondary = role(colors.secondary),
            secondaryContainer = role(colors.secondaryContainer),
            surface = role(colors.surface),
            surfaceTint = role(colors.surfaceTint),
            surfaceBright = role(colors.surfaceBright),
            surfaceDim = role(colors.surfaceDim),
            surfaceContainer = role(colors.surfaceContainer),
            surfaceContainerHigh = role(colors.surfaceContainerHigh),
            surfaceContainerHighest = role(colors.surfaceContainerHighest),
            surfaceContainerLow = role(colors.surfaceContainerLow),
            surfaceContainerLowest = role(colors.surfaceContainerLowest),
            surfaceVariant = role(colors.surfaceVariant),
            tertiary = role(colors.tertiary),
            tertiaryContainer = role(colors.tertiaryContainer)
        ) else lightColorScheme(
            background = role(colors.background),
            error = role(colors.error),
            errorContainer = role(colors.errorContainer),
            inverseOnSurface = role(colors.inverseOnSurface),
            inversePrimary = role(colors.inversePrimary),
            inverseSurface = role(colors.inverseSurface),
            onBackground = role(colors.onBackground),
            onError = role(colors.onError),
            onErrorContainer = role(colors.onErrorContainer),
            onPrimary = role(colors.onPrimary),
            onPrimaryContainer = role(colors.onPrimaryContainer),
            onSecondary = role(colors.onSecondary),
            onSecondaryContainer = role(colors.onSecondaryContainer),
            onSurface = role(colors.onSurface),
            onSurfaceVariant = role(colors.onSurfaceVariant),
            onTertiary = role(colors.onTertiary),
            onTertiaryContainer = role(colors.onTertiaryContainer),
            outline = role(colors.outline),
            outlineVariant = role(colors.outlineVariant),
            primary = role(colors.primary),
            primaryContainer = role(colors.primaryContainer),
            scrim = role(colors.scrim),
            secondary = role(colors.secondary),
            secondaryContainer = role(colors.secondaryContainer),
            surface = role(colors.surface),
            surfaceTint = role(colors.surfaceTint),
            surfaceBright = role(colors.surfaceBright),
            surfaceDim = role(colors.surfaceDim),
            surfaceContainer = role(colors.surfaceContainer),
            surfaceContainerHigh = role(colors.surfaceContainerHigh),
            surfaceContainerHighest = role(colors.surfaceContainerHighest),
            surfaceContainerLow = role(colors.surfaceContainerLow),
            surfaceContainerLowest = role(colors.surfaceContainerLowest),
            surfaceVariant = role(colors.surfaceVariant),
            tertiary = role(colors.tertiary),
            tertiaryContainer = role(colors.tertiaryContainer)
        )
    }

    private fun schemeOf(seed: Int, night: Boolean): ColorScheme =
        SchemeTonalSpot(Hct.fromInt(seed), night, 0.0).toColorScheme()

    // Blend.harmonize: the design colour's hue moved toward the source colour's (the scheme's primary).
    private fun harmonize(designColor: Color, sourceColor: Color): Color =
        Color(Blend.harmonize(designColor.toArgb(), sourceColor.toArgb()))

    // ScreenshotHelper.kt#captureForDevice: device name, spec, dark mode, qualifiers, content, captureRoboImage.
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
            val scheme = schemeOf(seed, night)
            captureForDevice(
                deviceName = "phone",
                deviceSpec = "w411dp-h914dp-420dpi",
                screenshotName = "colour_draft_${seedName}_phone_${if (night) "dark" else "light"}",
                scheme = scheme,
                darkMode = night
            ) { Page(night) }
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
    private fun Sides(today: @Composable () -> Unit, proposed: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { today() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { proposed() }
        }
    }

    @Composable
    private fun CallButton(fill: Color) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }

    @Composable
    private fun Chip(fill: Color) {
        Box(
            Modifier.clip(CircleShape).background(fill).widthIn(min = 96.dp).heightIn(min = 36.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Caution", style = MaterialTheme.typography.labelLarge, color = Color.White, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun Dots(colours: List<Color>) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            colours.forEach { Box(Modifier.size(20.dp).clip(CircleShape).background(it)) }
        }
    }

    @Composable
    private fun GlassCard(fill: Color, alpha: Float) {
        Box(
            Modifier.clip(MaterialTheme.shapes.extraLarge).background(fill.copy(alpha = alpha)).widthIn(min = 120.dp).heightIn(min = 56.dp).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Hello there", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun Page(night: Boolean) {
        val s = MaterialTheme.colorScheme
        val p = s.primary
        val caution = lerp(s.error, Color(0xFFE8890C), 0.4f)
        // ScreenEffect.kt: fireworks hues, love's heart reds, stars' gold.
        val fireworks = listOf(Color(0xFFFFD54F), Color(0xFFFF5E7E), Color(0xFF64D8FF), Color(0xFFB388FF), Color(0xFF69F0AE), Color(0xFFFFAB40))
        val love = listOf(Color(0xFFFF3B6B), Color(0xFFC2185B))
        val stars = listOf(Color(0xFFFFD36B))
        // Backgrounds.kt: the scene's ground, the colour mixed toward near black (night) or white (day).
        val base = if (night) Color(0xFF101114) else Color.White
        val fraction = if (night) 0.88f else 0.9f
        Column(
            Modifier.fillMaxSize().background(s.surface).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Sides(
                { Text("Today", style = MaterialTheme.typography.titleMedium, color = s.onSurface, textAlign = TextAlign.Center) },
                { Text("Proposed", style = MaterialTheme.typography.titleMedium, color = s.onSurface, textAlign = TextAlign.Center) }
            )
            Label("Call")
            Sides({ CallButton(Color(0xFF1E9E4A)) }, { CallButton(harmonize(Color(0xFF1E9E4A), p)) })
            Label("Caution")
            Sides({ Chip(caution) }, { Chip(harmonize(caution, p)) })
            Label("Fireworks")
            Sides({ Dots(fireworks) }, { Dots(fireworks.map { harmonize(it, p) }) })
            Label("Love")
            Sides({ Dots(love) }, { Dots(love.map { harmonize(it, p) }) })
            Label("Stars")
            Sides({ Dots(stars) }, { Dots(stars.map { harmonize(it, p) }) })
            Label("Glass")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(140.dp).blur(40.dp).clip(CircleShape).background(s.primaryContainer))
                Sides(
                    { GlassCard(if (night) Color(0xFF121212) else Color(0xFFFAFAFA), if (night) 0.62f else 0.72f) },
                    { GlassCard(s.surfaceContainerHigh, if (night) 0.62f else 0.72f) }
                )
            }
            Label("Scene ground")
            Sides(
                { Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(lerp(s.primaryContainer, base, fraction))) },
                { Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(lerp(s.surface, base, fraction))) }
            )
        }
    }

    @Test fun teal() = capture("teal", 0xFF006A6A.toInt())

    @Test fun orange() = capture("orange", 0xFFE8710A.toInt())
}

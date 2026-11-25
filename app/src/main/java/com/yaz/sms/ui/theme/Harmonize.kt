package com.yaz.sms.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.blend.Blend
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import kotlin.math.max

// https://github.com/material-foundation/material-color-utilities/blob/main/java/blend/Blend.java#harmonize
// The design colour's hue moved toward the source colour's (half the gap, 15 degrees at most), chroma and tone kept.
// Through materialkolor's own port of Blend.harmonize (its MathUtils is internal); the alpha is kept.
fun harmonize(designColor: Color, sourceColor: Color): Color =
    Color(Blend.harmonize(designColor.toArgb(), sourceColor.toArgb())).copy(alpha = designColor.alpha)

// Material Components' "Using Color Harmonization" (docs/theming/Color.md): a semantic colour harmonized with
// colorPrimary, as MaterialColors.harmonizeWithPrimary, the source colour being the scheme's primary.
@Composable
fun Color.harmonized(): Color = harmonize(this, MaterialTheme.colorScheme.primary)

// https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/typescript/utils/theme_utils.ts#CustomColor
class CustomColor(val value: Int, val name: String, val blend: Boolean)

// theme_utils.ts#ColorGroup
class ColorGroup(val color: Int, val onColor: Int, val colorContainer: Int, val onColorContainer: Int)

// theme_utils.ts#CustomColorGroup
class CustomColorGroup(val color: CustomColor, val value: Int, val light: ColorGroup, val dark: ColorGroup)

// https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/typescript/utils/theme_utils.ts#customColor
// CorePalette.of(value).a1 is TonalPalette.fromHueAndChroma of the value's hue and max(48, chroma); the 6.0.0 jar has
// no CorePalette class. not ported: themeFromSourceColor, themeFromImage, Theme, the typescript Scheme and palettes
// (one group only; the scheme comes from the app's theme).
fun customColor(source: Int, color: CustomColor): CustomColorGroup {
    var value = color.value
    val from = value
    val to = source
    if (color.blend) {
        value = Blend.harmonize(from, to)
    }
    val hct = Hct.fromInt(value)
    val tones = TonalPalette.fromHueAndChroma(hct.hue, max(48.0, hct.chroma))
    return CustomColorGroup(
        color = color,
        value = value,
        light = ColorGroup(
            color = tones.tone(40),
            onColor = tones.tone(100),
            colorContainer = tones.tone(90),
            onColorContainer = tones.tone(10)
        ),
        dark = ColorGroup(
            color = tones.tone(80),
            onColor = tones.tone(20),
            colorContainer = tones.tone(30),
            onColorContainer = tones.tone(90)
        )
    )
}

/** The four roles of a custom colour, as Compose colours. */
class CustomColorRoles(val color: Color, val onColor: Color, val colorContainer: Color, val onColorContainer: Color)

private fun ColorGroup.roles() =
    CustomColorRoles(Color(color), Color(onColor), Color(colorContainer), Color(onColorContainer))

/**
 * The group of [design] (harmonized with the scheme's primary) for the current scheme. AppTheme picks the scheme from
 * its darkTheme argument and exposes nothing else, so darkness is read from the scheme it chose: a background below
 * half luminance (pure black included), never the system's setting.
 */
@Composable
fun rememberCustomColor(design: Color): CustomColorRoles {
    val primary = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(design, primary, dark) {
        val group = customColor(primary.toArgb(), CustomColor(design.toArgb(), "Custom", true))
        (if (dark) group.dark else group.light).roles()
    }
}

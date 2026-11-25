package com.yaz.sms.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.blend.Blend

// https://github.com/material-foundation/material-color-utilities/blob/main/java/blend/Blend.java#harmonize
// The design colour's hue moved toward the source colour's (half the gap, 15 degrees at most), chroma and tone kept.
// Through materialkolor's own port of Blend.harmonize (its MathUtils is internal); the alpha is kept.
fun harmonize(designColor: Color, sourceColor: Color): Color =
    Color(Blend.harmonize(designColor.toArgb(), sourceColor.toArgb())).copy(alpha = designColor.alpha)

// Material Components' "Using Color Harmonization" (docs/theming/Color.md): a semantic colour harmonized with
// colorPrimary, as MaterialColors.harmonizeWithPrimary, the source colour being the scheme's primary.
@Composable
fun Color.harmonized(): Color = harmonize(this, MaterialTheme.colorScheme.primary)

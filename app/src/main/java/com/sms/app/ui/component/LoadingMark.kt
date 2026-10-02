package com.sms.app.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sms.app.ui.icon.AppIcons

/**
 * The app's loading mark until its own icon exists: the message glyph in
 * the accent, breathing, with rings of its colour leaving it.
 *
 * [progress] from 0 to 1 sends the rings out as far as a gesture has gone.
 * While [running] it moves on its own.
 */
@Composable
fun LoadingMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    running: Boolean = true,
    progress: Float = 0f
) {
    val accent = MaterialTheme.colorScheme.primary
    val glyph = rememberVectorPainter(AppIcons.TextSms)
    val loop = rememberInfiniteTransition(label = "mark")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t")
    val breath by loop.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val rings = if (running) t else progress
        for (k in 0 until 2) {
            val p = (rings + k / 2f) % 1f
            val fade = (1f - p) * (1f - p)
            drawCircle(accent.copy(alpha = 0.35f * fade), r * (0.55f + 0.45f * p), style = Stroke(width = (2.5f - 1.5f * p).dp.toPx()))
        }
        val side = this.size.minDimension * 0.5f
        translate((this.size.width - side) / 2f, (this.size.height - side) / 2f) {
            scale(if (running) breath else 1f, pivot = androidx.compose.ui.geometry.Offset(side / 2f, side / 2f)) {
                with(glyph) { draw(androidx.compose.ui.geometry.Size(side, side), colorFilter = ColorFilter.tint(accent)) }
            }
        }
    }
}

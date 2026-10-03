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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sms.app.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * SMS's loading mark, the launcher icon in motion: the bubble breathes,
 * rings of its colour leave it, and its three dots hop one after the
 * other, as someone typing. The bubble is the icon's: a grey body
 * multiplied by the theme's accent, its light over it, the dots on top.
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
    val body = ImageBitmap.imageResource(R.drawable.sms_mark_body)
    val shine = ImageBitmap.imageResource(R.drawable.sms_mark_shine)
    val dots = listOf(
        ImageBitmap.imageResource(R.drawable.sms_mark_dot1),
        ImageBitmap.imageResource(R.drawable.sms_mark_dot2),
        ImageBitmap.imageResource(R.drawable.sms_mark_dot3)
    )
    val accent = MaterialTheme.colorScheme.primary
    val tint = remember(accent) { ColorFilter.tint(accent, BlendMode.Modulate) }

    val transition = rememberInfiniteTransition(label = "typing")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "loop"
    )
    val t = if (running) clock else progress.coerceIn(0f, 1f) * 0.5f

    Canvas(modifier.size(size)) {
        val side = this.size.minDimension * BUBBLE
        // Two rings leaving the bubble, half a loop apart.
        for (k in 0 until 2) {
            val p = (t + k / 2f) % 1f
            val fade = (1f - p) * (1f - p)
            drawCircle(accent.copy(alpha = 0.35f * fade), side * 0.42f * (1f + 0.55f * p), style = Stroke(width = side * (0.025f - 0.015f * p)))
        }
        val breath = if (running) 1f + 0.03f * sin(2f * PI.toFloat() * t) else 1f
        scale(breath) {
            layer(body, side, 0f, tint)
            layer(shine, side, 0f, null)
            // Each dot hops in turn, a little after the one before it.
            dots.forEachIndexed { i, dot ->
                val phase = ((t * 2f - i * 0.16f) % 1f + 1f) % 1f
                val hop = if (running && phase < 0.35f) sin(phase / 0.35f * PI.toFloat()) else 0f
                layer(dot, side, -side * 0.06f * hop, null)
            }
        }
    }
}

private fun DrawScope.layer(image: ImageBitmap, side: Float, dy: Float, filter: ColorFilter?) {
    val s = side.toInt()
    drawImage(
        image,
        dstOffset = IntOffset((center.x - side / 2f).toInt(), (center.y - side / 2f + dy).toInt()),
        dstSize = IntSize(s, s),
        colorFilter = filter,
        filterQuality = FilterQuality.High
    )
}

/** The bubble's share of the mark, leaving room for the rings around it. */
private const val BUBBLE = 0.667f
private const val LOOP_MILLIS = 1800

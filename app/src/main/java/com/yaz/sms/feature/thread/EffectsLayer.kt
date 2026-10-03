package com.yaz.sms.feature.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaz.sms.core.sms.Effects.Effect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A screen effect over the conversation, once: fireworks, confetti,
 * balloons, a heart, lasers, a shooting star, a celebration, the words'
 * echo or a spotlight on the message at [origin]. Its pieces are glass:
 * see-through tones of the wallpaper's palette with a point of light on
 * each. [onDone] when it has played.
 */
@Composable
fun ScreenEffect(effect: Effect, words: String, origin: Offset?, onDone: () -> Unit) {
    val time = remember(effect, words) { Animatable(0f) }
    val length = when (effect) {
        Effect.FIREWORKS, Effect.LASERS -> 3200
        Effect.BALLOONS, Effect.ECHO -> 3400
        else -> 2800
    }
    LaunchedEffect(effect, words) {
        time.snapTo(0f)
        time.animateTo(1f, tween(length, easing = LinearEasing))
        onDone()
    }
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) {
        listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer, scheme.tertiaryContainer, Color(0xFFFFC94D), Color(0xFFFF6B8B), Color(0xFF5BC0FF))
    }
    val measurer = rememberTextMeasurer()
    val seed = remember(effect, words) { Random(words.hashCode() xor effect.ordinal) }
    val pieces = remember(effect, words) { List(220) { floatArrayOf(seed.nextFloat(), seed.nextFloat(), seed.nextFloat(), seed.nextFloat(), seed.nextFloat()) } }
    val textColor = scheme.onSurface
    Canvas(Modifier.fillMaxSize()) {
        val t = time.value
        val fade = if (t > 0.85f) (1f - t) / 0.15f else 1f
        when (effect) {
            Effect.CONFETTI -> confetti(t, fade, pieces, palette)
            Effect.BALLOONS -> balloons(t, pieces, palette)
            Effect.FIREWORKS -> fireworks(t, fade, pieces, palette)
            Effect.LOVE -> love(t, fade, origin)
            Effect.LASERS -> lasers(t, fade, palette)
            Effect.STARS -> stars(t, fade, pieces)
            Effect.CELEBRATION -> celebration(t, fade, pieces)
            Effect.SPOTLIGHT -> spotlight(t, origin)
            Effect.ECHO -> {
                val layout = measurer.measure(words.take(40), TextStyle(fontSize = 18.sp, color = textColor))
                pieces.take(26).forEachIndexed { i, p ->
                    val appear = (t * 1.6f - p[2] * 0.6f).coerceIn(0f, 1f)
                    if (appear <= 0f) return@forEachIndexed
                    val x = p[0] * (size.width - layout.size.width).coerceAtLeast(1f)
                    val y = size.height * (1.05f - appear * (0.4f + p[1] * 0.75f))
                    val a = (if (appear < 0.15f) appear / 0.15f else 1f) * fade * 0.85f
                    drawRoundRect(palette[i % palette.size].copy(alpha = 0.22f * a), Offset(x - 12.dp.toPx(), y - 8.dp.toPx()), Size(layout.size.width + 24.dp.toPx(), layout.size.height + 16.dp.toPx()), CornerRadius(20.dp.toPx()))
                    drawText(layout, topLeft = Offset(x, y), alpha = a)
                }
            }
            else -> Unit
        }
    }
}

/** A point of light on a piece of glass, up and to the left. */
private fun DrawScope.glint(center: Offset, r: Float, alpha: Float) {
    drawCircle(Color.White.copy(alpha = 0.55f * alpha), r * 0.28f, center + Offset(-r * 0.35f, -r * 0.35f))
}

private fun DrawScope.confetti(t: Float, fade: Float, pieces: List<FloatArray>, palette: List<Color>) {
    pieces.take(160).forEachIndexed { i, p ->
        val start = p[2] * 0.35f
        val k = ((t - start) / (1f - start)).coerceIn(0f, 1f)
        if (k <= 0f) return@forEachIndexed
        val x = p[0] * size.width + sin((k * 6 + p[3] * 6).toDouble()).toFloat() * 30.dp.toPx()
        val y = -20.dp.toPx() + k * (size.height + 40.dp.toPx()) * (0.7f + p[1] * 0.5f)
        val w = (5 + p[4] * 5).dp.toPx()
        val color = palette[i % palette.size]
        rotate(k * 720f * (if (p[3] > 0.5f) 1 else -1) + p[4] * 180f, Offset(x, y)) {
            drawRoundRect(color.copy(alpha = 0.85f * fade), Offset(x - w / 2, y - w), Size(w, w * 2), CornerRadius(w * 0.3f))
            drawRect(Color.White.copy(alpha = 0.35f * fade), Offset(x - w / 2, y - w), Size(w * 0.35f, w * 2))
        }
    }
}

private fun DrawScope.balloons(t: Float, pieces: List<FloatArray>, palette: List<Color>) {
    pieces.take(14).forEachIndexed { i, p ->
        val start = p[2] * 0.4f
        val k = ((t - start) / 0.75f).coerceIn(0f, 1.2f)
        if (k <= 0f) return@forEachIndexed
        val r = (26 + p[4] * 16).dp.toPx()
        val x = p[0] * size.width + sin((k * 5 + p[3] * 6).toDouble()).toFloat() * 18.dp.toPx()
        val y = size.height + r * 2 - k * (size.height + r * 5)
        val color = palette[i % palette.size]
        // The string first, then the balloon of tinted glass over it.
        val string = Path().apply {
            moveTo(x, y + r * 1.15f)
            cubicTo(x - r * 0.3f, y + r * 1.6f, x + r * 0.3f, y + r * 2.1f, x, y + r * 2.6f)
        }
        drawPath(string, Color.White.copy(alpha = 0.6f), style = Stroke(1.2.dp.toPx()))
        drawOval(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.65f)), center = Offset(x - r * 0.3f, y - r * 0.4f), radius = r * 1.6f), Offset(x - r, y - r * 1.15f), Size(r * 2, r * 2.3f))
        drawOval(Color.White.copy(alpha = 0.45f), Offset(x - r * 0.6f, y - r * 0.85f), Size(r * 0.45f, r * 0.7f))
        drawPath(Path().apply { moveTo(x - r * 0.16f, y + r * 1.25f); lineTo(x + r * 0.16f, y + r * 1.25f); lineTo(x, y + r * 1.1f); close() }, color)
    }
}

private fun DrawScope.fireworks(t: Float, fade: Float, pieces: List<FloatArray>, palette: List<Color>) {
    // The screen dims a little, so the light reads.
    drawRect(Color.Black.copy(alpha = 0.35f * (if (t < 0.1f) t / 0.1f else 1f) * fade))
    val bursts = 6
    for (b in 0 until bursts) {
        val start = b * 0.13f
        val k = ((t - start) / 0.45f).coerceIn(0f, 1f)
        if (k <= 0f || k >= 1f) continue
        val seed = pieces[b]
        val c = Offset(size.width * (0.18f + seed[0] * 0.64f), size.height * (0.15f + seed[1] * 0.45f))
        val color = palette[b % palette.size]
        val reach = (90 + seed[2] * 70).dp.toPx()
        val spread = 1f - (1f - k) * (1f - k)
        val a = (1f - k) * fade
        for (j in 0 until 48) {
            val ang = j / 48.0 * 2 * PI + seed[3]
            val d = reach * spread * (0.75f + pieces[j + 10][0] * 0.35f)
            val fall = 40.dp.toPx() * k * k
            val p = c + Offset((cos(ang) * d).toFloat(), (sin(ang) * d).toFloat() + fall)
            val tail = c + Offset((cos(ang) * d * 0.82f).toFloat(), (sin(ang) * d * 0.82f).toFloat() + fall * 0.8f)
            drawLine(color.copy(alpha = 0.55f * a), tail, p, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color.copy(alpha = a), 2.6.dp.toPx(), p)
            drawCircle(Color.White.copy(alpha = 0.7f * a), 1.1.dp.toPx(), p)
        }
    }
}

private fun heart(c: Offset, s: Float) = Path().apply {
    moveTo(c.x, c.y + s * 0.35f)
    cubicTo(c.x - s * 1.1f, c.y - s * 0.35f, c.x - s * 0.55f, c.y - s * 1.05f, c.x, c.y - s * 0.5f)
    cubicTo(c.x + s * 0.55f, c.y - s * 1.05f, c.x + s * 1.1f, c.y - s * 0.35f, c.x, c.y + s * 0.35f)
    close()
}

private fun DrawScope.love(t: Float, fade: Float, origin: Offset?) {
    val c = origin ?: Offset(size.width / 2, size.height * 0.55f)
    val grow = if (t < 0.35f) (t / 0.35f) else 1f
    val beat = 1f + 0.08f * sin(t * 18f)
    val s = size.minDimension * 0.42f * grow * beat
    val rise = if (t > 0.6f) (t - 0.6f) / 0.4f * size.height * 0.3f else 0f
    val at = Offset(size.width / 2 + (c.x - size.width / 2) * (1f - grow), c.y - rise - (c.y - size.height * 0.45f) * grow)
    val red = Color(0xFFFF3B6B)
    drawPath(heart(at, s), Brush.radialGradient(listOf(red.copy(alpha = 0.9f * fade), Color(0xFFC2185B).copy(alpha = 0.75f * fade)), center = at + Offset(-s * 0.3f, -s * 0.5f), radius = s * 1.6f))
    drawPath(heart(at + Offset(-s * 0.28f, -s * 0.45f), s * 0.22f), Color.White.copy(alpha = 0.4f * fade))
}

private fun DrawScope.lasers(t: Float, fade: Float, palette: List<Color>) {
    drawRect(Color.Black.copy(alpha = 0.55f * (if (t < 0.08f) t / 0.08f else 1f) * fade))
    val c = Offset(size.width / 2, size.height * 0.5f)
    for (i in 0 until 6) {
        val ang = t * 2.4f * PI.toFloat() * (if (i % 2 == 0) 1 else -1) + i * PI.toFloat() / 3
        val end = c + Offset(cos(ang) * size.maxDimension, sin(ang) * size.maxDimension)
        val color = palette[i % palette.size]
        // A glow, then the beam's bright core.
        drawLine(color.copy(alpha = 0.18f * fade), c, end, strokeWidth = 18.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color.copy(alpha = 0.45f * fade), c, end, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.85f * fade), c, end, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    }
    drawCircle(Color.White.copy(alpha = 0.8f * fade), 10.dp.toPx(), c)
}

private fun DrawScope.stars(t: Float, fade: Float, pieces: List<FloatArray>) {
    val gold = Color(0xFFFFD36B)
    val k = (t / 0.55f).coerceIn(0f, 1f)
    val head = Offset(-40.dp.toPx() + k * (size.width + 80.dp.toPx()), size.height * (0.18f + 0.22f * k))
    // The trail, fading behind the star.
    for (i in 0 until 24) {
        val back = i / 24f
        val p = head - Offset(back * 160.dp.toPx(), back * 60.dp.toPx() * 0.4f)
        drawCircle(gold.copy(alpha = (1f - back) * 0.5f * fade), (6 * (1f - back) + 1).dp.toPx(), p)
    }
    drawCircle(Color.White.copy(alpha = fade), 7.dp.toPx(), head)
    drawCircle(gold.copy(alpha = 0.6f * fade), 14.dp.toPx(), head)
    // Sparkles left in the sky once it has passed.
    pieces.take(40).forEach { p ->
        val when0 = p[0] * 0.55f
        if (t < when0) return@forEach
        val life = ((t - when0) / 0.45f).coerceIn(0f, 1f)
        val at = Offset(p[0] * size.width, size.height * (0.18f + 0.22f * p[0]) + (p[1] - 0.5f) * 80.dp.toPx() + life * 40.dp.toPx())
        drawCircle(gold.copy(alpha = (1f - life) * fade), (2 + p[2] * 2).dp.toPx(), at)
    }
}

private fun DrawScope.celebration(t: Float, fade: Float, pieces: List<FloatArray>) {
    val gold = listOf(Color(0xFFFFD36B), Color(0xFFFFB74D), Color(0xFFFFF1B8))
    val from = Offset(size.width, 0f)
    pieces.take(120).forEachIndexed { i, p ->
        val start = p[2] * 0.45f
        val k = ((t - start) / 0.6f).coerceIn(0f, 1f)
        if (k <= 0f || k >= 1f) return@forEachIndexed
        val ang = (PI * 0.5 + p[0] * PI * 0.5).toFloat()
        val d = size.maxDimension * 0.85f * k * (0.5f + p[1] * 0.6f)
        val at = from + Offset(cos(ang) * d * 1.0f, sin(ang) * d)
        val r = (2 + p[4] * 3).dp.toPx() * (1f - k * 0.5f)
        drawCircle(gold[i % 3].copy(alpha = (1f - k) * fade), r, at)
        glint(at, r * 2, (1f - k) * fade)
    }
}

private fun DrawScope.spotlight(t: Float, origin: Offset?) {
    val c = origin ?: Offset(size.width / 2, size.height * 0.6f)
    val open = if (t < 0.2f) t / 0.2f else if (t > 0.85f) (1f - t) / 0.15f else 1f
    val r = 90.dp.toPx() + 30.dp.toPx() * sin(t * 6f)
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.7f * open)), center = c, radius = r * 1.6f))
    translate(0f, 0f) { drawCircle(Color.White.copy(alpha = 0.10f * open), r, c) }
}

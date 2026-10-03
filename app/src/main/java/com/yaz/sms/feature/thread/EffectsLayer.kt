package com.yaz.sms.feature.thread

import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
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
        Effect.FIREWORKS -> 3600
        Effect.LASERS -> 3200
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
    // The festive ones play Google's animated emoji (Noto, CC BY 4.0) across
    // the screen, the drawn shapes kept as a light layer under them.
    lottieOf(effect)?.let { name -> LottieShower(name, effect, words, time.value, pieces) }
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
    // Night falls a little, so the light reads.
    drawRect(Color(0xFF05070F).copy(alpha = 0.55f * (if (t < 0.08f) t / 0.08f else 1f) * fade))
    // Bright, festive hues, not the theme's quiet ones.
    val hues = listOf(Color(0xFFFFD54F), Color(0xFFFF5E7E), Color(0xFF64D8FF), Color(0xFFB388FF), Color(0xFF69F0AE), Color(0xFFFFAB40), palette.first())
    val shells = 7
    for (b in 0 until shells) {
        val seed = pieces[b]
        val launch = b * 0.11f + seed[4] * 0.05f
        val climb = 0.16f
        val burst = Offset(size.width * (0.14f + seed[0] * 0.72f), size.height * (0.14f + seed[1] * 0.36f))
        val ground = Offset(burst.x + (seed[2] - 0.5f) * size.width * 0.12f, size.height * 1.02f)
        val color = hues[b % hues.size]
        val second = hues[(b + 3) % hues.size]
        // The rocket: a bright head climbing with a fading trail.
        val r = ((t - launch) / climb).coerceIn(0f, 1f)
        if (r > 0f && r < 1f) {
            val ease = 1f - (1f - r) * (1f - r)
            val head = ground + (burst - ground) * ease
            val tail = ground + (burst - ground) * (ease - 0.12f).coerceAtLeast(0f)
            drawLine(Brush.linearGradient(listOf(color.copy(alpha = 0f), Color.White.copy(alpha = 0.9f * fade)), tail, head), tail, head, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = fade), 2.8.dp.toPx(), head)
        }
        // The burst: sparks thrown out, slowing, falling, twinkling as they die.
        val k = ((t - launch - climb) / 0.42f).coerceIn(0f, 1f)
        if (k <= 0f || k >= 1f) continue
        if (k < 0.1f) drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = (1f - k / 0.1f) * fade), color.copy(alpha = 0f)), burst, 60.dp.toPx()), 60.dp.toPx(), burst)
        val reach = (110 + seed[3] * 80).dp.toPx()
        val out = 1f - (1f - k) * (1f - k) * (1f - k)
        val gravity = 70.dp.toPx() * k * k
        val sparks = 64
        for (j in 0 until sparks) {
            val jitter = pieces[(j * 3 + b) % pieces.size]
            val ang = j * 2 * PI / sparks + seed[3] * 6
            val speed = 0.7f + jitter[0] * 0.45f
            val d = reach * out * speed
            val p = burst + Offset((cos(ang) * d).toFloat(), (sin(ang) * d).toFloat() + gravity)
            val back = burst + Offset((cos(ang) * d * 0.86f).toFloat(), (sin(ang) * d * 0.86f).toFloat() + gravity * 0.8f)
            val twinkle = if (k > 0.55f) (0.5f + 0.5f * sin((k * 40f + jitter[1] * 20f))) else 1f
            val a = (1f - k) * fade * twinkle
            val c = if (j % 2 == 0) color else second
            drawLine(c.copy(alpha = 0.5f * a), back, p, strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(c.copy(alpha = a), 2.4.dp.toPx(), p)
            drawCircle(Color.White.copy(alpha = 0.8f * a), 1.dp.toPx(), p)
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

/** The animated emoji of a screen effect, or null when it is drawn only. */
private fun lottieOf(effect: Effect): String? = when (effect) {
    Effect.CONFETTI -> "1f38a"
    Effect.BALLOONS -> "1f388"
    Effect.CELEBRATION -> "1f389"
    Effect.LOVE -> "2764_fe0f"
    Effect.STARS -> "2728"
    else -> null
}

/**
 * A handful of the effect's animated emoji over the screen: balloons and
 * hearts rise from below, fireworks burst here and there, confetti and
 * party poppers go off along the sides; each plays its own animation.
 */
@Composable
private fun LottieShower(name: String, effect: Effect, words: String, t: Float, pieces: List<FloatArray>) {
    val composition by com.airbnb.lottie.compose.rememberLottieComposition(com.airbnb.lottie.compose.LottieCompositionSpec.Asset("emoji/$name.json"))
    val count = when (effect) { Effect.BALLOONS -> 9; Effect.LOVE -> 8; Effect.STARS -> 10; else -> 6 }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        repeat(count) { i ->
            val p = pieces[i]
            val start = p[2] * 0.45f
            val local = ((t - start) / 0.55f).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) return@repeat
            val size = w * (0.22f + p[3] * 0.16f)
            val rising = effect == Effect.BALLOONS || effect == Effect.LOVE
            val x = (w - size) * p[0]
            val y = if (rising) h * (1.05f - local * (1.15f + p[1] * 0.2f)) else (h * 0.65f) * p[1]
            val alpha = when {
                local < 0.12f -> local / 0.12f
                local > 0.85f -> (1f - local) / 0.15f
                else -> 1f
            }
            com.airbnb.lottie.compose.LottieAnimation(
                composition = composition,
                progress = { (local * (1.3f + p[4])) % 1f },
                modifier = Modifier
                    .offset(x, y)
                    .size(size)
                    .graphicsLayer {
                        this.alpha = alpha
                        rotationZ = if (rising) 8f * kotlin.math.sin((local * 6f + p[4] * 6f)) else 0f
                    }
            )
        }
    }
}

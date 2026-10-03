package com.yaz.sms.feature.thread

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import com.yaz.sms.core.sms.BigEmoji
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** The animations the app carries, read once. */
private object EmojiAssets {
    @Volatile private var names: Set<String>? = null
    fun find(context: Context, emoji: String): String? {
        val have = names ?: runCatching { context.assets.list("emoji").orEmpty().map { it.removeSuffix(".json") }.toSet() }
            .getOrDefault(emptySet()).also { names = it }
        return BigEmoji.names(emoji).firstOrNull { it in have }
    }
}

/** Messages whose emoji already played since the app opened: each plays once, then on a tap. */
internal object EmojiPlayed {
    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())
    fun first(uid: String): Boolean = seen.add(uid)
}

/**
 * One to three emoji shown big, without a bubble. Each moves once ([play]
 * counts the times asked): Noto's own animation when the app has it,
 * else the phone's emoji springs and sways. A soft glow of the accent
 * rises behind while they move.
 */
@Composable
internal fun BigEmojiRow(emoji: List<String>, play: Int, modifier: Modifier = Modifier) {
    val width = LocalConfiguration.current.screenWidthDp
    val one = (width * 0.24f).coerceIn(72f, 120f)
    val side = (one * when (emoji.size) { 1 -> 1f; 2 -> 0.8f; else -> 0.66f }).dp
    val glow = remember { Animatable(0f) }
    LaunchedEffect(play) {
        if (play == 0) return@LaunchedEffect
        glow.snapTo(0f)
        glow.animateTo(1f, tween(1900, easing = LinearEasing))
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .semantics { contentDescription = emoji.joinToString(" ") }
            .drawBehind {
                val k = glow.value
                if (k <= 0f || k >= 1f) return@drawBehind
                val a = sin(k * PI).toFloat()
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.28f * a), Color.Transparent),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = size.maxDimension * (0.45f + 0.25f * k)
                    ),
                    radius = size.maxDimension * (0.45f + 0.25f * k)
                )
            }
            .padding(4.dp)
    ) {
        emoji.forEachIndexed { i, e -> OneEmoji(e, side, play, i * 140L) }
    }
}

@Composable
private fun OneEmoji(emoji: String, side: Dp, play: Int, after: Long) {
    val context = LocalContext.current
    val name = remember(emoji) { EmojiAssets.find(context, emoji) }
    if (name != null) {
        // Noto's animation while it plays; at rest the phone's own emoji (some
        // animations start and end on another pose than the emoji's).
        val composition by rememberLottieComposition(LottieCompositionSpec.Asset("emoji/$name.json"))
        val anim = rememberLottieAnimatable()
        var moving by remember { mutableStateOf(false) }
        LaunchedEffect(composition, play) {
            val c = composition ?: return@LaunchedEffect
            if (play == 0) return@LaunchedEffect
            delay(after)
            moving = true
            try {
                anim.animate(c, iterations = 1)
            } finally {
                moving = false
            }
        }
        if (moving && composition != null) {
            LottieAnimation(composition, { anim.progress }, Modifier.size(side))
        } else {
            Glyph(emoji, side, Modifier)
        }
        return
    }
    // The phone's own emoji: a spring up and a sway, once.
    val k = remember { Animatable(1f) }
    LaunchedEffect(play) {
        if (play == 0) return@LaunchedEffect
        delay(after)
        k.snapTo(0f)
        launch { k.animateTo(1f, tween(900, easing = LinearEasing)) }
    }
    Glyph(emoji, side, Modifier.graphicsLayer {
        val t = k.value
        val spring = if (t < 1f) sin(t * PI * 2.5).toFloat() * (1f - t) else 0f
        val s = 1f + 0.22f * spring
        scaleX = s
        scaleY = s
        rotationZ = 10f * sin(t * PI * 3).toFloat() * (1f - t)
        translationY = -0.12f * side.toPx() * spring.coerceAtLeast(0f)
    })
}

@Composable
private fun Glyph(emoji: String, side: Dp, modifier: Modifier) {
    val density = LocalDensity.current
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = with(density) { (side * 0.78f).toSp() }, modifier = modifier)
    }
}

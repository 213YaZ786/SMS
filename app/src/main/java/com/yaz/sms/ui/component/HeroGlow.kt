package com.yaz.sms.ui.component

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The light behind a person's page: their photo, blurred and fading into
 * the page, or a glow when there is none: of the person's own [color]
 * (chosen in the Contacts app) or of the accent. [height] tall, drawn
 * under the top of the page.
 */
@Composable
fun HeroGlow(photo: String?, height: Dp, color: Color? = null) {
    val context = LocalContext.current
    // Three colours of the photo (left, right, below), never the picture itself.
    val tints by produceState<List<Color>?>(null, photo) {
        value = photo?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val full = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                    } ?: return@runCatching null
                    val small = android.graphics.Bitmap.createScaledBitmap(full, 3, 3, true)
                    listOf(small.getPixel(0, 0), small.getPixel(2, 0), small.getPixel(1, 2)).map { Color(it) }
                }.getOrNull()
            }
        }
    }
    val show = remember { Animatable(0f) }
    LaunchedEffect(Unit) { show.animateTo(1f, tween(700)) }
    val accent = color ?: MaterialTheme.colorScheme.primary
    // Faded out towards the bottom as a whole, so it melts into the page's
    // own light instead of ending on an edge.
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                alpha = show.value
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }
    ) {
        val colours = tints
        if (colours != null) {
            // Each colour a soft light of its own, as the glass's halos.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val spots = listOf(Offset(size.width * 0.15f, size.height * 0.2f), Offset(size.width * 0.9f, size.height * 0.3f), Offset(size.width * 0.5f, size.height * 0.75f))
                    colours.forEachIndexed { i, c ->
                        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), spots[i], size.width * 0.7f), size.width * 0.7f, spots[i])
                    }
                }
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent), radius = 900f)
                )
            )
        }
    }
}

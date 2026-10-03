package com.sms.app.ui.component

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
    val image by produceState<ImageBitmap?>(null, photo) {
        value = photo?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap()
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
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(40.dp).graphicsLayer { alpha = 0.6f }
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

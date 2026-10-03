package com.yaz.sms.ui.component

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.yaz.sms.core.dial.ContactLook
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A person as a round pane of glass: their photo when the contact has one,
 * else the monogram chosen in the Contacts app ([look]: an emoji, one or
 * two letters in a font, on their colour), else the first letter of their
 * name, else the outline of a person.
 */
@Composable
fun ContactAvatar(name: String?, photo: String?, size: Dp, modifier: Modifier = Modifier, look: ContactLook.Look? = null) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, photo, px) {
        value = photo?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    // The full photo, sampled down only to the size shown: sharp, never wasteful.
                    val u = Uri.parse(uri)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    ZoneSurface(shape = CircleShape, modifier = modifier.size(size)) {
        val bitmap = image
        val tone = look?.color?.let { Color(it) }
        // On their colour the letters take white or black, whichever reads.
        val ink = when {
            tone == null -> MaterialTheme.colorScheme.primary
            tone.luminance() > 0.5f -> Color.Black.copy(alpha = 0.8f)
            else -> Color.White
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = if (bitmap == null && tone != null) Modifier.fillMaxSize().clip(CircleShape).background(tone.copy(alpha = 0.85f)) else Modifier
        ) {
            val letter = look?.letters?.takeIf { it.isNotBlank() } ?: name?.firstOrNull { it.isLetter() }?.uppercase()
            when {
                bitmap != null -> Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape)
                )
                !look?.emoji.isNullOrBlank() -> Text(look?.emoji.orEmpty(), fontSize = (size.value * 0.5f).sp)
                letter != null -> Text(
                    letter,
                    fontSize = (size.value * (if (letter.length > 1) 0.36f else 0.42f)).sp,
                    color = ink,
                    fontFamily = when (look?.font) {
                        "serif" -> FontFamily.Serif
                        "mono" -> FontFamily.Monospace
                        "rounded" -> FontFamily.SansSerif
                        else -> FontFamily.Default
                    },
                    fontWeight = when (look?.font) {
                        "bold" -> FontWeight.Black
                        "rounded" -> FontWeight.Medium
                        "serif" -> FontWeight.Normal
                        else -> null
                    }
                )
                else -> Icon(AppIcons.Person, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.5f))
            }
        }
    }
}

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
import com.yaz.sms.core.dial.ContactLook
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A person as a round pane of glass: their photo when the contact has one,
 * else the monogram chosen in the Contacts app ([look]: an emoji, one or
 * two letters in a font, on their colour), else the first letter of their
 * name, else the outline of a person.
 *
 * [flat] is for a face that is not a tap (the call screen's): no glass, no rim,
 * no shadow, a flat circle as AOSP Dialer's LetterTileDrawable draws it, a
 * letter or the default avatar on a plain colour
 * (https://android.googlesource.com/platform/packages/apps/Dialer/+/refs/heads/main/java/com/android/contacts/common/lettertiles/LetterTileDrawable.java,
 * Apache-2.0). The photo is clipped to a circle the same way.
 */
@Composable
fun ContactAvatar(name: String?, photo: String?, size: Dp, modifier: Modifier = Modifier, look: ContactLook.Look? = null, flat: Boolean = false) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }
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
    val face: @Composable () -> Unit = {
        val bitmap = image
        val tone = look?.color?.let { Color(it) }
        // On their colour the letters take white or black, whichever reads.
        val ink = when {
            tone == null -> MaterialTheme.colorScheme.primary
            tone.luminance() > 0.5f -> Color.Black.copy(alpha = 0.8f)
            else -> Color.White
        }
        // Flat: the colour is plain (nothing behind it to show through), else the neutral tonal circle.
        val ground = when {
            bitmap != null -> null
            tone != null -> if (flat) tone else tone.copy(alpha = 0.85f)
            flat -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> null
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = if (ground != null) Modifier.fillMaxSize().clip(CircleShape).background(ground) else Modifier
        ) {
            val letter = look?.letters?.takeIf { it.isNotBlank() } ?: name?.firstOrNull { it.isLetter() }?.uppercase()
            when {
                bitmap != null -> Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape)
                )
                // The circle is fixed by design and the letters are a picture in it: sized from the circle in dp
                // (Density.toSp divides by the font scale, https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/Density),
                // so a 200 % font does not push them out of it.
                !look?.emoji.isNullOrBlank() -> Text(look?.emoji.orEmpty(), fontSize = with(density) { (size * 0.5f).toSp() })
                letter != null -> Text(
                    letter,
                    fontSize = with(density) { (size * (if (letter.length > 1) 0.36f else 0.42f)).toSp() },
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
    if (flat) Box(modifier.size(size), contentAlignment = Alignment.Center) { face() }
    else ZoneSurface(shape = CircleShape, modifier = modifier.size(size)) { face() }
}

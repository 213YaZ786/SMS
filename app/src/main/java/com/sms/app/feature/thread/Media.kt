package com.sms.app.feature.thread

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.sms.app.core.mms.ContentType
import com.sms.app.core.mms.MmsPart
import com.sms.app.core.mms.mediaWord
import com.sms.app.ui.component.FloatingAction
import com.sms.app.ui.component.ZoneSurface
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.icon.AppIcons
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A picture, video or sound of a message. A picture shows itself and
 * opens large in a tap; the others open in the app chosen for them.
 */
@Composable
fun MediaTile(part: MmsPart, mine: Boolean) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var large by remember { mutableStateOf(false) }
    if (ContentType.isImageType(part.contentType)) {
        val image by rememberPicture(part.uri, 720)
        val shown = image
        Box(
            Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable {
                    haptics.tick()
                    large = true
                }
        ) {
            if (shown != null) {
                Image(shown, contentDescription = "Photo", contentScale = ContentScale.FillWidth, modifier = Modifier.width(280.dp))
            } else {
                ZoneSurface(shape = RoundedCornerShape(20.dp), modifier = Modifier.size(200.dp, 150.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Image, contentDescription = null) }
                }
            }
        }
        if (large) PictureViewer(part.uri, onClose = { large = false })
    } else if (ContentType.isAudioType(part.contentType)) {
        VoiceTile(part, mine)
    } else {
        ZoneSurface(
            shape = RoundedCornerShape(20.dp),
            accent = mine,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable {
                haptics.tick()
                openOutside(context, part)
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Icon(
                    when {
                        part.contentType.contains("vcard") -> AppIcons.ContactPage
                        ContentType.isVideoType(part.contentType) || ContentType.isAudioType(part.contentType) -> AppIcons.Play
                        else -> AppIcons.AttachFile
                    },
                    contentDescription = null
                )
                Spacer(Modifier.width(10.dp))
                // A contact card shows the person's name, read from the card itself.
                val person by androidx.compose.runtime.produceState<String?>(null, part.uri) {
                    if (part.contentType.contains("vcard")) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(part.uri)?.bufferedReader()?.use { r ->
                                r.lineSequence().take(200).firstOrNull { it.startsWith("FN:") || it.startsWith("FN;") }?.substringAfter(':')?.trim()?.take(60)
                            }
                        }.getOrNull()
                    }
                }
                Text(person ?: part.name ?: mediaWord(part.contentType), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A picture on the whole screen, over a dark ground. */
@Composable
private fun PictureViewer(uri: Uri, onClose: () -> Unit) {
    val image by rememberPicture(uri, 2048)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            image?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.align(Alignment.TopStart).padding(16.dp)) { FloatingAction(AppIcons.Close, "Close", onClose) }
        }
    }
}

/** A picture read in the background, no larger than about [side] pixels. */
@Composable
fun rememberPicture(uri: Uri, side: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, uri, side) {
        value = withContext(Dispatchers.IO) { decode(context, uri, side) }
    }
}

private fun decode(context: Context, uri: Uri, side: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }?.asImageBitmap()
}.getOrNull()

/** A video or a sound opened in the app chosen for it: a copy shared for that one opening. */
private fun openOutside(context: Context, part: MmsPart) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "media." + com.sms.app.core.mms.MediaPrivacy.extension(part.contentType))
        context.contentResolver.openInputStream(part.uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        // An app hidden in a message is never handed to the installer.
        if (com.sms.app.core.mms.MediaPrivacy.isApp(part.contentType, file)) {
            file.delete()
            android.widget.Toast.makeText(context, "Apps can't be opened from a message.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val shared = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(shared, com.sms.app.core.mms.MediaPrivacy.safeType(part.contentType))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

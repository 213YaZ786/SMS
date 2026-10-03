package com.yaz.sms.feature.compose

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.ui.component.BoldButton
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Chatting with someone without any phone number: they scan this code, or
 * open the link, and the two phones exchange keys over the encrypted chat.
 * Their code read by the phone's camera, or their link, opens SMS the same
 * way. The code is the chat's own (a fingerprint, an address, two tokens):
 * no number, no name in it.
 */
@Composable
fun InviteDialog(onDismiss: () -> Unit, onJoined: (String) -> Unit) {
    val context = LocalContext.current
    val chat: RichChat = koinInject()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val link by produceState<String?>(null) { value = chat.inviteLink() }
    val code by produceState<ImageBitmap?>(null, link) { value = link?.let(::qr) }
    var joining by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun join(text: String) {
        joining = true
        scope.launch {
            val address = chat.joinByLink(text)
            joining = false
            if (address != null) {
                haptics.done()
                onJoined(address)
            } else {
                haptics.reject()
                failed = true
            }
        }
    }

    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Lock, null) },
        title = { Text("Without a number") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "They scan your code with their phone's camera, or open your link: an encrypted chat starts, no number given on either side.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Box(
                    Modifier.size(220.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = code
                    if (bitmap != null) Image(bitmap, contentDescription = "Your code", filterQuality = FilterQuality.None, modifier = Modifier.size(196.dp))
                    else if (link == null) CircularProgressIndicator()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoldButton(filled = true, onClick = {
                        val text = link ?: return@BoldButton
                        haptics.tick()
                        context.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share your link")
                        )
                    }) { Text("Share my link") }
                    BoldButton(filled = false, onClick = {
                        val pasted = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (looksLikeInvite(pasted)) join(pasted) else {
                            haptics.reject()
                            failed = true
                        }
                    }) { Text("Paste theirs") }
                }
                when {
                    joining -> Text("Joining…", style = MaterialTheme.typography.bodySmall)
                    failed -> Text("No invite link was found. Copy theirs first, or scan their code with your camera.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    else -> Text("Their code: scan it with your camera, it opens SMS.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** A chat invite link: the engine's own form, nothing else. */
fun looksLikeInvite(text: String): Boolean {
    val t = text.trim()
    return t.length in 20..2000 && t.none(Char::isWhitespace) && t.startsWith("https://i.delta.chat/#")
}

/** A QR code of [text], one pixel per module, black on white. */
private fun qr(text: String): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1)
    )
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

/**
 * An invite link that came from outside (a code read by the camera, a link
 * tapped elsewhere): never joined on its own, the user says yes first.
 */
@Composable
fun JoinQuestion(link: String, onDismiss: () -> Unit, onJoined: (String) -> Unit) {
    val chat: RichChat = koinInject()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var joining by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Lock, null) },
        title = { Text("Start an encrypted chat?") },
        text = {
            Text(
                if (failed) "This invite could not be joined. Is the encrypted chat on, and the phone online?"
                else "With the person who gave this code or link. No number is given on either side.",
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            TextButton(enabled = !joining, onClick = {
                joining = true
                scope.launch {
                    val address = chat.joinByLink(link)
                    joining = false
                    if (address != null) {
                        haptics.done()
                        onJoined(address)
                    } else {
                        haptics.reject()
                        failed = true
                    }
                }
            }) { Text(if (joining) "Joining…" else "Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}

package com.sms.app.feature.thread

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.sms.app.core.mms.Attachment
import android.content.pm.PackageManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sms.app.ui.component.FloatingPane
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.icon.AppIcons

/**
 * The message being written, floating over the conversation: the text in
 * a pane of glass that grows with it, the SIM it goes from when there are
 * two, how many SMS it makes once it is long, and Send on its own pane.
 */
@Composable
fun Composer(initial: String, quote: String?, onClearQuote: () -> Unit, modifier: Modifier, onSend: (String, Int, List<Attachment>) -> Boolean) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var text by rememberSaveable { mutableStateOf(initial) }
    val sims = remember { activeSims(context) }
    var simIndex by rememberSaveable { mutableIntStateOf(sims.indexOfFirst { it.subscriptionId == SubscriptionManager.getDefaultSmsSubscriptionId() }.coerceAtLeast(0)) }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        attachments = (attachments + uris.map { Attachment(it, context.contentResolver.getType(it) ?: "image/jpeg") }).distinctBy { it.uri }.take(5)
    }
    val length = remember(text, attachments) { if (text.isBlank() || attachments.isNotEmpty()) null else SmsMessage.calculateLength(text, false) }
    val canSend = text.isNotBlank() || attachments.isNotEmpty()
    val lift by animateFloatAsState(if (canSend) 1f else 0.86f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "send")

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
        // Shown once the message is more than one SMS, or nearly so.
        length?.let { (parts, _, left) ->
            if (parts > 1 || left < 20) {
                FloatingPane(shape = CircleShape) {
                    Text(
                        if (parts > 1) "$parts SMS · $left left" else "$left left",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        // The message being answered, until sent or let go.
        androidx.compose.animation.AnimatedVisibility(visible = quote != null) {
            var shown by remember { mutableStateOf("") }
            quote?.let { shown = it }
            FloatingPane(shape = RoundedCornerShape(18.dp), onClick = {
                haptics.tick()
                onClearQuote()
            }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Icon(AppIcons.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                    )
                    Icon(AppIcons.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                }
            }
        }
        // What goes with the text, each removable with a tap.
        if (attachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                attachments.forEach { a ->
                    val picture by rememberPicture(a.uri, 240)
                    FloatingPane(shape = RoundedCornerShape(16.dp), onClick = {
                        haptics.tick()
                        attachments = attachments - a
                    }, modifier = Modifier.size(64.dp)) {
                        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                            picture?.let { Image(it, contentDescription = "Remove", contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp)) }
                                ?: Icon(AppIcons.Play, contentDescription = "Remove")
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            FloatingPane(shape = CircleShape, onClick = {
                haptics.tick()
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }, modifier = Modifier.size(52.dp)) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.AddPhoto, contentDescription = "Add a photo or a video", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (sims.size > 1) {
                FloatingPane(shape = CircleShape, onClick = {
                    haptics.tick()
                    simIndex = (simIndex + 1) % sims.size
                }, modifier = Modifier.size(52.dp)) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Text("SIM ${simIndex + 1}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            FloatingPane(shape = RoundedCornerShape(26.dp), modifier = Modifier.weight(1f)) {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 15.dp)) {
                    if (text.isEmpty()) Text("Message", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().widthIn(min = 40.dp)
                    )
                }
            }
            FloatingPane(
                shape = CircleShape,
                accent = canSend,
                onClick = {
                    if (!canSend) return@FloatingPane
                    val sub = sims.getOrNull(simIndex)?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    if (onSend(text, sub, attachments)) {
                        haptics.done()
                        text = ""
                        attachments = emptyList()
                    } else {
                        haptics.reject()
                    }
                },
                modifier = Modifier.size(52.dp).graphicsLayer {
                    scaleX = lift
                    scaleY = lift
                }
            ) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(canSend, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "send") { ready ->
                        Icon(
                            AppIcons.Send,
                            contentDescription = "Send",
                            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** The SIMs in use, when the phone may be asked; one or none otherwise. */
private fun activeSims(context: android.content.Context): List<SubscriptionInfo> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return runCatching { context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())
}

package com.yaz.sms.feature.conversations

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaz.sms.core.dial.NumberActions
import com.yaz.sms.core.mms.MmsTransport
import com.yaz.sms.core.mms.mediaWord
import com.yaz.sms.core.sms.SmsSender
import com.yaz.sms.data.sms.Box as MessageBox
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.data.sms.Message
import com.yaz.sms.data.sms.Messages
import com.yaz.sms.ui.component.ContactAvatar
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * A glance at a conversation without leaving the list, held up in glass:
 * its last messages, a field to answer on the spot, and what can be done
 * with it. It is marked read only when answered or opened.
 */
@Composable
internal fun Peek(
    c: Conversation,
    title: String,
    photo: String?,
    look: com.yaz.sms.core.dial.ContactLook.Look?,
    pinned: Boolean,
    archived: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
    onRead: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val messages: Messages = koinInject()
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf<List<Message>>(emptyList()) }
    LaunchedEffect(c.threadId) { last = messages.thread(c.threadId).takeLast(4) }
    var text by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    // Rises from a little lower and smaller, settling with a slight bounce.
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f)) }
    val dim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { dim.animateTo(1f, tween(220)) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.yaz.sms.ui.component.BlurBehind()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f * dim.value))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            ZoneSurface(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val s = 0.86f + 0.14f * rise.value
                        scaleX = s
                        scaleY = s
                        translationY = (1f - rise.value) * 60f
                        alpha = rise.value.coerceIn(0f, 1f)
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            haptics.tick()
                            onOpen()
                        }
                    ) {
                        ContactAvatar(title, photo, 36.dp, look = look)
                        Spacer(Modifier.width(10.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    last.forEach { m ->
                        val mine = m.box != MessageBox.RECEIVED
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                            ZoneSurface(
                                shape = RoundedCornerShape(18.dp),
                                accent = mine,
                                modifier = Modifier.widthIn(max = 360.dp)
                            ) {
                                Text(
                                    m.body.ifBlank { mediaWord(m.parts.firstOrNull()?.contentType) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        FloatingPane(shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)) {
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                if (text.isEmpty()) Text("Quick reply", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                BasicTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    maxLines = 4,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        FloatingPane(shape = CircleShape, accent = text.isNotBlank(), onClick = {
                            if (text.isBlank()) return@FloatingPane
                            val body = text
                            haptics.done()
                            scope.launch(Dispatchers.IO) {
                                if (c.group) MmsTransport.send(context, c.addresses, body, emptyList(), -1)
                                else SmsSender.send(context, c.address, body)
                                Messages.markRead(context, c.threadId)
                            }
                            onDismiss()
                        }, modifier = Modifier.size(46.dp)) {
                            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                                Icon(AppIcons.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    // What can be done with the conversation, each on its own pane.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Action(AppIcons.PushPin, if (pinned) "Unpin" else "Pin") { onPin(!pinned); onDismiss() }
                        if (c.unread > 0) Action(AppIcons.DoneAll, "Read") { onRead(); onDismiss() }
                        Action(if (archived) AppIcons.Unarchive else AppIcons.Archive, if (archived) "Unarchive" else "Archive") { onArchive(!archived); onDismiss() }
                        if (!c.group && c.address.count(Char::isDigit) >= 3) Action(AppIcons.Call, "Call") { NumberActions.dial(context, c.address); onDismiss() }
                        Action(AppIcons.Delete, "Delete") { confirmDelete = true }
                    }
                }
            }
        }
        if (confirmDelete) {
            ZoneAlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this conversation?") },
                text = { Text("All messages with $title are deleted from this phone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        haptics.firm()
                        onDelete()
                        onDismiss()
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingPane(shape = CircleShape, onClick = {
            haptics.tick()
            onClick()
        }, modifier = Modifier.size(48.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

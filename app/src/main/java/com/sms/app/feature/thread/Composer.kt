package com.sms.app.feature.thread

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
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
fun Composer(
    initial: String,
    quote: String?,
    onClearQuote: () -> Unit,
    modifier: Modifier,
    restore: String? = null,
    onRestored: () -> Unit = {},
    onSchedule: ((String, Int) -> Unit)? = null,
    /** Held, Send offers the effects (and sending later): the text and SIM go there. */
    onEffects: ((String, Int) -> Unit)? = null,
    editing: String? = null,
    onCancelEdit: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    onSend: (String, Int, List<Attachment>) -> Boolean
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var text by rememberSaveable { mutableStateOf(initial) }
    // Where the cursor is, or what is selected: a style wraps it.
    var sel by remember { mutableStateOf(androidx.compose.ui.text.TextRange(initial.length)) }
    var formatting by remember { mutableStateOf(false) }
    // A message taken back before it went comes back into the field.
    androidx.compose.runtime.LaunchedEffect(restore) {
        if (restore != null) {
            text = restore
            onRestored()
        }
    }
    // One of the user's own messages being changed: its text in the field until sent or let go.
    var before by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing != null) {
            before = text
            text = editing
        }
    }
    val sims = remember { activeSims(context) }
    var simIndex by rememberSaveable { mutableIntStateOf(sims.indexOfFirst { it.subscriptionId == SubscriptionManager.getDefaultSmsSubscriptionId() }.coerceAtLeast(0)) }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    fun add(more: List<Attachment>) {
        attachments = (attachments + more).distinctBy { it.uri }.take(5)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        add(uris.map { Attachment(it, context.contentResolver.getType(it) ?: "image/jpeg") })
    }
    // A photo taken now, by the phone's camera app, into this app's cache.
    var shot by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = shot
        if (taken && uri != null) add(listOf(Attachment(uri, "image/jpeg")))
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) add(listOf(Attachment(uri, context.contentResolver.getType(uri) ?: "application/octet-stream")))
    }
    // A contact, sent as its card.
    val person = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        val card = uri?.let { contactCard(context, it) }
        if (card != null) add(listOf(Attachment(card, "text/x-vcard")))
    }
    var arcOpen by remember { mutableStateOf(false) }
    // A voice message being recorded, and how far the finger has slid to cancel or to lock.
    var take by remember { mutableStateOf<VoiceTake?>(null) }
    var slide by remember { mutableStateOf(0f) }
    var rise by remember { mutableStateOf(0f) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { take?.finish(keep = false) } }
    val veil by animateFloatAsState(if (arcOpen) 0.35f else 1f, label = "veil")
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
        // The message being answered or changed, until sent or let go.
        androidx.compose.animation.AnimatedVisibility(visible = quote != null || editing != null) {
            var shown by remember { mutableStateOf("") }
            (editing ?: quote)?.let { shown = it }
            FloatingPane(shape = RoundedCornerShape(18.dp), onClick = {
                haptics.tick()
                if (editing != null) {
                    text = before
                    onCancelEdit()
                } else {
                    onClearQuote()
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Icon(if (editing != null) AppIcons.Create else AppIcons.Reply, contentDescription = if (editing != null) "Editing" else null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
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
                                ?: Icon(
                                    when {
                                        a.contentType.contains("vcard") -> AppIcons.ContactPage
                                        a.contentType.startsWith("video") -> AppIcons.Play
                                        else -> AppIcons.AttachFile
                                    },
                                    contentDescription = "Remove"
                                )
                        }
                    }
                }
            }
        }
        // The styles, over the field: a tap wraps the selection (or the word) in one.
        androidx.compose.animation.AnimatedVisibility(visible = formatting && text.isNotEmpty() && take == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf(
                    com.sms.app.core.sms.Markup.Style.BOLD to AppIcons.FormatBold,
                    com.sms.app.core.sms.Markup.Style.ITALIC to AppIcons.FormatItalic,
                    com.sms.app.core.sms.Markup.Style.UNDERLINE to AppIcons.FormatUnderlined,
                    com.sms.app.core.sms.Markup.Style.STRIKE to AppIcons.FormatStrikethrough
                ).forEach { (style, icon) ->
                    FloatingPane(shape = CircleShape, onClick = {
                        haptics.tick()
                        val (t, r) = com.sms.app.core.sms.Markup.wrap(text, sel.min, sel.max, style)
                        text = t
                        sel = androidx.compose.ui.text.TextRange(r.first, r.last)
                    }, modifier = Modifier.size(44.dp)) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = style.name.lowercase(), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val recording = take
            if (recording != null && recording.locked) {
                // Locked: the hand is free; this throws the recording away.
                FloatingPane(shape = CircleShape, onClick = {
                    haptics.reject()
                    recording.finish(keep = false)
                    take = null
                }, modifier = Modifier.size(52.dp)) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Delete, contentDescription = "Discard the recording", tint = MaterialTheme.colorScheme.error)
                    }
                }
            } else if (recording == null) AttachArc(open = arcOpen, onOpen = { arcOpen = it }) { drop ->
                runCatching {
                    when (drop) {
                        Drop.PHOTOS -> pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        Drop.CAMERA -> {
                            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                            val out = java.io.File(dir, "${System.currentTimeMillis()}.jpg")
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".mms", out)
                            shot = uri
                            camera.launch(uri)
                        }
                        Drop.FILE -> file.launch(arrayOf("*/*"))
                        Drop.CONTACT -> person.launch(null)
                    }
                }
            }
            if (sims.size > 1 && recording == null) {
                FloatingPane(shape = CircleShape, onClick = {
                    haptics.tick()
                    simIndex = (simIndex + 1) % sims.size
                }, modifier = Modifier.size(52.dp)) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Text("SIM ${simIndex + 1}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (recording != null) {
                Column(Modifier.weight(1f)) {
                    if (!recording.locked) Text(
                        "‹ Slide to cancel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp).graphicsLayer { translationX = slide * 0.5f }
                    )
                    FloatingPane(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                        VoiceLevels(recording, Modifier.padding(vertical = 12.dp))
                    }
                }
            } else FloatingPane(shape = RoundedCornerShape(26.dp), modifier = Modifier.weight(1f).graphicsLayer { alpha = veil }) {
              Box {
                Box(Modifier.padding(start = 18.dp, end = if (text.isNotEmpty()) 44.dp else 18.dp, top = 15.dp, bottom = 15.dp)) {
                    if (text.isEmpty()) Text("Message", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    BasicTextField(
                        value = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(sel.start.coerceIn(0, text.length), sel.end.coerceIn(0, text.length))),
                        onValueChange = {
                            text = it.text
                            sel = it.selection
                        },
                        // The styles show as they are written, their marks faint.
                        visualTransformation = { raw -> androidx.compose.ui.text.input.TransformedText(markupHint(raw.text, faded), androidx.compose.ui.text.input.OffsetMapping.Identity) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().widthIn(min = 40.dp)
                    )
                }
                // Aa: the styles, shown or put away.
                if (text.isNotEmpty()) Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp).size(40.dp).clip(CircleShape).clickable(onClickLabel = "Text styles") {
                        haptics.tick()
                        formatting = !formatting
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.TextFormat, contentDescription = "Text styles", tint = if (formatting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
            fun sendVoice(file: java.io.File) {
                val sub = sims.getOrNull(simIndex)?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".mms", file) }.getOrNull() ?: return
                if (onSend("", sub, listOf(Attachment(uri, "audio/mp4")))) haptics.done() else haptics.reject()
            }
            // With nothing written, Send is a microphone: held, it records.
            val micMode = !canSend && editing == null && (take == null || take?.locked == false)
            val micScale = rememberMicScale(take != null && take?.locked == false)
            val density = androidx.compose.ui.platform.LocalDensity.current
            val cancelAt = with(density) { 110.dp.toPx() }
            val lockAt = with(density) { 90.dp.toPx() }
            // A tap sends; held, Send offers to send later.
            fun send() {
                take?.let { t ->
                    take = null
                    t.finish(keep = true)?.let(::sendVoice)
                    return
                }
                if (!canSend) return
                if (editing != null) {
                    haptics.done()
                    onEdit(text)
                    text = before
                    return
                }
                val sub = sims.getOrNull(simIndex)?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                if (onSend(text, sub, attachments)) {
                    haptics.done()
                    text = ""
                    attachments = emptyList()
                } else {
                    haptics.reject()
                }
            }
            Box(if (micMode) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    val t = VoiceTake.start(context) ?: return@awaitEachGesture
                    take = t
                    haptics.tick()
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val moved = change.position - down.position
                        slide = moved.x.coerceAtMost(0f)
                        rise = moved.y.coerceAtMost(0f)
                        change.consume()
                        if (moved.x < -cancelAt) {
                            cancelled = true
                            break
                        }
                        if (moved.y < -lockAt) {
                            t.locked = true
                            haptics.tick()
                            break
                        }
                    }
                    slide = 0f
                    rise = 0f
                    when {
                        cancelled -> {
                            t.finish(keep = false)
                            take = null
                            haptics.reject()
                        }
                        !t.locked -> {
                            take = null
                            t.finish(keep = true)?.let(::sendVoice)
                        }
                    }
                }
            } else Modifier.pointerInput(text, attachments, canSend, editing, take) {
                detectTapGestures(
                    onTap = { send() },
                    onLongPress = {
                        if (editing == null && text.isNotBlank() && attachments.isEmpty() && (onEffects != null || onSchedule != null)) {
                            haptics.firm()
                            val sub = sims.getOrNull(simIndex)?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                            if (onEffects != null) onEffects(text, sub) else onSchedule?.invoke(text, sub)
                            text = ""
                        }
                    }
                )
            }) {
            // While recording, the lock waits above the mic; slid up to, it frees the hand.
            if (take != null && take?.locked == false) {
                FloatingPane(shape = RoundedCornerShape(18.dp), modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { translationY = -88.dp.toPx() + rise * 0.6f }
                    .size(36.dp, 56.dp)) {
                    Box(Modifier.size(36.dp, 56.dp), contentAlignment = Alignment.TopCenter) {
                        Icon(AppIcons.Lock, contentDescription = "Slide up to lock", modifier = Modifier.padding(top = 8.dp).size(18.dp))
                    }
                }
            }
            FloatingPane(
                shape = CircleShape,
                accent = canSend || take != null,
                modifier = Modifier.size(52.dp).graphicsLayer {
                    val s = if (micMode) micScale else lift
                    scaleX = s
                    scaleY = s
                }
            ) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    val state = when {
                        take?.locked == true || canSend -> 1
                        else -> 0
                    }
                    AnimatedContent(state, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "send") { ready ->
                        if (ready == 1) Icon(AppIcons.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                        else Icon(
                            AppIcons.Mic,
                            contentDescription = "Hold to record a voice message",
                            tint = if (take != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

/** The card of a picked contact, read from Android's contacts: its address, or null. */
private fun contactCard(context: android.content.Context, picked: android.net.Uri): android.net.Uri? = runCatching {
    context.contentResolver.query(picked, arrayOf(android.provider.ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use { c ->
        if (c.moveToFirst()) android.net.Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_VCARD_URI, c.getString(0)) else null
    }
}.getOrNull()

/** The text being written with its styles shown and their marks faint, the marks kept in place. */
private fun markupHint(text: String, faint: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val marks = Regex("(?<![\\p{L}\\p{N}])(__|[*_~])(?=\\S)(.+?)(?<=\\S)\\1(?![\\p{L}\\p{N}])")
        marks.findAll(text).forEach { m ->
            val mark = m.groupValues[1]
            val style = when (mark) {
                "*" -> androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                "_" -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                "__" -> androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                else -> androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
            }
            val inner = m.groups[2]!!.range
            addStyle(style, inner.first, inner.last + 1)
            addStyle(androidx.compose.ui.text.SpanStyle(color = faint), m.range.first, m.range.first + mark.length)
            addStyle(androidx.compose.ui.text.SpanStyle(color = faint), m.range.last + 1 - mark.length, m.range.last + 1)
        }
    }

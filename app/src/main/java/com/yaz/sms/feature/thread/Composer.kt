package com.yaz.sms.feature.thread

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
import com.yaz.sms.core.mms.Attachment
import android.content.pm.PackageManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ShapeDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.launch

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
    /** Who wrote the quoted message: null when the user did (shown as "you"). */
    quoteFrom: String? = null,
    restore: String? = null,
    onRestored: () -> Unit = {},
    /** The saved draft, read late from the database: taken only while the field is still empty. */
    lateDraft: String? = null,
    onLateDraftTaken: () -> Unit = {},
    onSchedule: ((String, Int) -> Unit)? = null,
    /** Held, Send offers the effects (and sending later): the text and SIM go there. */
    /** Sends with an effect chosen from the + before the words were written. */
    onSendEffect: ((String, Int, com.yaz.sms.core.sms.Effects.Effect, List<Attachment>) -> Unit)? = null,
    /** The other side sees effects too (the encrypted chat). */
    effectsCarried: Boolean = false,
    /** Over the encrypted chat: the + also offers a poll. */
    onPoll: (() -> Unit)? = null,
    /** Over the encrypted chat: the + also offers a live location; the seconds to share for are given. */
    onLive: ((Int) -> Unit)? = null,
    editing: String? = null,
    onCancelEdit: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    /** What the field holds when the screen pauses or closes, to keep as the draft. */
    onDraft: ((String) -> Unit)? = null,
    /** Media shared from another app, dropped into the attachments once (Bugle's share-intent way in). */
    incoming: List<Attachment>? = null,
    onIncomingTaken: () -> Unit = {},
    /** The other members of a group: typing @ offers them (Signal's mentions picker); empty for one person. */
    mentionNames: List<String> = emptyList(),
    onSend: (String, Int, List<Attachment>) -> Boolean
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // The field's one source of truth: text, selection and the keyboard's composition live in a TextFieldState,
    // so a word being composed survives every recomposition (state-based BasicTextField, saved like the old
    // rememberSaveable text). https://developer.android.com/develop/ui/compose/text/user-input
    // https://developer.android.com/develop/ui/compose/text/migrate-state-based
    val state = rememberTextFieldState(initial)
    val text = state.text.toString()
    // Where the cursor is, or what is selected: a style wraps it.
    val sel = state.selection
    // The draft kept when the user goes, as AOSP Messaging keeps it on pause
    // (ConversationFragment.onPause -> saveDraft); never a message being edited.
    if (onDraft != null) {
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val keep by rememberUpdatedState(if (editing == null) text else null)
        DisposableEffect(lifecycle) {
            val watcher = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) keep?.let(onDraft)
            }
            lifecycle.lifecycle.addObserver(watcher)
            onDispose {
                lifecycle.lifecycle.removeObserver(watcher)
                keep?.let(onDraft)
            }
        }
    }
    var formatting by remember { mutableStateOf(false) }
    // A message taken back before it went comes back into the field.
    androidx.compose.runtime.LaunchedEffect(restore) {
        if (restore != null) {
            state.setTextAndPlaceCursorAtEnd(restore)
            onRestored()
        }
    }
    // A draft read late never overwrites what has been typed meanwhile; the cursor lands at its end.
    androidx.compose.runtime.LaunchedEffect(lateDraft) {
        if (lateDraft != null) {
            if (state.text.isEmpty() && editing == null) state.setTextAndPlaceCursorAtEnd(lateDraft)
            onLateDraftTaken()
        }
    }
    // One of the user's own messages being changed: its text in the field until sent or let go.
    var before by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing != null) {
            before = state.text.toString()
            state.setTextAndPlaceCursorAtEnd(editing)
        }
    }
    val sims = remember { activeSims(context) }
    var simIndex by rememberSaveable { mutableIntStateOf(sims.indexOfFirst { it.subscriptionId == SubscriptionManager.getDefaultSmsSubscriptionId() }.coerceAtLeast(0)) }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    fun add(more: List<Attachment>) {
        attachments = (attachments + more).distinctBy { it.uri }.take(5)
    }
    LaunchedEffect(incoming) {
        if (!incoming.isNullOrEmpty()) {
            add(incoming)
            onIncomingTaken()
        }
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
    // Where the phone is, once, into the words: the user sends it or not.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var lost by remember { mutableStateOf(false) }
    fun placeHere() {
        locating = true
        scope.launch {
            val here = com.yaz.sms.core.sms.HereNow.find(context)
            locating = false
            if (here == null) {
                haptics.reject()
                // Said in the field a moment: location off, or no fix within the time.
                lost = true
                kotlinx.coroutines.delay(3000)
                lost = false
            } else {
                haptics.done()
                val link = com.yaz.sms.core.sms.HereNow.link(here)
                val now = state.text.toString()
                state.setTextAndPlaceCursorAtEnd(if (now.isBlank()) "📍 $link" else now.trimEnd() + "\n📍 " + link)
            }
        }
    }
    // Refused for good, the dialog says so and opens Android's settings page (ui/component/PermissionHelp.kt).
    val askPlace = com.yaz.sms.ui.component.rememberPermissionAsk(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        title = "Location is off",
        message = "To share a place, allow location for SMS in the app's settings.",
        onRefused = { haptics.reject() }
    ) { placeHere() }
    // The live location's durations; the location permission is asked first, as for a place (HereNow).
    var choosingLive by remember { mutableStateOf(false) }
    val askLive = com.yaz.sms.ui.component.rememberPermissionAsk(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        title = "Location is off",
        message = "To share your live location, allow location for SMS in the app's settings.",
        onRefused = { haptics.reject() }
    ) { choosingLive = true }
    var arcOpen by remember { mutableStateOf(false) }
    // A voice message being recorded, and how far the finger has slid to cancel or to lock.
    var take by remember { mutableStateOf<VoiceTake?>(null) }
    // The effect picked from the + before writing, and its chooser.
    var armed by remember { mutableStateOf<com.yaz.sms.core.sms.Effects.Effect?>(null) }
    var choosingEffect by remember { mutableStateOf(false) }
    // What a pick of the + does, set where the launchers are known.
    val pickDrop = remember { mutableStateOf<((Drop) -> Unit)?>(null) }
    var slide by remember { mutableStateOf(0f) }
    var rise by remember { mutableStateOf(0f) }
    val askMic = com.yaz.sms.ui.component.rememberPermissionAsk(
        arrayOf(Manifest.permission.RECORD_AUDIO),
        title = "Microphone is off",
        message = "To record a voice message, allow the microphone for SMS in the app's settings.",
        onRefused = { haptics.reject() }
    ) { }
    // A photo needs the camera allowed when the app declares it, or the phone's camera app is refused
    // https://developer.android.com/media/camera/camera-intents
    fun takePhoto() {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val out = java.io.File(dir, "${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".mms", out)
        shot = uri
        // startActivity throws ActivityNotFoundException when no app handles ACTION_IMAGE_CAPTURE; said, never silent.
        // https://developer.android.com/media/camera/camera-intents
        try {
            camera.launch(uri)
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.w("Composer", "camera: " + e.javaClass.simpleName)
            haptics.reject()
            android.widget.Toast.makeText(context, "No camera app opened", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            android.util.Log.w("Composer", "camera: " + e.javaClass.simpleName)
            haptics.reject()
            android.widget.Toast.makeText(context, "The camera app refused", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val askCamera = com.yaz.sms.ui.component.rememberPermissionAsk(
        arrayOf(Manifest.permission.CAMERA),
        title = "Camera is off",
        message = "To take a photo, allow the camera for SMS in the app's settings.",
        onRefused = { haptics.reject() }
    ) { takePhoto() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { take?.finish(keep = false) } }
    val veil by animateFloatAsState(if (arcOpen) 0.35f else 1f, label = "veil")
    val length = remember(text, attachments) { if (text.isBlank() || attachments.isNotEmpty()) null else SmsMessage.calculateLength(text, false) }
    val canSend = text.isNotBlank() || attachments.isNotEmpty()
    val lift by animateFloatAsState(if (canSend) 1f else 0.86f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "send")

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
        // Shown once the message is more than one SMS, or nearly so.
        length?.let { (parts, _, left) ->
            if (parts > 1 || left < 20) {
                // It does nothing, so no zone: plain text on the page.
                Text(
                    if (parts > 1) "$parts SMS, $left left" else "$left left",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        // The message being answered or changed, until sent or let go.
        androidx.compose.animation.AnimatedVisibility(visible = quote != null || editing != null) {
            var shown by remember { mutableStateOf("") }
            var shownFrom by remember { mutableStateOf<String?>(null) }
            (editing ?: quote)?.let {
                shown = it
                shownFrom = if (editing != null) null else quoteFrom
            }
            val changing = editing != null
            // Element X's ReplyToModeView: the sender's name, the words under it (two lines, one at a large font), and a
            // close button that alone clears it; the pane itself does nothing (Signal's QuoteView in the input panel has its
            // dismiss button the same way).
            // https://github.com/element-hq/element-x-android/blob/develop/libraries/textcomposer/impl/src/main/kotlin/io/element/android/libraries/textcomposer/ComposerModeView.kt
            // https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/components/QuoteView.java
            // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
            FloatingPane(shape = ShapeDefaults.ExtraLarge, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val dense = androidx.compose.ui.platform.LocalDensity.current.let { it.density * it.fontScale >= 3.5f }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp)) {
                    Icon(if (changing) AppIcons.Create else AppIcons.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(
                            if (changing) "Editing" else "Replying to ${shownFrom ?: "you"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            shown,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (dense) 1 else 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    // A transparent round tap target of 48 dp inside the glass.
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (changing) "Cancel editing" else "Cancel reply") {
                            haptics.tick()
                            if (changing) {
                                state.setTextAndPlaceCursorAtEnd(before)
                                onCancelEdit()
                            } else {
                                onClearQuote()
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(AppIcons.Close, contentDescription = if (changing) "Cancel editing" else "Cancel reply", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (choosingEffect) EffectSheet(
            carried = effectsCarried,
            onLater = null,
            onPick = { effect ->
                choosingEffect = false
                armed = effect
                haptics.done()
            },
            onDismiss = { choosingEffect = false }
        )
        // The styles: a tap wraps the selection (or the word) in one. Shown on MentionLine's row, beside the clock.
        val styleButtons: (@Composable () -> Unit)? = if (formatting && text.isNotEmpty() && take == null) ({
            listOf(
                com.yaz.sms.core.sms.Markup.Style.BOLD to AppIcons.FormatBold,
                com.yaz.sms.core.sms.Markup.Style.ITALIC to AppIcons.FormatItalic,
                com.yaz.sms.core.sms.Markup.Style.UNDERLINE to AppIcons.FormatUnderlined,
                com.yaz.sms.core.sms.Markup.Style.STRIKE to AppIcons.FormatStrikethrough
            ).forEach { (style, icon) ->
                FloatingPane(shape = CircleShape, onClick = {
                    haptics.tick()
                    val (t, r) = com.yaz.sms.core.sms.Markup.wrap(text, sel.min, sel.max, style)
                    state.edit {
                        replace(0, this.length, t)
                        selection = androidx.compose.ui.text.TextRange(r.first, r.last)
                    }
                }, modifier = Modifier.size(40.dp)) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = style.name.lowercase(), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }) else null
        // The members offered while an @ is being typed: a tap puts "@Name " in its place. Signal's
        // MentionsPickerFragmentV2 over the composer, the query from ComposeText (core/chat/Mentions.kt).
        val mentionQuery = if (mentionNames.isEmpty() || sel.length > 0 || take != null || editing != null) null else com.yaz.sms.core.chat.Mentions.queryAt(text, sel.end)
        val mentionMatches = mentionQuery?.let { com.yaz.sms.core.chat.Mentions.search(mentionNames, it.text) }.orEmpty()
        if (choosingLive && onLive != null) LiveDurationSheet(
            onPick = { seconds ->
                choosingLive = false
                onLive(seconds)
            },
            onDismiss = { choosingLive = false }
        )
        val drops = Drop.entries.filter { (it != Drop.POLL || onPoll != null) && (it != Drop.EFFECTS || onSendEffect != null) && (it != Drop.LIVE || onLive != null) }
        // What the + offers, above the bar, on the + side: round buttons of glass.
        androidx.compose.animation.AnimatedVisibility(
            visible = arcOpen && take == null,
            // No fade on the parent: while it fades, its layer is offscreen and clips what the tiles draw
            // above its bounds (their pop overshoots); each Tile fades and pops itself.
            // https://developer.android.com/develop/ui/compose/graphics/draw/modifiers#compositing-strategy
            enter = EnterTransition.None,
            exit = fadeOut() + androidx.compose.animation.shrinkVertically(),
            modifier = Modifier.fillMaxWidth()
        ) {
            AttachTiles(drops) { drop ->
                arcOpen = false
                pickDrop.value?.invoke(drop)
            }
        }
        // The members offered while an @ is typed, as pills on the start side of the row that carries Send later's
        // clock at its end, over the bar (MentionLine).
        val laterReady = onSchedule != null && editing == null && take == null && text.isNotBlank() && attachments.isEmpty()
        // Send later: the clock's tap, and Send held (Signal's SendButton long press opens the scheduled send).
        fun sendLater() {
            val sub = sims.getOrNull(simIndex)?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            onSchedule?.invoke(text, sub)
            state.clearText()
        }
        MentionLine(
            names = mentionMatches,
            laterReady = laterReady,
            onMention = { who ->
                val (t, c) = com.yaz.sms.core.chat.Mentions.insert(text, sel.end, who)
                state.edit {
                    replace(0, this.length, t)
                    selection = androidx.compose.ui.text.TextRange(c)
                }
            },
            onLater = {
                haptics.tick()
                sendLater()
            },
            styles = styleButtons
        )
        // The mic grows while it records; the recording pane and the lock follow this one value.
        val micScale = rememberMicScale(take != null && take?.locked == false)
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
            } else if (recording == null) {
                pickDrop.value = { drop ->
                runCatching {
                    when (drop) {
                        // An effect is chosen, then sits in the bar: the message goes only on Send.
                        Drop.EFFECTS -> choosingEffect = true
                        Drop.PHOTOS -> pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        Drop.CAMERA ->
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePhoto()
                            else askCamera()
                        Drop.FILE -> file.launch(arrayOf("*/*"))
                        Drop.CONTACT -> person.launch(null)
                        Drop.POLL -> onPoll?.invoke()
                        Drop.PLACE -> if (com.yaz.sms.core.sms.HereNow.allowed(context)) placeHere()
                            else askPlace()
                        Drop.LIVE -> if (com.yaz.sms.core.sms.HereNow.allowed(context)) choosingLive = true
                            else askLive()
                    }
                }
            }
                AttachArc(open = arcOpen, onOpen = { arcOpen = it }, drops = drops) { }
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
                // The grown mic (52 dp, up to 1.5x) takes half its growth on each side: the pane narrows at the
                // same pace (end padding = (scale - 1) * 26 dp), so they never touch. Never negative: the spring undershoots.
                Column(Modifier.weight(1f).padding(end = (26.dp * (micScale - 1f)).coerceAtLeast(0.dp))) {
                    if (!recording.locked) Text(
                        "‹ Slide to cancel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp).graphicsLayer { translationX = slide * 0.5f }
                    )
                    FloatingPane(shape = ShapeDefaults.ExtraLarge, modifier = Modifier.fillMaxWidth()) {
                        VoiceLevels(recording, Modifier.padding(vertical = 12.dp))
                    }
                }
            } else FloatingPane(shape = ShapeDefaults.ExtraLarge, modifier = Modifier.weight(1f).graphicsLayer { alpha = veil }) {
              Column {
                // An effect chosen from the +, inside the bar with the words it goes with.
                armed?.let { effect ->
                    val face = effectFaces.firstOrNull { it.first == effect }?.second
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(onClickLabel = "No effect") { haptics.tick(); armed = null }
                            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Text((face?.first ?: "✨") + "  " + (face?.second ?: "Effect"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Icon(AppIcons.Close, contentDescription = "No effect", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                    }
                }
                // What goes with the text, inside the bar it leaves with, each with its cross.
                if (attachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp)
                    ) {
                        attachments.forEach { a ->
                            val picture by rememberPicture(a.uri, 240)
                            Box(Modifier.size(72.dp)) {
                                Box(
                                    Modifier.padding(top = 6.dp, end = 6.dp).size(66.dp).clip(ShapeDefaults.Large)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(66.dp)) }
                                        ?: Icon(
                                            when {
                                                a.contentType.contains("vcard") -> AppIcons.ContactPage
                                                a.contentType.startsWith("video") -> AppIcons.Play
                                                a.contentType.startsWith("audio") -> AppIcons.Mic
                                                else -> AppIcons.AttachFile
                                            },
                                            contentDescription = null
                                        )
                                }
                                Box(
                                    Modifier.align(Alignment.TopEnd).size(26.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.inverseSurface)
                                        .clickable(onClickLabel = "Remove") {
                                            haptics.tick()
                                            attachments = attachments - a
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(AppIcons.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
              Box {
                // Signal's quick camera button beside the field, shown while nothing is written or waiting
                // (conversation_input_panel.xml: quick_camera_toggle).
                // https://github.com/signalapp/Signal-Android/blob/main/app/src/main/res/layout/conversation_input_panel.xml
                val quickCamera = text.isEmpty() && attachments.isEmpty() && editing == null
                Box(Modifier.padding(start = 18.dp, end = if (text.isNotEmpty() || quickCamera) 44.dp else 18.dp, top = 15.dp, bottom = 15.dp)) {
                    val hint = if (locating) "Finding where you are…" else if (lost) "Not found: is location on?" else if (attachments.isNotEmpty()) "Add a message, or send" else if (armed != null) "Your message, with its effect" else "Message"
                    if (text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    val mentionInk = MaterialTheme.colorScheme.primary
                    // State-based BasicTextField: the keyboard's composition is kept by the state, never rebuilt.
                    // https://developer.android.com/develop/ui/compose/text/migrate-state-based
                    // Part of a mention taken away: all of it goes, the @ stays (Signal's MentionDeleter), as an
                    // InputTransformation (the migration guide: onValueChange filters become InputTransformation).
                    val mentionDeleter = remember(mentionNames) {
                        if (mentionNames.isEmpty()) null else InputTransformation {
                            val cut = com.yaz.sms.core.chat.Mentions.afterEdit(originalText.toString(), asCharSequence().toString(), mentionNames)
                            if (cut != null) {
                                val whole = onWholeCharacters(cut.first, cut.second)
                                replace(0, this.length, whole)
                                selection = androidx.compose.ui.text.TextRange(cut.second)
                            }
                        }
                    }
                    // The styles show as they are written, their marks faint; the members named stand out: display
                    // only, as an OutputTransformation (the guide: visualTransformation becomes OutputTransformation;
                    // spans only, so offsets stay the identity).
                    val markupOutput = remember(faded, mentionInk, mentionNames) {
                        OutputTransformation {
                            markupHint(asCharSequence().toString(), faded, mentionInk, mentionNames).spanStyles.forEach {
                                addStyle(it.item, it.start, it.end)
                            }
                        }
                    }
                    BasicTextField(
                        state = state,
                        inputTransformation = mentionDeleter,
                        outputTransformation = markupOutput,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        // The field named by its hint for TalkBack and tests: a text field's label in its semantics,
                        // https://developer.android.com/develop/ui/compose/accessibility/key-steps (content descriptions).
                        modifier = Modifier.fillMaxWidth().widthIn(min = 40.dp).semantics { contentDescription = hint }
                    )
                }
                // The camera: what the + arc's Camera tile does.
                if (quickCamera) PressedIconTap(
                    icon = AppIcons.PhotoCamera,
                    label = "Camera",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp)
                ) {
                    haptics.tick()
                    arcOpen = false
                    pickDrop.value?.invoke(Drop.CAMERA)
                }
                // Aa: the styles, shown or put away.
                if (text.isNotEmpty()) PressedIconTap(
                    icon = AppIcons.TextFormat,
                    label = "Text styles",
                    tint = if (formatting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp)
                ) {
                    haptics.tick()
                    formatting = !formatting
                }
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
                    state.setTextAndPlaceCursorAtEnd(before)
                    return
                }
                val sub = sims.getOrNull(simIndex)?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                // An effect chosen from the + goes with these words.
                val effect = armed
                if (effect != null && onSendEffect != null) {
                    haptics.done()
                    onSendEffect(text, sub, effect, attachments)
                    state.clearText()
                    attachments = emptyList()
                    armed = null
                    return
                }
                if (onSend(text, sub, attachments)) {
                    haptics.done()
                    state.clearText()
                    attachments = emptyList()
                } else {
                    haptics.reject()
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Send later's clock is in the row above (MentionLine), over Send.
            Box(if (micMode) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        askMic()
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
            } else Modifier.pointerInput(text, attachments, canSend, editing, take, laterReady) {
                detectTapGestures(
                    onTap = { send() },
                    // Held: Send later when it is offered (the same as the clock), else nothing, not even a send on letting go.
                    // https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/components/SendButton.kt
                    onLongPress = {
                        if (laterReady) {
                            haptics.firm()
                            sendLater()
                        }
                    }
                )
            }.then(if (laterReady) Modifier.semantics {
                onLongClick("Send later") {
                    haptics.firm()
                    sendLater()
                    true
                }
            } else Modifier)) {
            // While recording, the lock waits above the mic; slid up to, it frees the hand.
            if (take != null && take?.locked == false) {
                // Signal's lock_drop_target: a pill 40 x 64 dp, the lock at its top, a chevron up under it, both in the
                // secondary tint (signal_icon_tint_secondary).
                // https://github.com/signalapp/Signal-Android/blob/main/app/src/main/res/layout/microphone_recorder_view.xml
                // Its middle sits at the mic's middle plus the mic's grown half height (26 dp * scale), a gap of
                // 12 dp and the pill's half height (32 dp) above it; it rises with the finger.
                FloatingPane(shape = ShapeDefaults.ExtraLarge, modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { translationY = -(26.dp * micScale + 12.dp + 32.dp).toPx() + rise * 0.6f }
                    .size(40.dp, 64.dp)) {
                    Column(
                        Modifier.size(40.dp, 64.dp).semantics { contentDescription = "Slide up to lock" },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(AppIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp).size(24.dp))
                        Icon(AppIcons.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp).size(18.dp))
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
}

/**
 * A tap target of 40 dp inside the field's glass: transparent, no ripple, no shape; the icon itself shows the press
 * by dimming and shrinking (alpha 0.4, scale 0.88), as a highlighted UIButton dims its icon in iOS.
 * Pressed state: https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions
 * (interactionSource.collectIsPressedAsState); the spring as RoundAction (ui/component/RoundAction.kt).
 */
@Composable
private fun PressedIconTap(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val dim by animateFloatAsState(if (pressed) 0.4f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "dim")
    val sink by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "sink")
    Box(
        modifier.size(40.dp).clickable(
            interactionSource = source,
            indication = null,
            role = androidx.compose.ui.semantics.Role.Button,
            onClickLabel = label,
            onClick = onClick
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.graphicsLayer {
                alpha = dim.coerceIn(0f, 1f)
                scaleX = sink
                scaleY = sink
            }
        )
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

/**
 * [text] where a mention was cut at [at] (just after the kept @): what follows must start on a whole character,
 * so the half of a surrogate pair or the combining marks and joiners that belonged to the cut part go too.
 * Signal's MentionDeleter replaces the whole span range with the @ alone
 * (https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/components/mention/MentionDeleter.java);
 * the boundary test is Android's BreakIterator (https://developer.android.com/reference/java/text/BreakIterator).
 */
private fun onWholeCharacters(text: String, at: Int): String {
    if (at !in 0 until text.length) return text
    val boundaries = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
    var end = at
    while (end < text.length && (text[end].isLowSurrogate() || !boundaries.isBoundary(end))) end++
    return if (end == at) text else text.removeRange(at, end)
}

/** The text being written with its styles shown and their marks faint, the marks kept in place. */
private fun markupHint(
    text: String,
    faint: androidx.compose.ui.graphics.Color,
    mention: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    names: List<String> = emptyList()
): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        // A member named: the @ and the name stand out, the way Signal draws a mention in the field
        // (components/mention/MentionRendererDelegate), here by colour and weight.
        if (names.isNotEmpty() && mention != androidx.compose.ui.graphics.Color.Unspecified) {
            com.yaz.sms.core.chat.Mentions.find(text, names).forEach {
                addStyle(
                    androidx.compose.ui.text.SpanStyle(color = mention, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, background = mention.copy(alpha = 0.14f)),
                    it.start, it.end
                )
            }
        }
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

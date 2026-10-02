package com.sms.app.feature.thread

import android.content.ClipData
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import com.sms.app.ui.component.HeroGlow
import kotlin.math.roundToInt
import android.content.ClipboardManager
import android.provider.Telephony
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.sms.app.core.dial.NumberActions
import com.sms.app.core.dial.Numbers
import com.sms.app.core.dial.T9
import com.sms.app.core.link.LinkCleaner
import com.sms.app.core.sms.Codes
import com.sms.app.core.sms.MessageNotifier
import com.sms.app.core.sms.SmsSender
import com.sms.app.core.mms.MmsTransport
import com.sms.app.core.chat.RichChat
import com.sms.app.data.settings.SettingsStore
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import com.sms.app.core.chat.RichRef
import com.sms.app.data.contacts.PhoneBook
import com.sms.app.data.contacts.PhoneIndex
import com.sms.app.data.sms.Box as MessageBox
import com.sms.app.data.sms.Message
import com.sms.app.data.sms.Messages
import com.sms.app.feature.conversations.dayLabel
import com.sms.app.feature.conversations.timeLabel
import com.sms.app.navigation.LocalReadableInset
import com.sms.app.ui.component.FloatingAction
import com.sms.app.ui.component.FloatingFrame
import com.sms.app.ui.component.FloatingPane
import com.sms.app.ui.component.FloatingTop
import com.sms.app.ui.component.PillItem
import com.sms.app.ui.component.PillMenu
import com.sms.app.ui.component.PillMotion
import com.sms.app.ui.component.ZoneAlertDialog
import com.sms.app.ui.component.ZoneSurface
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.component.rememberPillMenu
import com.sms.app.ui.icon.AppIcons
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * One conversation on the whole screen: the messages pass under the name
 * at the top and under the message being written at the bottom, both in
 * glass. Opening it marks it read.
 */
@Composable
fun ThreadScreen(threadId: Long?, address: String, draft: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val messages: Messages = koinInject()
    val book: PhoneBook = koinInject()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (book.canRead()) book.refresh() }
    val contacts by book.entries.collectAsState()
    val changes by messages.changes.collectAsState()
    val chat: RichChat = koinInject()
    val chatChanges by chat.changes.collectAsState()
    val links by chat.links.collectAsState()
    val refs by chat.refs.collectAsState()

    // The thread is found from the numbers when only they are known, and the
    // numbers from the thread when only it is known (a notification).
    val given = remember(address) { address.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
    var people by remember { mutableStateOf(given) }
    var thread by remember { mutableStateOf(threadId) }
    LaunchedEffect(address) {
        if (thread == null && given.isNotEmpty()) {
            thread = withContext(Dispatchers.IO) { runCatching { Telephony.Threads.getOrCreateThreadId(context, given.toSet()) }.getOrNull() }
        }
        val t = thread
        if (t != null) messages.addressesOf(t).takeIf { it.isNotEmpty() }?.let { people = it }
    }
    var list by remember { mutableStateOf<List<Message>>(emptyList()) }
    // Read only while the conversation is really in front: in the
    // background its new messages stay unread and notified.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val inFront by lifecycle.currentStateFlow.collectAsState()
    val shown = inFront.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    // The newest messages first; going back in the conversation reads more.
    var window by remember { mutableIntStateOf(Messages.PAGE) }
    LaunchedEffect(thread, changes, chatChanges, shown, window) {
        val t = thread ?: return@LaunchedEffect
        list = messages.thread(t, window)
        if (!shown) return@LaunchedEffect
        if (list.any { it.box == MessageBox.RECEIVED && !it.read }) messages.markRead(t)
        MessageNotifier(context).cancel(t)
    }
    val index = remember(contacts) { PhoneIndex(contacts) }
    val to = people.firstOrNull().orEmpty()
    val group = people.size > 1
    val entry = remember(index, to) { index.find(T9.clean(to)) }
    val title = if (group) people.joinToString(", ") { index.find(T9.clean(it))?.name?.substringBefore(' ') ?: Numbers.format(context, it) }
    else entry?.name ?: Numbers.format(context, to)
    val canCall = !group && to.count(Char::isDigit) >= 3
    // With one number: the rich chat when it has SMS too, else SMS.
    val linked = remember(links, to) { if (group || to.isBlank()) null else chat.linkFor(to) }
    // A group goes over the encrypted chat when each of its members does.
    val encrypted = remember(links, people) { linked != null || (group && chat.groupReady(people)) }
    LaunchedEffect(encrypted, chatChanges, shown) { if (encrypted && shown) chat.markSeen(people) }

    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(0.dp) }
    // A message swiped to answer it: shown over the field, sent quoted.
    var quote by remember { mutableStateOf<String?>(null) }
    // One of the user's own chat messages being changed: its chat id and text.
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // How long messages last in the encrypted chat, in seconds; 0 for always.
    var vanish by remember { mutableIntStateOf(0) }
    LaunchedEffect(encrypted, chatChanges) { vanish = if (encrypted) chat.timer(people) else 0 }
    // Messages waiting their few seconds before they go, and text taken back.
    val store: SettingsStore = koinInject()
    var waiting by remember { mutableStateOf<List<Pending>>(emptyList()) }
    var restore by remember { mutableStateOf<String?>(null) }
    // A message held back to go at a chosen time.
    var scheduling by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var scheduled by remember { mutableStateOf(com.sms.app.core.sms.Timed.scheduled(context)) }
    // Received messages that arrive while the conversation is open drop in.
    var known by remember { mutableStateOf<Set<Long>?>(null) }
    var fresh by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val haptics = rememberHaptics()
    LaunchedEffect(list) {
        val received = list.filter { it.box == MessageBox.RECEIVED }.map { it.id }.toSet()
        val before = known
        if (before != null) {
            val new = received - before
            if (new.isNotEmpty()) {
                fresh = fresh + new
                haptics.tick()
            }
        }
        known = received
    }
    fun sendNow(p: Pending) {
        scope.launch(Dispatchers.IO) {
            // Photos leave without their place, camera and dates.
            val attachments = p.attachments.map { com.sms.app.core.mms.MediaPrivacy.clean(context, it) }
            // Over the rich chat when the number has it, quote included;
            // else a group or a picture as a picture message, the rest as SMS.
            if (encrypted && chat.send(people, p.text, attachments, p.quoted)) return@launch
            val text = p.quoted?.let { "«${excerpt(it)}»\n${p.text}" } ?: p.text
            if (group || attachments.isNotEmpty()) MmsTransport.send(context, people, text, attachments, p.sub)
            else SmsSender.send(context, to, text, p.sub)
            // The first message to a number asks, unseen, whether it has SMS too.
            if (!group) chat.hello(to)
        }
    }

    scheduling?.let { (text, sub) ->
        com.sms.app.feature.conversations.TimeChoice("Send later", onPick = { at ->
            com.sms.app.core.sms.Timed.schedule(context, com.sms.app.core.sms.Scheduled(System.currentTimeMillis(), people, text, at, sub))
            scheduled = com.sms.app.core.sms.Timed.scheduled(context)
            scheduling = null
        }, onDismiss = {
            restore = text
            scheduling = null
        })
    }
    FloatingFrame(
        bottom = composerHeight + 8.dp,
        top = {
            FloatingTop(
                title = null,
                leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) },
                trailing = { if (canCall) FloatingAction(AppIcons.Call, "Call", { NumberActions.dial(context, to) }) },
                center = {
                    PersonPill(title, to, entry?.contactId, group, vanish = if (encrypted) vanish else null) { seconds ->
                        scope.launch { if (chat.setTimer(people, seconds)) vanish = seconds }
                    }
                }
            )
            androidx.compose.animation.AnimatedVisibility(visible = encrypted, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                FloatingPane(shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(AppIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (vanish > 0) "Encrypted chat · vanish after ${vanishLabel(vanish)}" else "Encrypted chat", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        overlay = {
            Composer(
                initial = draft,
                quote = quote,
                onClearQuote = { quote = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = LocalReadableInset.current)
                    .onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
                restore = restore,
                onRestored = { restore = null },
                onSchedule = { text, sub -> scheduling = text to sub },
                editing = editing?.second,
                onCancelEdit = { editing = null },
                onEdit = { typed ->
                    val e = editing
                    editing = null
                    if (e != null && typed.isNotBlank() && typed != e.second) scope.launch { if (!chat.edit(e.first, typed)) haptics.reject() }
                },
                onSend = { typed, sub, attachments ->
                    if (people.isEmpty()) return@Composer false
                    val p = Pending(System.nanoTime(), typed, sub, attachments, quote)
                    quote = null
                    val delay = store.current.undoSeconds
                    if (delay <= 0) {
                        sendNow(p)
                    } else {
                        waiting = waiting + p
                        scope.launch {
                            kotlinx.coroutines.delay(delay * 1000L)
                            if (waiting.any { it.key == p.key }) {
                                waiting = waiting.filterNot { it.key == p.key }
                                haptics.done()
                                sendNow(p)
                            }
                        }
                    }
                    true
                }
            )
        }
    ) { padding ->
        val inset = LocalReadableInset.current
        val rows = remember(list) { rowsOf(list) }
        // The person's light behind the conversation: their photo, blurred, or a glow of the accent.
        HeroGlow(if (group) null else entry?.photo, height = padding.calculateTopPadding() + 320.dp)
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // Near the oldest message shown, with more behind it: read the next page.
        val nearTop by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 15 } }
        LaunchedEffect(nearTop, list.size) { if (nearTop && list.size >= window) window += Messages.PAGE }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().padding(horizontal = inset),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 14.dp),
            // A short conversation sits by the composer, as a long one does.
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)
        ) {
            // Messages scheduled for later, at the very bottom; a tap cancels one.
            items(scheduled.filter { it.to.toSet() == people.toSet() }, key = { "sched/${it.id}" }) { sc ->
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth().animateItem()) {
                    FloatingPane(shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), onClick = {
                        haptics.tick()
                        com.sms.app.core.sms.Timed.cancel(context, sc.id)
                        scheduled = com.sms.app.core.sms.Timed.scheduled(context)
                        restore = sc.text
                    }, modifier = Modifier.widthIn(max = 320.dp)) {
                        Text(sc.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, end = 6.dp)) {
                        Icon(AppIcons.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scheduled · " + com.sms.app.feature.conversations.aheadLabel(context, sc.at) + " · tap to cancel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // Waiting messages sit at the bottom, their ring emptying; a tap takes one back.
            items(waiting.asReversed(), key = { "wait/${it.key}" }) { p ->
                PendingBubble(p, store.current.undoSeconds, Modifier.animateItem()) {
                    haptics.tick()
                    waiting = waiting.filterNot { it.key == p.key }
                    restore = p.text
                }
            }
            items(rows.asReversed(), key = { it.key }) { row ->
                when (row) {
                    is Row.Day -> Text(
                        row.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    // A message deleted, here or for everyone, fades away as the others close up.
                    is Row.Bubble -> Box(Modifier.fillMaxWidth().animateItem(fadeOutSpec = androidx.compose.animation.core.tween(450))) { Bubble(
                        row.message,
                        row.last,
                        fresh = row.message.box == MessageBox.RECEIVED && row.message.id in fresh,
                        rich = chat.refOf(row.message.mms, row.message.id).also { refs.size },
                        onReact = { emoji -> scope.launch { chat.refs.value.entries.firstOrNull { it.value.mms == row.message.mms && it.value.id == row.message.id }?.let { chat.react(it.key.toLong(), emoji) } } },
                        sender = if (group && row.message.box == MessageBox.RECEIVED) row.message.address.let { index.find(T9.clean(it))?.name ?: Numbers.format(context, it) } else null,
                        onRetry = {
                            if (!row.message.mms) {
                                scope.launch(Dispatchers.IO) { SmsSender.retry(context, row.message.id, row.message.address, row.message.body, row.message.subId) }
                            }
                        },
                        onDelete = {
                            val gone = messages.deleteMessage(row.message)
                            scope.launch {
                                gone.join()
                                chat.prune()
                            }
                        },
                        onQuote = { quote = row.message.body.ifBlank { com.sms.app.core.mms.mediaWord(row.message.parts.firstOrNull()?.contentType) } },
                        onEdit = { id -> editing = id to row.message.body },
                        onDeleteForAll = { id -> scope.launch { if (!chat.deleteForAll(id)) haptics.reject() } },
                        onPin = { id, on -> scope.launch { chat.pin(id, on) } },
                        chatId = chat.chatIdOf(row.message.mms, row.message.id).also { refs.size }
                    ) }
                }
            }
        }
    }
}

/** A line of the conversation: a day's heading or a message. */
private sealed class Row(val key: String) {
    class Day(val label: String, day: String) : Row("day/$day")
    class Bubble(val message: Message, val last: Boolean) : Row("m/${message.id}")
}

private fun rowsOf(list: List<Message>): List<Row> {
    val out = ArrayList<Row>()
    var day: java.time.LocalDate? = null
    val lastMine = list.lastOrNull { it.box != MessageBox.RECEIVED }?.id
    list.forEach { m ->
        val d = Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()).toLocalDate()
        if (d != day) {
            day = d
            out += Row.Day(dayLabel(d), d.toString())
        }
        out += Row.Bubble(m, m.id == lastMine)
    }
    return out
}

/** The name at the top: a tap offers the calls with them, their contact, blocking. */
@Composable
private fun PersonPill(title: String, address: String, contactId: Long?, group: Boolean, vanish: Int?, onVanish: (Int) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    var pillBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var blocked by remember(address) { mutableStateOf(NumberActions.isBlocked(context, address)) }
    var confirmBlock by remember { mutableStateOf(false) }
    var choosingSignature by remember { mutableStateOf(false) }
    if (choosingSignature) SignatureDialog(address) { choosingSignature = false }
    var choosingVanish by remember { mutableStateOf(false) }
    if (choosingVanish && vanish != null) VanishDialog(vanish, onPick = onVanish) { choosingVanish = false }
    Box(Modifier.onGloballyPositioned { pillBounds = it.boundsInWindow() }) {
        FloatingPane(shape = CircleShape, onClick = {
            haptics.firm()
            menuOpen = true
        }) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        }
        val digits = !group && address.count(Char::isDigit) >= 3
        if (menuOpen) PaletteMenu(
            pillBounds,
            listOfNotNull(
                if (digits && NumberActions.canShowCalls(context)) MessageAction(AppIcons.Recents, "Calls") { NumberActions.showCalls(context, address) } else null,
                if (contactId != null) MessageAction(AppIcons.Person, "Contact") { NumberActions.openContact(context, contactId) }
                else if (digits) MessageAction(AppIcons.PersonAdd, "Add to contacts") { NumberActions.addContact(context, address) } else null,
                if (!group) MessageAction(AppIcons.Copy, "Copy number") { NumberActions.copy(context, address) } else null,
                if (!group) MessageAction(AppIcons.Vibration, "Vibration") { choosingSignature = true } else null,
                if (vanish != null) MessageAction(AppIcons.Timer, "Vanishing") { choosingVanish = true } else null,
                if (!group && NumberActions.canBlock(context)) MessageAction(AppIcons.Block, if (blocked) "Unblock" else "Block", danger = !blocked) {
                    if (blocked) {
                        NumberActions.unblock(context, address)
                        blocked = NumberActions.isBlocked(context, address)
                    } else {
                        confirmBlock = true
                    }
                } else null
            ),
            onDismiss = { menuOpen = false }
        )
    }
    if (confirmBlock) {
        ZoneAlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Block $title?") },
            text = { Text("Calls and messages from this number will not reach you.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    NumberActions.block(context, address)
                    blocked = NumberActions.isBlocked(context, address)
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Cancel") } }
        )
    }
}

/**
 * A message as a pane of glass: received on the left, the user's own on
 * the right in the accent. A code carries a button to copy it; links are
 * opened cleaned of their trackers. Held: copy, delete, try again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Message,
    last: Boolean,
    fresh: Boolean,
    rich: RichRef?,
    onReact: (String?) -> Unit,
    sender: String?,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onQuote: () -> Unit,
    onEdit: (Int) -> Unit,
    onDeleteForAll: (Int) -> Unit,
    onPin: (Int, Boolean) -> Unit,
    chatId: Int?
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // Held: the message lifts, the reactions and the actions come around it.
    var menuOpen by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val mine = m.box != MessageBox.RECEIVED
    // A vanishing message's ring: what is left of its time, emptying as it goes.
    var left by remember { mutableStateOf(1f) }
    val vanishAt = rich?.vanishAt ?: 0L
    LaunchedEffect(vanishAt) {
        val total = (rich?.vanishFor ?: 0) * 1000L
        while (vanishAt > 0 && total > 0) {
            left = ((vanishAt - System.currentTimeMillis()).toFloat() / total).coerceIn(0f, 1f)
            if (left <= 0f) break
            kotlinx.coroutines.delay((total / 200).coerceIn(250L, 60_000L))
        }
    }
    val ringAccent = MaterialTheme.colorScheme.primary
    val ringGround = MaterialTheme.colorScheme.surface
    val ringTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    var confirmDeleteAll by remember { mutableStateOf(false) }
    if (confirmDeleteAll && chatId != null) {
        ZoneAlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete for everyone?") },
            text = { Text("The message goes from this conversation, here and on the other phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    onDeleteForAll(chatId)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }
    val code = remember(m.body) { if (mine) null else Codes.find(m.body) }
    val parcel = remember(m.body) { if (mine || code != null) null else com.sms.app.core.sms.Finds.parcel(m.body) }
    val appointment = remember(m.body) { if (code != null) null else com.sms.app.core.sms.Finds.appointment(m.body) }
    val accent = MaterialTheme.colorScheme.primary
    val text = remember(m.body, accent) { linked(m.body, accent) }
    // A message that just came in drops into place, a ring of glass spreading from it.
    val drop = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(fresh) { if (fresh) drop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 320f)) }
    val ringColor = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val d = drop.value
                translationY = (1f - d) * -60f
                val sc = 0.85f + 0.15f * d
                scaleX = sc
                scaleY = sc
                alpha = d.coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
            }
            .drawBehind {
                if (fresh && drop.value < 0.999f) {
                    val t = drop.value
                    drawCircle(ringColor.copy(alpha = 0.5f * (1f - t)), radius = 24.dp.toPx() + t * 90.dp.toPx(), center = androidx.compose.ui.geometry.Offset(24.dp.toPx(), size.height - 20.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
    ) {
        sender?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        m.parts.forEach { part ->
            MediaTile(part, mine)
            Spacer(Modifier.height(4.dp))
        }
        val scope = rememberCoroutineScope()
        val pull = remember { Animatable(0f) }
        var armed by remember { mutableStateOf(false) }
        val reach = with(LocalDensity.current) { 56.dp.toPx() }
        if (m.body.isNotEmpty() || m.parts.isEmpty()) Box(
            Modifier
                .fillMaxWidth(0.82f)
                // Swiped right, it follows the finger, ticks once past the
                // point where it will be answered, and springs back.
                .pointerInput(m.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed) onQuote()
                            armed = false
                            scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
                        },
                        onHorizontalDrag = { change, dx ->
                            val give = if (pull.value > reach) 0.35f else 1f
                            val next = (pull.value + dx * give).coerceIn(0f, reach * 1.7f)
                            scope.launch { pull.snapTo(next) }
                            val now = next >= reach
                            if (now != armed) {
                                armed = now
                                haptics.threshold(now)
                            }
                            change.consume()
                        }
                    )
                }
                .offset { IntOffset(pull.value.roundToInt(), 0) },
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Icon(
                AppIcons.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((-pull.value * 0.6f).roundToInt(), 0) }
                    .graphicsLayer {
                        alpha = (pull.value / reach).coerceIn(0f, 1f)
                        val s = 0.6f + 0.4f * (pull.value / reach).coerceIn(0f, 1f)
                        scaleX = s
                        scaleY = s
                    }
            )
            val shape = RoundedCornerShape(
                topStart = 22.dp, topEnd = 22.dp,
                bottomStart = if (mine) 22.dp else 6.dp, bottomEnd = if (mine) 6.dp else 22.dp
            )
            ZoneSurface(
                shape = shape,
                accent = mine,
                modifier = Modifier
                    .onGloballyPositioned { bounds = it.boundsInWindow() }
                    .graphicsLayer { alpha = if (menuOpen) 0f else 1f }
                    .drawWithContent {
                        drawContent()
                        if (vanishAt > 0) {
                            val r = 9.dp.toPx()
                            val c = androidx.compose.ui.geometry.Offset(size.width - r * 0.6f, r * 0.6f)
                            drawCircle(ringGround, radius = r, center = c)
                            drawCircle(ringTrack, radius = r * 0.7f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                            drawArc(
                                ringAccent, startAngle = -90f, sweepAngle = 360f * left, useCenter = false,
                                topLeft = c - androidx.compose.ui.geometry.Offset(r * 0.7f, r * 0.7f),
                                size = androidx.compose.ui.geometry.Size(r * 1.4f, r * 1.4f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            )
                        }
                    }
                    .clip(shape).combinedClickable(
                    onClick = { if (m.box == MessageBox.FAILED) onRetry() },
                    // A double tap gives a heart, over the rich chat.
                    onDoubleClick = if (rich != null) ({
                        haptics.done()
                        onReact(if ("❤️" in rich.reactions) null else "❤️")
                    }) else null,
                    onLongClick = {
                        haptics.firm()
                        menuOpen = true
                    },
                    onLongClickLabel = "More"
                )
            ) {
                // An edit changes the words in a soft blur, not at a stroke.
                androidx.compose.animation.AnimatedContent(
                    text,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(420, delayMillis = 120)) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(260))
                    },
                    label = "words"
                ) { words ->
                    val blur by transition.animateDp(label = "blur") { if (it == androidx.compose.animation.EnterExitState.Visible) 0.dp else 6.dp }
                    Text(words, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.blur(blur).padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
            if (menuOpen) MessageMenu(
                bounds = bounds,
                mine = mine,
                reactions = if (rich != null) Reactions else null,
                chosen = rich?.reactions.orEmpty(),
                actions = listOfNotNull(
                    MessageAction(AppIcons.Reply, "Reply") { onQuote() },
                    // Only the user's own messages of the encrypted chat change for both sides.
                    if (chatId != null && rich?.mine == true && m.body.isNotBlank()) MessageAction(AppIcons.Create, "Edit") { onEdit(chatId) } else null,
                    if (m.body.isNotBlank()) MessageAction(AppIcons.Copy, "Copy") { copy(context, "Message", m.body) } else null,
                    if (m.body.isNotBlank()) MessageAction(AppIcons.Forward, "Forward") { forward(context, m.body) } else null,
                    if (chatId != null) MessageAction(AppIcons.PushPin, if (rich?.pinned == true) "Unpin" else "Pin") { onPin(chatId, rich?.pinned != true) } else null,
                    if (m.box == MessageBox.FAILED && !m.mms) MessageAction(AppIcons.Send, "Try again") { onRetry() } else null,
                    if (chatId != null && rich?.mine == true) MessageAction(AppIcons.Delete, "Delete for everyone", danger = true) { confirmDeleteAll = true } else null,
                    MessageAction(AppIcons.Delete, if (chatId != null && rich?.mine == true) "Delete for me" else "Delete", danger = true) { onDelete() }
                ),
                onReact = { emoji -> onReact(if (emoji in rich?.reactions.orEmpty()) null else emoji) },
                onDismiss = { menuOpen = false }
            ) {
                ZoneSurface(shape = shape, accent = mine, shadowElevation = 8.dp) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
        if (!rich?.reactions.isNullOrEmpty()) {
            FloatingPane(shape = CircleShape, modifier = Modifier.padding(top = 2.dp)) {
                Text(rich!!.reactions.joinToString(" "), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        // Pinned for both sides, or changed after it was sent.
        if (rich?.pinned == true || rich?.edited == true) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, start = 8.dp, end = 8.dp)) {
                if (rich.pinned) {
                    Icon(AppIcons.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    listOfNotNull(if (rich.pinned) "Pinned" else null, if (rich.edited) "edited" else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (parcel != null || appointment != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                parcel?.let { number ->
                    FloatingPane(shape = CircleShape, accent = true, onClick = {
                        haptics.done()
                        copy(context, "Tracking number", number)
                    }) {
                        Text("Copy tracking number", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                appointment?.let { at ->
                    FloatingPane(shape = CircleShape, accent = true, onClick = {
                        haptics.tick()
                        val begin = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
                                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000)
                                    .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, m.body)
                            )
                        }
                    }) {
                        Text("Add to calendar", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }
        }
        if (code != null) {
            Spacer(Modifier.height(6.dp))
            FloatingPane(shape = CircleShape, accent = true, onClick = {
                haptics.done()
                copy(context, "Code", code, sensitive = true)
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Icon(AppIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy $code", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        // Under the user's last message, and under any that failed: where it stands.
        if (mine && (last || m.box == MessageBox.FAILED)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, end = 6.dp)) {
                val (icon, word, failed) = when {
                    rich?.seen == true -> Triple(AppIcons.DoneAll, "Read · " + timeLabel(context, m.date), false)
                    m.box == MessageBox.FAILED -> Triple(AppIcons.Error, if (m.mms) "Not sent" else "Not sent · tap to try again", true)
                    m.box == MessageBox.SENDING -> Triple(AppIcons.Schedule, "Sending", false)
                    m.delivered -> Triple(AppIcons.DoneAll, "Delivered · " + timeLabel(context, m.date), false)
                    else -> Triple(AppIcons.Done, "Sent · " + timeLabel(context, m.date), false)
                }
                val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                // Read draws itself, stroke by stroke, the moment it comes.
                val read = rich?.seen == true
                val draw = remember { Animatable(if (read) 1f else 0f) }
                LaunchedEffect(read) { if (read) draw.animateTo(1f, androidx.compose.animation.core.tween(520)) }
                Icon(
                    icon, contentDescription = null,
                    tint = if (read) MaterialTheme.colorScheme.primary else tint,
                    modifier = Modifier.size(14.dp).drawWithContent {
                        if (!read) drawContent() else clipRect(right = size.width * draw.value) { this@drawWithContent.drawContent() }
                    }
                )
                Spacer(Modifier.width(4.dp))
                Text(word, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
    }
}

/** The text handed to this app's own new message screen, to choose who gets it. */
private fun forward(context: android.content.Context, text: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_SEND)
                .setPackage(context.packageName)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, text)
        )
    }
}

/** 5 min, 1 hour, 1 day, 1 week. */
private fun vanishLabel(seconds: Int): String = when {
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86_400 -> if (seconds == 3600) "1 hour" else "${seconds / 3600} hours"
    seconds < 604_800 -> if (seconds == 86_400) "1 day" else "${seconds / 86_400} days"
    else -> if (seconds == 604_800) "1 week" else "${seconds / 604_800} weeks"
}

/** How long messages last in this chat, for both sides. */
@Composable
private fun VanishDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vanishing messages") },
        text = {
            Column {
                Text(
                    "New messages vanish on both phones this long after they are read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                listOf(0, 300, 3600, 86_400, 604_800).forEach { seconds ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            onPick(seconds)
                            onDismiss()
                        }.padding(vertical = 10.dp)
                    ) {
                        androidx.compose.material3.RadioButton(selected = current == seconds, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(if (seconds == 0) "Off" else vanishLabel(seconds), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun copy(context: android.content.Context, label: String, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, text)
    // A code stays out of the clipboard's preview and history where Android offers it.
    if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** The text with its web links tappable, each opened cleaned of trackers. */
private fun linked(body: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val matcher = android.util.Patterns.WEB_URL.matcher(body)
    var at = 0
    val style = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
    while (matcher.find()) {
        val found = matcher.group()
        // Plain words with a dot ("e.g") are not links: a scheme or www. is.
        if (!found.contains("://") && !found.startsWith("www.", ignoreCase = true)) continue
        append(body.substring(at, matcher.start()))
        val url = if (found.contains("://")) found else "https://$found"
        withLink(LinkAnnotation.Url(LinkCleaner.clean(url), style)) { append(found) }
        at = matcher.end()
    }
    append(body.substring(at))
}

/** The start of a quoted message, short enough for the first line. */
private fun excerpt(text: String): String = text.replace('\n', ' ').let { if (it.length > 60) it.take(58).trimEnd() + "…" else it }

/** A message waiting its few seconds before it goes. */
private data class Pending(val key: Long, val text: String, val sub: Int, val attachments: List<com.sms.app.core.mms.Attachment>, val quoted: String?)

/**
 * The message just written: it flies up from the field into its place as
 * a drop of glass, and a ring on it empties over the seconds it waits; a
 * tap takes it back into the field.
 */
@Composable
private fun PendingBubble(p: Pending, seconds: Int, modifier: Modifier, onCancel: () -> Unit) {
    val flight = remember { Animatable(0f) }
    LaunchedEffect(Unit) { flight.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 260f)) }
    val ring = remember { Animatable(1f) }
    LaunchedEffect(Unit) { ring.animateTo(0f, androidx.compose.animation.core.tween(seconds * 1000, easing = androidx.compose.animation.core.LinearEasing)) }
    val accent = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.End, modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .graphicsLayer {
                    val f = flight.value
                    translationY = (1f - f) * 160f
                    val sc = 0.6f + 0.4f * f
                    scaleX = sc
                    scaleY = sc
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
                }
                .clickable(onClick = onCancel)
        ) {
            ZoneSurface(shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), accent = true, modifier = Modifier.widthIn(max = 320.dp)) {
                Text(
                    p.text.ifBlank { p.attachments.firstOrNull()?.let { com.sms.app.core.mms.mediaWord(it.contentType) } ?: "" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 34.dp, top = 10.dp, bottom = 10.dp)
                )
            }
            androidx.compose.foundation.Canvas(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)) {
                drawArc(accent, -90f, 360f * ring.value, false, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
        Text("Tap to take it back", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, end = 6.dp))
    }
}

/** A vibration of their own for this person: felt while choosing. */
@Composable
private fun SignatureDialog(address: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val key = com.sms.app.core.sms.Signatures.key(address)
    val current = settings.signatures[key]
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vibration") },
        text = {
            Column {
                (listOf<String?>(null) + com.sms.app.core.sms.Signatures.patterns.keys).forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (name != null) com.sms.app.core.sms.Signatures.play(context, name)
                            store.update { s -> s.copy(signatures = if (name == null) s.signatures - key else s.signatures + (key to name)) }
                        }.padding(vertical = 10.dp)
                    ) {
                        androidx.compose.material3.RadioButton(selected = current == name, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(name ?: "As usual", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

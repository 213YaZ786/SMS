package com.yaz.sms.feature.thread

import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import android.content.ClipData
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import com.yaz.sms.ui.component.HeroGlow
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
import com.yaz.sms.core.dial.NumberActions
import com.yaz.sms.core.dial.Numbers
import com.yaz.sms.core.dial.T9
import com.yaz.sms.core.link.LinkCleaner
import com.yaz.sms.core.sms.Codes
import com.yaz.sms.core.sms.MessageNotifier
import com.yaz.sms.core.sms.SmsSender
import com.yaz.sms.core.mms.MmsTransport
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.data.settings.SettingsStore
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import com.yaz.sms.core.chat.RichRef
import com.yaz.sms.data.contacts.PhoneBook
import com.yaz.sms.data.contacts.PhoneIndex
import com.yaz.sms.data.sms.Box as MessageBox
import com.yaz.sms.data.sms.Message
import com.yaz.sms.data.sms.Messages
import com.yaz.sms.feature.conversations.dayLabel
import com.yaz.sms.feature.conversations.timeLabel
import com.yaz.sms.navigation.LocalReadableInset
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingFrame
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.FloatingTop
import com.yaz.sms.ui.component.PillItem
import com.yaz.sms.ui.component.PillMenu
import com.yaz.sms.ui.component.PillMotion
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.component.rememberPillMenu
import com.yaz.sms.ui.icon.AppIcons
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlin.math.PI
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
    // The conversation's own background: the light all its glass lies on.
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    var shownThread by remember { mutableStateOf(threadId) }
    val base = com.yaz.sms.ui.glass.LocalGlass.current
    // Unless chosen here, the person's own colour from the Contacts app.
    val context = LocalContext.current
    val personColor by androidx.compose.runtime.produceState<Int?>(null, address) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (address.contains(',')) null else com.yaz.sms.core.dial.ContactLook.ofNumber(context, address)?.color
        }
    }
    val chosen = shownThread?.let { settings.backgrounds[it] }
    val look = rememberSceneLook(chosen ?: personColor?.let { colorCode(it) }, base)
    androidx.compose.runtime.CompositionLocalProvider(com.yaz.sms.ui.glass.LocalGlass provides look) {
        Box(Modifier.fillMaxSize()) {
            // A photo chosen for this conversation, under everything.
            val picture by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, chosen, shownThread) {
                value = shownThread?.takeIf { chosen?.startsWith(PHOTO) == true }?.let { t ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { android.graphics.BitmapFactory.decodeFile(pictureFile(context, t).path)?.asImageBitmap() }.getOrNull()
                    }
                }
            }
            ThreadContent(threadId, address, draft, onBack, base, picture, onThread = { shownThread = it })
            SceneWash(look)
        }
    }
}

@Composable
private fun ThreadContent(
    threadId: Long?,
    address: String,
    draft: String,
    onBack: () -> Unit,
    appLook: com.yaz.sms.ui.glass.GlassLook?,
    picture: androidx.compose.ui.graphics.ImageBitmap?,
    onThread: (Long?) -> Unit
) {
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
    val index = remember(contacts, com.yaz.sms.core.dial.PrivateNames.version.intValue) { PhoneIndex(contacts) }
    val to = people.firstOrNull().orEmpty()
    val group = people.size > 1
    val entry = remember(index, to) { index.find(T9.clean(to)) }
    val prefs by koinInject<SettingsStore>().settings.collectAsState()
    val title = if (group) thread?.let { prefs.groupNames[it] } ?: people.joinToString(", ") { index.find(T9.clean(it))?.name?.substringBefore(' ') ?: Numbers.format(context, it) }
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
    var scheduled by remember { mutableStateOf(com.yaz.sms.core.sms.Timed.scheduled(context)) }
    // Received messages that arrive while the conversation is open drop in.
    var known by remember { mutableStateOf<Set<String>?>(null) }
    // Sent with an effect: it plays here at once, and the bubble remembers it a while.
    val sentEffects = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<com.yaz.sms.core.sms.Effects.Effect, Long>>() }
    var playing by remember { mutableStateOf<Triple<com.yaz.sms.core.sms.Effects.Effect, String, androidx.compose.ui.geometry.Offset?>?>(null) }
    var effecting by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var fresh by remember { mutableStateOf<Set<String>>(emptySet()) }
    val haptics = rememberHaptics()
    LaunchedEffect(list) {
        val received = list.filter { it.box == MessageBox.RECEIVED }.map { it.uid }.toSet()
        val before = known
        if (before != null) {
            val new = received - before
            if (new.isNotEmpty()) {
                fresh = fresh + new
                haptics.tick()
                // A message that came in with an effect, or whose words bring one, plays it.
                list.filter { it.uid in new }.maxByOrNull { it.date }?.let { m ->
                    val (words, carried) = com.yaz.sms.core.sms.Effects.read(m.body)
                    val screen = carried?.takeIf { it.screen } ?: com.yaz.sms.core.sms.Effects.fromWords(words)
                    screen?.let { playing = Triple(it, words, null) }
                }
            } else {
                // Opened on unread news: the newest unread one with an effect plays once.
                Unit
            }
        }
        known = received
    }
    fun sendNow(p: Pending) {
        scope.launch(Dispatchers.IO) {
            // Photos leave without their place, camera and dates.
            val attachments = p.attachments.map { com.yaz.sms.core.mms.MediaPrivacy.clean(context, it) }
            // Over the rich chat when the number has it, quote included;
            // else a group or a picture as a picture message, the rest as SMS.
            // The effect travels over the encrypted chat; an SMS stays plain (and cheap).
            if (encrypted && chat.send(people, p.effect?.let { com.yaz.sms.core.sms.Effects.mark(p.text, it) } ?: p.text, attachments, p.quoted)) return@launch
            val text = p.quoted?.let { "«${excerpt(it)}»\n${p.text}" } ?: p.text
            if (group || attachments.isNotEmpty()) MmsTransport.send(context, people, text, attachments, p.sub)
            else SmsSender.send(context, to, text, p.sub)
            // The first message to a number asks, unseen, whether it has SMS too.
            if (!group) chat.hello(to)
        }
    }

    fun queue(p: Pending) {
        quote = null
        p.effect?.let { e ->
            sentEffects[p.text] = e to System.currentTimeMillis()
            if (e.screen) playing = Triple(e, p.text, null)
        }
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
    }
    effecting?.let { (text, sub) ->
        EffectSheet(
            carried = encrypted,
            onLater = {
                effecting = null
                scheduling = text to sub
            },
            onPick = { effect ->
                effecting = null
                queue(Pending(System.nanoTime(), text, sub, emptyList(), quote, effect))
            },
            onDismiss = {
                effecting = null
                restore = text
            }
        )
    }
    scheduling?.let { (text, sub) ->
        com.yaz.sms.feature.conversations.TimeChoice("Send later", onPick = { at ->
            com.yaz.sms.core.sms.Timed.schedule(context, com.yaz.sms.core.sms.Scheduled(System.currentTimeMillis(), people, text, at, sub))
            scheduled = com.yaz.sms.core.sms.Timed.scheduled(context)
            scheduling = null
        }, onDismiss = {
            restore = text
            scheduling = null
        })
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val calls = rememberCallChoices(to, linked != null, canCall)
    // Off the main thread: folding reactions and days over a long thread
    // would hold the first frame; the rows on screen stay until the new ones are ready.
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    // The messages the rows on screen were made from.
    var rowsOfList by remember { mutableStateOf<List<Message>?>(null) }
    LaunchedEffect(list) {
        val from = list
        rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { rowsOf(from) }
        rowsOfList = from
    }
    // The person's page, opened from their face: the calls, the tools, what was shared.
    var personOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var at by remember { mutableStateOf(0) }
    var sharedOpen by remember { mutableStateOf(false) }
    var silenceOpen by remember { mutableStateOf(false) }
    var remindOpen by remember { mutableStateOf(false) }
    var encryptionOpen by remember { mutableStateOf(false) }
    // Asked from the Contacts app's "verified" mark: the keys at once.
    val requests: com.yaz.sms.core.sms.OpenRequests = koinInject()
    val keysFor by requests.keysFor.collectAsState()
    LaunchedEffect(keysFor) {
        val number = keysFor ?: return@LaunchedEffect
        if (com.yaz.sms.core.dial.T9.clean(number).takeLast(9) == com.yaz.sms.core.dial.T9.clean(to).takeLast(9)) {
            encryptionOpen = true
            requests.keysFor.value = null
        }
    }
    val settingsNow by store.settings.collectAsState()
    val silencedUntil = thread?.let { t -> settingsNow.silenced[t]?.takeIf { it > System.currentTimeMillis() } }
    LaunchedEffect(thread) { onThread(thread) }
    var pollOpen by remember { mutableStateOf(false) }
    if (pollOpen) PollDialog(onSend = { question, options ->
        pollOpen = false
        queue(Pending(System.nanoTime(), com.yaz.sms.core.sms.Polls.write(question, options), android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID, emptyList(), null))
    }) { pollOpen = false }
    var backgroundOpen by remember { mutableStateOf(false) }
    val theirColor by androidx.compose.runtime.produceState<Int?>(null, to) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.yaz.sms.core.dial.ContactLook.ofNumber(context, to)?.color }
    }
    if (backgroundOpen && thread != null && appLook != null) BackgroundSheet(
        current = settingsNow.backgrounds[thread],
        base = appLook,
        theirPhoto = if (group) null else entry?.photo,
        theirColor = if (group) null else theirColor,
        picture = pictureFile(context, thread!!),
        onPick = { code ->
            // A photo no longer used goes from the phone.
            if (code?.startsWith(PHOTO) != true) thread?.let { pictureFile(context, it).delete() }
            backgroundOpen = false
            val t = thread ?: return@BackgroundSheet
            store.update { s -> s.copy(backgrounds = if (code == null) s.backgrounds - t else s.backgrounds + (t to code)) }
        },
        onDismiss = { backgroundOpen = false }
    )
    // Searching reads further back than the screen does.
    LaunchedEffect(searching) { if (searching && window < 5000) window = 5000 }
    val matches = remember(rows, query) {
        val q = fold(query.trim())
        if (q.isEmpty()) emptyList() else rows.indices.filter { i -> (rows[i] as? Row.Bubble)?.message?.body?.let { fold(it).contains(q) } == true }.reversed()
    }
    LaunchedEffect(matches) { at = 0 }
    LaunchedEffect(at, matches) {
        val row = matches.getOrNull(at) ?: return@LaunchedEffect
        val before = scheduled.count { it.to.toSet() == people.toSet() } + waiting.size
        runCatching { listState.animateScrollToItem(before + rows.size - 1 - row) }
    }
    if (silenceOpen && thread != null) SilenceDialog(silencedUntil, onPick = { until ->
        val t = thread ?: return@SilenceDialog
        store.update { s -> s.copy(silenced = if (until == null) s.silenced - t else s.silenced.filterValues { it > System.currentTimeMillis() } + (t to until)) }
        if (until != null) com.yaz.sms.core.sms.MessageNotifier(context).cancel(t)
    }) { silenceOpen = false }
    if (remindOpen && thread != null) com.yaz.sms.feature.conversations.TimeChoice(
        "Remind me to reply",
        explain = "The conversation leaves the list and comes back on top, with a notification, at the time you choose.",
        onPick = { time ->
            thread?.let { t -> com.yaz.sms.core.sms.Timed.later(context, store, t, time) }
            remindOpen = false
        },
        onDismiss = { remindOpen = false }
    )
    if (encryptionOpen) EncryptionDialog(to) { encryptionOpen = false }
    if (personOpen) PersonPage(
        name = if (group) title else entry?.name ?: Numbers.format(context, to),
        number = if (group) null else Numbers.format(context, to),
        address = to,
        photo = entry?.photo,
        contactId = entry?.contactId,
        group = group,
        encrypted = encrypted,
        vanish = if (encrypted) vanish else null,
        messages = list,
        stranger = { m -> index.find(T9.clean(m.address)) == null },
        calls = calls,
        silencedUntil = silencedUntil,
        onSearch = { searching = true },
        onSilence = { silenceOpen = true },
        onBackground = if (thread != null && appLook != null) ({ backgroundOpen = true }) else null,
        onRemind = if (thread != null) ({ remindOpen = true }) else null,
        onEncryption = { encryptionOpen = true },
        onName = { name ->
            thread?.let { t ->
                store.update { s -> s.copy(groupNames = if (name.isBlank()) s.groupNames - t else s.groupNames + (t to name.trim())) }
                if (encrypted && name.isNotBlank()) scope.launch { chat.nameGroup(people, name.trim()) }
            }
        },
        onVanish = { seconds -> scope.launch { if (chat.setTimer(people, seconds)) vanish = seconds } },
        onClose = { personOpen = false }
    )
    LaunchedEffect(personOpen) { if (personOpen && window < 5000) window = 5000 }
    if (sharedOpen) {
        // Everything the conversation holds, not only what the screen shows.
        LaunchedEffect(Unit) { if (window < 5000) window = 5000 }
        SharedPage(if (group) title else entry?.name ?: Numbers.format(context, to), list, stranger = { m -> index.find(T9.clean(m.address)) == null }) { sharedOpen = false }
    }
    FloatingFrame(
        bottom = composerHeight + 8.dp,
        top = {
            // Back, the person's face and name, and the calls one tap away on the right.
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Back, the person's face and name at the centre (their page a tap away), the green call.
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)) {
                Box(Modifier.align(Alignment.TopStart)) { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }
                Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 60.dp)) {
                    NameBlock(
                        name = title,
                        photo = if (group) null else entry?.photo,
                        encrypted = encrypted,
                        onOpen = { personOpen = true },
                        look = if (group) null else entry?.look
                    )
                }
                Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp)) { CallButtons(calls) }
            }

            androidx.compose.animation.AnimatedVisibility(visible = searching) {
                SearchBar(query, { query = it }, matches.size, at, onOlder = { if (matches.isNotEmpty()) at = (at + 1) % matches.size }, onNewer = { if (matches.isNotEmpty()) at = (at - 1 + matches.size) % matches.size }, onClose = {
                    searching = false
                    query = ""
                })
            }
          }
        },
        overlay = {
          // Short answers to the last message, from the phone's own engine, over the field.
          val newest = list.maxByOrNull { it.date }
          var usedFor by remember { mutableStateOf<String?>(null) }
          val service = to.any(Char::isLetter) || to.count(Char::isDigit) in 1..6
          val replies by androidx.compose.runtime.produceState(emptyList<String>(), newest?.uid) {
              value = if (newest == null || newest.box != MessageBox.RECEIVED || service) emptyList()
              else withContext(Dispatchers.Default) { com.yaz.sms.core.sms.Replies.suggest(context, list.sortedBy { it.date }) }
          }
          Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier
                  .align(Alignment.BottomCenter)
                  .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                  .padding(horizontal = LocalReadableInset.current)
                  .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
          ) {
            // Reading further up: the way back to the newest, and how many came in meanwhile.
            val away by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }
            var seenAt by remember { mutableStateOf(0L) }
            LaunchedEffect(away) { if (away) seenAt = list.maxOfOrNull { it.date } ?: 0L }
            val newCount = if (away) list.count { it.box == MessageBox.RECEIVED && it.date > seenAt } else 0
            androidx.compose.animation.AnimatedVisibility(
                visible = away,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.6f),
                modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 8.dp)
            ) {
                LatestButton(newCount) { scope.launch { listState.animateScrollToItem(0) } }
            }
            ReplyChips(if (usedFor == newest?.uid) emptyList() else replies) { reply ->
                haptics.tick()
                usedFor = newest?.uid
                restore = reply
            }
            Composer(
                initial = draft,
                quote = quote,
                onClearQuote = { quote = null },
                modifier = Modifier,
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
                onEffects = { text, sub -> effecting = text to sub },
                onPoll = if (encrypted) ({ pollOpen = true }) else null,
                onSend = { typed, sub, attachments ->
                    if (people.isEmpty()) return@Composer false
                    queue(Pending(System.nanoTime(), typed, sub, attachments, quote))
                    true
                }
            )
          }
          // A screen effect over everything, letting touches through.
          playing?.let { (e, w, o) -> ScreenEffect(e, w, o) { playing = null } }
        }
    ) { padding ->
        val inset = LocalReadableInset.current
        // The conversation's own photo when one was chosen; else the person's light: their photo, blurred, or a glow.
        if (picture != null) PictureGround(picture, com.yaz.sms.ui.glass.LocalGlass.current?.ground ?: MaterialTheme.colorScheme.background)
        else HeroGlow(if (group) null else entry?.photo, height = padding.calculateTopPadding() + 320.dp)
        // Near the oldest message shown, with more behind it: read the next page.
        // Only once the rows of the messages read are on screen: before, the
        // empty list looks like its top, and pages were read one after the
        // other until the whole conversation was (seconds on a long one).
        val nearTop by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 15 } }
        LaunchedEffect(nearTop, rowsOfList, list.size) {
            if (nearTop && rowsOfList === list && rows.isNotEmpty() && list.size >= window && listState.layoutInfo.totalItemsCount > 15) window += Messages.PAGE
        }
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
                        com.yaz.sms.core.sms.Timed.cancel(context, sc.id)
                        scheduled = com.yaz.sms.core.sms.Timed.scheduled(context)
                        restore = sc.text
                    }, modifier = Modifier.widthIn(max = 320.dp)) {
                        Text(sc.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, end = 6.dp)) {
                        Icon(AppIcons.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scheduled · " + com.yaz.sms.feature.conversations.aheadLabel(context, sc.at) + " · tap to cancel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        fresh = row.message.box == MessageBox.RECEIVED && row.message.uid in fresh,
                        rich = chat.refOf(row.message.mms, row.message.id).also { refs.size },
                        smsReactions = row.smsReactions,
                        // A reaction by SMS to a message with words, one to one: Google Messages shows it as such, an iPhone as words.
                        onSmsReact = if (!group && !row.message.rich && row.message.body.isNotBlank()) ({ emoji ->
                            val removed = emoji in row.smsMine
                            val said = com.yaz.sms.core.sms.Effects.read(row.message.body).first
                            scope.launch(Dispatchers.IO) { SmsSender.send(context, to, com.yaz.sms.core.sms.SmsReactions.write(emoji, said, removed), row.message.subId) }
                        }) else null,
                        smsMine = row.smsMine,
                        onReact = { emoji -> scope.launch { chat.refs.value.entries.firstOrNull { it.value.mms == row.message.mms && it.value.id == row.message.id }?.let { chat.react(it.key.toLong(), emoji) } } },
                        onPollShown = { scope.launch { chat.recount(row.message.mms, row.message.id) } },
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
                        onQuote = { quote = row.message.body.ifBlank { com.yaz.sms.core.mms.mediaWord(row.message.parts.firstOrNull()?.contentType) } },
                        onEdit = { id -> editing = id to row.message.body },
                        onDeleteForAll = { id -> scope.launch { if (!chat.deleteForAll(id)) haptics.reject() } },
                        onPin = { id, on -> scope.launch { chat.pin(id, on) } },
                        chatId = chat.chatIdOf(row.message.mms, row.message.id).also { refs.size },
                        stranger = row.message.box == MessageBox.RECEIVED && index.find(T9.clean(row.message.address)) == null,
                        highlight = if (searching) query.trim().takeIf { it.isNotEmpty() } else null,
                        sentEffect = sentEffects[com.yaz.sms.core.sms.Effects.plain(row.message.body)]?.takeIf { (_, at) -> row.message.box != MessageBox.RECEIVED && row.message.date >= at - 10_000 }?.first,
                        onReplay = { e, words -> playing = Triple(e, words, null) }
                    ) }
                }
            }
        }
    }
}

/** A line of the conversation: a day's heading or a message. */
private sealed class Row(val key: String) {
    class Day(val label: String, day: String) : Row("day/$day")
    // An SMS and a picture message may hold the same number: the kind is part of the key.
    class Bubble(val message: Message, val last: Boolean, val smsReactions: List<String> = emptyList(), val smsMine: List<String> = emptyList()) : Row("m/${message.uid}")
}

private fun rowsOf(all: List<Message>): List<Row> {
    // Reactions that came as SMS sit under their message, not as messages of their own.
    val folded = com.yaz.sms.core.sms.SmsReactions.fold(all)
    val list = folded.messages
    val out = ArrayList<Row>()
    var day: java.time.LocalDate? = null
    val lastMine = list.lastOrNull { it.box != MessageBox.RECEIVED }?.id
    list.forEach { m ->
        val d = Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()).toLocalDate()
        if (d != day) {
            day = d
            out += Row.Day(dayLabel(d), d.toString())
        }
        out += Row.Bubble(m, m.id == lastMine, folded.reactions[m.uid].orEmpty(), folded.mine[m.uid].orEmpty())
    }
    return out
}

/**
 * The ways to call this person: the phone call (Dialer), and with the
 * encrypted chat an encrypted call, or held, an encrypted video call.
 */
@Composable
private fun rememberCallChoices(phone: String, encrypted: Boolean, canCall: Boolean): CallChoices {
    val context = LocalContext.current
    // Dialer shows the encrypted calls: only offered when it is the phone app.
    val line = encrypted && remember { com.yaz.sms.core.call.CallLine.dialerShowsCalls(context) }
    fun encryptedCall(video: Boolean) {
        if (!com.yaz.sms.core.call.CallBook.place(context, phone, video)) {
            android.widget.Toast.makeText(context, "The call cannot be made now.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    var wantVideo by remember { mutableStateOf(false) }
    val askMedia = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[android.Manifest.permission.RECORD_AUDIO] != false) encryptedCall(wantVideo)
    }
    fun call(video: Boolean) {
        wantVideo = video
        val needed = listOfNotNull(android.Manifest.permission.RECORD_AUDIO, if (video) android.Manifest.permission.CAMERA else null)
            .filter { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) encryptedCall(video) else askMedia.launch(needed.toTypedArray())
    }
    if (!canCall) return CallChoices(null, null, null)
    return CallChoices(
        phone = { NumberActions.dial(context, phone) },
        encrypted = if (line) ({ call(video = false) }) else null,
        video = if (line) ({ call(video = true) }) else null
    )
}

/** The name at the top: a tap offers the calls with them, their contact, blocking. */
@Composable
private fun PersonPill(title: String, address: String, contactId: Long?, group: Boolean, vanish: Int?, under: String? = null, face: Pair<String?, String?>? = null, onUnder: (() -> Unit)? = null, onName: (String) -> Unit = {}, onVanish: (Int) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    var pillBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var blocked by remember(address) { mutableStateOf(NumberActions.isBlocked(context, address)) }
    var confirmBlock by remember { mutableStateOf(false) }
    var choosingSignature by remember { mutableStateOf(false) }
    if (choosingSignature) SignatureDialog(address) { choosingSignature = false }
    var choosingVanish by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    if (naming) GroupNameDialog(if (group && title.contains(',')) "" else title, onDone = { name ->
        naming = false
        onName(name)
    }) { naming = false }
    if (choosingVanish && vanish != null) VanishDialog(vanish, onPick = onVanish) { choosingVanish = false }
    Box(Modifier.onGloballyPositioned { pillBounds = it.boundsInWindow() }) {
        FloatingPane(shape = CircleShape, onClick = {
            haptics.firm()
            menuOpen = true
        }) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = if (face != null) 5.dp else 0.dp)) {
            face?.let { (name, photo) -> com.yaz.sms.ui.component.ContactAvatar(name, photo, 34.dp, look = if (group) null else com.yaz.sms.ui.component.rememberLook(address).value) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = if (face != null) 10.dp else 18.dp, end = 18.dp, top = if (under != null) 6.dp else 10.dp, bottom = if (under != null) 6.dp else 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                under?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = if (onUnder != null) Modifier.clickable(onClickLabel = "Encryption", onClick = onUnder) else Modifier) {
                        Icon(AppIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
          }
        }
        val digits = !group && address.count(Char::isDigit) >= 3
        if (menuOpen) PaletteMenu(
            pillBounds,
            listOfNotNull(
                if (digits && NumberActions.canShowCalls(context)) MessageAction(AppIcons.Recents, "Calls") { NumberActions.showCalls(context, address) } else null,
                // A private person of the Contacts app (no id in Android's contacts): nothing to open or add.
                if (contactId != null && contactId > 0) MessageAction(AppIcons.Person, "Contact") { NumberActions.openContact(context, contactId) }
                else if (digits && contactId == null) MessageAction(AppIcons.PersonAdd, "Add to contacts") { NumberActions.addContact(context, address) } else null,
                if (!group) MessageAction(AppIcons.Copy, "Copy number") { NumberActions.copy(context, address) } else null,
                if (!group) MessageAction(AppIcons.Vibration, "Vibration") { choosingSignature = true } else null,
                if (group) MessageAction(AppIcons.Create, "Name the group") { naming = true } else null,
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
    smsReactions: List<String> = emptyList(),
    smsMine: List<String> = emptyList(),
    onSmsReact: ((String) -> Unit)? = null,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onQuote: () -> Unit,
    onEdit: (Int) -> Unit,
    onDeleteForAll: (Int) -> Unit,
    onPin: (Int, Boolean) -> Unit,
    chatId: Int?,
    stranger: Boolean = false,
    highlight: String? = null,
    sentEffect: com.yaz.sms.core.sms.Effects.Effect? = null,
    onReplay: (com.yaz.sms.core.sms.Effects.Effect, String) -> Unit = { _, _ -> },
    onPollShown: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // Held: the message lifts, the reactions and the actions come around it.
    var menuOpen by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val mine = m.box != MessageBox.RECEIVED
    // The words without an effect's mark, and the effect: carried, sent from here, or brought by the words.
    val (said, carried) = remember(m.body) { com.yaz.sms.core.sms.Effects.read(m.body) }
    val bubbleEffect = (carried ?: sentEffect)?.takeIf { !it.screen }
    var replays by remember { mutableStateOf(0) }
    // Only one to three emoji: big, without a bubble, moving once (then on a tap).
    val big = remember(said, m.parts.isEmpty()) { if (m.parts.isEmpty()) com.yaz.sms.core.sms.BigEmoji.read(said) else null }
    // A poll, over the encrypted chat where votes travel as reactions.
    val poll = remember(said, rich != null) { if (rich != null && m.parts.isEmpty()) com.yaz.sms.core.sms.Polls.read(said) else null }
    LaunchedEffect(poll != null) { if (poll != null) onPollShown() }
    var emojiPlay by remember(m.uid) { mutableStateOf(if (big != null && EmojiPlayed.first(m.uid)) 1 else 0) }
    // Invisible ink: blurred until touched.
    var inked by remember(m.uid) { mutableStateOf(bubbleEffect == com.yaz.sms.core.sms.Effects.Effect.INK) }
    val screenEffect = (carried ?: sentEffect)?.takeIf { it.screen } ?: remember(said) { com.yaz.sms.core.sms.Effects.fromWords(said) }
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
    var pickingEmoji by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    if (pickingEmoji) EmojiPickerDialog(onPick = { emoji ->
        pickingEmoji = false
        haptics.done()
        onReact(emoji)
    }) { pickingEmoji = false }
    if (selecting) SelectTextDialog(said) { selecting = false }
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
    val code = remember(said) { if (mine) null else Codes.find(said) }
    val parcel = remember(said) { if (mine || code != null) null else com.yaz.sms.core.sms.Finds.parcel(said) }
    val appointment = remember(said) { if (code != null) null else com.yaz.sms.core.sms.Finds.appointment(said) }
    val accent = MaterialTheme.colorScheme.primary
    // Links in what was received are looked at for what they may hide; one tapped asks first.
    val danger = cautionColor()
    val listVersion by com.yaz.sms.core.link.BadHosts.version.collectAsState()
    val checks = remember(said, stranger, listVersion, mine) {
        if (mine) emptyMap() else links(said).associateWith { url ->
            com.yaz.sms.core.link.LinkCheck.check(url, stranger) { host -> com.yaz.sms.core.link.BadHosts.listed(context, host) }
        }.filterValues { it != null }.mapValues { it.value!! }
    }
    var risky by remember { mutableStateOf<Pair<String, com.yaz.sms.core.link.LinkCheck.Verdict>?>(null) }
    risky?.let { (url, verdict) -> RiskyLinkDialog(url, verdict, onDismiss = { risky = null }) }
    val found = MaterialTheme.colorScheme.tertiary
    val text = remember(said, accent, checks, highlight) {
        marked(linked(said, accent, danger, checks) { url, verdict ->
            haptics.reject()
            risky = url to verdict
        }, highlight, found)
    }
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
        // The bubble's effect, played as it comes in or is sent, and again on Replay.
        val fx = remember(m.uid) { Animatable(if (bubbleEffect != null && (fresh || sentEffect != null)) 0f else 1f) }
        LaunchedEffect(replays, bubbleEffect) {
            val e = bubbleEffect ?: return@LaunchedEffect
            if (replays == 0 && fx.value >= 1f) return@LaunchedEffect
            fx.snapTo(0f)
            fx.animateTo(1f, androidx.compose.animation.core.tween(if (e == com.yaz.sms.core.sms.Effects.Effect.GENTLE) 1700 else 950, easing = androidx.compose.animation.core.LinearEasing))
        }
        LaunchedEffect(fx.value >= 0.35f) { if (bubbleEffect == com.yaz.sms.core.sms.Effects.Effect.SLAM && fx.value in 0.35f..0.5f) haptics.firm() }
        if (said.isNotEmpty() || m.parts.isEmpty()) Box(
            Modifier
                .fillMaxWidth(0.82f)
                .graphicsLayer {
                    val k = fx.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (mine) 1f else 0f, 1f)
                    when (bubbleEffect) {
                        // Slammed down from above, the last bit with a bounce.
                        com.yaz.sms.core.sms.Effects.Effect.SLAM -> {
                            val drop = if (k < 0.4f) 1f - (k / 0.4f) * (k / 0.4f) else 0f
                            val bounce = if (k >= 0.4f) kotlin.math.sin(((k - 0.4f) / 0.6f) * 3 * PI).toFloat() * (1f - (k - 0.4f) / 0.6f) * 0.08f else 0f
                            val sc = 1f + 1.3f * drop + bounce
                            scaleX = sc; scaleY = sc
                            translationY = -drop * 60.dp.toPx()
                        }
                        // Big and shaking, then settling.
                        com.yaz.sms.core.sms.Effects.Effect.LOUD -> {
                            val big = if (k < 0.65f) 1f else 1f - (k - 0.65f) / 0.35f
                            val sc = 1f + 0.55f * big
                            scaleX = sc; scaleY = sc
                            rotationZ = kotlin.math.sin(k * 70f) * 4f * big
                        }
                        // Small and quiet, growing slowly into place.
                        com.yaz.sms.core.sms.Effects.Effect.GENTLE -> {
                            val sc = 0.55f + 0.45f * (if (k < 0.5f) 0f else (k - 0.5f) / 0.5f)
                            scaleX = sc; scaleY = sc
                            alpha = 0.6f + 0.4f * k
                        }
                        else -> Unit
                    }
                }
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
            val held = Modifier
                    .then(if (poll != null) Modifier.fillMaxWidth() else Modifier)
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
                    // Big emoji are not clipped: their glow stays round, and a tap shows as their motion.
                    .then(if (big != null) Modifier else Modifier.clip(shape)).combinedClickable(
                    interactionSource = null,
                    indication = if (big != null) null else androidx.compose.foundation.LocalIndication.current,
                    onClick = {
                        if (inked) {
                            haptics.tick()
                            inked = false
                        } else if (m.box == MessageBox.FAILED) onRetry()
                        else if (big != null) {
                            haptics.tick()
                            emojiPlay++
                        }
                    },
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
            if (big != null) BigEmojiRow(big, emojiPlay, if (inked) held.blur(14.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else held)
            else ZoneSurface(shape = shape, accent = mine, modifier = held) {
              Column {
                if (poll != null && rich != null) PollCard(poll, rich.counts, rich.myReactions, onVote = onReact)
                // An edit changes the words in a soft blur, not at a stroke.
                else androidx.compose.animation.AnimatedContent(
                    text,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(420, delayMillis = 120)) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(260))
                    },
                    label = "words"
                ) { words ->
                    val blur by transition.animateDp(label = "blur") { if (it == androidx.compose.animation.EnterExitState.Visible) 0.dp else 6.dp }
                    Text(words, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.blur(if (inked) 14.dp else blur).padding(horizontal = 16.dp, vertical = 10.dp))
                }
              }
            }
            if (menuOpen) MessageMenu(
                bounds = bounds,
                mine = mine,
                reactions = if (rich != null || onSmsReact != null) Reactions else null,
                chosen = if (rich != null) rich.reactions else smsMine,
                actions = listOfNotNull(
                    MessageAction(AppIcons.Reply, "Reply") { onQuote() },
                    if (bubbleEffect != null || screenEffect != null) MessageAction(AppIcons.Play, "Replay") {
                        if (bubbleEffect != null) {
                            if (bubbleEffect == com.yaz.sms.core.sms.Effects.Effect.INK) inked = true
                            replays++
                        }
                        screenEffect?.let { onReplay(it, said) }
                    } else null,
                    // Only the user's own messages of the encrypted chat change for both sides.
                    if (chatId != null && rich?.mine == true && said.isNotBlank()) MessageAction(AppIcons.Create, "Edit") { onEdit(chatId) } else null,
                    if (said.isNotBlank()) MessageAction(AppIcons.Copy, "Copy") { copy(context, "Message", said) } else null,
                    if (said.length > 12) MessageAction(AppIcons.SelectAll, "Select") { selecting = true } else null,
                    if (said.isNotBlank()) MessageAction(AppIcons.Forward, "Forward") { forward(context, said) } else null,
                    if (chatId != null) MessageAction(AppIcons.PushPin, if (rich?.pinned == true) "Unpin" else "Pin") { onPin(chatId, rich?.pinned != true) } else null,
                    if (m.box == MessageBox.FAILED && !m.mms) MessageAction(AppIcons.Send, "Try again") { onRetry() } else null,
                    if (chatId != null && rich?.mine == true) MessageAction(AppIcons.Delete, "Delete for everyone", danger = true) { confirmDeleteAll = true } else null,
                    MessageAction(AppIcons.Delete, if (chatId != null && rich?.mine == true) "Delete for me" else "Delete", danger = true) { onDelete() }
                ),
                onReact = { emoji -> if (rich == null && onSmsReact != null) onSmsReact(emoji) else onReact(if (emoji in rich?.reactions.orEmpty()) null else emoji) },
                onDismiss = { menuOpen = false },
                onMoreReactions = if (rich != null) ({ pickingEmoji = true }) else null
            ) {
                if (big != null) BigEmojiRow(big, 0)
                else ZoneSurface(shape = shape, accent = mine, shadowElevation = 8.dp) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
        // A link not recognized: one capsule of glass just under the message, a tap tells where it goes.
        // Never an accusation (it may be a sound link), except a site on the public list.
        checks.entries.firstOrNull()?.let { (url, verdict) ->
            SuspiciousCapsule(several = checks.size > 1, dangerous = checks.values.any { it.risk == com.yaz.sms.core.link.LinkCheck.Risk.LISTED }) {
                haptics.reject()
                risky = url to verdict
            }
        }
        // A place: its card, opened in the user's map app; else a sound link: its preview on a tap, never fetched by itself.
        val place = remember(said) { com.yaz.sms.core.sms.Places.find(said) }
        val sound = remember(said, checks) { links(said).firstOrNull { it !in checks } }
        if (place != null && checks.isEmpty()) PlaceCard(place)
        else if (sound != null && checks.isEmpty() && m.parts.isEmpty()) LinkPreviewCapsule(sound)
        // A poll's votes are in its card, not here.
        val shownReactions = rich?.reactions.orEmpty().filter { poll == null || com.yaz.sms.core.sms.Polls.choiceOf(it) < 0 } + smsReactions
        if (shownReactions.isNotEmpty()) {
            FloatingPane(shape = CircleShape, modifier = Modifier.padding(top = 2.dp)) {
                Text(shownReactions.joinToString(" "), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
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
                                    .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, said)
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

/** A group's name, written by hand; empty gives back the list of its members. */
@Composable
internal fun GroupNameDialog(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name the group") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                singleLine = true,
                placeholder = { Text("Family, Friday team…") }
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** 5 min, 1 hour, 1 day, 1 week. */
internal fun vanishLabel(seconds: Int): String = when {
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86_400 -> if (seconds == 3600) "1 hour" else "${seconds / 3600} hours"
    seconds < 604_800 -> if (seconds == 86_400) "1 day" else "${seconds / 86_400} days"
    else -> if (seconds == 604_800) "1 week" else "${seconds / 604_800} weeks"
}

/** How long messages last in this chat, for both sides. */
@Composable
internal fun VanishDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
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

internal fun copy(context: android.content.Context, label: String, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, text)
    // A code stays out of the clipboard's preview and history where Android offers it.
    if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** Text folded for a search: lower case, without accents. */
private fun fold(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** [text] with every place [words] are found lit in [color], accents and case aside. */
private fun marked(text: AnnotatedString, words: String?, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (words.isNullOrBlank()) return text
    val folded = fold(text.text)
    val q = fold(words)
    // Folding keeps one character for each: positions in the folded text are those of the text.
    if (folded.length != text.text.length) return text
    return buildAnnotatedString {
        append(text)
        var from = folded.indexOf(q)
        while (from >= 0 && q.isNotEmpty()) {
            addStyle(SpanStyle(background = color.copy(alpha = 0.35f)), from, from + q.length)
            from = folded.indexOf(q, from + q.length)
        }
    }
}

/** The web links of [body], as they will open: with a scheme, http(s) only. */
internal fun links(body: String): List<String> {
    val matcher = android.util.Patterns.WEB_URL.matcher(body)
    val found = mutableListOf<String>()
    while (matcher.find()) linkOf(matcher.group())?.let { found += it }
    return found
}

/** The address a found piece of text opens, or null when it is no web link. */
private fun linkOf(found: String): String? {
    // Plain words with a dot ("e.g") are not links: a scheme or www. is.
    if (!found.contains("://") && !found.startsWith("www.", ignoreCase = true)) return null
    // Only web pages open from a message, never another kind of link.
    if (found.contains("://") && !found.startsWith("https://", ignoreCase = true) && !found.startsWith("http://", ignoreCase = true)) return null
    return if (found.contains("://")) found else "https://$found"
}

/**
 * The text with its styles (*bold*, _italic_, __underline__, ~struck~, the
 * marks taken away) and its web links tappable, each opened cleaned of
 * trackers; a doubtful one ([checks]) is in the caution tone and asks
 * first ([onRisky]).
 */
private fun linked(
    body: String,
    accent: androidx.compose.ui.graphics.Color,
    danger: androidx.compose.ui.graphics.Color = accent,
    checks: Map<String, com.yaz.sms.core.link.LinkCheck.Verdict> = emptyMap(),
    onRisky: (String, com.yaz.sms.core.link.LinkCheck.Verdict) -> Unit = { _, _ -> }
): AnnotatedString = buildAnnotatedString {
    val read = com.yaz.sms.core.sms.Markup.read(body)
    append(read.text)
    read.spans.forEach { span ->
        val style = when (span.style) {
            com.yaz.sms.core.sms.Markup.Style.BOLD -> SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            com.yaz.sms.core.sms.Markup.Style.ITALIC -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            com.yaz.sms.core.sms.Markup.Style.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
            com.yaz.sms.core.sms.Markup.Style.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        }
        addStyle(style, span.start, span.end)
    }
    val matcher = android.util.Patterns.WEB_URL.matcher(read.text)
    val sound = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
    val doubtful = TextLinkStyles(SpanStyle(color = danger, textDecoration = TextDecoration.Underline))
    while (matcher.find()) {
        val url = linkOf(matcher.group()) ?: continue
        val verdict = checks[url]
        if (verdict == null) addLink(LinkAnnotation.Url(LinkCleaner.clean(url), sound), matcher.start(), matcher.end())
        else addLink(LinkAnnotation.Clickable(url, doubtful) { onRisky(url, verdict) }, matcher.start(), matcher.end())
    }
}

/**
 * Under a message's words: a capsule of red liquid glass with the warning
 * sign, popping in as the message shows, the sign beating once; a tap
 * says why and where the link goes.
 */
@Composable
private fun SuspiciousCapsule(several: Boolean, dangerous: Boolean, onClick: () -> Unit) {
    val red = if (dangerous) MaterialTheme.colorScheme.error else cautionColor()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val pop = remember { Animatable(0.6f) }
    val beat = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }
        delay(160)
        beat.animateTo(1.3f, androidx.compose.animation.core.tween(110))
        beat.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f))
    }
    val shape = CircleShape
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 4.dp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                alpha = ((pop.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            }
            .clip(shape)
            // Red glass: the colour through it, light caught on top, a soft rim all around.
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(red.copy(alpha = if (dark) 0.30f else 0.20f), red.copy(alpha = if (dark) 0.18f else 0.10f))))
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.10f else 0.35f), 0.5f to Color.Transparent))
                val r = size.height / 2
                listOf(1f to 0.55f, 3f to 0.22f).forEach { (dp, k) ->
                    val w = dp.dp.toPx()
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.5f * k), red.copy(alpha = 0.6f * k))),
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(r - w / 2),
                        style = Stroke(w)
                    )
                }
            }
            .clickable(onClickLabel = "Why", onClick = onClick)
            .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Icon(AppIcons.Warning, contentDescription = null, tint = red, modifier = Modifier.size(16.dp).graphicsLayer { scaleX = beat.value; scaleY = beat.value })
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                dangerous -> if (several) "Dangerous links" else "Dangerous link"
                else -> if (several) "Unrecognized links" else "Unrecognized link"
            },
            style = MaterialTheme.typography.labelLarge,
            color = red
        )
    }
}

/** The tone of a link that was not recognized: a warm red, a caution rather than an alarm. */
@Composable
internal fun cautionColor(): Color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.error, Color(0xFFE8890C), 0.4f)

/**
 * A link not recognized, tapped: where it goes, and to open it only when
 * the sender is trusted; Cancel first, opening it is a choice made on purpose.
 */
@Composable
internal fun RiskyLinkDialog(url: String, verdict: com.yaz.sms.core.link.LinkCheck.Verdict, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val host = remember(url) { runCatching { java.net.URI(url).host }.getOrNull() ?: url }
    val listed = verdict.risk == com.yaz.sms.core.link.LinkCheck.Risk.LISTED
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Warning, contentDescription = null, tint = if (listed) MaterialTheme.colorScheme.error else cautionColor()) },
        title = { Text(if (listed) "Dangerous link" else "Unrecognized link") },
        text = {
            Column {
                Text(
                    if (listed) "This site is on a public list of dangerous sites." else "Open it only if you trust the sender.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text("Goes to $host", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(LinkCleaner.clean(url))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Open", color = if (listed) MaterialTheme.colorScheme.error else cautionColor()) }
        }
    )
}

/** The start of a quoted message, short enough for the first line. */
private fun excerpt(text: String): String = text.replace('\n', ' ').let { if (it.length > 60) it.take(58).trimEnd() + "…" else it }

/** A message waiting its few seconds before it goes. */
private data class Pending(val key: Long, val text: String, val sub: Int, val attachments: List<com.yaz.sms.core.mms.Attachment>, val quoted: String?, val effect: com.yaz.sms.core.sms.Effects.Effect? = null)

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
                    p.text.ifBlank { p.attachments.firstOrNull()?.let { com.yaz.sms.core.mms.mediaWord(it.contentType) } ?: "" },
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
internal fun SignatureDialog(address: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val key = com.yaz.sms.core.sms.Signatures.key(address)
    val current = settings.signatures[key]
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vibration") },
        text = {
            Column {
                (listOf<String?>(null) + com.yaz.sms.core.sms.Signatures.patterns.keys).forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (name != null) com.yaz.sms.core.sms.Signatures.play(context, name)
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

/**
 * Short answers offered over the field, side by side and onto a second
 * line when they need it, each popping in a moment after the one before;
 * a tap puts it in the field, sending stays the user's.
 */
@Composable
private fun ReplyChips(replies: List<String>, onPick: (String) -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = replies.isNotEmpty(),
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom)
    ) {
        var shown by remember { mutableStateOf(replies) }
        if (replies.isNotEmpty()) shown = replies
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
        ) {
            shown.forEachIndexed { i, reply ->
                val pop = remember(reply) { Animatable(0f) }
                LaunchedEffect(reply) {
                    kotlinx.coroutines.delay(70L * i)
                    pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
                }
                FloatingPane(
                    shape = CircleShape,
                    onClick = { onPick(reply) },
                    modifier = Modifier.graphicsLayer {
                        scaleX = 0.7f + 0.3f * pop.value
                        scaleY = 0.7f + 0.3f * pop.value
                        alpha = pop.value.coerceIn(0f, 1f)
                    }
                ) {
                    Text(reply, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        }
    }
}

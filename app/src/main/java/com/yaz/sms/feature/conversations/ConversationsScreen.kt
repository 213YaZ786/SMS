package com.yaz.sms.feature.conversations

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.dial.NumberActions
import com.yaz.sms.core.dial.Numbers
import com.yaz.sms.core.dial.T9
import com.yaz.sms.core.sms.Filter
import com.yaz.sms.core.sms.Lists
import com.yaz.sms.data.contacts.PhoneBook
import com.yaz.sms.data.contacts.PhoneIndex
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.data.sms.Messages
import com.yaz.sms.feature.main.TabFrame
import com.yaz.sms.ui.component.ContactAvatar
import com.yaz.sms.ui.component.EmptyZone
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.LoadingMark
import com.yaz.sms.ui.component.PillItem
import com.yaz.sms.ui.component.PillMenu
import com.yaz.sms.ui.component.PillMotion
import com.yaz.sms.ui.component.SearchPill
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.component.rememberPillMenu
import com.yaz.sms.ui.icon.AppIcons
import org.koin.compose.koinInject


/**
 * The conversations, on the whole screen under floating glass: who waits
 * for an answer on top, then pinned ones, then the newest. A tap opens
 * one, a long press offers the rest.
 */
@Composable
fun ConversationsScreen(onOpenSettings: () -> Unit, onOpenThread: (Long, String) -> Unit) {
    val messages: Messages = koinInject()
    val chat: com.yaz.sms.core.chat.RichChat = koinInject()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val book: PhoneBook = koinInject()
    val store: SettingsStore = koinInject()
    // Read again on each return: the role may have been given meanwhile.
    LifecycleResumeEffect(Unit) {
        messages.refresh()
        if (book.canRead()) book.refresh()
        onPauseOrDispose { }
    }
    val all by messages.conversations.collectAsState()
    val loaded by messages.loaded.collectAsState()
    val contacts by book.entries.collectAsState()
    val settings by store.settings.collectAsState()
    val index = remember(contacts, com.yaz.sms.core.dial.PrivateNames.version.intValue) { PhoneIndex(contacts) }
    val context = LocalContext.current
    // The list of dangerous sites is kept a day fresh while the app is in front (Android
    // gives an app started in the background for a message no network).
    androidx.compose.runtime.LaunchedEffect(settings.checkLinks) {
        if (settings.checkLinks) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.yaz.sms.core.link.BadHosts.refresh(context) }
    }
    // Everything together, SMS and chat; the archive behind its button.
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    androidx.activity.compose.BackHandler(enabled = filter != Filter.ALL) { filter = Filter.ALL }
    var query by rememberSaveable { mutableStateOf("") }

    fun nameOf(c: Conversation): String? =
        if (c.group) settings.groupNames[c.threadId] ?: c.addresses.joinToString(", ") { a -> index.find(T9.clean(a))?.name?.substringBefore(' ') ?: Numbers.format(context, a) }
        else index.find(T9.clean(c.address))?.name
    // Deleted by a swipe, waiting its few seconds to be taken back.
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    val rememberedHaptics = rememberHaptics()
    val shown = remember(all, filter, settings.pinned, settings.archived, query, index, deleting) {
        val q = query.trim().lowercase()
        Lists.shown(all, filter, settings.pinned, settings.archived, known = { c -> index.find(T9.clean(c.address)) != null }).filter { c ->
            c.threadId != deleting?.threadId &&
            (q.isEmpty() || nameOf(c)?.lowercase()?.contains(q) == true || c.addresses.any { it.contains(q) } || c.snippet.lowercase().contains(q))
        }
    }
    val code = remember(all) { Lists.latestCode(all, System.currentTimeMillis()) }
    var servicesOpen by rememberSaveable { mutableStateOf(false) }
    var peeking by remember { mutableStateOf<Conversation?>(null) }
    // To answer: each card once. The cards already shown are read when the
    // screen comes back, and the ones shown now are kept as seen when it goes.
    var seenAtOpen by remember { mutableStateOf(store.current.answerSeen) }
    val waiting = remember(all, settings.archived, filter, query, seenAtOpen) {
        if (filter != Filter.ALL || query.isNotBlank()) emptyList() else Lists.toAnswer(all, settings.archived, seenAtOpen)
    }
    val shownCards by androidx.compose.runtime.rememberUpdatedState(waiting)
    // A new card to answer comes in on top: in sight when the list was at its top.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(waiting.firstOrNull()?.let(Lists::key)) {
        if (waiting.isNotEmpty() && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    LifecycleResumeEffect(Unit) {
        seenAtOpen = store.current.answerSeen
        onPauseOrDispose {
            val keys = shownCards.map(Lists::key)
            if (keys.isNotEmpty()) store.update { s ->
                // Only cards of messages still unread are worth keeping.
                val alive = all.filter { it.unread > 0 }.map(Lists::key).toSet()
                s.copy(answerSeen = (s.answerSeen + keys).filter { it in alive || it in keys }.toSet())
            }
        }
    }

    val line: @Composable (Conversation) -> Unit = { c ->
        ConversationLine(
            c,
            name = nameOf(c),
            photo = if (c.group) null else index.find(T9.clean(c.address))?.photo,
            look = if (c.group) null else index.find(T9.clean(c.address))?.look,
            pinned = c.threadId in settings.pinned,
            onOpen = { onOpenThread(c.threadId, c.addresses.joinToString(",")) },
            onPeek = { peeking = c },
            left = com.yaz.sms.core.sms.SwipeAction.of(settings.swipeLeft, com.yaz.sms.core.sms.SwipeAction.ARCHIVE),
            right = com.yaz.sms.core.sms.SwipeAction.of(settings.swipeRight, com.yaz.sms.core.sms.SwipeAction.DELETE),
            archived = Lists.isArchived(c, settings.archived),
            onSwipe = { action ->
                when (action) {
                    com.yaz.sms.core.sms.SwipeAction.ARCHIVE -> {
                        val on = !Lists.isArchived(c, settings.archived)
                        store.update { s -> s.copy(archived = if (on) s.archived + (c.threadId to c.date) else s.archived - c.threadId) }
                    }
                    com.yaz.sms.core.sms.SwipeAction.DELETE -> deleting = c
                    com.yaz.sms.core.sms.SwipeAction.READ -> messages.markRead(c.threadId)
                    com.yaz.sms.core.sms.SwipeAction.PIN -> store.update { s -> s.copy(pinned = if (c.threadId in s.pinned) s.pinned - c.threadId else s.pinned + c.threadId) }
                    com.yaz.sms.core.sms.SwipeAction.REPLY -> onOpenThread(c.threadId, c.addresses.joinToString(","))
                    com.yaz.sms.core.sms.SwipeAction.NONE -> Unit
                }
            }
        )
    }

    val archive = filter == Filter.ARCHIVED
    TabFrame(
        title = if (archive) "Archive" else "Messages",
        onOpenSettings = onOpenSettings,
        // Top left, facing Settings: the archive, and back from it.
        leading = {
            com.yaz.sms.ui.component.FloatingAction(
                if (archive) AppIcons.ArrowBack else AppIcons.Archive,
                if (archive) "Back to messages" else "Archive",
                { filter = if (archive) Filter.ALL else Filter.ARCHIVED }
            )
        },
        controls = {
            if (messages.canRead() && all.isNotEmpty()) Filters(query, { query = it }, if (archive) null else filter, { filter = it })
        },
        overlay = {
            // A conversation deleted by a swipe can be taken back for a few seconds.
            androidx.compose.animation.AnimatedVisibility(
                visible = deleting != null,
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                    .padding(bottom = 16.dp)
            ) {
                FloatingPane(shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
                        Text("Conversation deleted", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.TextButton(onClick = {
                            rememberedHaptics.done()
                            deleting = null
                        }) { Text("Undo") }
                    }
                }
            }
        }
    ) { padding ->
        when {
            !messages.canRead() -> EmptyZone(
                title = "Your messages show here",
                message = "Once SMS is your messaging app.",
                icon = AppIcons.Message,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            !loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
            shown.isEmpty() && waiting.isEmpty() -> EmptyZone(
                title = when {
                    query.isNotBlank() -> "Nothing found"
                    filter == Filter.UNREAD -> "All read"
                    filter == Filter.ARCHIVED -> "Nothing in the archive"
                    filter == Filter.UNKNOWN -> "No one unknown"
                    else -> "No messages yet"
                },
                message = when {
                    query.isNotBlank() -> "No name, number or message matches \"$query\"."
                    filter == Filter.ALL -> "Write to someone with the button below."
                    else -> "Archive a conversation with a long press; it comes back with its next message."
                },
                icon = AppIcons.Message,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (code != null && filter == Filter.ALL && query.isBlank()) {
                    item(key = "code") { CodeChip(code.first, code.second, nameOf(code.first)) }
                }
                if (waiting.isNotEmpty()) {
                    item(key = "waiting") {
                        Waiting(waiting, index, ::nameOf) { c -> onOpenThread(c.threadId, c.addresses.joinToString(",")) }
                    }
                }
                // People first; every service (banks, deliveries, codes) in one stack.
                val (services, people) = shown.partition { Lists.isService(it) && filter == Filter.ALL && query.isBlank() }
                if (services.isNotEmpty() && people.isNotEmpty()) item(key = "people") { Heading("People") }
                items(people, key = { it.threadId }) { c -> line(c) }
                if (services.isNotEmpty()) {
                    item(key = "services") {
                        ServicesStack(services, open = servicesOpen) { servicesOpen = !servicesOpen }
                    }
                    if (servicesOpen) items(services, key = { "s/${it.threadId}" }) { c ->
                        Box(Modifier.animateItem()) { line(c) }
                    }
                }
            }
        }
    }

    // The conversation deleted by a swipe goes after a few seconds, unless taken back.
    deleting?.let { c ->
        androidx.compose.runtime.LaunchedEffect(c.threadId) {
            kotlinx.coroutines.delay(5_000)
            val gone = messages.delete(c.threadId)
            scope.launch {
                gone.join()
                chat.prune()
            }
            store.update { s -> s.copy(pinned = s.pinned - c.threadId, archived = s.archived - c.threadId) }
            deleting = null
        }
    }

    peeking?.let { c ->
        Peek(
            c,
            title = nameOf(c) ?: Numbers.format(context, c.address),
            photo = if (c.group) null else index.find(T9.clean(c.address))?.photo,
            look = if (c.group) null else index.find(T9.clean(c.address))?.look,
            pinned = c.threadId in settings.pinned,
            archived = Lists.isArchived(c, settings.archived),
            onDismiss = { peeking = null },
            onOpen = {
                peeking = null
                onOpenThread(c.threadId, c.addresses.joinToString(","))
            },
            onPin = { on -> store.update { s -> s.copy(pinned = if (on) s.pinned + c.threadId else s.pinned - c.threadId) } },
            onArchive = { on -> store.update { s -> s.copy(archived = if (on) s.archived + (c.threadId to c.date) else s.archived - c.threadId) } },
            onRead = { messages.markRead(c.threadId) },
            onDelete = {
                val gone = messages.delete(c.threadId)
                // The chat side of its messages goes too, before their numbers are reused.
                scope.launch {
                    gone.join()
                    chat.prune()
                }
                store.update { s -> s.copy(pinned = s.pinned - c.threadId, archived = s.archived - c.threadId) }
            }
        )
    }
}

/** The search and, as in Dialer's calls, All and Unread on the same line, in glass. */
@Composable
private fun Filters(query: String, onQuery: (String) -> Unit, filter: Filter?, onFilter: (Filter) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        SearchPill(query, onQuery, hint = "Search", modifier = Modifier.widthIn(max = 420.dp).weight(1f, fill = false).fillMaxWidth(), floating = true)
        if (filter != null) listOf(Filter.ALL to "All", Filter.UNREAD to "Unread").forEach { (value, label) ->
            FloatingPane(
                shape = CircleShape,
                accent = filter == value,
                onClick = {
                    haptics.tick()
                    onFilter(value)
                }
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
            }
        }
    }
}

/** The people waiting for an answer, side by side on one or two lines: a tap opens the conversation. */
@Composable
private fun Waiting(waiting: List<Conversation>, index: PhoneIndex, nameOf: (Conversation) -> String?, onOpen: (Conversation) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth()) {
        Text(
            "To answer",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp)
        )
        // Side by side, the next ones on a second line, never scrolled
        // sideways; past two lines they stay in the list below.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 10.dp
            val columns = ((maxWidth + gap) / (120.dp + gap)).toInt().coerceIn(2, 5)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                waiting.take(columns * 2).chunked(columns).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        line.forEach { c ->
                            // A group under its name or its members, never as its first member alone.
                            val entry = if (c.group) null else index.find(T9.clean(c.address))
                            val name = nameOf(c) ?: Numbers.format(context, c.address)
                            Face(name, entry?.photo, entry?.look, timeLabel(context, c.date), Modifier.weight(1f)) { onOpen(c) }
                        }
                        repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Face(name: String, photo: String?, look: com.yaz.sms.core.dial.ContactLook.Look?, under: String, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "sink")
    val shape = RoundedCornerShape(24.dp)
    ZoneSurface(
        shape = shape,
        modifier = modifier
            .graphicsLayer {
                scaleX = sink
                scaleY = sink
            }
            .clip(shape)
            .combinedClickable(interactionSource = press, indication = null, onClick = {
                haptics.tick()
                onClick()
            })
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            ContactAvatar(name, photo, 44.dp, look = look)
            Spacer(Modifier.height(6.dp))
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(under, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationLine(
    c: Conversation,
    name: String?,
    photo: String?,
    look: com.yaz.sms.core.dial.ContactLook.Look?,
    pinned: Boolean,
    onOpen: () -> Unit,
    onPeek: () -> Unit,
    left: com.yaz.sms.core.sms.SwipeAction,
    right: com.yaz.sms.core.sms.SwipeAction,
    archived: Boolean,
    onSwipe: (com.yaz.sms.core.sms.SwipeAction) -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val title = name ?: Numbers.format(context, c.address)
    val unread = c.unread > 0
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "sink")

    // Swiped either way, it follows the finger and, past a point, does
    // what Settings chose for that side (archive and delete at first).
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pull = remember { androidx.compose.animation.core.Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    val reach = with(androidx.compose.ui.platform.LocalDensity.current) { 88.dp.toPx() }
    val side = if (pull.value < 0f) left else right
    Box(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().pointerInput(c.threadId, left, right) {
        detectHorizontalDragGestures(
            onDragEnd = {
                val action = if (pull.value < 0f) left else right
                val fire = armed && action != com.yaz.sms.core.sms.SwipeAction.NONE
                armed = false
                scope.launch {
                    if (fire && (action == com.yaz.sms.core.sms.SwipeAction.DELETE || action == com.yaz.sms.core.sms.SwipeAction.ARCHIVE)) {
                        // It leaves the way it was pushed.
                        pull.animateTo(if (pull.value < 0f) -size.width.toFloat() else size.width.toFloat(), androidx.compose.animation.core.tween(180))
                        onSwipe(action)
                        pull.snapTo(0f)
                    } else {
                        if (fire) onSwipe(action)
                        pull.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 400f))
                    }
                }
            },
            onDragCancel = {
                armed = false
                scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 400f)) }
            },
            onHorizontalDrag = { change, dx ->
                val beyond = kotlin.math.abs(pull.value) > reach
                val next = (pull.value + dx * (if (beyond) 0.35f else 1f)).coerceIn(-reach * 1.6f, reach * 1.6f)
                scope.launch { pull.snapTo(next) }
                val now = kotlin.math.abs(next) >= reach && (if (next < 0f) left else right) != com.yaz.sms.core.sms.SwipeAction.NONE
                if (now != armed) {
                    armed = now
                    haptics.threshold(now)
                }
                change.consume()
            }
        )
    }) {
        // Under the line as it slides: what letting go will do, on the side it uncovers.
        if (pull.value != 0f && side != com.yaz.sms.core.sms.SwipeAction.NONE) {
            val danger = side == com.yaz.sms.core.sms.SwipeAction.DELETE
            val tint = when {
                !armed -> MaterialTheme.colorScheme.onSurfaceVariant
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            val icon = when (side) {
                com.yaz.sms.core.sms.SwipeAction.ARCHIVE -> if (archived) AppIcons.Unarchive else AppIcons.Archive
                com.yaz.sms.core.sms.SwipeAction.DELETE -> AppIcons.Delete
                com.yaz.sms.core.sms.SwipeAction.READ -> AppIcons.DoneAll
                com.yaz.sms.core.sms.SwipeAction.PIN -> AppIcons.PushPin
                else -> AppIcons.Reply
            }
            val label = when (side) {
                com.yaz.sms.core.sms.SwipeAction.ARCHIVE -> if (archived) "Unarchive" else "Archive"
                com.yaz.sms.core.sms.SwipeAction.PIN -> if (pinned) "Unpin" else "Pin"
                else -> side.label
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(if (pull.value < 0f) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 20.dp).graphicsLayer {
                        val shown = (kotlin.math.abs(pull.value) / reach).coerceIn(0f, 1f)
                        alpha = shown
                        scaleX = 0.7f + 0.3f * shown
                        scaleY = 0.7f + 0.3f * shown
                    }
            ) {
                Icon(icon, contentDescription = null, tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
            }
        }
        val shape = RoundedCornerShape(22.dp)
        ZoneSurface(
            shape = shape,
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                scaleX = sink
                scaleY = sink
                translationX = pull.value
            }.clip(shape).combinedClickable(
                interactionSource = press,
                indication = null,
                onClickLabel = "Open",
                onLongClickLabel = "Glance",
                onClick = {
                    haptics.tick()
                    onOpen()
                },
                onLongClick = {
                    haptics.firm()
                    onPeek()
                }
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Box {
                    ContactAvatar(name, photo, 48.dp, look = look)
                    if (unread) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(20.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Text(
                                if (c.unread > 9) "9+" else c.unread.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The name takes all the room the time leaves, the pin right after it.
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (pinned) {
                                Spacer(Modifier.width(6.dp))
                                Icon(AppIcons.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            timeLabel(context, c.date),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        when {
                            c.failed -> "Not sent: " + com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(c.snippet)))
                            c.fromMe -> "You: " + com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(c.snippet)))
                            else -> com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(c.snippet)))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            c.failed -> MaterialTheme.colorScheme.error
                            unread -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

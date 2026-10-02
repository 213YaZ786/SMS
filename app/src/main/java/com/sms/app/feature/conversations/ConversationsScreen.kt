package com.sms.app.feature.conversations

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sms.app.core.dial.NumberActions
import com.sms.app.core.dial.Numbers
import com.sms.app.core.dial.T9
import com.sms.app.core.sms.Filter
import com.sms.app.core.sms.Lists
import com.sms.app.data.contacts.PhoneBook
import com.sms.app.data.contacts.PhoneIndex
import com.sms.app.data.settings.SettingsStore
import com.sms.app.data.sms.Conversation
import com.sms.app.data.sms.Messages
import com.sms.app.feature.main.TabFrame
import com.sms.app.ui.component.ContactAvatar
import com.sms.app.ui.component.EmptyZone
import com.sms.app.ui.component.FloatingPane
import com.sms.app.ui.component.LoadingMark
import com.sms.app.ui.component.PillItem
import com.sms.app.ui.component.PillMenu
import com.sms.app.ui.component.PillMotion
import com.sms.app.ui.component.SearchPill
import com.sms.app.ui.component.ZoneAlertDialog
import com.sms.app.ui.component.ZoneSurface
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.component.rememberPillMenu
import com.sms.app.ui.icon.AppIcons
import org.koin.compose.koinInject


/**
 * The conversations, on the whole screen under floating glass: who waits
 * for an answer on top, then pinned ones, then the newest. A tap opens
 * one, a long press offers the rest.
 */
@Composable
fun ConversationsScreen(onOpenSettings: () -> Unit, onOpenThread: (Long, String) -> Unit) {
    val messages: Messages = koinInject()
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
    val index = remember(contacts) { PhoneIndex(contacts) }
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }

    fun nameOf(c: Conversation): String? =
        if (c.group) c.addresses.joinToString(", ") { a -> index.find(T9.clean(a))?.name?.substringBefore(' ') ?: Numbers.format(context, a) }
        else index.find(T9.clean(c.address))?.name
    val shown = remember(all, filter, settings.pinned, settings.archived, query, index) {
        val q = query.trim().lowercase()
        Lists.shown(all, filter, settings.pinned, settings.archived).filter { c ->
            q.isEmpty() || nameOf(c)?.lowercase()?.contains(q) == true || c.addresses.any { it.contains(q) } || c.snippet.lowercase().contains(q)
        }
    }
    val code = remember(all) { Lists.latestCode(all, System.currentTimeMillis()) }
    var servicesOpen by rememberSaveable { mutableStateOf(false) }
    var peeking by remember { mutableStateOf<Conversation?>(null) }
    val waiting = remember(all, settings.archived, filter, query) {
        if (filter != Filter.ALL || query.isNotBlank()) emptyList() else Lists.waiting(all, settings.archived, System.currentTimeMillis())
    }

    val line: @Composable (Conversation) -> Unit = { c ->
        ConversationLine(
            c,
            name = nameOf(c),
            photo = if (c.group) null else index.find(T9.clean(c.address))?.photo,
            pinned = c.threadId in settings.pinned,
            onOpen = { onOpenThread(c.threadId, c.addresses.joinToString(",")) },
            onPeek = { peeking = c }
        )
    }

    TabFrame(
        title = "Messages",
        onOpenSettings = onOpenSettings,
        controls = {
            if (messages.canRead() && all.isNotEmpty()) Filters(filter, { filter = it }, query, { query = it })
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
                    filter == Filter.ARCHIVED -> "Nothing archived"
                    else -> "No messages yet"
                },
                message = when {
                    query.isNotBlank() -> "No name, number or message matches \"$query\"."
                    filter == Filter.ALL -> "Write to someone with the button below."
                    else -> ""
                },
                icon = AppIcons.Message,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (code != null && filter == Filter.ALL && query.isBlank()) {
                    item(key = "code") { CodeChip(code.first, code.second, nameOf(code.first)) }
                }
                if (waiting.isNotEmpty()) {
                    item(key = "waiting") {
                        Waiting(waiting, index) { c -> onOpenThread(c.threadId, c.addresses.joinToString(",")) }
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
                    if (servicesOpen) items(services, key = { "s/${it.threadId}" }) { c -> line(c) }
                }
            }
        }
    }

    peeking?.let { c ->
        Peek(
            c,
            title = nameOf(c) ?: Numbers.format(context, c.address),
            photo = if (c.group) null else index.find(T9.clean(c.address))?.photo,
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
                messages.delete(c.threadId)
                store.update { s -> s.copy(pinned = s.pinned - c.threadId, archived = s.archived - c.threadId) }
            }
        )
    }
}

/** All, unread or archived conversations, and the search: under the name, in glass. */
@Composable
private fun Filters(filter: Filter, onFilter: (Filter) -> Unit, query: String, onQuery: (String) -> Unit) {
    val haptics = rememberHaptics()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        SearchPill(query, onQuery, hint = "Search messages", modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(), floating = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Filter.ALL to "All", Filter.UNREAD to "Unread", Filter.ARCHIVED to "Archived").forEach { (value, label) ->
                FloatingPane(
                    shape = CircleShape,
                    accent = filter == value,
                    onClick = {
                        haptics.tick()
                        onFilter(value)
                    }
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
        }
    }
}

/** The people waiting for an answer, side by side: a tap opens the conversation. */
@Composable
private fun Waiting(waiting: List<Conversation>, index: PhoneIndex, onOpen: (Conversation) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth()) {
        Text(
            "To answer",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
            items(waiting, key = { it.threadId }) { c ->
                val entry = index.find(T9.clean(c.address))
                val name = entry?.name ?: Numbers.format(context, c.address)
                Face(name, entry?.photo, timeLabel(context, c.date)) { onOpen(c) }
            }
        }
    }
}

@Composable
private fun Face(name: String, photo: String?, under: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "sink")
    val shape = RoundedCornerShape(24.dp)
    ZoneSurface(
        shape = shape,
        modifier = Modifier
            .width(116.dp)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp)) {
            ContactAvatar(name, photo, 56.dp)
            Spacer(Modifier.height(10.dp))
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
    pinned: Boolean,
    onOpen: () -> Unit,
    onPeek: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val title = name ?: Numbers.format(context, c.address)
    val unread = c.unread > 0
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "sink")

    Box(Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth().graphicsLayer {
        scaleX = sink
        scaleY = sink
    }) {
        val shape = RoundedCornerShape(22.dp)
        ZoneSurface(
            shape = shape,
            modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(
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
                    ContactAvatar(name, photo, 48.dp)
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
                        Spacer(Modifier.weight(1f))
                        Text(
                            timeLabel(context, c.date),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        when {
                            c.failed -> "Not sent: " + c.snippet
                            c.fromMe -> "You: " + c.snippet
                            else -> c.snippet
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

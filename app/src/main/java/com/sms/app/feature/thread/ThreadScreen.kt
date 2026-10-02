package com.sms.app.feature.thread

import android.content.ClipData
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    // The thread is found from the number when only the number is known.
    var thread by remember { mutableStateOf(threadId) }
    LaunchedEffect(address) {
        if (thread == null && address.isNotBlank()) {
            thread = withContext(Dispatchers.IO) { runCatching { Telephony.Threads.getOrCreateThreadId(context, address) }.getOrNull() }
        }
    }
    var list by remember { mutableStateOf<List<Message>>(emptyList()) }
    LaunchedEffect(thread, changes) {
        val t = thread ?: return@LaunchedEffect
        list = messages.thread(t)
        if (list.any { it.box == MessageBox.RECEIVED && !it.read }) messages.markRead(t)
        MessageNotifier(context).cancel(t)
    }
    val to = address.ifBlank { list.firstOrNull()?.address.orEmpty() }
    val entry = remember(contacts, to) { PhoneIndex(contacts).find(T9.clean(to)) }
    val title = entry?.name ?: Numbers.format(context, to)
    val canCall = to.count(Char::isDigit) >= 3

    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(0.dp) }

    FloatingFrame(
        bottom = composerHeight + 8.dp,
        top = {
            FloatingTop(
                title = null,
                leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) },
                trailing = { if (canCall) FloatingAction(AppIcons.Call, "Call", { NumberActions.dial(context, to) }) },
                center = { PersonPill(title, to, entry?.contactId) }
            )
        },
        overlay = {
            Composer(
                initial = draft,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = LocalReadableInset.current)
                    .onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
                onSend = { text, sub ->
                    if (to.isBlank()) return@Composer false
                    scope.launch(Dispatchers.IO) { SmsSender.send(context, to, text, sub) }
                    true
                }
            )
        }
    ) { padding ->
        val inset = LocalReadableInset.current
        val rows = remember(list) { rowsOf(list) }
        LazyColumn(
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().padding(horizontal = inset),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(rows.asReversed(), key = { it.key }) { row ->
                when (row) {
                    is Row.Day -> Text(
                        row.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    is Row.Bubble -> Bubble(row.message, row.last, onRetry = {
                        scope.launch(Dispatchers.IO) { SmsSender.retry(context, row.message.id, row.message.address, row.message.body, row.message.subId) }
                    }, onDelete = { messages.deleteMessage(row.message.id) })
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
private fun PersonPill(title: String, address: String, contactId: Long?) {
    val context = LocalContext.current
    val menu = rememberPillMenu()
    var blocked by remember(address) { mutableStateOf(NumberActions.isBlocked(context, address)) }
    var confirmBlock by remember { mutableStateOf(false) }
    Box(Modifier.then(menu.tracker)) {
        FloatingPane(shape = CircleShape, onClick = menu::open) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        }
        val digits = address.count(Char::isDigit) >= 3
        PillMenu(
            menu,
            listOfNotNull(
                if (digits && NumberActions.canShowCalls(context)) PillItem(AppIcons.Recents, "Calls", PillMotion.BOUNCE) { NumberActions.showCalls(context, address) } else null,
                if (contactId != null) PillItem(AppIcons.Person, "Contact", PillMotion.BOUNCE) { NumberActions.openContact(context, contactId) }
                else if (digits) PillItem(AppIcons.PersonAdd, "Add to contacts", PillMotion.BOUNCE) { NumberActions.addContact(context, address) } else null,
                PillItem(AppIcons.Copy, "Copy number", PillMotion.BOUNCE) { NumberActions.copy(context, address) },
                if (NumberActions.canBlock(context)) PillItem(AppIcons.Block, if (blocked) "Unblock" else "Block", PillMotion.DROP) {
                    if (blocked) {
                        NumberActions.unblock(context, address)
                        blocked = NumberActions.isBlocked(context, address)
                    } else {
                        confirmBlock = true
                    }
                } else null
            )
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
private fun Bubble(m: Message, last: Boolean, onRetry: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val menu = rememberPillMenu()
    val mine = m.box != MessageBox.RECEIVED
    val code = remember(m.body) { if (mine) null else Codes.find(m.body) }
    val accent = MaterialTheme.colorScheme.primary
    val text = remember(m.body, accent) { linked(m.body, accent) }
    Column(
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxWidth(0.82f).then(menu.tracker), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            val shape = RoundedCornerShape(
                topStart = 22.dp, topEnd = 22.dp,
                bottomStart = if (mine) 22.dp else 6.dp, bottomEnd = if (mine) 6.dp else 22.dp
            )
            ZoneSurface(
                shape = shape,
                accent = mine,
                modifier = Modifier.clip(shape).combinedClickable(
                    onClick = { if (m.box == MessageBox.FAILED) onRetry() },
                    onLongClick = menu::open,
                    onLongClickLabel = "More"
                )
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            PillMenu(
                menu,
                listOfNotNull(
                    PillItem(AppIcons.Copy, "Copy", PillMotion.BOUNCE) { copy(context, "Message", m.body) },
                    if (m.box == MessageBox.FAILED) PillItem(AppIcons.Send, "Try again", PillMotion.BOUNCE) { onRetry() } else null,
                    PillItem(AppIcons.Delete, "Delete", PillMotion.DROP) { onDelete() }
                )
            )
        }
        if (code != null) {
            Spacer(Modifier.height(6.dp))
            FloatingPane(shape = CircleShape, accent = true, onClick = {
                haptics.done()
                copy(context, "Code", code)
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
                    m.box == MessageBox.FAILED -> Triple(AppIcons.Error, "Not sent · tap to try again", true)
                    m.box == MessageBox.SENDING -> Triple(AppIcons.Schedule, "Sending", false)
                    m.delivered -> Triple(AppIcons.DoneAll, "Delivered · " + timeLabel(context, m.date), false)
                    else -> Triple(AppIcons.Done, "Sent · " + timeLabel(context, m.date), false)
                }
                val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(word, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
    }
}

private fun copy(context: android.content.Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
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

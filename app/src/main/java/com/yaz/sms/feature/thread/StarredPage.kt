package com.yaz.sms.feature.thread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaz.sms.core.mms.mediaWord
import com.yaz.sms.data.settings.StarStore
import com.yaz.sms.data.settings.Stars
import com.yaz.sms.data.sms.Message
import com.yaz.sms.data.sms.Messages
import com.yaz.sms.ui.component.EmptyZone
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingTop
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.koin.compose.koinInject

/** What a starred message says in the list: its words, else a word for its media. */
internal fun starredText(m: Message): String =
    com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(m.body)))
        .ifBlank { mediaWord(m.parts.firstOrNull()?.contentType) }

/**
 * The starred messages, newest first, each under the name of its conversation:
 * the page Zulip calls its starred messages narrow, a list of the messages that
 * carry the flag, each with the header of the conversation it belongs to, a tap
 * opening that conversation anchored at the message
 * (https://github.com/zulip/zulip-flutter/blob/main/lib/widgets/message_list.dart,
 * StarredMessagesNarrow: RecipientHeader on every message, tapOpensConversation,
 * the empty page saying how to star a message). On a pane of glass over a full
 * screen dialog like the Shared page. [thread] narrows it to one conversation
 * (the conversation's own search); null lists every conversation.
 * Messages deleted since are dropped from the stars as the list is read.
 */
@Composable
internal fun StarredPage(thread: Long?, titleOf: (Message) -> String, onOpen: (Message) -> Unit, onClose: () -> Unit) {
    val messages: Messages = koinInject()
    val store: StarStore = koinInject()
    val stars by store.stars.collectAsState()
    val changes by messages.changes.collectAsState()
    val asked = remember(stars, thread) { if (thread == null) stars.keys else Stars.inThread(stars, thread) }
    // Null until read, and null again when the store cannot be read: nothing is then taken for deleted.
    val found by produceState<List<Message>?>(null, asked, changes) { value = messages.byUid(asked) }
    LaunchedEffect(found) {
        val read = found ?: return@LaunchedEffect
        // What the store answered, plus the stars of other conversations that this page did not ask for.
        store.keepAlive(read.associate { it.uid to it.threadId } + stars.filter { thread != null && it.value != thread })
    }
    val shown = remember(found, stars) { found.orEmpty().filter { stars[it.uid] == it.threadId }.sortedByDescending { it.date } }
    val haptics = rememberHaptics()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PageBars()
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                if (found != null && shown.isEmpty()) EmptyZone(
                    title = "No starred messages",
                    message = "Touch and hold a message, then choose Star.",
                    icon = AppIcons.Star,
                    modifier = Modifier.fillMaxSize()
                ) else LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 100.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(shown, key = { it.uid }) { m ->
                        StarredLine(m, titleOf(m), onOpen = {
                            haptics.tick()
                            onOpen(m)
                        }, onUnstar = {
                            haptics.done()
                            store.toggle(m.uid, m.threadId)
                        })
                    }
                }
                // The top floats over the list, in glass.
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingTop(title = "Starred messages", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onClose) })
                }
            }
        }
    }
}

/**
 * One starred message: its conversation's name, its words (or a word for its
 * media) and its date. Swiped or held, the star comes off, the star button
 * does it too (Jetpack Compose's SwipeToDismissBox,
 * https://developer.android.com/develop/ui/compose/touch-input/user-interactions/swipe-to-dismiss).
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StarredLine(m: Message, name: String, onOpen: () -> Unit, onUnstar: () -> Unit) {
    val haptics = rememberHaptics()
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()))
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled) onUnstar()
        value != SwipeToDismissBoxValue.Settled
    })
    // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
    val shape = ShapeDefaults.ExtraLarge
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        backgroundContent = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Icon(AppIcons.StarOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Unstar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    ) {
        ZoneSurface(
            shape = shape,
            modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(
                onClickLabel = "Open the conversation",
                onLongClickLabel = "Unstar",
                onClick = onOpen,
                onLongClick = {
                    haptics.firm()
                    onUnstar()
                }
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(starredText(m), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FloatingAction(AppIcons.Star, "Unstar", onUnstar, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

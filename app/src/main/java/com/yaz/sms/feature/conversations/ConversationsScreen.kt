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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.yaz.sms.ui.component.BoldButton
import com.yaz.sms.ui.component.QuietButton
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
import com.yaz.sms.core.sms.SpamJudge
import com.yaz.sms.data.contacts.PhoneBook
import com.yaz.sms.data.contacts.PhoneIndex
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.data.settings.SpamStore
import com.yaz.sms.data.settings.Spams
import com.yaz.sms.data.settings.Trash
import com.yaz.sms.data.settings.TrashStore
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
    // The Trash keeps conversations apart, 30 days (data/settings/TrashStore.kt); a trashed
    // conversation is not read, warmed, answered or announced while it is there.
    val trash: TrashStore = koinInject()
    val trashed by trash.items.collectAsState()
    val live = remember(all, trashed) { all.filterNot { Lists.isTrashed(it, trashed) } }
    // The newest conversations ready before a tap (their latest messages laid out), while this list shows.
    val ahead = live.take(6).map { it.threadId to it.date }
    androidx.compose.runtime.LaunchedEffect(ahead) {
        ahead.forEach { (t, date) -> if (warmed[t] != date) { com.yaz.sms.feature.thread.ThreadsAhead.forget(t); warmed[t] = date } }
        com.yaz.sms.feature.thread.ThreadsAhead.warm(messages, ahead.map { it.first })
    }
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
    // Spam (SpamJudge): the senders reported or cleared answer at once, the classifier trained on the
    // labelled messages follows off the main thread (SpamStore.filter). The classifier judges only a
    // sender not in the contacts, with fewer than three replies and no encrypted chat. Each
    // conversation's verdict is kept while its newest message and its known state stay the same,
    // so a change of the store reads only what moved.
    val spamStore: SpamStore = koinInject()
    val spamData by spamStore.data.collectAsState()
    val spamFilter by androidx.compose.runtime.produceState(spamStore.filter(spamData, withModel = false), spamData) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { spamStore.filter(spamData) }
    }
    val chatLinks by chat.links.collectAsState()
    val kept = remember { HashMap<Long, Pair<List<Any>, Boolean>>() }
    val spamThreads: Set<Long> = remember(all, index, chatLinks, settings.spamProtection, spamFilter) {
        if (!settings.spamProtection) emptySet() else buildSet {
            for (c in all) {
                val isKnown = SpamJudge.isKnown(index.find(T9.clean(c.address)) != null, c.sent) ||
                    (!c.group && chat.linkFor(c.address) != null)
                val key = listOf<Any>(c.date, c.snippet, c.fromMe, isKnown, spamFilter)
                val spam = kept[c.threadId]?.takeIf { it.first == key }?.second
                    ?: SpamJudge.isSpam(c, isKnown, spamFilter).also { kept[c.threadId] = key to it }
                if (spam) add(c.threadId)
            }
        }
    }
    // Everything together, SMS and chat; the archive behind its button.
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    androidx.activity.compose.BackHandler(enabled = filter != Filter.ALL) { filter = Filter.ALL }
    var query by rememberSaveable { mutableStateOf("") }

    fun nameOf(c: Conversation): String? =
        if (c.group) settings.groupNames[c.threadId] ?: c.addresses.joinToString(", ") { a -> index.find(T9.clean(a))?.name?.substringBefore(' ') ?: Numbers.format(context, a) }
        // Someone with no number goes by the name they chose in the chat.
        else if ('@' in c.address) chat.nameFor(c.address) ?: c.address.substringBefore('@')
        else index.find(T9.clean(c.address))?.name
    // Put in the Trash by a swipe, waiting its few seconds to be taken back.
    var swiped by remember { mutableStateOf<Conversation?>(null) }
    // A trashed conversation tapped: it is restored to be read.
    var restoring by remember { mutableStateOf<Conversation?>(null) }
    val rememberedHaptics = rememberHaptics()
    val shown = remember(all, filter, settings.pinned, settings.archived, trashed, query, index, spamThreads) {
        val q = query.trim().lowercase()
        Lists.shown(all, filter, settings.pinned, settings.archived, trashed = trashed, spam = spamThreads).filter { c ->
            q.isEmpty() || nameOf(c)?.lowercase()?.contains(q) == true || c.addresses.any { it.contains(q) } || c.snippet.lowercase().contains(q)
        }
    }
    val code = remember(live) { Lists.latestCode(live, System.currentTimeMillis()) }
    // Conversations the store no longer holds leave the Trash's list.
    androidx.compose.runtime.LaunchedEffect(all) {
        if (all.isNotEmpty()) trash.keepThreads(all.mapTo(HashSet()) { it.threadId })
    }
    var servicesOpen by rememberSaveable { mutableStateOf(false) }
    var peeking by remember { mutableStateOf<Conversation?>(null) }
    // Several conversations taken at once (AOSP Messaging's multi select,
    // MultiSelectActionModeCallback): archive, read, block or delete in one go.
    var picked by remember { mutableStateOf<Set<Long>?>(null) }
    var confirmTrashMany by remember { mutableStateOf(false) }
    var confirmBlockMany by remember { mutableStateOf(false) }
    // Conversations to delete for good, taken from the Trash; emptying says it was all of it.
    var forever by remember { mutableStateOf<Set<Long>?>(null) }
    var emptying by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(picked != null) { picked = null }
    // To answer: each card once. The cards already shown are read when the
    // screen comes back, and the ones shown now are kept as seen when it goes.
    var seenAtOpen by remember { mutableStateOf(store.current.answerSeen) }
    val waiting = remember(live, settings.archived, filter, query, seenAtOpen, spamThreads) {
        // Spam waits in Spam, not among the cards.
        if (filter != Filter.ALL || query.isNotBlank()) emptyList()
        else Lists.toAnswer(live.filterNot { it.threadId in spamThreads }, settings.archived, seenAtOpen)
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

    // Into the Trash: nothing is deleted, the date of each conversation's newest message is kept
    // as its cutoff. Same cutoff rule as AOSP Messaging's DeleteConversationAction
    // (https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/datamodel/action/DeleteConversationAction.java);
    // the time to keep and the list of what waits are Aves' bin (https://github.com/deckerst/aves/blob/develop/lib/model/source/trash.dart),
    // and the move is Fossify Messages' moveMessageToRecycleBin (https://github.com/FossifyOrg/Messages, extensions/Context.kt).
    fun moveToTrash(those: Collection<Conversation>) = trash.trash(those.associate { it.threadId to it.date })

    // Report spam and Not spam on a conversation, as Google Messages offers them
    // (https://support.google.com/messages/answer/9061432): the sender is reported or cleared and the
    // messages received from it train the classifier (SpamStore, SpamBlocker's LocalAI.kt updateCategory:
    // spam or ham). Report spam may block the number too, a box of its confirmation.
    var reporting by remember { mutableStateOf<Conversation?>(null) }
    var blocking by remember { mutableStateOf<Conversation?>(null) }
    fun reportSpam(c: Conversation, block: Boolean) {
        scope.launch {
            spamStore.report(SpamJudge.senderKey(c.address), Spams.received(messages.thread(c.threadId, Spams.MAX_PER_REPORT * 2)))
            if (block) NumberActions.block(context, c.address)
        }
    }
    fun notSpam(c: Conversation) {
        scope.launch { spamStore.notSpam(SpamJudge.senderKey(c.address),Spams.received(messages.thread(c.threadId, Spams.MAX_PER_REPORT * 2))) }
    }
    // A person with a number or a name, not a group nor someone met over the chat: what SpamJudge can sort.
    fun canReport(c: Conversation) = settings.spamProtection && !c.group && c.address.isNotBlank() && '@' !in c.address

    // Deleted for good, from the Trash: the messages up to each cutoff (Messages.deleteUpTo, AOSP
    // Messaging's MmsUtils.deleteThread), then the chat side of them and the entry go. An entry
    // whose deletion the store refused stays, as Aves' deleteExpiredTrash keeps what it could not delete
    // (https://github.com/deckerst/aves/blob/develop/lib/model/source/trash.dart); one, several or
    // all of the Trash is Fossify Messages' emptyMessagesRecycleBin and
    // emptyMessagesRecycleBinForConversation (https://github.com/FossifyOrg/Messages, extensions/Context.kt).
    fun deleteForever(threads: Set<Long>) {
        val entries = trashed.filterKeys { it in threads }
        scope.launch {
            val gone = entries.filter { (thread, t) -> messages.deleteUpTo(thread, t.cutoff) }.keys
            if (gone.size < entries.size) rememberedHaptics.reject()
            if (gone.isEmpty()) return@launch
            // The chat side of its messages goes too, before their numbers are reused.
            chat.prune()
            store.update { s -> s.copy(pinned = s.pinned - gone, archived = s.archived - gone) }
            trash.forget(gone)
        }
    }

    // Conversations over the encrypted chat carry a lock in the list.
    val links by chat.links.collectAsState()
    fun encrypted(c: Conversation): Boolean = if (c.group) chat.groupReady(c.addresses) else chat.linkFor(c.address) != null
    // A tap and a long press, the same on a row and on a pinned avatar.
    val openConversation: (Conversation) -> Unit = { c ->
        val taking = picked
        // A trashed conversation is restored to be read (Google Messages).
        if (taking == null && filter == Filter.TRASH) restoring = c
        else if (taking == null) onOpenThread(c.threadId, c.addresses.joinToString(","))
        else picked = (if (c.threadId in taking) taking - c.threadId else taking + c.threadId).ifEmpty { null }
    }
    val peekConversation: (Conversation) -> Unit = { c ->
        val taking = picked
        // In the Trash a long press selects; a glance would read what is not to be read.
        if (taking == null && filter != Filter.TRASH) peeking = c
        else picked = (if (taking != null && c.threadId in taking) taking - c.threadId else (taking ?: emptySet()) + c.threadId).ifEmpty { null }
    }
    val line: @Composable (Conversation) -> Unit = { c ->
        ConversationLine(
            c,
            name = nameOf(c),
            photo = if (c.group) null else index.find(T9.clean(c.address))?.photo,
            look = if (c.group) null else index.find(T9.clean(c.address))?.look,
            pinned = c.threadId in settings.pinned,
            onOpen = { openConversation(c) },
            onPeek = { peekConversation(c) },
            encrypted = remember(links, c.threadId) { encrypted(c) },
            left = if (picked != null || filter == Filter.TRASH) com.yaz.sms.core.sms.SwipeAction.NONE else com.yaz.sms.core.sms.SwipeAction.of(settings.swipeLeft, com.yaz.sms.core.sms.SwipeAction.ARCHIVE),
            right = if (picked != null || filter == Filter.TRASH) com.yaz.sms.core.sms.SwipeAction.NONE else com.yaz.sms.core.sms.SwipeAction.of(settings.swipeRight, com.yaz.sms.core.sms.SwipeAction.DELETE),
            note = if (filter == Filter.TRASH) trashed[c.threadId]?.let { Trash.daysLabel(Trash.daysLeft(it, System.currentTimeMillis())) } else null,
            archived = Lists.isArchived(c, settings.archived),
            onSwipe = { action ->
                when (action) {
                    com.yaz.sms.core.sms.SwipeAction.ARCHIVE -> {
                        val on = !Lists.isArchived(c, settings.archived)
                        store.update { s -> s.copy(archived = if (on) s.archived + (c.threadId to c.date) else s.archived - c.threadId) }
                    }
                    com.yaz.sms.core.sms.SwipeAction.DELETE -> {
                        moveToTrash(listOf(c))
                        swiped = c
                    }
                    com.yaz.sms.core.sms.SwipeAction.READ -> messages.markRead(c.threadId)
                    com.yaz.sms.core.sms.SwipeAction.PIN -> store.update { s -> s.copy(pinned = if (c.threadId in s.pinned) s.pinned - c.threadId else s.pinned + c.threadId) }
                    com.yaz.sms.core.sms.SwipeAction.REPLY -> onOpenThread(c.threadId, c.addresses.joinToString(","))
                    com.yaz.sms.core.sms.SwipeAction.NONE -> Unit
                }
            },
            selected = picked?.contains(c.threadId)
        )
    }

    val archive = filter == Filter.ARCHIVED
    val inTrash = filter == Filter.TRASH
    // Spam is a place of its own (Google Messages' Spam & blocked).
    val inSpam = filter == Filter.SPAM
    // The Archive, the Trash and Spam are places side by side, away from the inbox.
    val apart = archive || inTrash || inSpam
    // One line at the top (the user, 2026-10-16): the search behind a magnifier that
    // opens into the field, as Material 3's search from an app bar icon, and one
    // Unread toggle; no title on the list, as Google Messages shows none.
    var searching by rememberSaveable { mutableStateOf(false) }
    val canFilter = messages.canRead() && all.isNotEmpty()
    var starredOpen by rememberSaveable { mutableStateOf(false) }
    val openRequests: com.yaz.sms.core.sms.OpenRequests = koinInject()
    if (starredOpen) com.yaz.sms.feature.thread.StarredPage(
        thread = null,
        titleOf = { m ->
            val c = all.firstOrNull { it.threadId == m.threadId }
            (c?.let { nameOf(it) }) ?: Numbers.format(context, c?.address ?: m.address)
        },
        onOpen = { m ->
            val there = all.firstOrNull { it.threadId == m.threadId }
            if (there != null && Lists.isTrashed(there, trashed)) restoring = there
            else {
                starredOpen = false
                searching = false
                query = ""
                openRequests.showMessage.value = m.uid
                onOpenThread(m.threadId, there?.addresses?.joinToString(",") ?: m.address)
            }
        },
        onClose = { starredOpen = false }
    )
    // The search folded away: the list back at its top, where the keyboard had pushed it from.
    var wasSearching by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(searching || query.isNotEmpty()) {
        val open = searching || query.isNotEmpty()
        if (wasSearching && !open) listState.animateScrollToItem(0)
        wasSearching = open
    }
    TabFrame(
        title = if (archive) "Archive" else if (inTrash) "Trash" else null,
        onOpenSettings = onOpenSettings,
        // Top left, facing Settings: the archive, and back from it or from the Trash.
        leading = {
            com.yaz.sms.ui.component.FloatingAction(
                if (apart) AppIcons.ArrowBack else AppIcons.Archive,
                if (apart) "Back to messages" else "Archive",
                {
                    searching = false
                    query = ""
                    picked = null
                    filter = if (apart) Filter.ALL else Filter.ARCHIVED
                }
            )
        },
        center = if (apart || !canFilter) null else ({
            androidx.compose.animation.AnimatedContent(searching || query.isNotEmpty(), label = "search") { open ->
                if (open) SearchPill(
                    query, { query = it }, hint = "Search",
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    floating = true, autoFocus = searching && query.isEmpty(),
                    onClose = { searching = false }
                )
                else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.yaz.sms.ui.component.FloatingAction(AppIcons.Search, "Search", {
                        rememberedHaptics.tick()
                        searching = true
                    })
                    val unreadOnly = filter == Filter.UNREAD
                    FloatingPane(shape = CircleShape, accent = unreadOnly, onClick = {
                        rememberedHaptics.tick()
                        filter = if (unreadOnly) Filter.ALL else Filter.UNREAD
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Icon(AppIcons.MarkUnread, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Unread", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }),
        controls = {
            // In the archive and the Trash, the search stays on its own line under the name, with
            // the way from one to the other beside it.
            if (apart && canFilter) Filters(
                query, { query = it }, filter,
                listOfNotNull(
                    Filter.ARCHIVED to "Archive",
                    Filter.TRASH to "Trash",
                    if (settings.spamProtection) Filter.SPAM to "Spam" else null
                )
            ) {
                picked = null
                filter = it
            }
            // The starred messages of every conversation, where the search lives (Google Messages).
            if (!apart && canFilter && (searching || query.isNotEmpty())) Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                com.yaz.sms.feature.thread.StarredEntry { starredOpen = true }
            }
        },
        overlay = {
            // Several conversations taken: their actions in one pill, as AOSP
            // Messaging's select mode offers them (archive, read, block, delete).
            androidx.compose.animation.AnimatedVisibility(
                visible = picked != null,
                enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
                    .padding(top = 8.dp)
            ) {
                val taken = picked.orEmpty()
                val those = all.filter { it.threadId in taken }
                FloatingPane(shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        com.yaz.sms.ui.component.FloatingAction(AppIcons.Close, "Leave the selection", { picked = null })
                        Text("${taken.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 6.dp))
                        if (inTrash) {
                            // Restore or delete for good what was taken (Google Messages' Trash).
                            com.yaz.sms.ui.component.FloatingAction(AppIcons.RestoreFromTrash, "Restore", {
                                rememberedHaptics.done()
                                trash.restore(taken)
                                picked = null
                            })
                            com.yaz.sms.ui.component.FloatingAction(AppIcons.DeleteForever, "Delete forever", { forever = taken }, tint = MaterialTheme.colorScheme.error)
                        } else {
                            com.yaz.sms.ui.component.FloatingAction(AppIcons.DoneAll, "Mark read", {
                                rememberedHaptics.done()
                                those.forEach { messages.markRead(it.threadId) }
                                picked = null
                            })
                            com.yaz.sms.ui.component.FloatingAction(if (archive) AppIcons.Unarchive else AppIcons.Archive, if (archive) "Unarchive" else "Archive", {
                                rememberedHaptics.done()
                                store.update { s ->
                                    s.copy(archived = if (archive) s.archived - taken else s.archived + those.map { it.threadId to it.date })
                                }
                                picked = null
                            })
                            // In Spam: Not spam for what was taken, beside Block and Move to trash.
                            if (inSpam) com.yaz.sms.ui.component.FloatingAction(AppIcons.ReportOff, "Not spam", {
                                rememberedHaptics.done()
                                those.forEach(::notSpam)
                                picked = null
                            })
                            if (those.isNotEmpty() && those.all { !it.group && it.address.count(Char::isDigit) >= 3 } && NumberActions.canBlock(context))
                                com.yaz.sms.ui.component.FloatingAction(AppIcons.Block, "Block", { confirmBlockMany = true })
                            com.yaz.sms.ui.component.FloatingAction(AppIcons.Delete, "Move to trash", { confirmTrashMany = true }, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            // A conversation put in the Trash by a swipe can be taken back for a few seconds.
            androidx.compose.animation.AnimatedVisibility(
                visible = swiped != null,
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                    .padding(bottom = 16.dp)
            ) {
                FloatingPane(shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
                        Text("Moved to Trash", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.TextButton(onClick = {
                            rememberedHaptics.done()
                            swiped?.let { trash.restore(listOf(it.threadId)) }
                            swiped = null
                        }) { Text("Undo") }
                    }
                }
            }
            // In the Trash with nothing taken: all of it at once.
            androidx.compose.animation.AnimatedVisibility(
                visible = inTrash && picked == null && query.isBlank() && shown.isNotEmpty(),
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                    .padding(bottom = 16.dp)
            ) {
                FloatingPane(shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        androidx.compose.material3.TextButton(onClick = {
                            rememberedHaptics.done()
                            trash.restore(shown.map { it.threadId })
                        }) { Text("Restore all") }
                        androidx.compose.material3.TextButton(onClick = {
                            emptying = true
                            forever = shown.mapTo(HashSet()) { it.threadId }
                        }) { Text("Empty trash", color = MaterialTheme.colorScheme.error) }
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
                    filter == Filter.TRASH -> "Trash is empty"
                    filter == Filter.SPAM -> "No spam"
                    else -> "No messages yet"
                },
                message = when {
                    query.isNotBlank() -> "No name, number or message matches \"$query\"."
                    filter == Filter.ALL -> "Write to someone with the button below."
                    filter == Filter.TRASH -> "Conversations you trash stay here 30 days."
                    inSpam -> "Report a conversation as spam to put it here."
                    else -> "Archive a conversation with a long press; it comes back with its next message."
                },
                icon = when (filter) {
                    Filter.TRASH -> AppIcons.Delete
                    Filter.SPAM -> AppIcons.Report
                    else -> AppIcons.Message
                },                modifier = Modifier.fillMaxSize().padding(padding)
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
                // The first nine pinned conversations leave the rows for avatars on top (iOS Messages), in
                // the inbox only: not in a search, the Archive, the Trash, Spam nor the Unread view.
                val faces = if (filter == Filter.ALL && query.isBlank()) shown.filter { it.threadId in settings.pinned }.take(PINNED_FACES_MAX) else emptyList()
                if (faces.isNotEmpty()) {
                    item(key = "pinned") {
                        PinnedFaces(
                            faces,
                            face = { c ->
                                val entry = if (c.group) null else index.find(T9.clean(c.address))
                                PinnedFace(nameOf(c) ?: Numbers.format(context, c.address), entry?.photo, entry?.look)
                            },
                            selected = { c -> picked?.contains(c.threadId) },
                            onOpen = openConversation,
                            onPeek = peekConversation
                        )
                    }
                }
                val onFaces = faces.mapTo(HashSet()) { it.threadId }
                // People first; every service (banks, deliveries, codes) in one stack.
                val (services, people) = shown.filterNot { it.threadId in onFaces }.partition { Lists.isService(it) && filter == Filter.ALL && query.isBlank() }
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

    // The offer to take back a swiped conversation lasts a few seconds.
    swiped?.let { c ->
        androidx.compose.runtime.LaunchedEffect(c.threadId) {
            kotlinx.coroutines.delay(5_000)
            swiped = null
        }
    }

    if (confirmTrashMany) {
        val taken = picked.orEmpty()
        com.yaz.sms.ui.component.ZoneAlertDialog(
            onDismissRequest = { confirmTrashMany = false },
            title = { Text(if (taken.size == 1) "Move this conversation to Trash?" else "Move ${taken.size} conversations to Trash?") },
            text = { Text("They are kept 30 days.") },
            confirmButton = {
                BoldButton(filled = true, onClick = {
                    confirmTrashMany = false
                    rememberedHaptics.done()
                    moveToTrash(all.filter { it.threadId in taken })
                    picked = null
                }) { Text("Move to trash") }
            },
            dismissButton = { QuietButton(onClick = { confirmTrashMany = false }) { Text("Cancel") } }
        )
    }
    // Deleting for good asks first (Google Messages), one conversation, several, or all of the Trash.
    forever?.let { threads ->
        val close = { forever = null; emptying = false }
        com.yaz.sms.ui.component.ZoneAlertDialog(
            onDismissRequest = close,
            title = { Text(if (emptying) "Empty Trash?" else if (threads.size == 1) "Delete this conversation forever?" else "Delete ${threads.size} conversations forever?") },
            text = { Text("All their messages are deleted from this phone.") },
            confirmButton = {
                BoldButton(filled = true, onClick = {
                    close()
                    rememberedHaptics.firm()
                    deleteForever(threads)
                    picked = null
                }) { Text(if (emptying) "Empty trash" else "Delete forever") }
            },
            dismissButton = { QuietButton(onClick = close) { Text("Cancel") } }
        )
    }
    // A trashed conversation is restored before it is read.
    restoring?.let { c ->
        com.yaz.sms.ui.component.ZoneAlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(nameOf(c) ?: Numbers.format(context, c.address)) },
            text = { Text("Restore this conversation to read it.") },
            confirmButton = {
                BoldButton(filled = true, onClick = {
                    rememberedHaptics.done()
                    trash.restore(listOf(c.threadId))
                    restoring = null
                }) { Text("Restore") }
            },
            dismissButton = { QuietButton(onClick = { restoring = null }) { Text("Cancel") } }
        )
    }
    if (confirmBlockMany) {
        val those = all.filter { it.threadId in picked.orEmpty() }
        com.yaz.sms.ui.component.ZoneAlertDialog(
            onDismissRequest = { confirmBlockMany = false },
            title = { Text(if (those.size == 1) "Block this number?" else "Block ${those.size} numbers?") },
            text = { Text("Calls and messages from them will not reach you.") },
            confirmButton = {
                BoldButton(filled = true, onClick = {
                    confirmBlockMany = false
                    rememberedHaptics.done()
                    those.forEach { NumberActions.block(context, it.address) }
                    picked = null
                }) { Text("Block") }
            },
            dismissButton = { QuietButton(onClick = { confirmBlockMany = false }) { Text("Cancel") } }
        )
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
            onSelect = {
                picked = setOf(c.threadId)
                peeking = null
            },
            onDelete = { moveToTrash(listOf(c)) },
            onReportSpam = if (canReport(c) && c.threadId !in spamThreads) ({ reporting = c }) else null,
            onNotSpam = if (c.threadId in spamThreads) ({ notSpam(c) }) else null,
            // In Spam: Block asks first, as the selection's Block does.
            onBlock = if (c.threadId in spamThreads && !c.group &&
                c.address.count(Char::isDigit) >= 3 && NumberActions.canBlock(context)
            ) ({ blocking = c }) else null
        )
    }
    // The Block of a conversation in Spam asks first, as the selection's Block does.
    blocking?.let { c ->
        ZoneAlertDialog(
            onDismissRequest = { blocking = null },
            title = { Text("Block this number?") },
            text = { Text("Calls and messages from them will not reach you.") },
            confirmButton = {
                BoldButton(filled = true, onClick = {
                    blocking = null
                    rememberedHaptics.done()
                    NumberActions.block(context, c.address)
                }) { Text("Block") }
            },
            dismissButton = { QuietButton(onClick = { blocking = null }) { Text("Cancel") } }
        )
    }
    // Report spam asks first; the number may be blocked with it.
    reporting?.let { c ->
        ReportSpamDialog(
            name = nameOf(c) ?: Numbers.format(context, c.address),
            canBlock = !c.group && c.address.count(Char::isDigit) >= 3 && NumberActions.canBlock(context),
            onDismiss = { reporting = null }
        ) { block ->
            reporting = null
            rememberedHaptics.done()
            reportSpam(c, block)
        }
    }
}

/** The search and, as in Dialer's calls, the places to switch between on the same line, in glass (the Archive and the Trash). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Filters(
    query: String, onQuery: (String) -> Unit, filter: Filter, chips: List<Pair<Filter, String>>,
    selected: (Filter) -> Boolean = { it == filter },
    onFilter: (Filter) -> Unit
) {
    val haptics = rememberHaptics()
    // Three places do not fit beside the search on a phone: the search takes its own line.
    val stacked = chips.size > 2
    if (stacked) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        SearchPill(query, onQuery, hint = "Search", modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(), floating = true)
    }
    val chipsRow: @Composable () -> Unit = {
        chips.forEach { (value, label) ->
            FloatingPane(
                shape = CircleShape,
                accent = selected(value),
                onClick = {
                    haptics.tick()
                    onFilter(value)
                }
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
            }
        }
    }
    if (stacked) {
        // Three places (Archive, Trash, Spam) wrap onto a second line when a phone is too narrow, never sideways
        // (Jetpack Compose FlowRow, https://developer.android.com/develop/ui/compose/layouts/flow).
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        ) { chipsRow() }
    } else Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        SearchPill(query, onQuery, hint = "Search", modifier = Modifier.widthIn(max = 420.dp).weight(1f, fill = false).fillMaxWidth(), floating = true)
        chipsRow()
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
    encrypted: Boolean,
    left: com.yaz.sms.core.sms.SwipeAction,
    right: com.yaz.sms.core.sms.SwipeAction,
    archived: Boolean,
    /** In the Trash, what remains of the 30 days, in the place of the time. */
    note: String? = null,
    onSwipe: (com.yaz.sms.core.sms.SwipeAction) -> Unit,
    /** Several at once (AOSP Messaging's multi select): null out of it, else whether this one is taken. */
    selected: Boolean? = null
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
                    // Taken in the multi select: the check stands where the face was.
                    if (selected == true) Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        Icon(AppIcons.Done, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    else ContactAvatar(name, photo, 48.dp, look = look)
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
                            if (encrypted) {
                                Spacer(Modifier.width(6.dp))
                                Icon(AppIcons.Lock, contentDescription = "End-to-end encrypted", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            }
                            if (pinned) {
                                Spacer(Modifier.width(6.dp))
                                Icon(AppIcons.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            note ?: timeLabel(context, c.date),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // A picture message's subject over its snippet, as AOSP Messaging's conversation list does
                    // (ConversationListItemView.setSubject: the "Subject: " label and the cleansed subject, a line of its own).
                    if (c.subject != null && c.draft == null) Text(
                        "Subject: " + c.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val line = when {
                        // A draft wins the line, as AOSP Messaging shows its drafts first.
                        c.draft != null -> "Draft: " + com.yaz.sms.core.sms.Markup.plain(c.draft)
                        c.failed -> "Not sent: " + com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(c.snippet)))
                        c.fromMe -> "You: " + com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(c.snippet)))
                        else -> com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(c.snippet)))
                    }
                    // A message of a subject alone has no snippet line (fromMe or not: "You: " alone says nothing).
                    if (c.snippet.isNotEmpty() || c.draft != null) Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            c.draft != null -> MaterialTheme.colorScheme.primary
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

/** The date of each conversation's newest message when it was made ready. */
private val warmed = HashMap<Long, Long>()

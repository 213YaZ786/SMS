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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
import com.yaz.sms.feature.main.SetupStep
import com.yaz.sms.feature.main.TabFrame
import com.yaz.sms.feature.main.rememberSetup
import com.yaz.sms.core.dial.People
import com.yaz.sms.feature.thread.Delivery
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
fun ConversationsScreen(onOpenSettings: () -> Unit, onOpenThread: (Long, String) -> Unit, openThread: Long? = null) {
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
    // The read itself failed (Messages.failed): the list says so, with Retry, rather than "No messages yet".
    val failedRead by messages.failed.collectAsState()
    val contacts by book.entries.collectAsState()
    val settings by store.settings.collectAsState()
    val context = LocalContext.current
    val index = remember(contacts, com.yaz.sms.core.dial.PrivateNames.version.intValue) { PhoneIndex(contacts, Numbers.countryIso(context)) }
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
    // The chat's read marks, for the delivery tick of the user's own newest message.
    val chatRefs by chat.refs.collectAsState()
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
    // What was just done, waiting its few seconds to be taken back (the Undo pane): Trash, archive, block.
    var undoing by remember { mutableStateOf<UndoOffer?>(null) }
    val undoWords = remember { arrayOfNulls<UndoOffer>(1) }
    // Conversations whose deletion Android refused, how many (the dialog with the role button).
    var deleteFailed by remember { mutableStateOf(0) }
    val notifier = remember { com.yaz.sms.core.sms.MessageNotifier(context) }
    val setup = rememberSetup()
    val say: (String) -> Unit = { words -> android.widget.Toast.makeText(context, words, android.widget.Toast.LENGTH_SHORT).show() }
    // The threads the store holds now: a selection or a pin list may name some that are gone.
    val existing = remember(all) { all.mapTo(HashSet()) { it.threadId } }
    // A trashed conversation tapped: it is restored to be read.
    var restoring by remember { mutableStateOf<Conversation?>(null) }
    val rememberedHaptics = rememberHaptics()
    // The words of the messages themselves, as AOSP Messaging's SearchActivity finds them (Messages.search, the
    // provider's text search), asked off the main thread once the typing has paused for 300 ms. Kept with the
    // query it answers, so a stale answer never lights a conversation for the next letters.
    val bodies by androidx.compose.runtime.produceState(Pair("", emptyMap<Long, com.yaz.sms.data.sms.Found>()), query, all) {
        val q = query.trim()
        if (q.isEmpty()) {
            value = Pair("", emptyMap())
        } else {
            kotlinx.coroutines.delay(300)
            value = Pair(q, messages.search(q).associateBy { it.threadId })
        }
    }
    // Names, numbers and the newest message first, then the conversations only an older message matches (with the
    // words found, which stand under the name in place of the last message).
    val searched = remember(all, filter, settings.pinned, settings.archived, trashed, query, index, spamThreads, bodies) {
        // Case and accents folded as the new message picker does (People.plain), a number found with or without spaces (T9.clean).
        val q = People.plain(query.trim())
        val digits = if (query.any(Char::isLetter)) "" else T9.clean(query.filterNot(Char::isWhitespace)).filter(Char::isDigit)
        // The pins stay in the order the user pinned them.
        val listed = Lists.shown(all, filter, settings.pinned, settings.archived, trashed = trashed, spam = spamThreads)
            .let { list -> list.filter { it.threadId in settings.pinned }.sortedBy { settings.pinned.indexOf(it.threadId) } + list.filterNot { it.threadId in settings.pinned } }
        if (q.isEmpty()) Pair(listed, emptyMap<Long, com.yaz.sms.data.sms.Found>())
        else {
            val (named, rest) = listed.partition { c ->
                nameOf(c)?.let { People.plain(it).contains(q) } == true ||
                    c.addresses.any { a -> People.plain(a).contains(q) || (digits.isNotEmpty() && T9.clean(a).contains(digits)) } ||
                    People.plain(c.snippet).contains(q)
            }
            val inside = if (bodies.first == query.trim()) bodies.second else emptyMap()
            val older = rest.filter { it.threadId in inside }
            Pair(named + older, older.associate { it.threadId to inside.getValue(it.threadId) })
        }
    }
    val shown = searched.first
    val olderFound = searched.second
    // The code chip comes only from a conversation the inbox shows: not archived, not in Spam.
    val code = remember(live, settings.archived, spamThreads) {
        Lists.latestCode(live.filter { !Lists.isArchived(it, settings.archived) && it.threadId !in spamThreads }, System.currentTimeMillis())
    }
    // What Spam holds, on its chip.
    val spamCount = remember(all, settings.archived, trashed, spamThreads) {
        all.count { it.threadId in spamThreads && !Lists.isArchived(it, settings.archived) && !Lists.isTrashed(it, trashed) }
    }
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
    // The list moves under a selection: what was taken and is gone leaves it, and an empty one ends it.
    androidx.compose.runtime.LaunchedEffect(existing) {
        val taking = picked ?: return@LaunchedEffect
        val still = taking.filterTo(HashSet()) { it in existing }
        if (still.size != taking.size) picked = still.ifEmpty { null }
    }
    // No "To answer" cards (the user, 2026-10-28): the pinned faces and the unread counts say it already.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Into the Trash: nothing is deleted, the date of each conversation's newest message is kept
    // as its cutoff. Same cutoff rule as AOSP Messaging's DeleteConversationAction
    // (https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/datamodel/action/DeleteConversationAction.java);
    // the time to keep and the list of what waits are Aves' bin (https://github.com/deckerst/aves/blob/develop/lib/model/source/trash.dart),
    // and the move is Fossify Messages' moveMessageToRecycleBin (https://github.com/FossifyOrg/Messages, extensions/Context.kt).
    fun moveToTrash(those: Collection<Conversation>) {
        trash.trash(those.associate { it.threadId to it.date })
        // The notification of a conversation put away goes with it.
        those.forEach { notifier.cancel(it.threadId) }
    }

    // The Trash with its Undo pane, shaped like onActionBarArchive's snackbar (its undoRunnable does the other way):
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/ui/conversationlist/AbstractConversationListActivity.java#L146-L176
    fun onActionBarTrash(conversations: Collection<Conversation>) {
        val conversationIds = conversations.map { it.threadId }
        if (conversationIds.isEmpty()) return
        moveToTrash(conversations)
        val undoRunnable = { trash.restore(conversationIds) }
        undoing = UndoOffer("Moved to Trash", undoRunnable)
    }

    // Archive and unarchive in one function, AOSP Messaging's onActionBarArchive(conversations, isToArchive): each
    // conversation archived or not, an undoRunnable that does the other way, the message in a snackbar with Undo,
    // the selection left. Signal's handleArchive and handleUnarchive are the same two ways (selectedConversations,
    // count, snackBarTitle, the selection ended).
    // Not ported: handleArchive, handleUnarchive (both are this function with isToArchive), showProgress, progressDialog,
    // dismissProgressDialog, SignalProgressDialog (the store is written at once, nothing to wait for), Completable and
    // lifecycleDisposable (RxJava off-thread work: store.update is not on the main thread),
    // mainNavigationViewModel.getSnackbarRegistry (this screen's Undo pane), UpdateConversationArchiveStatusAction
    // (Android's SMS store has no archive: Settings keeps thread to date).
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/ui/conversationlist/AbstractConversationListActivity.java#L146-L176
    // https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/conversationlist/ConversationListFragment.java#handleArchive
    fun onActionBarArchive(conversations: Collection<Conversation>, isToArchive: Boolean) {
        val selectedConversations = conversations.toList()
        val count = selectedConversations.size
        if (count == 0) return
        val conversationIds = selectedConversations.mapTo(LinkedHashSet()) { it.threadId }
        val snackBarTitle = when {
            isToArchive && count == 1 -> "Archived"
            isToArchive -> "$count archived"
            count == 1 -> "Moved to inbox"
            else -> "$count moved to inbox"
        }
        store.update { s ->
            s.copy(archived = if (isToArchive) s.archived + selectedConversations.map { it.threadId to it.date } else s.archived - conversationIds)
        }
        if (isToArchive) conversationIds.forEach { notifier.cancel(it) }
        picked = null
        val undoRunnable = { onActionBarArchive(selectedConversations, !isToArchive) }
        undoing = UndoOffer(snackBarTitle, undoRunnable)
    }

    // Signal's handlePin: the conversations not pinned yet in the order given (LinkedHashSet), more than the limit
    // refused with its words, the selection ended. Signal's limit is 3 chats; this list shows nine faces.
    // Not ported: SimpleTask, SignalExecutors.BOUNDED, ThreadTable.pinConversations, ConversationUtil.refreshRecipientShortcuts
    // (the pin is Settings' list, kept in the order pinned), RemoteConfig.pinnedChatLimit (PINNED_FACES_MAX).
    // https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/conversationlist/ConversationListFragment.java#handlePin
    fun handlePin(conversations: Collection<Conversation>) {
        val toPin = conversations.filter { it.threadId !in settings.pinned }.mapTo(LinkedHashSet()) { it.threadId }
        val pinnedCount = settings.pinned.count { it in existing }
        if (toPin.size + pinnedCount > PINNED_FACES_MAX) {
            rememberedHaptics.reject()
            say("Up to $PINNED_FACES_MAX pinned conversations")
            picked = null
            return
        }
        store.update { s -> s.copy(pinned = s.pinned + toPin.filterNot { it in s.pinned }) }
        picked = null
    }

    // Signal's handleUnpin.
    // https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/conversationlist/ConversationListFragment.java#handleUnpin
    fun handleUnpin(ids: Collection<Long>) {
        store.update { s -> s.copy(pinned = s.pinned - ids.toSet()) }
        picked = null
    }

    // Signal's handleMarkAsUnread: setForcedUnread off the main thread, then the selection ended. Android's store has
    // no forced flag: the newest message received in each thread is set unread (setForcedUnread below).
    // Not ported: SimpleTask (a coroutine does it), SignalDatabase.threads() (Telephony's providers).
    // https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/conversationlist/ConversationListFragment.java#handleMarkAsUnread
    fun handleMarkAsUnread(conversations: Collection<Conversation>) {
        val ids = conversations.map { it.threadId }
        scope.launch {
            val marked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { setForcedUnread(context, ids) }
            if (!marked) {
                rememberedHaptics.reject()
                say("Could not mark as unread")
            }
            picked = null
        }
    }

    // AOSP Messaging's onActionBarBlock: the destinations blocked, then onUpdateDestinationBlockedAction: on success a
    // snackbar whose Undo (undoRunnable) unblocks them; on failure an error.
    // Not ported: AlertDialog (the screens' own confirm dialogs ask first), UpdateDestinationBlockedAction (Android's
    // BlockedNumberContract is written by NumberActions), SnackBarInteraction, exitMultiSelectState (picked = null by the caller).
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/ui/conversationlist/AbstractConversationListActivity.java#L193-L227
    fun onActionBarBlock(conversations: Collection<Conversation>) {
        scope.launch {
            val blocked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                conversations.filter { NumberActions.block(context, it.address) }
            }
            val success = blocked.isNotEmpty()
            if (!success) {
                rememberedHaptics.reject()
                say("Could not block")
                return@launch
            }
            if (blocked.size < conversations.size) say("Could not block ${conversations.size - blocked.size}")
            val undoRunnable = {
                scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { blocked.forEach { NumberActions.unblock(context, it.address) } } }
                Unit
            }
            undoing = UndoOffer("Blocked", undoRunnable)
        }
    }

    // Report spam and Not spam on a conversation, as Google Messages offers them
    // (https://support.google.com/messages/answer/9061432): the sender is reported or cleared and the
    // messages received from it train the classifier (SpamStore, SpamBlocker's LocalAI.kt updateCategory:
    // spam or ham). Report spam may block the number too, a box of its confirmation.
    var reporting by remember { mutableStateOf<Conversation?>(null) }
    var blocking by remember { mutableStateOf<Conversation?>(null) }
    fun reportSpam(c: Conversation, block: Boolean) {
        scope.launch {
            spamStore.report(SpamJudge.senderKey(c.address), Spams.received(messages.thread(c.threadId, Spams.MAX_PER_REPORT * 2)))
            if (block) onActionBarBlock(listOf(c))
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
            if (gone.size < entries.size) {
                rememberedHaptics.reject()
                deleteFailed = entries.size - gone.size
            }
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
    val asking: com.yaz.sms.core.sms.OpenRequests = koinInject()
    // A tap and a long press, the same on a row and on a pinned avatar.
    val openConversation: (Conversation) -> Unit = { c ->
        val taking = picked
        // A trashed conversation is restored to be read (Google Messages).
        if (taking == null && filter == Filter.TRASH) restoring = c
        else if (taking == null) {
            // A conversation found by an older message opens on it (SearchActivity hands ComposeMessageActivity
            // "select_id"; here the thread scrolls to the uid and lights it, as for SHOW_MESSAGE).
            olderFound[c.threadId]?.let { hit ->
                asking.showIn.value = c.threadId
                asking.showMessage.value = hit.uid
            }
            onOpenThread(c.threadId, c.addresses.joinToString(","))
        }
        else picked = (if (c.threadId in taking) taking - c.threadId else taking + c.threadId).ifEmpty { null }
    }
    val peekConversation: (Conversation) -> Unit = { c ->
        val taking = picked
        // In the Trash a long press selects; a glance would read what is not to be read.
        if (taking == null && filter != Filter.TRASH) peeking = c
        else picked = (if (taking != null && c.threadId in taking) taking - c.threadId else (taking ?: emptySet()) + c.threadId).ifEmpty { null }
    }
    // A group's people, up to four, for the cluster of their faces (Element X's AvatarCluster).
    fun membersOf(c: Conversation): List<PinnedFace> = if (!c.group) emptyList() else c.addresses.take(4).map { a ->
        val e = index.find(T9.clean(a))
        PinnedFace(e?.name ?: "", e?.photo, e?.look)
    }
    val line: @Composable (Conversation, Modifier) -> Unit = { c, cardModifier ->
        ConversationLine(
            c,
            modifier = cardModifier,
            members = remember(c.addresses, index) { membersOf(c) },
            name = nameOf(c),
            // The thread's rule (ThreadScreen holdMedia): the media of a message received from a number not in the
            // contacts wait for a tap while Settings "Show media from unknown numbers" is off; a group is held
            // when any of its people is unknown.
            holdMedia = !settings.showStrangerMedia && !c.fromMe &&
                (if (c.group) c.addresses.any { index.find(T9.clean(it)) == null } else index.find(T9.clean(c.address)) == null),
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
                    com.yaz.sms.core.sms.SwipeAction.ARCHIVE -> onActionBarArchive(listOf(c), !Lists.isArchived(c, settings.archived))
                    com.yaz.sms.core.sms.SwipeAction.DELETE -> onActionBarTrash(listOf(c))
                    // The swipe toggles: Read on an unread conversation, Unread on a read one (Signal's handleMarkAsRead, handleMarkAsUnread).
                    com.yaz.sms.core.sms.SwipeAction.READ -> if (c.unread > 0) messages.markRead(c.threadId) else handleMarkAsUnread(listOf(c))
                    com.yaz.sms.core.sms.SwipeAction.PIN -> if (c.threadId in settings.pinned) handleUnpin(listOf(c.threadId)) else handlePin(listOf(c))
                    com.yaz.sms.core.sms.SwipeAction.REPLY -> onOpenThread(c.threadId, c.addresses.joinToString(","))
                    com.yaz.sms.core.sms.SwipeAction.NONE -> Unit
                }
            },
            selected = picked?.contains(c.threadId),
            open = c.threadId == openThread,
            // Silence from the conversation's menu: its end time kept in Settings, a bell while it lasts.
            silenced = (settings.silenced[c.threadId] ?: 0L) > System.currentTimeMillis(),
            delivery = remember(c, chatRefs) { deliveryOf(c, chat) },
            found = olderFound[c.threadId]?.body,
            target = query.trim()
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
        scroll = listState,
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
                    if (settings.spamProtection) Filter.SPAM to (if (spamCount > 0) "Spam $spamCount" else "Spam") else null
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
                // Only the conversations that still exist (the list moves under a selection).
                val taken = picked.orEmpty().filterTo(HashSet()) { it in existing }
                val those = all.filter { it.threadId in taken }
                FloatingPane(shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        com.yaz.sms.ui.component.FloatingAction(AppIcons.Close, "Leave the selection", { picked = null })
                        Text("${taken.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 6.dp))
                        // Select all: every conversation the list shows now.
                        if (taken.size < shown.size) com.yaz.sms.ui.component.FloatingAction(AppIcons.SelectAll, "Select all", {
                            rememberedHaptics.tick()
                            picked = shown.mapTo(HashSet()) { it.threadId }
                        })
                        if (inTrash) {
                            // Restore or delete for good what was taken (Google Messages' Trash).
                            com.yaz.sms.ui.component.FloatingAction(AppIcons.RestoreFromTrash, "Restore", {
                                rememberedHaptics.done()
                                trash.restore(taken)
                                picked = null
                            })
                            com.yaz.sms.ui.component.FloatingAction(AppIcons.DeleteForever, "Delete forever", { forever = taken }, tint = MaterialTheme.colorScheme.error)
                        } else {
                            // Signal's selection menu: Read when something in it is unread, else Unread (handleMarkAsRead, handleMarkAsUnread).
                            if (those.any { it.unread > 0 }) com.yaz.sms.ui.component.FloatingAction(AppIcons.DoneAll, "Mark read", {
                                rememberedHaptics.done()
                                those.forEach { messages.markRead(it.threadId) }
                                picked = null
                            }) else com.yaz.sms.ui.component.FloatingAction(AppIcons.MarkUnread, "Mark unread", {
                                rememberedHaptics.done()
                                handleMarkAsUnread(those)
                            })
                            com.yaz.sms.ui.component.FloatingAction(if (archive) AppIcons.Unarchive else AppIcons.Archive, if (archive) "Unarchive" else "Archive", {
                                rememberedHaptics.done()
                                onActionBarArchive(those, !archive)
                            })
                            // In Spam: Not spam for what was taken, beside Block and Move to trash.
                            if (inSpam) com.yaz.sms.ui.component.FloatingAction(AppIcons.ReportOff, "Not spam", {
                                rememberedHaptics.done()
                                those.forEach(::notSpam)
                                picked = null
                            })
                            if (those.isNotEmpty() && those.all { !it.group && it.address.count(Char::isDigit) >= 3 } && NumberActions.canBlock(context) &&
                                those.any { !NumberActions.isBlocked(context, it.address) })
                                com.yaz.sms.ui.component.FloatingAction(AppIcons.Block, "Block", { confirmBlockMany = true })
                            com.yaz.sms.ui.component.FloatingAction(AppIcons.Delete, "Move to trash", { confirmTrashMany = true }, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            // What was just done (Trash, archive, block) can be taken back for a few seconds: the Undo pane,
            // its words kept while it slides out.
            val offer = undoing
            if (offer != null) undoWords[0] = offer
            androidx.compose.animation.AnimatedVisibility(
                visible = offer != null,
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                    .padding(bottom = 16.dp)
            ) {
                FloatingPane(shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(undoWords[0]?.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.TextButton(onClick = {
                            rememberedHaptics.done()
                            offer?.undo?.invoke()
                            undoing = null
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
        // https://raw.githubusercontent.com/element-hq/element-x-android/develop/features/home/impl/src/main/kotlin/io/element/android/features/home/impl/filters/RoomListFiltersEmptyStateResources.kt#fromSelectedFilters
        val filtered = FiltersEmptyState.fromSelectedFilters(listOfNotNull(filter.takeIf { it == Filter.UNREAD }))
        when {
            // The role not held: the text gets the button that asks for it again, whatever "Not now" said before.
            !messages.canRead() -> EmptyZone(
                title = "Your messages show here",
                message = "Once SMS is your messaging app.",
                icon = AppIcons.Message,
                actionLabel = "Choose SMS",
                onAction = { setup.run(SetupStep.ROLE) },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            // The read itself failed: not "No messages yet".
            failedRead && all.isEmpty() -> EmptyZone(
                title = "Could not read your messages",
                message = "Android did not answer.",
                icon = AppIcons.Message,
                actionLabel = "Retry",
                onAction = { messages.refresh() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            !loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
            // The words of the messages are still being looked for: no "Nothing found" before they are.
            shown.isEmpty() && query.isNotBlank() && bodies.first != query.trim() ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
            shown.isEmpty() -> EmptyZone(
                // Element X's empty state of a filter: its own words and a way to clear it (fromSelectedFilters).
                title = when {
                    query.isNotBlank() -> "Nothing found"
                    filtered != null -> filtered.title
                    filter == Filter.ARCHIVED -> "Nothing in the archive"
                    filter == Filter.TRASH -> "Trash is empty"
                    filter == Filter.SPAM -> "No spam"
                    else -> "No messages yet"
                },
                message = when {
                    query.isNotBlank() -> "No name, number or message matches \"$query\"."
                    filtered != null -> filtered.subtitle
                    filter == Filter.ALL -> "Write to someone with the button below."
                    filter == Filter.TRASH -> "Conversations you trash stay here 30 days."
                    inSpam -> "Report a conversation as spam to put it here."
                    else -> "Archive a conversation with a long press; it comes back with its next message."
                },
                icon = when (filter) {
                    Filter.TRASH -> AppIcons.Delete
                    Filter.SPAM -> AppIcons.Report
                    else -> AppIcons.Message
                },
                actionLabel = "Show all".takeIf { filtered != null && query.isBlank() },
                onAction = { filter = Filter.ALL },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
              // As many columns as fit, each card at least 280 dp (its face column and a bubble of words), as
              // Android's "Feed" canonical layout, GridCells.Adaptive(minSize): a phone upright keeps one, a
              // tablet in landscape takes three (the user, 2026-10-28: two left room unused).
              // https://developer.android.com/guide/topics/large-screens/large-screen-canonical-layouts
              val gap = 8.dp
              val inner = maxWidth - 32.dp
              val columns = ((inner + gap) / (280.dp + gap)).toInt().coerceIn(1, 4)
              val cell = (inner - gap * (columns - 1)) / columns
              val oneColumn = Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth()
              LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                // The cards of a list, one per line or in rows of equal cells: this app's SettingsScreen.kt
                // TileGrid (tiles.chunked(columns), a row per chunk), after AOSP SystemUI's TileLayout
                // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/qs/TileLayout.java
                fun androidx.compose.foundation.lazy.LazyListScope.cards(list: List<Conversation>, key: (Conversation) -> Any) {
                    if (columns == 1) items(list, key = key) { c -> Box(Modifier.animateItem()) { line(c, oneColumn) } }
                    else items(list.chunked(columns), key = { row -> key(row.first()) }) { row ->
                        CardRow(row, cell, gap, Modifier.animateItem()) { c, m -> line(c, m) }
                    }
                }
                if (code != null && filter == Filter.ALL && query.isBlank()) {
                    item(key = "code") { CodeChip(code.first, code.second, nameOf(code.first)) }
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
                                PinnedFace(nameOf(c) ?: Numbers.format(context, c.address), entry?.photo, entry?.look, membersOf(c))
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
                cards(people) { it.threadId }
                if (services.isNotEmpty()) {
                    item(key = "services") {
                        ServicesStack(services, open = servicesOpen) { servicesOpen = !servicesOpen }
                    }
                    if (servicesOpen) cards(services) { "s/${it.threadId}" }
                }
              }
            }
        }
    }

    // The offer to take back what was just done lasts a few seconds.
    undoing?.let { offer ->
        androidx.compose.runtime.LaunchedEffect(offer) {
            kotlinx.coroutines.delay(5_000)
            if (undoing === offer) undoing = null
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
                    onActionBarTrash(all.filter { it.threadId in taken })
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
        // Only the numbers not yet blocked (AbstractConversationListActivity.java#onActionBarBlock offers Block for a number once).
        val those = all.filter { it.threadId in picked.orEmpty() && !NumberActions.isBlocked(context, it.address) }
        com.yaz.sms.ui.component.ZoneAlertDialog(
            onDismissRequest = { confirmBlockMany = false },
            title = { Text(if (those.size == 1) "Block this number?" else "Block ${those.size} numbers?") },
            text = { Text("Calls and messages from them will not reach you.") },
            confirmButton = {
                BoldButton(filled = true, onClick = {
                    confirmBlockMany = false
                    rememberedHaptics.done()
                    onActionBarBlock(those)
                    picked = null
                }) { Text("Block") }
            },
            dismissButton = { QuietButton(onClick = { confirmBlockMany = false }) { Text("Cancel") } }
        )
    }
    // A deletion Android refused: AbstractConversationListActivity#onActionBarDelete answers "requires default sms app" with a
    // change button when SMS is not the default app (isDefaultSmsApp, getChangeDefaultSmsAppIntent), here the dialog of this
    // file's confirmTrashMany:
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/ui/conversationlist/AbstractConversationListActivity.java#onActionBarDelete
    // https://raw.githubusercontent.com/213YaZ786/SMS/main/app/src/main/java/com/yaz/sms/feature/conversations/ConversationsScreen.kt#confirmTrashMany
    if (deleteFailed > 0) {
        val isDefaultSmsApp = android.provider.Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        com.yaz.sms.ui.component.ZoneAlertDialog(
            onDismissRequest = { deleteFailed = 0 },
            title = { Text(if (deleteFailed == 1) "Could not delete 1 conversation" else "Could not delete $deleteFailed conversations") },
            text = { Text(if (isDefaultSmsApp) "Android refused. Try again." else "SMS must be your messaging app to delete messages.") },
            confirmButton = {
                BoldButton(filled = true, onClick = {
                    deleteFailed = 0
                    if (!isDefaultSmsApp) setup.run(SetupStep.ROLE)
                }) { Text(if (isDefaultSmsApp) "Close" else "Choose SMS") }
            },
            dismissButton = if (isDefaultSmsApp) null else ({ QuietButton(onClick = { deleteFailed = 0 }) { Text("Close") } })
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
            // https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/conversationlist/ConversationListFragment.java#handlePin
            onPin = { on -> if (on) handlePin(listOf(c)) else handleUnpin(listOf(c.threadId)) },
            // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/ui/conversationlist/AbstractConversationListActivity.java#onActionBarArchive
            onArchive = { on -> onActionBarArchive(listOf(c), on) },
            onRead = { messages.markRead(c.threadId) },
            // https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/conversationlist/ConversationListFragment.java#handleMarkAsUnread
            onUnread = { handleMarkAsUnread(listOf(c)) },
            onSelect = {
                picked = setOf(c.threadId)
                peeking = null
            },
            // The Trash with its Undo pane, as onActionBarArchive's snackbar (AbstractConversationListActivity.java#onActionBarArchive).
            onDelete = { onActionBarTrash(listOf(c)) },
            onReportSpam = if (canReport(c) && c.threadId !in spamThreads) ({ reporting = c }) else null,
            onNotSpam = if (c.threadId in spamThreads) ({ notSpam(c) }) else null,
            // In Spam: Block asks first, as the selection's Block does; hidden when the number is already blocked.
            onBlock = if (c.threadId in spamThreads && !c.group &&
                c.address.count(Char::isDigit) >= 3 && NumberActions.canBlock(context) && !NumberActions.isBlocked(context, c.address)
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
                    onActionBarBlock(listOf(c))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationLine(
    c: Conversation,
    name: String?,
    /** The newest message's media wait for a tap (a stranger's, Settings asking so): nothing is read or decoded. */
    holdMedia: Boolean,
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
    selected: Boolean? = null,
    /**
     * The conversation shown beside the list on a wide window: its card carries the accent, as the open
     * conversation in Google Messages and Signal on a tablet (ZoneSurface's accent, Modules ui/component).
     */
    open: Boolean = false,
    /** A group's people, for the cluster of their faces. */
    members: List<PinnedFace> = emptyList(),
    /** Where the user's own newest message stands, drawn before the time (Signal's DeliveryStatusView); null when it is not theirs. */
    delivery: Delivery? = null,
    /** The conversation is silenced: a bell after the name (AOSP Messaging's mNotificationBellView). */
    silenced: Boolean = false,
    /** The words of an older message the search found here, shown in place of the last message, [target] in bold. */
    found: String? = null,
    target: String = "",
    /** The card's outer size: a column's width by default, a grid cell's in the grid. */
    modifier: Modifier = Modifier.widthIn(max = LINE_WIDTH).fillMaxWidth()
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // An empty name falls back to the formatted number, else "Unknown" (Dialer's CallLine after AOSP Dialer's
    // CallLogEntryText.java).
    val title = remember(name, c.address) { conversationTitle(name, Numbers.format(context, c.address)) }
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
    Box(modifier.pointerInput(c.threadId, left, right) {
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
                com.yaz.sms.core.sms.SwipeAction.READ -> if (c.unread > 0) AppIcons.DoneAll else AppIcons.MarkUnread
                com.yaz.sms.core.sms.SwipeAction.PIN -> AppIcons.PushPin
                else -> AppIcons.Reply
            }
            val label = when (side) {
                com.yaz.sms.core.sms.SwipeAction.READ -> if (c.unread > 0) "Mark as read" else "Mark as unread"
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
        // The list card of every app: Material 3's extraLarge corner (ShapeTokens.CornerExtraLarge, 28 dp,
        // https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ShapeTokens.kt).
        val shape = MaterialTheme.shapes.extraLarge
        ZoneSurface(
            shape = shape,
            accent = open,
            modifier = Modifier.fillMaxSize().graphicsLayer {
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
            // The row of Dialer's recents (RecentsScreen.kt CallLine, after AOSP Dialer's call log entry) and of
            // Signal's conversation list item (conversation_list_item_view.xml, avatar at the start, the name with
            // the date pinned at the end of the same line, the snippet under it and the unread count at its end):
            // the face, then a column that takes all the width left. First line the name (or the number) and the
            // time (or the Trash's note), second line the last message and the unread count. The lock and the
            // pin sit on the face's bottom corners.
            // The face is centred on the name and message block (Google Messages and iOS Messages centre the
            // avatar on the two lines; Signal anchors it to the name's top, the user asked for centred), the whole
            // row centred in a grid cell made taller by a neighbour's thumbnail.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)) {
                Box(Modifier.padding(start = 6.dp)) {
                    // Taken in the multi select: the check stands where the face was.
                    if (selected == true) Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(CARD_FACE).background(MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        Icon(AppIcons.Done, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    else FaceAvatar(name?.takeIf { it.isNotBlank() }, photo, look, members, CARD_FACE)
                    if (encrypted) FaceMark(AppIcons.Lock, "End-to-end encrypted", Modifier.align(Alignment.BottomStart).offset(x = (-2).dp, y = 2.dp))
                    if (pinned) FaceMark(AppIcons.PushPin, "Pinned", Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    // The name takes what the time leaves and is cut with an ellipsis; the time is never cut and
                    // never wraps. The time is centred on the name's line (Signal's conversation_list_item_view.xml:
                    // conversation_list_item_date constrained top and bottom to the name,
                    // https://github.com/signalapp/Signal-Android/blob/main/app/src/main/res/layout/conversation_list_item_view.xml).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The name, then the bell right after it (AOSP Messaging's mNotificationBellView, shown for a
                        // conversation whose notifications are off, ConversationListItemView.java,
                        // https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/ui/conversationlist/ConversationListItemView.java).
                        // The bell is measured first, so a long name is cut before it, never the bell.
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            // The name starts at the face's side whatever its script (Dialer's CallLine, Compose's
                            // TextDirection): an Arabic name in a left-to-right app would end up against the time.
                            style = MaterialTheme.typography.titleMedium.copy(
                                textDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr
                            ),
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            // A number is cut in its middle, so its last digits, the ones that tell it apart, stay
                            // (Compose's TextOverflow.MiddleEllipsis, single line).
                            overflow = if (name.isNullOrBlank()) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (silenced) {
                            Spacer(Modifier.width(6.dp))
                            Icon(AppIcons.NotificationsOff, contentDescription = "Silenced", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                        }
                        Spacer(Modifier.width(8.dp))
                        // The user's own newest message: its tick (or the error mark) before the time, the icons and
                        // tints of the thread's MessageFooter; the words are for TalkBack.
                        if (delivery != null) {
                            Icon(
                                when (delivery) {
                                    Delivery.Sending -> AppIcons.Schedule
                                    Delivery.Sent -> AppIcons.Done
                                    Delivery.Delivered, Delivery.Read -> AppIcons.DoneAll
                                    Delivery.Failed -> AppIcons.Error
                                },
                                contentDescription = delivery.word,
                                tint = when (delivery) {
                                    Delivery.Failed -> MaterialTheme.colorScheme.error
                                    Delivery.Read -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            note ?: timeLabel(context, c.date),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    // The last message straight in the card; its end stops before the unread count, which sits at
                    // the end of the message's first line, right under the time (Signal's
                    // conversation_list_item_view.xml: conversation_list_item_status_container at the parent's end,
                    // below the date, level with the summary's first line,
                    // https://github.com/signalapp/Signal-Android/blob/main/app/src/main/res/layout/conversation_list_item_view.xml).
                    // The rule: the badge stands in a box as tall as one line of the message's style (bodyMedium, as
                    // LastMessageBubble), at the top of the row, its centre on that first line's centre. A subject or a
                    // media lead above the text keeps the rule: the badge meets the block's first line.
                    Row(verticalAlignment = Alignment.Top) {
                        if (found != null) Text(
                            remember(found, target) { foundSnippet(found, target) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).heightIn(min = 20.dp)
                        ) else LastMessageBubble(
                            c, unread, who = if (c.fromMe) "you" else title, hold = holdMedia,
                            time = note ?: timeLabel(context, c.date),
                            modifier = Modifier.weight(1f)
                        )
                        if (unread) {
                            Spacer(Modifier.width(8.dp))
                            val line = with(androidx.compose.ui.platform.LocalDensity.current) {
                                MaterialTheme.typography.bodyMedium.lineHeight.toDp()
                            }
                            Box(Modifier.height(line), contentAlignment = Alignment.Center) { UnreadBadge(c.unread) }
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * A small mark on a face's bottom corner (the lock of an encrypted conversation, the pin): a round of the card's
 * colour so the mark reads over a photo, the icon in the accent. Taken from HomeParts' count badge on the pinned
 * faces (feature/conversations/HomeParts.kt, a round aligned to the face's corner and offset). No outside app's
 * code for such marks was found (GitHub code search of Element X's avatar components and Signal's conversation
 * list, 2026-10-28). TalkBack reads the description with the card.
 */
@Composable
internal fun FaceMark(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(18.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
    }
}

/** The face of a person card. */
private val CARD_FACE = 48.dp

/**
 * The text of a conversation's title: its name, else the number as formatted, else "Unknown". A blank name counts
 * as empty. Dialer's CallLine (RecentsScreen.kt: `name ?: shown`, name taken only when not blank, shown =
 * formatted number `ifBlank { "Unknown" }`) after AOSP Dialer's CallLogEntryText.java.
 */
internal fun conversationTitle(name: String?, formatted: String): String =
    name?.takeIf { it.isNotBlank() } ?: formatted.ifBlank { "Unknown" }

/**
 * The unread count's text: the number, "9+" past nine. Signal's ConversationListItem.java sets the indicator's
 * text from the count (setUnreadIndicator, https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/conversationlist/ConversationListItem.java);
 * the cap at nine is this app's badge as before (HomeParts' pinned faces).
 */
/**
 * Signal's unread indicator (conversation_list_item_view.xml): wrap_content, minWidth 18 dp, a round of the
 * accent behind white text, so it grows with the font and "9+" is never clipped. TalkBack reads it as "3 unread".
 */
@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .clearAndSetSemantics { contentDescription = "$count unread" }
    ) {
        Text(
            unreadLabel(count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            softWrap = false
        )
    }
}

internal fun unreadLabel(count: Int): String = if (count > 9) "9+" else count.toString()

/**
 * Where the user's own newest message stands, for the mark before the time; null when the newest message is not
 * theirs, or is a draft. Read as the thread's own bubble does (ThreadScreen: the chat's read mark, then failed,
 * leaving, delivered, else sent), from what readConversations kept of the same row.
 */
internal fun deliveryOf(c: Conversation, chat: com.yaz.sms.core.chat.RichChat): Delivery? = when {
    !c.fromMe || c.draft != null || c.lastId == 0L -> null
    chat.refOf(c.lastMms, c.lastId)?.seen == true -> Delivery.Read
    c.failed -> Delivery.Failed
    c.sending -> Delivery.Sending
    c.delivered -> Delivery.Delivered
    else -> Delivery.Sent
}

/**
 * The words of a message that the search found, cut around the first match with an ellipsis where the text goes on,
 * every match in bold. Ported from AOSP Messaging's SearchActivity.TextViewSnippet (setText, onLayout:
 * pattern "\b" + the quoted target, case insensitive; the snippet starts at the first match; each match gets a
 * bold StyleSpan; https://android.googlesource.com/platform/packages/apps/Mms/+/refs/heads/main/src/com/android/mms/ui/SearchActivity.java).
 * Changed for the card: the window is a fixed lead before the match instead of one grown to the width of the view
 * (the card's own two lines cut the end), and each word of the query is lit, as the provider's search matches words.
 * The text is first cleared of the chat's markup, effects and reaction wording, as the card's last message is.
 */
internal fun foundSnippet(body: String, target: String): androidx.compose.ui.text.AnnotatedString {
    val text = com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(body))).replace('\n', ' ')
    val words = target.split(' ', '\t').filter { it.isNotEmpty() }
    if (words.isEmpty()) return androidx.compose.ui.text.AnnotatedString(text)
    val pattern = Regex(words.joinToString("|") { (if (it.first().isLetterOrDigit()) "\\b" else "") + Regex.escape(it) }, RegexOption.IGNORE_CASE)
    val first = pattern.find(text)?.range?.first ?: 0
    val from = if (first > LEAD) first - LEAD else 0
    val shown = (if (from > 0) "…" else "") + text.substring(from)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(shown)
        pattern.findAll(shown).forEach { m ->
            addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
        }
    }
}

/** How much of the message stands before the first match on the card. */
private const val LEAD = 24

/**
 * The conversation's last message straight in the card, no bubble (Signal's conversation_list_item_view.xml: the
 * snippet is plain text, two lines, under the name). A message the user sent starts with "You:" (Signal's list
 * item prefixes the sender), a draft keeps "Draft:" (bold), a message that failed keeps "Not sent:" in the error
 * colour, a picture message's subject is the first line (AOSP Messaging's ConversationListItemView.setSubject).
 * Two lines at most, then an ellipsis. Not clickable: the card is the tap target.
 */
@Composable
private fun LastMessageBubble(c: Conversation, unread: Boolean, who: String, hold: Boolean, time: String, modifier: Modifier = Modifier) {
    // What the newest message is besides words (LastMessagePreview.kt): a reaction, a picture or a film, a sound,
    // a card, a place, a file, a link alone. Null for words and for a draft.
    val lead = remember(c) { Lead.of(c) }
    if (lead is Lead.Reaction) {
        ReactionBubble(c, lead, who, unread, time, modifier)
        return
    }
    val said = lead?.caption(c) ?: c.snippet
    val words = com.yaz.sms.core.sms.Markup.plain(com.yaz.sms.core.sms.Effects.plain(com.yaz.sms.core.sms.SmsReactions.plain(said)))
    val draft = c.draft?.let { com.yaz.sms.core.sms.Markup.plain(it) }
    val subject = c.subject?.takeIf { c.draft == null }
    // A message of a subject alone has no snippet line.
    // A message the user sent starts with "You:" (a draft with "Draft:", a failure with "Not sent:").
    val sent = c.fromMe && !c.failed && draft == null
    val text = when {
        draft != null -> androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Draft:") }
            append(" ")
            append(draft)
        }
        said.isEmpty() -> null
        // With a lead, "Not sent:" stands on a line of its own above it.
        c.failed && lead == null -> androidx.compose.ui.text.AnnotatedString("Not sent: $words")
        sent -> androidx.compose.ui.text.AnnotatedString("You: $words")
        else -> androidx.compose.ui.text.AnnotatedString(words)
    }
    val ink = when {
        c.failed -> MaterialTheme.colorScheme.error
        unread -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val weight = if (unread) FontWeight.Medium else FontWeight.Normal
    // At least one line high, for a message with nothing to say.
    Column(modifier.heightIn(min = 20.dp)) {
        if (subject != null) Text(
            "Subject: $subject",
            style = MaterialTheme.typography.bodyMedium,
            color = ink,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (lead != null) {
            if (c.failed) Text("Not sent:", style = MaterialTheme.typography.bodyMedium, color = ink, fontWeight = FontWeight.Bold)
            LeadView(lead, who, hold, ink, weight)
        }
        if (text != null) Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ink,
            fontWeight = weight,
            modifier = Modifier.padding(top = if (lead != null) 4.dp else 0.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The conversations laid out in [columns] cells of equal width, each row as tall as its tallest card, as
 * SettingsScreen's TileGrid does it (equal cells, a row of IntrinsicSize.Min whose cells fill its height; a
 * last row that is not full is centred).
 */
@Composable
private fun CardRow(cards: List<Conversation>, cell: androidx.compose.ui.unit.Dp, gap: androidx.compose.ui.unit.Dp, modifier: Modifier, card: @Composable (Conversation, Modifier) -> Unit) {
    Row(
        modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    ) {
        cards.forEach { c -> androidx.compose.runtime.key(c.threadId) { card(c, Modifier.width(cell).fillMaxHeight()) } }
    }
}

/** The date of each conversation's newest message when it was made ready. */
private val warmed = HashMap<Long, Long>()

/** What the Undo pane says, and what its button does. */
internal class UndoOffer(val text: String, val undo: () -> Any?)

/**
 * The words of the list when a filter leaves it empty: Element X's RoomListFiltersEmptyStateResources, the title
 * and the subtitle, built from the filters selected. Here only Unread is a filter of the inbox (the Archive, the
 * Trash and Spam are places with their own empty words).
 * Not ported: RoomListFilter.People, RoomListFilter.Rooms, RoomListFilter.Favourites, RoomListFilter.Invites (filters
 * this list does not have), StringRes (the words are written here), isSpaceFilterSelected's spaces (no spaces here).
 */
internal data class FiltersEmptyState(val title: String, val subtitle: String) {
    companion object {
        // https://raw.githubusercontent.com/element-hq/element-x-android/develop/features/home/impl/src/main/kotlin/io/element/android/features/home/impl/filters/RoomListFiltersEmptyStateResources.kt#fromSelectedFilters
        fun fromSelectedFilters(selectedFilters: List<Filter>, isSpaceFilterSelected: Boolean = false): FiltersEmptyState? {
            return when {
                isSpaceFilterSelected -> FiltersEmptyState(
                    title = "No conversations found",
                    subtitle = "Remove a filter to see more."
                )
                selectedFilters.isEmpty() -> null
                selectedFilters.size == 1 -> {
                    when (selectedFilters.first()) {
                        Filter.UNREAD -> FiltersEmptyState(
                            title = "No unread conversations",
                            subtitle = "Everything is read."
                        )
                        else -> null
                    }
                }
                else -> FiltersEmptyState(
                    title = "No conversations found",
                    subtitle = "Remove a filter to see more."
                )
            }
        }
    }
}

/**
 * Signal's ThreadTable.setForcedUnread for Android's store, which has no such flag: the newest message received in
 * each thread is set unread (read = 0), so the list counts one unread message and the dot shows. True when every
 * thread could be marked; false when one has nothing received, or the store refused (SMS is not the default app).
 * Shaped like Messages.markRead (this app's data/sms/Messages.kt, the same two tables written the same way).
 * Not ported: markRead (the opposite write: read and seen to 1 for the whole thread), SignalDatabase.threads(), the
 * forced-unread column (Signal's own database).
 */
// https://raw.githubusercontent.com/213YaZ786/SMS/main/app/src/main/java/com/yaz/sms/data/sms/Messages.kt#markRead
internal fun setForcedUnread(context: android.content.Context, ids: Collection<Long>): Boolean {
    var marked = true
    for (threadId in ids) {
        val thread = arrayOf(threadId.toString())
        val done = runCatching {
            val smsId = context.contentResolver.query(
                android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(android.provider.Telephony.Sms._ID, android.provider.Telephony.Sms.DATE),
                "${android.provider.Telephony.Sms.THREAD_ID} = ?", thread, "${android.provider.Telephony.Sms.DATE} DESC LIMIT 1"
            )?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
            // A picture message keeps its date in seconds, an SMS in milliseconds.
            val mmsId = context.contentResolver.query(
                android.provider.Telephony.Mms.Inbox.CONTENT_URI,
                arrayOf(android.provider.Telephony.Mms._ID, android.provider.Telephony.Mms.DATE),
                "${android.provider.Telephony.Mms.THREAD_ID} = ?", thread, "${android.provider.Telephony.Mms.DATE} DESC LIMIT 1"
            )?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) * 1000 else null }
            val row = when {
                smsId == null && mmsId == null -> return@runCatching false
                mmsId == null || (smsId != null && smsId.second >= mmsId.second) ->
                    android.content.ContentUris.withAppendedId(android.provider.Telephony.Sms.CONTENT_URI, smsId!!.first)
                else -> android.content.ContentUris.withAppendedId(android.provider.Telephony.Mms.CONTENT_URI, mmsId.first)
            }
            context.contentResolver.update(
                row,
                android.content.ContentValues().apply { put(android.provider.Telephony.Sms.READ, 0) },
                null, null
            ) > 0
        }.getOrDefault(false)
        if (!done) marked = false
    }
    return marked
}

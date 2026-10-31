package com.yaz.sms.feature.thread

import com.yaz.sms.ui.glass.glassZone

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import com.yaz.sms.ui.component.BoldButton
import com.yaz.sms.ui.component.QuietButton
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.yaz.sms.core.sms.Codes
import com.yaz.sms.core.sms.MessageNotifier
import com.yaz.sms.core.sms.SmsSender
import com.yaz.sms.core.mms.MmsTransport
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.data.settings.SettingsStore
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import com.yaz.sms.core.chat.RichRef
import com.yaz.sms.core.translate.TranslationResult
import com.yaz.sms.core.translate.Translator
import java.util.Locale
import com.yaz.sms.data.contacts.PhoneBook
import com.yaz.sms.data.contacts.PhoneIndex
import com.yaz.sms.data.sms.Box as MessageBox
import com.yaz.sms.data.sms.Message
import com.yaz.sms.data.sms.Messages
import com.yaz.sms.feature.conversations.dayLabel
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
fun ThreadScreen(threadId: Long?, address: String, draft: String, onBack: () -> Unit, bubble: Boolean = false) {
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
            ThreadContent(threadId, address, draft, onBack, base, picture, doodles = chosen == DOODLE, bubble = bubble, onThread = { shownThread = it })
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
    doodles: Boolean,
    bubble: Boolean,
    onThread: (Long?) -> Unit
) {
    val context = LocalContext.current
    val messages: Messages = koinInject()
    val book: PhoneBook = koinInject()
    val scope = rememberCoroutineScope()
    val contacts by book.entries.collectAsState()
    // Read once, then kept up to date by the book itself: not again at each conversation opened.
    LaunchedEffect(Unit) { if (book.canRead() && contacts.isEmpty()) book.refresh() }
    val changes by messages.changes.collectAsState()
    LaunchedEffect(Unit) { messages.watch() }
    val chat: RichChat = koinInject()
    // Media shared from another app, waiting for the conversation the user picks.
    val openRequests: com.yaz.sms.core.sms.OpenRequests = koinInject()
    val sharedIn by openRequests.sharedMedia.collectAsState()
    val sharedTo by openRequests.sharedTo.collectAsState()
    val chatChanges by chat.changes.collectAsState()
    val links by chat.links.collectAsState()
    val refs by chat.refs.collectAsState()
    // The starred messages, by uid (Zulip's starredMessages set, see StarStore).
    val starStore: com.yaz.sms.data.settings.StarStore = koinInject()
    val stars by starStore.stars.collectAsState()
    // The reactions the user sends most come first in the long press bar (ReactionStore, Element Web's recent emoji).
    val reactionStore: com.yaz.sms.data.settings.ReactionStore = koinInject()
    val reactionUses by reactionStore.uses.collectAsState()
    val reactionBar = remember(reactionUses) { com.yaz.sms.data.settings.ReactionHistory.bar(reactionUses) }
    // The "+" of the bar waits here for the emoji, whichever message asked: what to do with it.
    var emojiPick by remember { mutableStateOf<((String) -> Unit)?>(null) }

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
    // A conversation opened before shows at once as it was left, then is read again.
    var list by remember { mutableStateOf(threadId?.let { opened.get(it)?.first } ?: emptyList()) }
    // Read only while the conversation is really in front: in the
    // background its new messages stay unread and notified.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val inFront by lifecycle.currentStateFlow.collectAsState()
    val shown = inFront.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    // The newest messages first; going back in the conversation reads more.
    var window by remember { mutableIntStateOf(Messages.PAGE) }
    val kept = remember { threadId?.let { opened.get(it) } }
    var rows by remember { mutableStateOf(kept?.second ?: emptyList()) }
    // The messages the rows on screen were made from.
    var rowsOfList by remember { mutableStateOf(kept?.first) }
    // The encrypted chat's events concern this conversation only when it goes over the chat.
    val overChat = remember(links, people) { people.size > 1 || people.any { '@' in it || chat.linkFor(it) != null } }
    val chatKey = if (overChat) chatChanges else 0
    // True once the conversation has been read as it is now: what was there when it opened is not
    // news, only what comes after it drops in (Google Messages and iOS Messages show a conversation
    // at its newest message at once; only a message arriving while it is open animates in).
    var read by remember { mutableStateOf(false) }
    LaunchedEffect(thread, changes, chatKey, window) {
        val t = thread ?: return@LaunchedEffect
        // Opened for the first time: the newest few first, on screen at once, then the page.
        if (list.isEmpty() && window == Messages.PAGE) {
            val first = messages.thread(t, FIRST)
            list = first
            // On screen before the page is read: its rows are made first.
            if (first.isNotEmpty()) androidx.compose.runtime.snapshotFlow { rowsOfList }.first { it === first }
        }
        list = messages.thread(t, window)
        read = true
    }
    // The messages unread when the conversation opened, kept before they are marked read below: the divider
    // "N unread messages" stays until the conversation is left. The read flag is AOSP Messaging's
    // ConversationMessageData.getIsRead (MessageColumns.READ,
    // https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/datamodel/data/ConversationMessageData.java);
    // Signal Android takes the unread state once, when the conversation loads, before it marks anything read
    // (ConversationFragment: conversationItemDecorations.setUnreadState(unreadCount, firstUnreadId),
    // https://github.com/signalapp/Signal-Android app/src/main/java/org/thoughtcrime/securesms/conversation/v2/ConversationFragment.kt).
    var unreadUids by remember(thread) { mutableStateOf<Set<String>>(emptySet()) }
    var unreadChecked by remember(thread) { mutableStateOf(false) }
    // True once the list may show: nothing unread, or opened at the divider (else a jump would show).
    var unreadSettled by remember(thread) { mutableStateOf(false) }
    LaunchedEffect(thread) {
        delay(1500)
        unreadSettled = true
    }
    LaunchedEffect(thread, list, shown) {
        val t = thread ?: return@LaunchedEffect
        if (!shown || list.isEmpty()) return@LaunchedEffect
        if (!unreadChecked) {
            // Read again, not from what the list holds: a conversation reopened from memory may be out of date.
            val fresh = messages.thread(t, Messages.PAGE)
            unreadUids = fresh.filter { it.box == MessageBox.RECEIVED && !it.read }.map { it.uid }.toSet()
            unreadChecked = true
            if (unreadUids.isEmpty()) unreadSettled = true
        }
        if (list.any { it.box == MessageBox.RECEIVED && !it.read }) messages.markRead(t)
        // In its bubble the notification holds the bubble: it is only quieted.
        if (bubble) MessageNotifier(context).settle(t) else MessageNotifier(context).cancel(t)
    }
    val index = remember(contacts, com.yaz.sms.core.dial.PrivateNames.version.intValue) { PhoneIndex(contacts, Numbers.countryIso(context)) }
    val to = people.firstOrNull().orEmpty()
    val group = people.size > 1
    // A group's people still in it, and whether the user left (the chat's group follows its members).
    val groupsNow by chat.groups.collectAsState()
    val members = remember(groupsNow, people) { if (group) chat.membersOf(people) else people }
    val left = remember(groupsNow, people) { group && chat.groupOf(people)?.left == true }
    val entry = remember(index, to) { index.find(T9.clean(to)) }
    val prefs by koinInject<SettingsStore>().settings.collectAsState()
    val title = if (group) thread?.let { prefs.groupNames[it] } ?: members.joinToString(", ") { index.find(T9.clean(it))?.name?.substringBefore(' ') ?: Numbers.format(context, it) }
    else if ('@' in to) chat.nameFor(to) ?: to.substringBefore('@')
    else entry?.name ?: Numbers.format(context, to)
    val canCall = !group && to.count(Char::isDigit) >= 3
    // With one number: the rich chat when it has SMS too, else SMS.
    val linked = remember(links, to) { if (group || to.isBlank()) null else chat.linkFor(to) }
    // A group goes over the encrypted chat when each of its members does.
    val encrypted = remember(links, people) { linked != null || (group && chat.groupReady(people)) }
    LaunchedEffect(encrypted, chatChanges, shown) { if (encrypted && shown) chat.markSeen(people) }
    // Live location (Delta Chat Android's location streaming): ours, while shared, and what the others share.
    val locator: com.yaz.sms.core.chat.LiveLocator = koinInject()
    val liveEnd = if (encrypted && !left) chat.liveChat(people)?.let { prefs.liveUntil[it] } else null
    val locationChanges by chat.locations.collectAsState()
    val chatReady by chat.ready.collectAsState()
    val liveSpots by androidx.compose.runtime.produceState(emptyList<RichChat.LiveSpot>(), encrypted, people, locationChanges, chatReady) {
        value = if (encrypted && chatReady) chat.liveSpots(people) else emptyList()
    }
    // "Updated N min ago" and the end of the sharing, read again every half minute while the screen is in front.
    var liveNow by remember { mutableStateOf(System.currentTimeMillis()) }
    val liveShown = liveEnd != null || liveSpots.isNotEmpty()
    LaunchedEffect(shown, liveShown, locationChanges) {
        liveNow = System.currentTimeMillis()
        while (shown && liveShown) {
            delay(30_000)
            liveNow = System.currentTimeMillis()
        }
    }
    val sharingLive = liveEnd != null && liveEnd > liveNow
    // Mentions in a group (Signal's): the others offered when @ is typed; "@Name" is the mention, in the chat and over SMS.
    val mentionSetting = remember { com.yaz.sms.core.chat.MentionSetting.of(context) }
    val mentionsOff by mentionSetting.off.collectAsState()
    val chatNames by androidx.compose.runtime.produceState<Map<String, String>>(emptyMap(), members, encrypted, group) {
        value = if (group && encrypted) chat.chatNames(members) else emptyMap()
    }
    val myNames by androidx.compose.runtime.produceState<List<String>>(emptyList(), group) {
        value = if (group) withContext(Dispatchers.IO) { com.yaz.sms.core.chat.MentionSetting.myNames(context) } else emptyList()
    }
    // The name each member goes by on this phone, and the number it stands for.
    val localNames = remember(members, index, group) {
        if (!group) emptyMap() else members.associateBy { p -> index.find(T9.clean(p))?.name ?: Numbers.format(context, p) }.mapKeys { it.key.trim() }.filterKeys { it.isNotEmpty() }
    }
    val mentionWords = remember(localNames, chatNames, myNames) { (localNames.keys + chatNames.values + myNames).distinct() }
    /** The words as the others must read them: each name given here replaced by the one the member chose in the chat. */
    fun mentionsWire(text: String): String? {
        if (!group || !encrypted || com.yaz.sms.core.chat.Mentions.STARTER !in text) return null
        val found = com.yaz.sms.core.chat.Mentions.find(text, localNames.keys)
        if (found.isEmpty()) return null
        return com.yaz.sms.core.chat.Mentions.replace(text, found) { f ->
            val phone = localNames.entries.firstOrNull { it.key.equals(f.name, ignoreCase = true) }?.value
            com.yaz.sms.core.chat.Mentions.STARTER + (phone?.let { chatNames[it] } ?: f.name)
        }.first
    }

    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(0.dp) }
    // Where the composer (and the + options) begins and where the list ends, in the window:
    // the last message keeps the same gap above it, received or sent, options open or not.
    var composerTop by remember { mutableStateOf(Float.NaN) }
    var listBottom by remember { mutableStateOf(Float.NaN) }
    // A message swiped to answer it: shown over the field, sent quoted.
    var quote by remember { mutableStateOf<String?>(null) }
    // One of the user's own chat messages being changed: its chat id and text.
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // How long messages last in the encrypted chat, in seconds; 0 for always.
    var vanish by remember { mutableIntStateOf(0) }
    LaunchedEffect(encrypted, chatChanges) { vanish = if (encrypted) chat.timer(people) else 0 }
    // Messages waiting their few seconds before they go, and text taken back.
    val store: SettingsStore = koinInject()
    val sends: com.yaz.sms.core.sms.PendingSends = koinInject()
    val allWaiting by sends.waiting.collectAsState()
    val waiting = remember(allWaiting, people, thread) {
        val set = people.toSet()
        allWaiting.filter { (thread != null && it.thread == thread) || it.people.toSet() == set }
    }
    var restore by remember { mutableStateOf<String?>(null) }
    // A send refused after the delay gives its text back, even if the screen was away meanwhile.
    val returnedAll by sends.returned.collectAsState()
    LaunchedEffect(returnedAll, people) {
        sends.takeReturned(people).takeIf { it.isNotEmpty() }?.let { restore = it.joinToString("\n") }
    }
    // The draft left last time back into the field, read as AOSP Messaging
    // reads it when its conversation opens (ReadDraftDataAction).
    LaunchedEffect(thread) {
        if (draft.isEmpty()) {
            val t = thread ?: return@LaunchedEffect
            withContext(Dispatchers.IO) { com.yaz.sms.data.sms.Drafts.of(context, t) }?.let { restore = it }
        }
    }
    // A message held back to go at a chosen time.
    var scheduling by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var scheduled by remember { mutableStateOf(com.yaz.sms.core.sms.Timed.scheduled(context)) }
    // Received messages that arrive while the conversation is open drop in: those newer than
    // the newest one read when it opened, so neither the first read nor an older page counts.
    var known by remember { mutableStateOf<Set<String>?>(null) }
    var newestKnown by remember { mutableStateOf(0L) }
    // Sent with an effect: it plays here at once, and the bubble remembers it a while.
    val sentEffects = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<com.yaz.sms.core.sms.Effects.Effect, Long>>() }
    var playing by remember { mutableStateOf<Triple<com.yaz.sms.core.sms.Effects.Effect, String, androidx.compose.ui.geometry.Offset?>?>(null) }
    var fresh by remember { mutableStateOf<Set<String>>(emptySet()) }
    val haptics = rememberHaptics()
    // The tick of a message leaving: only when this conversation's screen is there to feel it.
    val peopleNow by androidx.compose.runtime.rememberUpdatedState(people)
    LaunchedEffect(sends) {
        sends.sent.collect { w -> if (w.people.toSet() == peopleNow.toSet()) haptics.done() }
    }
    LaunchedEffect(list, read) {
        if (!read) return@LaunchedEffect
        val received = list.filter { it.box == MessageBox.RECEIVED }.map { it.uid }.toSet()
        val before = known
        if (before != null) {
            val new = list.filter { it.box == MessageBox.RECEIVED && it.uid !in before && it.date > newestKnown }.map { it.uid }.toSet()
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
        newestKnown = maxOf(newestKnown, list.maxOfOrNull { it.date } ?: 0L)
    }
    // The sending and its countdown belong to the app (PendingSends): leaving the conversation
    // never cancels a message in its delay. Here: what the screen alone does (effects, field, haptic).
    fun queue(text: String, sub: Int, attachments: List<com.yaz.sms.core.mms.Attachment>, quoted: String?, effect: com.yaz.sms.core.sms.Effects.Effect? = null) {
        quote = null
        effect?.let { e ->
            sentEffects[text] = e to System.currentTimeMillis()
            if (e.screen) playing = Triple(e, text, null)
        }
        val seconds = store.current.undoSeconds
        val p = com.yaz.sms.core.sms.PendingSends.Waiting(
            key = System.nanoTime(), text = text, sub = sub, attachments = attachments, quoted = quoted, effect = effect,
            people = people, thread = thread, wire = mentionsWire(text), seconds = seconds,
            dueAt = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
        )
        if (seconds <= 0) sends.sendNow(p) else sends.queue(p)
    }
    /**
     * "Send again x N": the user's own message goes [count] more times, each its own message through the same
     * path as the composer, PendingSends (undo delay, the encrypted chat when the person has it, SMS or MMS
     * otherwise), with the original's attachments (the parts of its picture message). Own idea of the user, no app
     * has it. The copies are spaced by [SEND_AGAIN_GAP] seconds (the pause between two messages of a run, idea only
     * from https://github.com/CodingInfinite/BulkSMSSender and https://github.com/AhsenSaeed/AndroidBulkSmsSender,
     * no licence file: no code taken), and each waits as its own item in PendingSends, so the Cancel of a waiting
     * bubble drops the copies not yet sent (idea of cancelling a run while it goes, same two repositories).
     */
    fun sendAgain(m: Message, words: String, count: Int) {
        val files = m.parts.map { com.yaz.sms.core.mms.Attachment(it.uri, it.contentType) }
        val undo = store.current.undoSeconds
        val now = android.os.SystemClock.elapsedRealtime()
        val base = System.nanoTime()
        repeat(count.coerceIn(1, SEND_AGAIN_MAX)) { i ->
            val seconds = undo + SEND_AGAIN_GAP * i
            val p = com.yaz.sms.core.sms.PendingSends.Waiting(
                key = base + i, text = words, sub = m.subId, attachments = files, quoted = null, effect = null,
                people = people, thread = thread, wire = mentionsWire(words), seconds = seconds,
                dueAt = now + seconds * 1000L
            )
            if (seconds <= 0) sends.sendNow(p) else sends.queue(p)
        }
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
    LaunchedEffect(list) {
        val from = list
        if (from === rowsOfList) return@LaunchedEffect
        rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { rowsOf(from) }
        rowsOfList = from
        thread?.let { opened.put(it, from to rows) }
    }
    val clusters = remember(rows, group) { clustersOf(rows, group) }
    // The divider: the oldest of the messages unread at opening that the rows show (a reaction folded under its
    // message has no row), and how many of them there are.
    val unreadMark = remember(rows, unreadUids) {
        if (unreadUids.isEmpty()) null
        else rows.filterIsInstance<Row.Bubble>().filter { it.message.uid in unreadUids }.let { found ->
            found.firstOrNull()?.let { it.message.uid to found.size }
        }
    }
    // Opened with the divider near the top (Signal Android's UnreadDividerInstrumentationTest: "opens scrolled to
    // oldest unread" rather than at the bottom, https://github.com/signalapp/Signal-Android
    // app/src/androidTest/java/org/thoughtcrime/securesms/conversation/v2/UnreadDividerInstrumentationTest.kt).
    // The list is in reverse: the message's row is brought to the bottom edge, then the list is moved back
    // toward the newest by the room left, which stops at the newest message when all the unread ones fit.
    LaunchedEffect(unreadMark, read, rowsOfList === list, composerTop, listBottom, unreadChecked) {
        if (unreadSettled || !unreadChecked || unreadUids.isEmpty()) return@LaunchedEffect
        if (!read || rowsOfList !== list || composerTop.isNaN() || listBottom.isNaN()) return@LaunchedEffect
        val first = unreadMark?.first
        val row = if (first == null) -1 else rows.indexOfFirst { it.key == "m/$first" }
        if (row < 0) {
            unreadSettled = true
            return@LaunchedEffect
        }
        val before = scheduled.count { it.to.toSet() == people.toSet() } + waiting.size
        val key = "m/$first"
        runCatching {
            listState.scrollToItem(before + rows.size - 1 - row)
            var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            var tries = 0
            while (item == null && tries++ < 3) {
                androidx.compose.runtime.withFrameNanos { }
                item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            }
            val info = listState.layoutInfo
            if (item != null) {
                val room = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding - item.size
                if (room > 0) listState.scrollBy(-room.toFloat())
            }
        }
        unreadSettled = true
    }
    // The day of the topmost message on screen floats under the header while the list moves, and goes
    // 1.5 s after it stops; never at rest at the newest message.
    val listMoving = listState.isScrollInProgress
    val awayFromNewest by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    // Telegram's updateMessagesVisiblePart
    // (https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java):
    // the floating date reads the topmost cell below clipTop, the line under the header where it sits (a view whose
    // bottom <= clipTop is skipped). The list is in reverse: an item's offset counts from the bottom of the content,
    // so its top in the list's own coordinates is viewportEndOffset - offset - size (LazyListLayoutInfo,
    // androidx LazyListMeasure.kt / LazyListMeasuredItem.kt: placed at mainAxisLayoutSize - offset - size), and
    // clipTop is afterContentPadding, the list's top padding (padding top + 8 dp).
    val dayOnTop by remember(rows, scheduled, waiting, people) {
        derivedStateOf {
            val before = scheduled.count { it.to.toSet() == people.toSet() } + waiting.size
            val zone = ZoneId.systemDefault()
            var label = ""
            val info = listState.layoutInfo
            for (item in info.visibleItemsInfo.asReversed()) {
                if (info.viewportEndOffset - item.offset <= info.afterContentPadding) continue
                val row = rows.getOrNull(rows.size - 1 - (item.index - before)) ?: continue
                label = when (row) {
                    is Row.Day -> row.label
                    is Row.Bubble -> dayLabel(Instant.ofEpochMilli(row.message.date).atZone(zone).toLocalDate())
                }
                break
            }
            label
        }
    }
    var dayShown by remember { mutableStateOf(false) }
    LaunchedEffect(listMoving) {
        if (listMoving && unreadSettled) dayShown = true
        else {
            delay(1500)
            dayShown = false
        }
    }
    // The floating day is on screen, its exit included: the in-flow heading it replaces stays hidden until it is gone,
    // so the same word is never drawn twice. Read in the rows' layer block only, never in composition.
    var dayHeld by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { dayShown && awayFromNewest }.collectLatest { floats ->
            if (floats) dayHeld = true
            else {
                delay(400)
                dayHeld = false
            }
        }
    }
    // Telegram's minDateChild (updateMessagesVisiblePart, ChatActivity.java): among the day headings whose bottom is
    // below clipTop, the topmost (smallest bottom). Its top, in the list's coordinates, drives the hiding of the
    // heading and the push of the floating day; read from layer blocks only (no recomposition per frame).
    val dayLine by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            var line: DayLine? = null
            // Ascending index runs from the bottom up: the last heading kept is the topmost.
            for (item in info.visibleItemsInfo) {
                val top = info.viewportEndOffset - item.offset - item.size
                if (top + item.size <= info.afterContentPadding) continue
                if ((item.key as? String)?.startsWith("day/") == true) line = DayLine(item.key, top)
            }
            line
        }
    }
    // A message added at the bottom while reading there comes into sight, not under the field. Several
    // added at once (Send again ×N) push the row in sight up by as many: they count in "reading there".
    var waitingBefore by remember { mutableStateOf(0) }
    LaunchedEffect(rows.lastOrNull()?.key, waiting.size) {
        val added = (waiting.size - waitingBefore).coerceAtLeast(0)
        waitingBefore = waiting.size
        if (listState.firstVisibleItemIndex <= 2 + added) listState.animateScrollToItem(0)
    }
    // The person's page, opened from their face: the calls, the tools, what was shared.
    // Held by OpenRequests, so a turn of the screen or a move between one pane and two keeps it open.
    val requests: com.yaz.sms.core.sms.OpenRequests = koinInject()
    val personOf by requests.personOf.collectAsState()
    val personOpen = personOf == address
    val showPerson: (Boolean) -> Unit = { on -> requests.personOf.value = if (on) address else null }
    // Another conversation opened (a notification) forgets the page left open in the last one.
    LaunchedEffect(address) { if (requests.personOf.value != address) requests.personOf.value = null }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var at by remember { mutableStateOf(0) }
    var sharedOpen by remember { mutableStateOf(false) }
    var silenceOpen by remember { mutableStateOf(false) }
    var choosingVanish by remember { mutableStateOf(false) }
    var encryptionOpen by remember { mutableStateOf(false) }
    // Asked from the Contacts app's "verified" mark: the keys at once.
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
        queue(com.yaz.sms.core.sms.Polls.write(question, options), android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID, emptyList(), null)
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
    // Opened at a starred message: the conversation scrolls to it once, the way Zulip opens a
    // conversation anchored at the message tapped in its starred list
    // (https://github.com/zulip/zulip-flutter/blob/main/lib/widgets/message_list.dart,
    // tapOpensConversation / initAnchorMessageId). Further back than the page, it reads further.
    val showUid by requests.showMessage.collectAsState()
    var starredOpen by remember { mutableStateOf(false) }
    // The message a sister app asked for is lit for a moment once reached, as Signal pulses a message jumped to
    // (ConversationAdapter.pulseAtPosition marks it for one bind; here a wash of the accent that fades).
    var lit by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showUid, rows, thread) {
        val uid = showUid ?: return@LaunchedEffect
        // A starred message of this conversation, or the one a sister app asked for here (SHOW_MESSAGE, checked in MainActivity).
        val asked = requests.showIn.value == thread
        if (stars[uid] != thread && !asked) return@LaunchedEffect
        val row = rows.indexOfFirst { it.key == "m/$uid" }
        if (row < 0) {
            if (window < 5000) window = 5000
            return@LaunchedEffect
        }
        val before = scheduled.count { it.to.toSet() == people.toSet() } + waiting.size
        runCatching { listState.animateScrollToItem(before + rows.size - 1 - row) }
        requests.showMessage.value = null
        if (asked) {
            requests.showIn.value = null
            lit = uid
        }
    }
    LaunchedEffect(lit) {
        if (lit != null) {
            delay(1800)
            lit = null
        }
    }
    // A request for another conversation (still pending while this one leaves) is not this screen's to clear.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (requests.showIn.value == null) requests.showMessage.value = null } }
    if (starredOpen) StarredPage(
        thread = thread,
        titleOf = { title },
        onOpen = { m ->
            starredOpen = false
            searching = false
            query = ""
            requests.showMessage.value = m.uid
        },
        onClose = { starredOpen = false }
    )
    if (silenceOpen && thread != null) SilenceDialog(silencedUntil, onPick = { until ->
        val t = thread ?: return@SilenceDialog
        store.update { s -> s.copy(silenced = if (until == null) s.silenced - t else s.silenced.filterValues { it > System.currentTimeMillis() } + (t to until)) }
        if (until != null) com.yaz.sms.core.sms.MessageNotifier(context).cancel(t)
    }) { silenceOpen = false }
    if (choosingVanish && encrypted) VanishDialog(vanish, onPick = { seconds -> scope.launch { if (chat.setTimer(people, seconds)) vanish = seconds } }) { choosingVanish = false }
    if (encryptionOpen) EncryptionDialog(to) { encryptionOpen = false }
    // The group's picture as the chat has it, read when its page is open and after any change of the chat.
    val groupPicture by androidx.compose.runtime.produceState<String?>(null, personOpen, group, encrypted, chatChanges, groupsNow) {
        value = if (personOpen && group && encrypted) chat.groupPicture(people) else null
    }
    if (personOpen) PersonPage(
        name = if (group) title else entry?.name ?: Numbers.format(context, to),
        number = if (group) null else Numbers.format(context, to),
        address = to,
        photo = entry?.photo,
        contactId = entry?.contactId,
        group = group,
        encrypted = encrypted,
        messages = list,
        stranger = { m -> index.find(T9.clean(m.address)) == null },
        holdMedia = { m -> !prefs.showStrangerMedia && m.box == MessageBox.RECEIVED && index.find(T9.clean(m.address)) == null },
        calls = calls,
        onBackground = if (thread != null && appLook != null) ({ backgroundOpen = true }) else null,
        onEncryption = { encryptionOpen = true },
        onStarred = if (thread != null) ({ showPerson(false); starredOpen = true }) else null,
        onName = { name ->
            thread?.let { t ->
                store.update { s -> s.copy(groupNames = if (name.isBlank()) s.groupNames - t else s.groupNames + (t to name.trim())) }
                if (encrypted && name.isNotBlank()) scope.launch { chat.nameGroup(people, name.trim()) }
            }
        },
        groupTools = if (group) GroupTools(
            members = members.map { p ->
                val e = index.find(T9.clean(p))
                GroupMember(p, e?.name ?: Numbers.format(context, p), e?.photo)
            },
            chat = encrypted,
            left = left,
            picture = groupPicture,
            mentionsOn = thread?.let { it !in mentionsOff } ?: true,
            onMentions = { on -> thread?.let { mentionSetting.set(it, on) } },
            onPicture = { jpeg -> scope.launch { if (chat.setGroupPicture(people, jpeg)) haptics.done() else haptics.reject() } },
            onRemove = { who -> scope.launch { if (chat.removeMember(people, who.phone)) haptics.done() else haptics.reject() } },
            onLeave = { scope.launch { if (chat.leaveGroup(people)) haptics.done() else haptics.reject() } }
        ) else null,
        onClose = { showPerson(false) }
    )
    LaunchedEffect(personOpen) { if (personOpen && window < 5000) window = 5000 }
    if (sharedOpen) {
        // Everything the conversation holds, not only what the screen shows.
        LaunchedEffect(Unit) { if (window < 5000) window = 5000 }
        SharedPage(if (group) title else entry?.name ?: Numbers.format(context, to), list, stranger = { m -> index.find(T9.clean(m.address)) == null },
            holdMedia = { m -> !prefs.showStrangerMedia && m.box == MessageBox.RECEIVED && index.find(T9.clean(m.address)) == null }) { sharedOpen = false }
    }
    // The keyboard lifts the conversation with the field, the message being answered kept in sight.
    val keyboard = WindowInsets.ime.exclude(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
    FloatingFrame(
        bottom = composerHeight + 8.dp + keyboard,
        // The list is laid out from the bottom: a drag on the header scrolls it the way a drag on it does.
        scroll = listState,
        reverseLayout = true,
        // The bubbles pass whole under the round buttons to the top of the screen (the user, 2026-10-31), as iOS's Messages.
        softEdge = false,
        top = {
            // Back, the person's face and name, and the calls one tap away on the right.
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Back, the person's face and name at the centre (their page a tap away), the green call.
            // All on one line: the name's pill at the centre of the room between back and the calls.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
            ) {
                FloatingAction(AppIcons.ArrowBack, "Back", onBack)
                Spacer(Modifier.weight(1f))
                // The face alone, no name: a round tap target the size of the other round actions, the person's page
                // behind it. The face is the list's (FaceAvatar: photo, look, a group's members in one circle, the
                // default person icon without a picture) with its lock mark (FaceMark). After AOSP Messaging's
                // ContactIconView, the contact's round icon that a tap opens onto the contact:
                // https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/ui/ContactIconView.java
                // and the list's FaceAvatar / FaceMark (feature/conversations/GroupFace.kt, ConversationsScreen.kt).
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(com.yaz.sms.ui.component.TopActionSize)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClickLabel = "About them"
                        ) {
                            haptics.tick()
                            showPerson(true)
                        }
                        .semantics { contentDescription = "$title, details" }
                ) {
                    val faceMembers = if (group) members.take(4).map { p ->
                        val e = index.find(T9.clean(p))
                        com.yaz.sms.feature.conversations.PinnedFace(e?.name ?: "", e?.photo, e?.look)
                    } else emptyList()
                    com.yaz.sms.feature.conversations.FaceAvatar(
                        if (group) title else entry?.name?.takeIf { it.isNotBlank() },
                        if (group) null else entry?.photo,
                        if (group) null else entry?.look,
                        faceMembers,
                        com.yaz.sms.ui.component.TopActionSize
                    )
                    if (encrypted) com.yaz.sms.feature.conversations.FaceMark(
                        AppIcons.Lock, "End-to-end encrypted",
                        Modifier.align(Alignment.BottomStart).offset(x = (-2).dp, y = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                CallButtons(
                    calls,
                    onAddContact = if (!group && entry == null && to.count(Char::isDigit) >= 3) ({ NumberActions.addContact(context, to) }) else null,
                    onSearch = { searching = true },
                    silenced = silencedUntil != null,
                    onSilence = if (thread != null) ({ silenceOpen = true }) else null,
                    vanish = if (encrypted) vanish else null,
                    onVanish = { choosingVanish = true }
                )
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
          // The "+" of the reaction bar: with our Keyboard as the phone's keyboard, it opens on its emoji page and
          // the first emoji tapped is the reaction; else Android's own emoji picker (EmojiPickerDialog).
          emojiPick?.let { target ->
              val ourKeyboard = remember { KeyboardEmoji.isCurrent(context) }
              val chosen: (String) -> Unit = { emoji ->
                  emojiPick = null
                  haptics.done()
                  target(emoji)
              }
              if (ourKeyboard) KeyboardEmojiTarget(onPick = chosen, onClose = { emojiPick = null })
              else EmojiPickerDialog(onPick = chosen) { emojiPick = null }
          }
          // Short answers to the last message, from the phone's own engine, over the field.
          val newest = list.maxByOrNull { it.date }
          var usedFor by remember { mutableStateOf<String?>(null) }
          val service = to.any(Char::isLetter) || to.count(Char::isDigit) in 1..6
          val replies by androidx.compose.runtime.produceState(emptyList<String>(), newest?.uid) {
              value = if (newest == null || newest.box != MessageBox.RECEIVED || service) emptyList()
              else withContext(Dispatchers.Default) { com.yaz.sms.core.sms.Replies.suggest(context, list.sortedBy { it.date }) }
          }
          // Reading further up: the way back to the newest, and how many came in meanwhile.
          // It floats over the conversation, above the composer, as AOSP Messaging's notice
          // does: appearing or going, it never moves the messages.
          val away by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }
          var seenAt by remember { mutableStateOf(0L) }
          LaunchedEffect(away) { if (away) seenAt = list.maxOfOrNull { it.date } ?: 0L }
          val newCount = if (away) list.count { it.box == MessageBox.RECEIVED && it.date > seenAt } else 0
          androidx.compose.animation.AnimatedVisibility(
              visible = away,
              enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f),
              exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.6f),
              modifier = Modifier
                  .align(Alignment.BottomEnd)
                  .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                  .padding(horizontal = LocalReadableInset.current)
                  .padding(end = 16.dp, bottom = composerHeight + 8.dp)
          ) {
              LatestButton(newCount) { scope.launch { listState.animateScrollToItem(0) } }
          }
          Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier
                  .align(Alignment.BottomCenter)
                  .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                  .padding(horizontal = LocalReadableInset.current)
                  .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                  .onGloballyPositioned { composerTop = it.boundsInWindow().top }
          ) {
            ReplyChips(if (usedFor == newest?.uid) emptyList() else replies) { reply ->
                haptics.tick()
                usedFor = newest?.uid
                restore = reply
            }
            // The live location of the others, then ours while shared (a pill with Stop), over the field.
            liveSpots.take(3).forEach { LiveCard(it, group, liveNow) }
            if (sharingLive && liveEnd != null) LivePill(liveEnd) {
                scope.launch { if (locator.stopSharing(people)) haptics.done() else haptics.reject() }
            }
            // A group the user left can be read, not written to (Delta Chat's chat that can no longer send).
            // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
            // It does nothing, so no zone: plain text, centred (the software rules' "a zone means a tap").
            if (left) Text(
                "You left this group",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 23.dp)
            ) else Composer(
                initial = draft,
                mentionNames = remember(localNames) { localNames.keys.toList() },
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
                onSendEffect = { text, sub, effect, attachments -> if (people.isNotEmpty()) queue(text, sub, attachments, quote, effect) },
                onDraft = { typed ->
                    // Kept even as the screen goes: its own scope ends with it.
                    val with = people.filter { '@' !in it }.toSet()
                    Thread { com.yaz.sms.data.sms.Drafts.keep(context, with, typed) }.start()
                },
                incoming = sharedIn.takeIf { it.isNotEmpty() && sharedTo != null && sharedTo == address },
                onIncomingTaken = {
                    openRequests.sharedMedia.value = emptyList()
                    openRequests.sharedTo.value = null
                },
                effectsCarried = encrypted,
                onPoll = if (encrypted) ({ pollOpen = true }) else null,
                onLive = if (encrypted) ({ seconds -> scope.launch { if (locator.share(people, seconds)) haptics.done() else haptics.reject() } }) else null,
                onSend = { typed, sub, attachments ->
                    if (people.isEmpty()) return@Composer false
                    queue(typed, sub, attachments, quote)
                    true
                }
            )
          }
          // A screen effect over everything, letting touches through.
          playing?.let { (e, w, o) -> com.yaz.sms.ui.effects.ScreenEffect(com.yaz.sms.ui.effects.ScreenKind.valueOf(e.name), w, o) { playing = null } }
        }
    ) { padding ->
        val inset = LocalReadableInset.current
        // The conversation's own photo when one was chosen; else the person's light.
        if (picture != null) PictureGround(picture, com.yaz.sms.ui.glass.LocalGlass.current?.ground ?: MaterialTheme.colorScheme.background)
        else if (doodles) DoodleGround(doodleInk(com.yaz.sms.ui.glass.LocalGlass.current?.dark ?: (MaterialTheme.colorScheme.background.luminance() < 0.5f)))
        // Their light: the colours of their photo (never the picture), else of their colour.
        else HeroGlow(if (group) null else entry?.photo, height = padding.calculateTopPadding() + 320.dp, color = if (group) null else entry?.look?.color?.let { androidx.compose.ui.graphics.Color(it) })
        // Near the oldest message shown, with more behind it: read the next page.
        // Only once the rows of the messages read are on screen: before, the
        // empty list looks like its top, and pages were read one after the
        // other until the whole conversation was (seconds on a long one).
        val nearTop by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 15 } }
        LaunchedEffect(nearTop, rowsOfList, list.size) {
            if (nearTop && rowsOfList === list && rows.isNotEmpty() && list.size >= window && listState.layoutInfo.totalItemsCount > 15) window += Messages.PAGE
        }
        // Hidden while the list moves to the divider, so no jump is seen: only a conversation that shows unread messages.
        val unreadPending = thread != null && !unreadSettled && list.any { it.box == MessageBox.RECEIVED && !it.read }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = inset)
                .onGloballyPositioned { listBottom = it.boundsInWindow().bottom }
                // Shown once the composer is measured: its first frame never lays the messages
                // out against a guessed gap, then moves them.
                .graphicsLayer { alpha = if (composerTop.isNaN() || listBottom.isNaN() || unreadPending) 0f else 1f },
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = if (composerTop.isNaN() || listBottom.isNaN()) padding.calculateBottomPadding() + 16.dp
                else with(density) { (listBottom - composerTop).coerceAtLeast(0f).toDp() } + 16.dp
            ),
            // A short conversation sits by the composer, as a long one does.
            // No gap of its own: each message brings its margins by its place in its cluster (Signal's
            // MessageShape top and bottom padding, Cluster), the other rows their own.
            verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Bottom)
        ) {
            // Messages scheduled for later, at the very bottom; a tap cancels one.
            items(scheduled.filter { it.to.toSet() == people.toSet() }, key = { "sched/${it.id}" }) { sc ->
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth().animateItem().padding(vertical = 3.dp)) {
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
                        Text("Scheduled " + com.yaz.sms.feature.conversations.aheadLabel(context, sc.at).replaceFirstChar { it.lowercase() } + ", tap to cancel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // Waiting messages sit at the bottom, their ring emptying; a tap takes one back.
            items(waiting.asReversed(), key = { "wait/${it.key}" }) { p ->
                PendingBubble(p, Modifier.animateItem().padding(vertical = Cluster.SINGLE.top)) {
                    haptics.tick()
                    // Already gone when its delay ended a moment ago: nothing to take back.
                    sends.cancel(p.key)?.let { restore = it.text }
                }
            }
            items(rows.asReversed(), key = { it.key }) { row ->
                when (row) {
                    is Row.Day -> Text(
                        row.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            // Telegram's updateMessagesVisiblePart (ChatActivity.java): a date cell whose bottom is above
                            // clipTop is alpha 0, under the header; the topmost one below it, once its top reaches clipTop,
                            // is alpha 0 too while the floating date stands in its place; else alpha 1. Read in the
                            // layer block, so a scroll frame redraws these few rows and recomposes nothing.
                            .graphicsLayer {
                                val info = listState.layoutInfo
                                val item = info.visibleItemsInfo.firstOrNull { it.key == row.key }
                                alpha = if (item == null) 1f else {
                                    val top = info.viewportEndOffset - item.offset - item.size
                                    when {
                                        top + item.size <= info.afterContentPadding -> 0f
                                        dayHeld && dayLine?.key == row.key && top <= info.afterContentPadding -> 0f
                                        else -> 1f
                                    }
                                }
                            }
                            .fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    // A message deleted, here or for everyone, fades away as the others close up.
                    is Row.Bubble -> {
                    val wash by animateFloatAsState(if (lit == row.message.uid) 1f else 0f, androidx.compose.animation.core.tween(500), label = "lit")
                    val rich = chat.refOf(row.message.mms, row.message.id).also { refs.size }
                    // Its place in its run of messages; one with reactions (a poll's votes aside) ends the run.
                    val cluster = (clusters[row.message.uid] ?: Cluster.SINGLE).endedBy(
                        row.smsReactions.isNotEmpty() || rich?.reactions.orEmpty().any { com.yaz.sms.core.sms.Polls.choiceOf(it) < 0 }
                    )
                    Box(
                        // No entrance for the rows a conversation opens with (fadeInSpec null until it has been
                        // read); a message added after that fades in (LazyItemScope.animateItem, the default spring).
                        Modifier.fillMaxWidth().animateItem(
                            fadeInSpec = if (read) spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow) else null,
                            fadeOutSpec = androidx.compose.animation.core.tween(450)
                        )
                            .padding(top = cluster.top, bottom = cluster.bottom)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * wash), ShapeDefaults.Large)
                    ) {
                      val mineRow = row.message.box != MessageBox.RECEIVED
                      Column(
                          Modifier.fillMaxWidth(),
                          horizontalAlignment = if (mineRow) Alignment.End else Alignment.Start
                      ) {
                      // Where reading stopped, above the oldest message that was unread when the conversation opened.
                      if (unreadMark != null && unreadMark.first == row.message.uid) UnreadDivider(unreadMark.second)
                      // What a reaction by text is about: the message's words, or "image" / "video" for a message of only
                      // that (the iPhone names it, quoting nothing). Said in Google's way or the iPhone's (Settings, and
                      // Automatic: the way of the last reaction received here); a group takes it as it takes any text.
                      // Quik's sendReaction (https://github.com/quik-sms/quik/pull/846) sends to the one address of a
                      // conversation; here the people of the group get it in a picture message, as a group's other texts.
                      val reactWords = remember(row.message.body) { com.yaz.sms.core.sms.Effects.read(row.message.body).first }
                      val reactMedia = if (reactWords.isNotBlank()) null else row.message.parts.firstNotNullOfOrNull { p ->
                          when {
                              p.contentType.startsWith("image/", ignoreCase = true) -> "image"
                              p.contentType.startsWith("video/", ignoreCase = true) -> "video"
                              else -> null
                          }
                      }
                      Bubble(
                        row.message,
                        fresh = row.message.box == MessageBox.RECEIVED && row.message.uid in fresh,
                        rich = rich,
                        cluster = cluster,
                        smsReactions = row.smsReactions,
                        // A reaction by text to a message with words, a picture or a film: Google Messages shows Google's
                        // as such, an iPhone reads either as words.
                        onSmsReact = if (!row.message.rich && !left && people.none { '@' in it } && (reactWords.isNotBlank() || reactMedia != null)) ({ emoji ->
                            val removed = emoji in row.smsMine
                            val style = com.yaz.sms.core.sms.SmsReactions.styleFor(prefs.reactionFormat, list)
                            val text = com.yaz.sms.core.sms.SmsReactions.compose(style, emoji, reactWords, removed, reactMedia)
                            scope.launch(Dispatchers.IO) {
                                if (group) MmsTransport.send(context, people, text, emptyList(), row.message.subId, thread)
                                else SmsSender.send(context, to, text, row.message.subId, thread)
                            }
                        }) else null,
                        smsMine = row.smsMine,
                        reactionBar = reactionBar,
                        onReactionUsed = { emoji -> reactionStore.used(emoji) },
                        onPickEmoji = { target -> emojiPick = target },
                        onReact = { emoji -> scope.launch { chat.refs.value.entries.firstOrNull { it.value.mms == row.message.mms && it.value.id == row.message.id }?.let { chat.react(it.key.toLong(), emoji) } } },
                        onPollShown = { scope.launch { chat.recount(row.message.mms, row.message.id) } },
                        sender = if (group && row.message.box == MessageBox.RECEIVED) row.message.address.let { index.find(T9.clean(it))?.name ?: Numbers.format(context, it) } else null,
                        onRetry = {
                            if (!row.message.mms) {
                                scope.launch(Dispatchers.IO) { SmsSender.retry(context, row.message.id, row.message.address, row.message.body, row.message.subId) }
                            } else {
                                // A failed picture message goes again (AOSP Messaging's ResendMessageAction).
                                scope.launch(Dispatchers.IO) { com.yaz.sms.core.mms.MmsTransport.resend(context, row.message.id) }
                            }
                        },
                        onDelete = {
                            // A message that is gone loses its star (zulip-flutter handleDeleteMessageEvent).
                            starStore.remove(row.message.uid)
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
                        holdMedia = !prefs.showStrangerMedia && row.message.box == MessageBox.RECEIVED && index.find(T9.clean(row.message.address)) == null,
                        highlight = if (searching) query.trim().takeIf { it.isNotEmpty() } else null,
                        mentionWords = mentionWords,
                        starred = row.message.uid in stars,
                        onStar = {
                            haptics.tick()
                            starStore.toggle(row.message.uid, row.message.threadId)
                        },
                        sentEffect = sentEffects[com.yaz.sms.core.sms.Effects.plain(row.message.body)]?.takeIf { (_, at) -> row.message.box != MessageBox.RECEIVED && row.message.date >= at - 10_000 }?.first,
                        onReplay = { e, words -> playing = Triple(e, words, null) },
                        onSendAgain = if (!left && people.isNotEmpty()) ({ count -> sendAgain(row.message, reactWords, count) }) else null
                    )
                      }
                    }
                    }
                }
            }
        }
        // The day floating under the header (and under the search bar) while the list moves.
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding() + 8.dp), contentAlignment = Alignment.TopCenter) {
            FloatingDay(
                label = { dayOnTop },
                visible = { dayShown && awayFromNewest },
                // Telegram's floatingDateViewOffset (updateMessagesVisiblePart, ChatActivity.java): the next heading
                // pushes the floating date up. Telegram's offset = minDateChild.getY() + its height - clipTop is
                // taken in (h, 2h) with h the floating date's height, its cells being as tall as it is; ours are
                // taller, so the heading's top stands for it: offset = top + h - clipTop, translation = offset - 2h,
                // zero once the heading's top is a full h below clipTop and when it has reached clipTop.
                modifier = Modifier.graphicsLayer {
                    val line = dayLine
                    val gap = if (line == null) 0 else line.top - listState.layoutInfo.afterContentPadding
                    val h = size.height
                    translationY = if (line != null && gap > 0 && gap < h) (gap - h).toFloat() else 0f
                }
            )
        }
    }
}

/**
 * "N unread messages" above the oldest message that was unread when the conversation opened: a line, the
 * words, a line. Signal Android draws it as an item decoration over the first unread message
 * (https://github.com/signalapp/Signal-Android app/src/main/java/org/thoughtcrime/securesms/conversation/v2/ConversationItemDecorations.kt,
 * UnreadViewHolder / isFirstUnread / setUnreadState); here it sits at the top of that message's own row.
 */
@Composable
private fun UnreadDivider(count: Int) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.4f)))
        Text(
            if (count == 1) "1 unread message" else "$count unread messages",
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.4f)))
    }
}

/**
 * The day of the messages on screen, floating under the header while the list moves. Signal Android's
 * ScrollDateHeaderHelper and ScrollListener in
 * https://github.com/signalapp/Signal-Android app/src/main/java/org/thoughtcrime/securesms/conversation/v2/ConversationFragment.kt
 * (the text bound to the topmost visible message at each scroll, shown while the scroll state is not idle, hidden
 * a moment after it is idle again: SCROLL_HEADER_CLOSE_DELAY, here 1.5 s; the same text as the in-flow date headers
 * of ConversationItemDecorations). The in-flow days of this app are plain text, so this is plain text with a soft
 * shadow, no zone; it takes no touch.
 */
@Composable
private fun FloatingDay(label: () -> String, visible: () -> Boolean, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible() && label().isNotEmpty(),
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 },
        modifier = modifier
    ) {
        // Plain text, no zone (it does nothing): a halo in the page's own colour keeps it readable over the
        // bubbles, light or dark (TextStyle shadow drawn twice, haloShadow in MessageMenu.kt;
        // https://developer.android.com/develop/ui/compose/text/style-text#add-shadow)
        val halo = MaterialTheme.typography.labelMedium.copy(shadow = haloShadow())
        HaloStack(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) { m ->
            Text(label(), m, style = halo, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The topmost day heading below the floating day's line: its key and its top in the list's coordinates. Telegram's
 * minDateChild and its getY() (https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java,
 * updateMessagesVisiblePart).
 */
private data class DayLine(val key: Any, val top: Int)

/** A line of the conversation: a day's heading or a message. */
private sealed class Row(val key: String) {
    class Day(val label: String, day: String) : Row("day/$day")
    // An SMS and a picture message may hold the same number: the kind is part of the key.
    class Bubble(val message: Message, val smsReactions: List<String> = emptyList(), val smsMine: List<String> = emptyList()) : Row("m/${message.uid}")
}

/** The conversations opened lately, as they were shown: reopened, one shows at once. */
private val opened = android.util.LruCache<Long, Pair<List<Message>, List<Row>>>(12)

/**
 * The newest conversations made ready while the list shows, as AOSP
 * Messaging has them in its own database before one is opened: their
 * newest messages read and laid out, so a tap shows them at once.
 */
internal object ThreadsAhead {
    suspend fun warm(messages: com.yaz.sms.data.sms.Messages, threads: List<Long>) {
        for (t in threads) {
            if (opened.get(t) != null) continue
            val list = messages.thread(t, FIRST)
            if (list.isEmpty()) continue
            val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { rowsOf(list) }
            opened.put(t, list to rows)
        }
    }

    /** A conversation that changed is read again when opened. */
    fun forget(thread: Long) { opened.remove(thread) }
}

/** How many messages a conversation opened for the first time shows before its first page. */
private const val FIRST = 40

private fun rowsOf(all: List<Message>): List<Row> {
    // Reactions that came as SMS sit under their message, not as messages of their own.
    val folded = com.yaz.sms.core.sms.SmsReactions.fold(all)
    val list = folded.messages
    val out = ArrayList<Row>()
    var day: java.time.LocalDate? = null
    list.forEach { m ->
        val d = Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()).toLocalDate()
        if (d != day) {
            day = d
            out += Row.Day(dayLabel(d), d.toString())
        }
        // Each message carries its own time in its bubble (MessageBody's footer).
        out += Row.Bubble(m, folded.reactions[m.uid].orEmpty(), folded.mine[m.uid].orEmpty())
    }
    return out
}

/**
 * Each message's place in its run of messages of one sender, by its neighbours (Signal's V2ConversationItemShape,
 * see [Clusters.of]); the neighbours are the messages before and after it, whatever lies between them in the list.
 */
private fun clustersOf(rows: List<Row>, group: Boolean): Map<String, Cluster> {
    val messages = rows.filterIsInstance<Row.Bubble>().map { it.message }
    val zone = ZoneId.systemDefault()
    val out = HashMap<String, Cluster>(messages.size * 2)
    messages.forEachIndexed { i, m ->
        out[m.uid] = Clusters.of(m, messages.getOrNull(i - 1), messages.getOrNull(i + 1), group, zone)
    }
    return out
}

/** A message's time of day in the phone's own format (DateFormat.getTimeFormat, developer.android.com). */
internal fun clockOf(context: android.content.Context, millis: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(millis))

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

/**
 * A message as a pane of glass: received on the left, the user's own on
 * the right in the accent. A code carries a button to copy it; links are
 * opened cleaned of their trackers. Held: copy, delete, try again.
 */
@OptIn(ExperimentalFoundationApi::class)
/**
 * A message in glass, as iOS's bubbles: its rim lit all round, the sides as
 * well as the top and the bottom, following its tail; one's own is coloured
 * glass of the accent (a GlassLook with its tint), never a flat wash over it.
 */
@Composable
private fun BubbleGlass(shape: androidx.compose.ui.graphics.Shape, mine: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val look = com.yaz.sms.ui.glass.LocalGlass.current
    if (look == null) {
        ZoneSurface(shape = shape, accent = mine, modifier = modifier, content = content)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val glass = if (!mine) look else remember(look, scheme) {
        com.yaz.sms.ui.glass.GlassLook(
            dark = look.dark,
            ground = look.ground,
            halos = look.halos,
            zoneTint = if (look.dark) scheme.primary.copy(alpha = 0.36f) else scheme.primaryContainer.copy(alpha = 0.55f),
            floatTint = look.floatTint,
            accentTint = look.accentTint
        )
    }
    val ink = when {
        !mine -> scheme.onSurface
        look.dark -> scheme.onSurface
        else -> scheme.onPrimaryContainer
    }
    androidx.compose.material3.Surface(
        modifier = modifier.glassZone(shape, glass, lens = 1f, flanks = 1f),
        shape = shape,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = ink,
        content = content
    )
}

@Composable
private fun Bubble(
    m: Message,
    fresh: Boolean,
    rich: RichRef?,
    onReact: (String?) -> Unit,
    sender: String?,
    smsReactions: List<String> = emptyList(),
    smsMine: List<String> = emptyList(),
    onSmsReact: ((String) -> Unit)? = null,
    /** The six reactions of the long press bar: the user's most used, then the defaults. */
    reactionBar: List<String> = com.yaz.sms.data.settings.ReactionHistory.DEFAULTS,
    /** A reaction the user just sent, to be counted. */
    onReactionUsed: (String) -> Unit = {},
    /** The "+" of the bar: hands the screen what to do with the emoji it picks. */
    onPickEmoji: (((String) -> Unit)) -> Unit = {},
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onQuote: () -> Unit,
    onEdit: (Int) -> Unit,
    onDeleteForAll: (Int) -> Unit,
    onPin: (Int, Boolean) -> Unit,
    chatId: Int?,
    /** Its place in its run of messages of one sender: the corners of its bubble (Signal's V2ConversationItemShape). */
    cluster: Cluster = Cluster.SINGLE,
    stranger: Boolean = false,
    /** Its media wait for a tap: from a stranger, when Settings asks so. */
    holdMedia: Boolean = stranger,
    highlight: String? = null,
    /** The names of a group's members and the user: "@Name" with one of them stands out in the words. */
    mentionWords: List<String> = emptyList(),
    starred: Boolean = false,
    onStar: () -> Unit = {},
    sentEffect: com.yaz.sms.core.sms.Effects.Effect? = null,
    onReplay: (com.yaz.sms.core.sms.Effects.Effect, String) -> Unit = { _, _ -> },
    onPollShown: () -> Unit = {},
    /** "Send again" with a count: null where the conversation cannot send (a group the user left). */
    onSendAgain: ((Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // Held: the message lifts, the reactions and the actions come around it.
    var menuOpen by remember { mutableStateOf(false) }
    var showingDetails by remember { mutableStateOf(false) }
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
    var selecting by remember { mutableStateOf(false) }
    var sendingAgain by remember { mutableStateOf(false) }
    // A reaction chosen in the bar or picked: sent the way this conversation sends one (the chat, or SMS in Google's or
    // Apple's words), a reaction the user already gave taken back. That toggle is Element X's reaction click,
    // TimelineItemReactionsView's onReactionClick and MessagesReactionButton (highlighted = the user's own), in
    // https://github.com/element-hq/element-x-android/blob/develop/features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/components/TimelineItemReactionsView.kt
    // ; it is counted for the bar when added, not when taken back (ReactionStore).
    fun react(emoji: String) {
        if (rich == null && onSmsReact != null) {
            if (emoji !in smsMine) onReactionUsed(emoji)
            onSmsReact(emoji)
        } else {
            val taking = emoji in rich?.myReactions.orEmpty()
            if (!taking) onReactionUsed(emoji)
            onReact(if (taking) null else emoji)
        }
    }
    if (selecting) SelectTextDialog(said) { selecting = false }
    if (sendingAgain && onSendAgain != null) SendAgainDialog(
        words = said,
        attachments = m.parts.size,
        onSend = { count ->
            sendingAgain = false
            onSendAgain(count)
        },
        onDismiss = { sendingAgain = false }
    )
    if (confirmDeleteAll && chatId != null) {
        ZoneAlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete for everyone?") },
            text = { Text("The message goes from this conversation, here and on the other phone.") },
            confirmButton = {
                BoldButton(filled = true, onClick = {
                    confirmDeleteAll = false
                    onDeleteForAll(chatId)
                }) { Text("Delete") }
            },
            dismissButton = { QuietButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }
    // Translate: the words in the user's language, in the same bubble, with a line to switch to the original. The idea is
    // WhatsApp's and iOS 26 Messages' (the translation in the bubble, a tap shows the original); in memory only, never
    // stored, never sent. Translator is DavidVentura/offline-translator, reached through its AidlTranslationService
    // (https://github.com/DavidVentura/offline-translator/blob/main/app/src/main/java/dev/davidv/translator/AidlTranslationService.kt)
    // by core/translate/Translator.
    var translated by remember(m.uid) { mutableStateOf<String?>(null) }
    var showOriginal by remember(m.uid) { mutableStateOf(false) }
    var translating by remember(m.uid) { mutableStateOf(false) }
    val translatorHere = remember { Translator.installed(context) }
    val shown = translated?.takeIf { !showOriginal } ?: said
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
    // A tapped link asks first: where it goes, what it may hide (LinkAsk), whatever the sender.
    var tapped by remember { mutableStateOf<Pair<String, com.yaz.sms.core.link.LinkCheck.Verdict?>?>(null) }
    tapped?.let { (url, verdict) -> LinkAsk(url, verdict, onDismiss = { tapped = null }) }
    val found = MaterialTheme.colorScheme.tertiary
    // A mention stands out: in the accent on a received bubble, in bold on one's own (the accent is its ground).
    val mentionStyle = remember(mine, accent) {
        if (mine) SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        else SpanStyle(color = accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, background = accent.copy(alpha = 0.14f))
    }
    val text = remember(shown, accent, checks, highlight, mentionWords, mentionStyle) {
        marked(linked(shown, accent, danger, checks, mentionWords, mentionStyle) { url, verdict ->
            if (verdict != null) haptics.reject() else haptics.tick()
            tapped = url to verdict
        }, highlight, found)
    }
    // A message that just came in drops into place, a ring of glass spreading from it.
    val drop = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(fresh) { if (fresh) drop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 320f)) }
    val ringColor = MaterialTheme.colorScheme.primary
    // The pictures and films go in the bubble with the words and the time, as Element X's event row holds
    // its media and caption (https://github.com/element-hq/element-x-android, TimelineItemEventRow.kt); sounds,
    // files and cards go in the same bubble above the words (TimelineItemFileView, TimelineItemAudioView, MessageBody).
    // What each part is comes from its first bytes (MediaCheck), all at once.
    val kinds by rememberKinds(remember(m.parts) { m.parts.map { it.uri } })
    val inBubble = m.parts.indices.filter { m.parts[it].let { p -> isPicture(p, kinds.getOrNull(it)) || isFilm(p, kinds.getOrNull(it)) } }
    val visual = remember(m.parts, inBubble) { inBubble.map { m.parts[it] } }
    val visualKinds = remember(kinds, inBubble) { inBubble.map { kinds.getOrNull(it) } }
    val tiles = remember(m.parts, inBubble) { m.parts.filterIndexed { i, _ -> i !in inBubble } }
    // Every message with words or parts has its bubble; a message waiting for its download has none.
    val hasBubble = !m.waiting && (said.isNotEmpty() || visual.isNotEmpty() || tiles.isNotEmpty() || m.parts.isEmpty() || m.subject != null)
    // The corners follow the message's place in its run of messages of one sender (Signal's V2ConversationItemShape).
    val shape = remember(cluster, mine) { Clusters.corners(cluster, mine) }
    val clock = remember(m.date) { clockOf(context, m.date) }
    val delivery = if (!mine) null else when {
        rich?.seen == true -> Delivery.Read
        m.box == MessageBox.FAILED -> Delivery.Failed
        m.box == MessageBox.SENDING -> Delivery.Sending
        m.delivered -> Delivery.Delivered
        else -> Delivery.Sent
    }
    val footer = @Composable { mismatched: Boolean, onMedia: Boolean ->
        if (translated != null && !showOriginal && !onMedia) {
            // iOS 26 Messages' "Translated" mark before the time; no zone, the switch back is in the long press menu.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Translated",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.66f),
                    modifier = Modifier.padding(end = 6.dp)
                )
                MessageFooter(clock, delivery, starred, onMedia = false, mismatched = mismatched)
            }
        } else MessageFooter(clock, delivery, starred, onMedia = onMedia, mismatched = mismatched)
    }
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
        // A group's sender is named once, over the first of their run of messages (Signal shows the name at the start of a cluster).
        sender?.takeIf { cluster.isStarting }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        // Announced by the network, not here yet: a tap fetches it, as AOSP
        // Messaging's download action on a waiting picture message.
        if (m.waiting) {
            val busy by com.yaz.sms.core.mms.MmsTransport.downloading.collectAsState()
            val fetching = m.id in busy
            ZoneSurface(
                shape = ShapeDefaults.ExtraLarge,
                modifier = Modifier.clip(ShapeDefaults.ExtraLarge).clickable(enabled = !fetching) {
                    haptics.tick()
                    Thread {
                        if (!com.yaz.sms.core.mms.MmsTransport.downloadStored(context, m.id)) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(context, "Could not download", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Icon(AppIcons.Download, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(if (fetching) "Downloading…" else "Picture message, tap to download", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        val scope = rememberCoroutineScope()
        // One Translator.Session per tap, closed after it (Keyboard's TranslateActivity.kt keeps one per panel), into the
        // phone's language, on https://github.com/DavidVentura/offline-translator/blob/main/app/src/main/java/dev/davidv/translator/AidlTranslationService.kt
        // through core/translate; each TranslationResult said plainly.
        fun translate() {
            if (translating) return
            translating = true
            scope.launch {
                try {
                    val to = Translator.codeFor(Locale.getDefault()) ?: "en"
                    when (val r = Translator.Session(context).use { it.translate(said, to) }) {
                        // The same words back: Translator took them for the phone's language (its log: "from English to English").
                        is TranslationResult.Ok -> if (r.text.trim() == said.trim()) {
                            android.widget.Toast.makeText(context, "Translator found it already in your language", android.widget.Toast.LENGTH_SHORT).show()
                        } else { haptics.done(); translated = r.text; showOriginal = false }
                        is TranslationResult.NotDownloaded -> {
                            android.widget.Toast.makeText(context, "${r.language} is not downloaded in Translator", android.widget.Toast.LENGTH_LONG).show()
                            Translator.launchIntent(context)?.let { runCatching { context.startActivity(it) } }
                        }
                        TranslationResult.NotDetected -> android.widget.Toast.makeText(context, "Language not recognised", android.widget.Toast.LENGTH_SHORT).show()
                        TranslationResult.Failed -> android.widget.Toast.makeText(context, "Could not translate", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    translating = false
                }
            }
        }
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
        // Media bubbles take 0.78 of the list's width, as Element X's MessageEventBubble (BUBBLE_WIDTH_RATIO).
        if (hasBubble) Box(
            Modifier
                .fillMaxWidth(if (visual.isNotEmpty() || tiles.isNotEmpty()) 0.78f else 0.82f)
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
                // Swiped left, nothing: the move is not consumed, so the pager and the back gesture
                // keep it (Compose foundation's detectHorizontalDragGestures,
                // https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/DragGestureDetector.kt:
                // a touch slop reached without consuming keeps waiting, and the drag starts only when a callback consumes).
                .pointerInput(m.id) {
                    var started = false
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed) onQuote()
                            armed = false
                            started = false
                            scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
                        },
                        onDragCancel = {
                            armed = false
                            started = false
                            scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
                        },
                        onHorizontalDrag = { change, dx ->
                            if (!started) {
                                if (dx <= 0f) return@detectHorizontalDragGestures
                                started = true
                            }
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
            val held = Modifier
                    .then(if (poll != null) Modifier.fillMaxWidth() else Modifier)
                    // At least 80 dp wide, as Element X's MIN_BUBBLE_WIDTH.
                    .then(if (visual.isNotEmpty()) Modifier.widthIn(min = 80.dp) else Modifier)
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
                        react("❤️")
                    }) else null,
                    onLongClick = {
                        haptics.firm()
                        menuOpen = true
                    },
                    onLongClickLabel = "More"
                )
            if (big != null) BigEmojiRow(big, emojiPlay, if (inked) held.blur(14.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else held)
            else BubbleGlass(shape = shape, mine = mine, modifier = held) {
                MessageBody(
                    words = text, plain = shown, blurred = inked,
                    poll = if (poll != null && rich != null) ({ PollCard(poll, rich.counts, rich.myReactions, onVote = onReact) }) else null,
                    visual = visual, kinds = visualKinds, held = holdMedia && !mine, footer = footer,
                    onMore = {
                        haptics.firm()
                        menuOpen = true
                    },
                    tiles = tiles, mine = mine, subject = m.subject
                )
            }
        }
        // No bubble to hold the time (big emoji as Signal puts it, a tile alone, a message waiting): its line under it.
        if (!hasBubble || big != null) Box(Modifier.padding(top = if (big != null) 0.dp else 2.dp)) { footer(false, false) }
        if (showingDetails) MessageDetailsDialog(m) { showingDetails = false }
        // Out of the bubble's block: a message of only media opens its menu too.
        if (menuOpen) MessageMenu(
                bounds = bounds,
                reactions = if (rich != null || onSmsReact != null) reactionBar else null,
                chosen = if (rich != null) rich.myReactions else smsMine,
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
                    // Translate, or switch a translated bubble: WhatsApp's and iOS 26 Messages' long press Translate; the call is
                    // Keyboard's TranslateActivity.kt (Translator.Session) on offline-translator's AidlTranslationService
                    // (https://github.com/DavidVentura/offline-translator/blob/main/app/src/main/java/dev/davidv/translator/AidlTranslationService.kt).
                    if (translatorHere && said.isNotBlank() && big == null && poll == null) {
                        if (translated != null) MessageAction(AppIcons.Translate, if (showOriginal) "Show translation" else "Show original") { showOriginal = !showOriginal }
                        else MessageAction(AppIcons.Translate, "Translate") { translate() }
                    } else null,
                    // Send again x N: the user's own sent messages only (not a poll, which would make a second poll).
                    if (onSendAgain != null && mine && m.box == MessageBox.SENT && poll == null && (said.isNotBlank() || m.parts.isNotEmpty()))
                        MessageAction(AppIcons.Send, "Send again") { sendingAgain = true } else null,
                    // A message's media saved or handed on, as AOSP Messaging offers (save_attachment, share_message_menu).
                    if (m.parts.isNotEmpty()) MessageAction(AppIcons.Download, "Save") {
                        Thread {
                            val n = com.yaz.sms.core.mms.SaveParts.save(context, m.parts)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(context, if (n > 0) "Saved" else "Could not save", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    } else null,
                    if (m.parts.size == 1) MessageAction(AppIcons.Share, "Share") { shareOutside(context, m.parts.first()) } else null,
                    // Star and Unstar in the held message's menu, as Google Messages offers
                    // (https://support.google.com/messages/answer/10930955) and Zulip's StarButton
                    // flips its label (zulip-flutter lib/widgets/action_sheet.dart).
                    MessageAction(if (starred) AppIcons.StarOutline else AppIcons.Star, if (starred) "Unstar" else "Star") { onStar() },
                    MessageAction(AppIcons.Info, "Details") { showingDetails = true },
                    if (chatId != null) MessageAction(AppIcons.PushPin, if (rich?.pinned == true) "Unpin" else "Pin") { onPin(chatId, rich?.pinned != true) } else null,
                    if (m.box == MessageBox.FAILED) MessageAction(AppIcons.Send, "Try again") { onRetry() } else null,
                    if (chatId != null && rich?.mine == true) MessageAction(AppIcons.Delete, "Delete for everyone", danger = true) { confirmDeleteAll = true } else null,
                    MessageAction(AppIcons.Delete, if (chatId != null && rich?.mine == true) "Delete for me" else "Delete", danger = true) { onDelete() }
                ),
                onReact = { emoji -> react(emoji) },
                onDismiss = { menuOpen = false },
                // The picker lives in the screen, not in this list item (see ThreadScreen's emojiPick): the item leaves
                // the composition when the keyboard shrinks the list.
                onMoreReactions = if (rich != null || onSmsReact != null) ({ onPickEmoji { emoji -> react(emoji) } }) else null
            ) {
                if (big != null) BigEmojiRow(big, 0)
                // The bubble's own shape, its cluster corners, with what it holds (tiles that open nothing).
                else ZoneSurface(
                    shape = shape,
                    accent = mine, shadowElevation = 8.dp
                ) {
                    MessageBody(
                        words = text, plain = shown, blurred = false,
                        poll = null,
                        visual = visual, kinds = visualKinds, held = holdMedia && !mine, footer = footer,
                        onMore = null, interactive = false,
                        tiles = tiles, mine = mine, subject = m.subject
                    )
                }
            }
        // A link not recognized: one capsule of glass just under the message, a tap tells where it goes.
        // Never an accusation (it may be a sound link), except a site on the public list.
        checks.entries.firstOrNull()?.let { (url, verdict) ->
            SuspiciousCapsule(several = checks.size > 1, dangerous = checks.values.any { it.risk == com.yaz.sms.core.link.LinkCheck.Risk.LISTED }) {
                haptics.reject()
                tapped = url to verdict
            }
        }
        // A place: its card, opened in the user's map app; else a sound link: its preview on a tap, never fetched by itself.
        val place = remember(said) { com.yaz.sms.core.sms.Places.find(said) }
        val sound = remember(said, checks) { links(said).firstOrNull { it !in checks } }
        if (place != null && checks.isEmpty()) PlaceCard(place)
        else if (sound != null && checks.isEmpty() && m.parts.isEmpty()) LinkPreviewCapsule(sound)
        // A poll's votes are in its card, not here.
        // Identical reactions gathered in one chip with their count, the user's own in accent glass
        // (Element X's TimelineItemReactionsView, see ReactionChips).
        val chatKeys = rich?.reactions.orEmpty().filter { poll == null || com.yaz.sms.core.sms.Polls.choiceOf(it) < 0 }
        val reactionGroups = com.yaz.sms.core.sms.ReactionGroups.ofCounts(chatKeys, rich?.counts.orEmpty(), rich?.myReactions.orEmpty()) +
            com.yaz.sms.core.sms.ReactionGroups.ofList(smsReactions, smsMine)
        ReactionChips(reactionGroups, mine, Modifier.padding(top = 2.dp), onToggle = { emoji ->
            haptics.tick()
            react(emoji)
        })
        // Pinned for both sides, or changed after it was sent.
        if (rich?.pinned == true || rich?.edited == true) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, start = 8.dp, end = 8.dp)) {
                if (rich.pinned) {
                    Icon(AppIcons.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    listOfNotNull(if (rich.pinned) "Pinned" else null, if (rich.edited) "edited" else null).joinToString(", "),
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
        // Under a message that failed: where it stands, and the way to send it again (its time and status are in its footer).
        if (mine && m.box == MessageBox.FAILED) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, end = 14.dp, bottom = 6.dp)) {
                Icon(AppIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (m.mms) "Not sent" else "Not sent, tap to try again", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
        confirmButton = { BoldButton(filled = true, onClick = { onDone(name) }) { Text("Save") } },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } }
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
    com.yaz.sms.ui.component.ChoiceDialog(
        title = "Vanishing messages",
        text = "New messages vanish on both phones this long after they are read.",
        options = listOf(0, 300, 3600, 86_400, 604_800).map { seconds -> seconds to (if (seconds == 0) "Off" else vanishLabel(seconds)) },
        selected = current,
        onPick = onPick,
        onDismiss = onDismiss
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
 * marks taken away) and its web links tappable: a tap asks first ([onLink],
 * the link sheet shows where it goes) and a doubtful link ([checks]) is in
 * the caution tone.
 */
private fun linked(
    body: String,
    accent: androidx.compose.ui.graphics.Color,
    danger: androidx.compose.ui.graphics.Color = accent,
    checks: Map<String, com.yaz.sms.core.link.LinkCheck.Verdict> = emptyMap(),
    mentions: List<String> = emptyList(),
    mentionStyle: SpanStyle = SpanStyle(),
    onLink: (String, com.yaz.sms.core.link.LinkCheck.Verdict?) -> Unit = { _, _ -> }
): AnnotatedString = buildAnnotatedString {
    val read = com.yaz.sms.core.sms.Markup.read(body)
    append(read.text)
    // The mentions of a group, found by its members' names (Signal draws them with MentionRendererDelegate;
    // here the words "@Name" are all the message carries, core/chat/Mentions.kt).
    if (mentions.isNotEmpty()) com.yaz.sms.core.chat.Mentions.find(read.text, mentions).forEach { addStyle(mentionStyle, it.start, it.end) }
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
        addLink(LinkAnnotation.Clickable(url, if (verdict == null) sound else doubtful) { onLink(url, verdict) }, matcher.start(), matcher.end())
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
 * A link tapped, in a message or in the shared tab: the links kit's sheet (LinkSheet, after URLCheck's
 * intermediary, https://github.com/TrianguloY/URLCheck) shows where it goes and what it may hide, with
 * SMS's own finding ([verdict]: a site on the public list, a bare address, a lookalike, a stranger's
 * shortened link) beside the shared ones. Open goes out through Android's view intent, as the links
 * always did here. Expand is not offered: SMS has no redirect resolver (the reader kit's), so a
 * shortened link is not followed.
 */
@Composable
internal fun LinkAsk(url: String, verdict: com.yaz.sms.core.link.LinkCheck.Verdict?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val own = remember(verdict) {
        listOfNotNull(
            verdict?.let {
                com.yaz.sms.core.link.LinkWarnings.Warning(
                    com.yaz.sms.core.link.LinkWarnings.Kind.OTHER,
                    if (it.risk == com.yaz.sms.core.link.LinkCheck.Risk.LISTED) com.yaz.sms.core.link.LinkWarnings.Level.DANGER else com.yaz.sms.core.link.LinkWarnings.Level.CAUTION,
                    it.words
                )
            }
        )
    }
    com.yaz.sms.core.link.LinkSheet(
        url = url,
        onDismiss = onDismiss,
        onOpen = { clean ->
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(clean)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        extraWarnings = own
    )
}

/**
 * The message just written: it flies up from the field into its place as
 * a drop of glass, and a ring on it empties over the seconds it waits; a
 * tap takes it back into the field.
 */
@Composable
private fun PendingBubble(p: com.yaz.sms.core.sms.PendingSends.Waiting, modifier: Modifier, onCancel: () -> Unit) {
    val flight = remember { Animatable(0f) }
    LaunchedEffect(Unit) { flight.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 260f)) }
    // The ring starts where the countdown is: full for a message just written, shorter for one come back to.
    val ring = remember { Animatable(p.remaining() / (p.seconds * 1000f).coerceAtLeast(1f)) }
    LaunchedEffect(Unit) { ring.animateTo(0f, androidx.compose.animation.core.tween(p.remaining().toInt(), easing = androidx.compose.animation.core.LinearEasing)) }
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
                    // Up to two lines, broken between words, when a reply is wider than the row (the 200 % font, a longer language).
                    Text(reply, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        }
    }
}

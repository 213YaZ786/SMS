package com.yaz.sms.feature.thread

import com.yaz.sms.ui.glass.glassGround
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.yaz.sms.ui.component.QuietButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaz.sms.core.dial.NumberActions
import com.yaz.sms.core.dial.Sisters
import com.yaz.sms.data.sms.Message
import com.yaz.sms.ui.component.BlockConfirm
import com.yaz.sms.ui.component.ContactAvatar
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.HeroGlow
import com.yaz.sms.ui.component.PersonActionTile
import com.yaz.sms.ui.component.PersonActions
import com.yaz.sms.ui.component.PersonHeader
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons

/**
 * The person behind a conversation, opened from their face at the top:
 * their photo large, the ways to reach them and the conversation's tools
 * as a row of glass buttons, how the chat is encrypted, what was shared
 * by kind (each with a few shown and the rest a tap away), and what can
 * be done about them (their contact, a vibration of their own, blocking).
 */
@Composable
fun PersonPage(
    name: String,
    number: String?,
    address: String,
    photo: String?,
    contactId: Long?,
    group: Boolean,
    encrypted: Boolean,
    messages: List<Message>,
    stranger: (Message) -> Boolean,
    /** Its media wait for a tap (a stranger's, when Settings asks so); asked when shown. */
    holdMedia: (Message) -> Boolean = { false },
    calls: CallChoices,
    onBackground: (() -> Unit)?,
    onEncryption: () -> Unit,
    /** The conversation's starred messages (the page closes first); null when there is no conversation yet. */
    onStarred: (() -> Unit)? = null,
    onName: (String) -> Unit,
    /** A group's picture, members, leaving and mentions (Delta Chat Android's group profile); null for one person. */
    groupTools: GroupTools? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val groupUi = remember { GroupUi() }
    if (groupTools != null) GroupDialogs(groupTools, groupUi)
    // Koin is read the way MessageNotifier reads it, so a screen drawn without the app's
    // container (the screenshot tests) simply has no spam buttons.
    val koin = remember { org.koin.core.context.GlobalContext.getOrNull() }
    val senderKey = remember(address) { com.yaz.sms.core.sms.SpamJudge.senderKey(address) }
    // Report spam and Not spam on the person's page, as Google Messages has them in a conversation's
    // details (https://support.google.com/messages/answer/9061432); the store and the training are
    // SpamBlocker's (https://github.com/aj3423/SpamBlocker, LocalAI.kt updateCategory), as the list's.
    val spamStore = remember(koin) { koin?.get<com.yaz.sms.data.settings.SpamStore>() }
    val spamFlow = remember(spamStore) { spamStore?.data ?: kotlinx.coroutines.flow.MutableStateFlow(com.yaz.sms.data.settings.SpamFile()) }
    val spamData by spamFlow.collectAsState()
    val canSpam = spamStore != null && !group && address.isNotBlank() && '@' !in address
    val isSpam = spamData.senders[senderKey] == true
    var reporting by remember { mutableStateOf(false) }
    // Read here, before Report spam may block (Compose's state recipe, https://developer.android.com/develop/ui/compose/state).
    var blocked by remember(address) { mutableStateOf(NumberActions.isBlocked(context, address)) }
    val spamScope = androidx.compose.runtime.rememberCoroutineScope()
    fun labelSpam(spam: Boolean, block: Boolean = false) {
        val store = spamStore ?: return
        spamScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val received = com.yaz.sms.data.settings.Spams.received(messages)
            if (spam) store.report(senderKey, received) else store.notSpam(senderKey, received)
            if (block) {
                NumberActions.block(context, address)
                blocked = NumberActions.isBlocked(context, address)
            }
        }
    }
    if (reporting) com.yaz.sms.feature.conversations.ReportSpamDialog(
        name = name,
        canBlock = !blocked && address.count(Char::isDigit) >= 3 && NumberActions.canBlock(context),
        onDismiss = { reporting = false }
    ) { block ->
        reporting = false
        labelSpam(true, block)
    }
    val items = remember(messages) { gather(messages, stranger) }
    var seeAll by remember { mutableStateOf<Kind?>(null) }
    var showAll by remember { mutableStateOf(false) }
    if (showAll) SharedPage(name, messages, stranger, holdMedia, start = seeAll) { showAll = false }
    var confirmBlock by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    if (naming) GroupNameDialog(if (group && name.contains(',')) "" else name, onDone = { n ->
        naming = false
        onName(n)
    }) { naming = false }
    // The shared confirmation (ui/component/BlockConfirm), from AOSP Dialer's BlockReportSpamDialogs.
    if (confirmBlock) BlockConfirm(name, onDismiss = { confirmBlock = false }) {
        confirmBlock = false
        NumberActions.block(context, address)
        blocked = NumberActions.isBlocked(context, address)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PageBars()
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize().glassGround(com.yaz.sms.ui.glass.LocalGlass.current, MaterialTheme.colorScheme.surface)) {
                HeroGlow(if (group) null else photo, height = 420.dp)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Who they are: the shared person header (ui/component/PersonHeader), from AOSP Dialer's call details
                    // header, the photo then the name and the number to its end
                    // (https://android.googlesource.com/platform/packages/apps/Dialer/+/refs/heads/main/java/com/android/dialer/calldetails/res/layout/contact_container.xml, Apache-2.0).
                    item {
                        Box(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                            PersonHeader(
                                name = name,
                                number = number?.takeIf { it != name },
                                face = { size ->
                                    if (group && groupTools != null) GroupFace(groupTools.picture, name, size)
                                    else ContactAvatar(name, if (group) null else photo, size, look = if (group) null else com.yaz.sms.ui.component.rememberLook(address).value)
                                },
                                onFace = if (group && groupTools != null) ({ groupUi.needsChat(groupTools) { groupUi.pick?.invoke() } })
                                else if (!group && contactId != null && contactId > 0) ({ NumberActions.openContact(context, contactId) })
                                else null,
                                badges = if (encrypted) {
                                    {
                                        FloatingPane(shape = CircleShape, onClick = onEncryption) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                                Icon(AppIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("End-to-end encrypted", style = MaterialTheme.typography.labelLarge)
                                            }
                                        }
                                    }
                                } else null
                            )
                        }
                    }
                    // What can be done with them: the shared row of tiles (ui/component/PersonActions), wired as Dialer's
                    // number page does (feature/recents/NumberScreen.kt). This page lies over the conversation, so
                    // Message goes back to it. A group or a sender without a number has no row.
                    if (!group && address.count(Char::isDigit) >= 3) item {
                        Box(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                            PersonActions(
                                number = address,
                                contactId = contactId,
                                onCall = calls.phone,
                                onMessage = onClose,
                                more = {
                                    calls.encrypted?.let { PersonActionTile(AppIcons.Lock, "Encrypted call", Modifier.weight(1f), onClick = it) }
                                    calls.video?.let { PersonActionTile(AppIcons.Videocam, "Video call", Modifier.weight(1f), onClick = it) }
                                }
                            )
                        }
                    }
                    // About them: the rest of the actions, Block last.
                    item {
                        val digits = !group && address.count(Char::isDigit) >= 3
                        // Cards in two columns, each its icon over its name, the rows as tall as each other.
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            maxItemsInEachRow = 2,
                            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                        ) {
                            if (digits && NumberActions.canShowCalls(context)) Row(AppIcons.Recents, "Calls with them") { NumberActions.showCalls(context, address) }
                            onBackground?.let { open -> Row(AppIcons.Palette, "Background") { open() } }
                            onStarred?.let { open -> Row(AppIcons.Star, "Starred messages") { open() } }
                            // The person's look is kept by the Contacts app: the row shows only when it is ours and on the phone.
                            if (!group && contactId != null && contactId > 0 && Sisters.packages("contacts").any { Sisters.isOurs(context, it) }) {
                                Row(AppIcons.Vibration, "Vibration") { com.yaz.sms.core.dial.ContactLook.openInContacts(context, contactId) }
                            }
                            if (group) Row(AppIcons.Create, "Name the group") { naming = true }
                            // A group's own: its picture, who is in it, leaving it, mentions. Delta Chat Android's group profile
                            // (ProfileActivity / ProfileFragment); the picture, removing and leaving go by the encrypted chat only.
                            if (group && groupTools != null) {
                                val hint = if (groupTools.chat && !groupTools.left) null else if (groupTools.left) "You left" else "Encrypted chat only"
                                Row(AppIcons.AddPhoto, "Group picture", hint = hint) { groupUi.needsChat(groupTools) { groupUi.pick?.invoke() } }
                                if (groupTools.picture != null && groupTools.chat && !groupTools.left) {
                                    Row(AppIcons.Delete, "Remove picture") { groupUi.clearing = true }
                                }
                                Row(AppIcons.AlternateEmail, if (groupTools.mentionsOn) "Notify me of mentions" else "Mentions without notification") {
                                    groupTools.onMentions(!groupTools.mentionsOn)
                                }
                                Row(AppIcons.ExitToApp, "Leave group", danger = true, hint = hint) { groupUi.needsChat(groupTools) { groupUi.leaving = true } }
                            }
                            // Report spam beside Block, as Google Messages' conversation details
                            // (https://support.google.com/messages/answer/9061432), SpamBlocker's labelling.
                            if (canSpam) {
                                if (isSpam) Row(AppIcons.ReportOff, "Not spam") { labelSpam(false) }
                                else Row(AppIcons.Report, "Report spam", danger = true) { reporting = true }
                            }
                            if (!group && NumberActions.canBlock(context)) Row(AppIcons.Block, if (blocked) "Unblock" else "Block", danger = !blocked) {
                                if (blocked) {
                                    NumberActions.unblock(context, address)
                                    blocked = NumberActions.isBlocked(context, address)
                                } else confirmBlock = true
                            }
                        }
                        }
                    }
                    // The people of a group, each one a line: a swipe left, a long press or the button takes them
                    // out (over the encrypted chat; after the question). Delta Chat Android's ProfileFragment members list.
                    if (group && groupTools != null) {
                        item(key = "members") {
                            Column(verticalArrangement = MemberSpacing, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 6.dp)) {
                                Text(
                                    if (groupTools.members.size == 1) "1 member" else "${groupTools.members.size} members",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                                )
                                groupTools.members.forEach { member ->
                                    MemberLine(
                                        member,
                                        number = com.yaz.sms.core.dial.Numbers.format(context, member.phone),
                                        onRemove = if (groupTools.chat && !groupTools.left) ({ groupUi.removing = member }) else null
                                    )
                                }
                            }
                        }
                    }
                    // What they and the user sent each other, by kind, under every action: Apple's
                    // Messages details, its Photos, Links and Documents below (https://support.apple.com/en-gb/111116).
                    Kind.entries.forEach { kind ->
                        val these = items.filter { kindOf(it) == kind }
                        if (these.isEmpty()) return@forEach
                        item(key = "head/$kind") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 6.dp)) {
                                Text("${kind.label} (${these.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(start = 6.dp))
                                if (these.size > (if (kind == Kind.PICTURES) 6 else 3)) QuietButton(onClick = {
                                    seeAll = kind
                                    showAll = true
                                }) { Text("See all") }
                            }
                        }
                        if (kind == Kind.PICTURES) {
                            item(key = "pics") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                                    these.filterIsInstance<Item.Picture>().take(6).chunked(3).forEach { line ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            line.forEach { PictureCell(it.part, Modifier.weight(1f), holdMedia(it.from)) }
                                            repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                        } else {
                            these.take(3).forEachIndexed { i, it -> item(key = "$kind/$i") { Line(it) } }
                        }
                    }
                }
                Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) { FloatingAction(AppIcons.ArrowBack, "Back", onClose) }
            }
        }
    }
}

/** A line of glass for something about the person. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.Row(icon: ImageVector, label: String, danger: Boolean = false, hint: String? = null, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f).fillMaxRowHeight().heightIn(min = 100.dp), onClick = {
        haptics.tick()
        onClick()
    }) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2
            )
            // Said under the action when it is not available here (a group by SMS has no server for it).
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

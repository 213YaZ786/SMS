package com.yaz.sms.feature.thread

import androidx.compose.foundation.background
import com.yaz.sms.ui.glass.glassGround
import androidx.compose.foundation.clickable
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
import com.yaz.sms.ui.component.BoldButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaz.sms.core.dial.NumberActions
import com.yaz.sms.data.sms.Message
import com.yaz.sms.ui.component.ContactAvatar
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.HeroGlow
import com.yaz.sms.ui.component.ZoneAlertDialog
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
    vanish: Int?,
    messages: List<Message>,
    stranger: (Message) -> Boolean,
    /** Its media wait for a tap (a stranger's, when Settings asks so); asked when shown. */
    holdMedia: (Message) -> Boolean = { false },
    calls: CallChoices,
    silencedUntil: Long?,
    onSearch: () -> Unit,
    onSilence: () -> Unit,
    onBackground: (() -> Unit)?,
    onEncryption: () -> Unit,
    onName: (String) -> Unit,
    onVanish: (Int) -> Unit,
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
    var choosingSignature by remember { mutableStateOf(false) }
    var choosingVanish by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    if (choosingSignature) SignatureDialog(address) { choosingSignature = false }
    if (naming) GroupNameDialog(if (group && name.contains(',')) "" else name, onDone = { n ->
        naming = false
        onName(n)
    }) { naming = false }
    if (choosingVanish && vanish != null) VanishDialog(vanish, onPick = onVanish) { choosingVanish = false }
    if (confirmBlock) ZoneAlertDialog(
        onDismissRequest = { confirmBlock = false },
        title = { Text("Block $name?") },
        text = { Text("Calls and messages from this number will not reach you.") },
        confirmButton = {
            BoldButton(filled = true, onClick = {
                confirmBlock = false
                NumberActions.block(context, address)
                blocked = NumberActions.isBlocked(context, address)
            }) { Text("Block") }
        },
        dismissButton = { QuietButton(onClick = { confirmBlock = false }) { Text("Cancel") } }
    )

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize().glassGround(com.yaz.sms.ui.glass.LocalGlass.current, MaterialTheme.colorScheme.surface)) {
                HeroGlow(if (group) null else photo, height = 420.dp)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Who they are.
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                            if (group && groupTools != null) Box(
                                Modifier.clip(CircleShape).clickable(onClickLabel = "Group picture") { groupUi.needsChat(groupTools) { groupUi.pick?.invoke() } }
                            ) { GroupFace(groupTools.picture, name, 112.dp) }
                            else ContactAvatar(name, if (group) null else photo, 112.dp, look = if (group) null else com.yaz.sms.ui.component.rememberLook(address).value)
                            Spacer(Modifier.height(14.dp))
                            Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            number?.takeIf { it != name }?.let {
                                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (encrypted) {
                                Spacer(Modifier.height(10.dp))
                                FloatingPane(shape = CircleShape, onClick = onEncryption) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                        Icon(AppIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("End-to-end encrypted", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                    // What can be done with them, side by side and onto a second line.
                    item {
                        // Lines as even as can be: 6 = 3 + 3, 5 = 3 + 2.
                        // Someone not saved yet: adding them comes first, at hand.
                        val unsaved = !group && address.count(Char::isDigit) >= 3 && contactId == null
                        val count = listOfNotNull(calls.phone, calls.encrypted, calls.video, onBackground).size + 2 + (if (unsaved) 1 else 0)
                        // Cards in two columns, as the rest of the page and as wide as it (560 dp, the members and
                        // the media below); the calls in their colour.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            maxItemsInEachRow = 2,
                            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                        ) {
                            if (unsaved) Tile(AppIcons.PersonAdd, "Add contact", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) { NumberActions.addContact(context, address) }
                            calls.phone?.let { Tile(AppIcons.Call, "Call", CallGreen, Color.White, it) }
                            calls.encrypted?.let { Tile(AppIcons.Lock, "Encrypted call", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, it) }
                            calls.video?.let { Tile(AppIcons.Videocam, "Video call", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, it) }
                            Tile(AppIcons.Search, "Search", null, null) {
                                onClose()
                                onSearch()
                            }
                            Tile(if (silencedUntil != null) AppIcons.NotificationsOn else AppIcons.NotificationsOff, if (silencedUntil != null) "Silenced" else "Silence", null, null, onSilence)
                            onBackground?.let { Tile(AppIcons.Palette, "Background", null, null, it) }
                        }
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
                            // A private person of the Contacts app (no id in Android's contacts): nothing to open or add.
                            if (!group && contactId != null && contactId > 0) Row(AppIcons.Person, "Contact") { NumberActions.openContact(context, contactId) }
                            if (!group) Row(AppIcons.Copy, "Copy number") { NumberActions.copy(context, address) }
                            // Kept in the contact by the Contacts app when it is on the phone; here otherwise.
                            if (!group) Row(AppIcons.Vibration, "Vibration") {
                                if (contactId == null || contactId < 0 || !com.yaz.sms.core.dial.ContactLook.openInContacts(context, contactId)) choosingSignature = true
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
                                Row(AppIcons.AlternateEmail, if (groupTools.mentionsOn) "Mentions · Notify me" else "Mentions · Don't notify") {
                                    groupTools.onMentions(!groupTools.mentionsOn)
                                }
                                Row(AppIcons.ExitToApp, "Leave group", danger = true, hint = hint) { groupUi.needsChat(groupTools) { groupUi.leaving = true } }
                            }
                            if (vanish != null) Row(AppIcons.Timer, if (vanish > 0) "Vanishing messages · ${vanishLabel(vanish)}" else "Vanishing messages") { choosingVanish = true }
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
                                    "Members · ${groupTools.members.size}",
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
                                Text("${kind.label} · ${these.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(start = 6.dp))
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

/** A card, its icon over its name; filled in its colour when it calls. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.Tile(icon: ImageVector, label: String, fill: Color?, tint: Color?, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(24.dp)
    val content: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = tint ?: LocalContentColor.current, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
    if (fill != null) androidx.compose.material3.Surface(
        shape = shape,
        color = fill,
        shadowElevation = 3.dp,
        onClick = { haptics.firm(); onClick() },
        modifier = Modifier.weight(1f).fillMaxRowHeight().heightIn(min = 100.dp)
    ) { content() }
    else ZoneSurface(shape = shape, modifier = Modifier.weight(1f).fillMaxRowHeight().heightIn(min = 100.dp), onClick = { haptics.tick(); onClick() }) { content() }
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

/**
 * The top of a conversation: the person's face, and their name under it on
 * a small pill of glass, a lock beside it when the chat is encrypted; a tap
 * on either opens their page.
 */
@Composable
fun NameBlock(name: String, photo: String?, encrypted: Boolean, onOpen: () -> Unit, look: com.yaz.sms.core.dial.ContactLook.Look? = null) {
    val haptics = rememberHaptics()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClickLabel = "About them"
        ) {
            haptics.tick()
            onOpen()
        }
    ) {
        // The name alone, in its pill: the face would be too small to help.
        FloatingPane(shape = CircleShape, onClick = {
            haptics.tick()
            onOpen()
        }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                if (encrypted) {
                    Icon(AppIcons.Lock, contentDescription = "Encrypted", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

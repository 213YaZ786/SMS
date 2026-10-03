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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    calls: CallChoices,
    silencedUntil: Long?,
    onSearch: () -> Unit,
    onSilence: () -> Unit,
    onBackground: (() -> Unit)?,
    onEncryption: () -> Unit,
    onName: (String) -> Unit,
    onVanish: (Int) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val items = remember(messages) { gather(messages, stranger) }
    var seeAll by remember { mutableStateOf<Kind?>(null) }
    var showAll by remember { mutableStateOf(false) }
    if (showAll) SharedPage(name, messages, stranger, start = seeAll) { showAll = false }
    var blocked by remember(address) { mutableStateOf(NumberActions.isBlocked(context, address)) }
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
            TextButton(onClick = {
                confirmBlock = false
                NumberActions.block(context, address)
                blocked = NumberActions.isBlocked(context, address)
            }) { Text("Block") }
        },
        dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Cancel") } }
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
                            ContactAvatar(name, if (group) null else photo, 112.dp, look = if (group) null else com.yaz.sms.ui.component.rememberLook(address).value)
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
                        val count = listOfNotNull(calls.phone, calls.encrypted, calls.video, onBackground).size + 2
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            maxItemsInEachRow = if (count <= 4) count else (count + 1) / 2,
                            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                        ) {
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
                    // What they and the user sent each other, by kind.
                    Kind.entries.forEach { kind ->
                        val these = items.filter { kindOf(it) == kind }
                        if (these.isEmpty()) return@forEach
                        item(key = "head/$kind") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 6.dp)) {
                                Text("${kind.label} · ${these.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(start = 6.dp))
                                if (these.size > (if (kind == Kind.PICTURES) 6 else 3)) TextButton(onClick = {
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
                                            line.forEach { PictureCell(it.part, Modifier.weight(1f)) }
                                            repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                        } else {
                            these.take(3).forEachIndexed { i, it -> item(key = "$kind/$i") { Line(it) } }
                        }
                    }
                    // About them.
                    item {
                        val digits = !group && address.count(Char::isDigit) >= 3
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 8.dp)) {
                            if (digits && NumberActions.canShowCalls(context)) Row(AppIcons.Recents, "Calls with them") { NumberActions.showCalls(context, address) }
                            // A private person of the Contacts app (no id in Android's contacts): nothing to open or add.
                            if (contactId != null && contactId > 0) Row(AppIcons.Person, "Contact") { NumberActions.openContact(context, contactId) }
                            else if (digits && contactId == null) Row(AppIcons.PersonAdd, "Add to contacts") { NumberActions.addContact(context, address) }
                            if (!group) Row(AppIcons.Copy, "Copy number") { NumberActions.copy(context, address) }
                            // Kept in the contact by the Contacts app when it is on the phone; here otherwise.
                            if (!group) Row(AppIcons.Vibration, "Vibration") {
                                if (contactId == null || contactId < 0 || !com.yaz.sms.core.dial.ContactLook.openInContacts(context, contactId)) choosingSignature = true
                            }
                            if (group) Row(AppIcons.Create, "Name the group") { naming = true }
                            if (vanish != null) Row(AppIcons.Timer, if (vanish > 0) "Vanishing messages · ${vanishLabel(vanish)}" else "Vanishing messages") { choosingVanish = true }
                            if (!group && NumberActions.canBlock(context)) Row(AppIcons.Block, if (blocked) "Unblock" else "Block", danger = !blocked) {
                                if (blocked) {
                                    NumberActions.unblock(context, address)
                                    blocked = NumberActions.isBlocked(context, address)
                                } else confirmBlock = true
                            }
                        }
                    }
                }
                Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) { FloatingAction(AppIcons.ArrowBack, "Back", onClose) }
            }
        }
    }
}

/** A round button of glass, its name under it; coloured when it calls. */
@Composable
private fun Tile(icon: ImageVector, label: String, fill: Color?, tint: Color?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
        if (fill != null) {
            GlassCallButton(icon, label, fill, onClick, iconTint = tint ?: Color.White)
        } else {
            val haptics = rememberHaptics()
            FloatingPane(shape = CircleShape, onClick = {
                haptics.tick()
                onClick()
            }) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** A line of glass for something about the person. */
@Composable
private fun Row(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    ZoneSurface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), onClick = {
        haptics.tick()
        onClick()
    }) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Icon(icon, contentDescription = null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current)
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

package com.yaz.sms.feature.compose

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.provider.Telephony
import android.telephony.SmsManager
import android.widget.Toast
import kotlinx.coroutines.Job
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.yaz.sms.core.dial.Numbers
import com.yaz.sms.core.dial.People
import com.yaz.sms.core.dial.T9
import com.yaz.sms.core.mms.OwnNumbers
import com.yaz.sms.data.settings.BroadcastStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yaz.sms.data.contacts.PhoneBook
import com.yaz.sms.ui.component.ContactAvatar
import com.yaz.sms.ui.component.EmptyZone
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingFrame
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.FloatingTop
import com.yaz.sms.ui.component.SearchPill
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import org.koin.compose.koinInject

/**
 * Who to write to: a name found among the contacts, or any number typed.
 * Every number of a contact is its own line, so the right one is chosen.
 */
@Composable
fun NewMessageScreen(onBack: () -> Unit, onPick: (String) -> Unit) {
    val book: PhoneBook = koinInject()
    var allowed by remember { mutableStateOf(book.canRead()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(allowed) { if (allowed) book.refresh() }
    val entries by book.entries.collectAsState()
    val people = remember(entries) { People.of(entries) }
    var query by rememberSaveable { mutableStateOf("") }
    // Several people: a group, sent as picture messages to all.
    var group by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var each by rememberSaveable { mutableStateOf(false) }
    val region = remember { Numbers.countryIso(context) }
    val ownNumbers = remember { OwnNumbers.of(context) }
    val say: (String) -> Unit = { words -> Toast.makeText(context, words, Toast.LENGTH_SHORT).show() }
    // The carrier's MMS settings, read once for the recipient limit (MmsConfig's mValues).
    val mValues = remember { runCatching { context.getSystemService(SmsManager::class.java).carrierConfigValues }.getOrNull() }
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsConfig.java#getRecipientLimit
    fun getRecipientLimit(): Int {
        val limit = mValues?.getInt(CONFIG_RECIPIENT_LIMIT, CONFIG_RECIPIENT_LIMIT_DEFAULT) ?: CONFIG_RECIPIENT_LIMIT_DEFAULT
        return if (limit < 0) Int.MAX_VALUE else limit
    }
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/data/ContactPickerData.java#isTooManyParticipants
    fun isTooManyParticipants(participantCount: Int): Boolean = participantCount > getRecipientLimit()
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/data/ContactPickerData.java#getCanAddMoreParticipants
    fun getCanAddMoreParticipants(participantCount: Int): Boolean = participantCount < getRecipientLimit()
    var canAddMore by remember { mutableStateOf(getCanAddMoreParticipants(chosen.size)) }
    var mSelectedPhoneNumbers by remember { mutableStateOf(chosen) }
    // The chips changed: whether one more fits is told again, the copy of the selected numbers refreshed.
    // Not ported: MODE_PICK_INITIAL_CONTACT, MODE_CHIPS_ONLY, maybeGetOrCreateConversation, onInitiateAddMoreParticipants
    // (this screen has one mode), mHost.onParticipantCountChanged (the screen is the host), invalidateContactLists.
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/ui/contact/ContactPickerFragment.java#onContactChipsChanged
    fun onContactChipsChanged(oldCount: Int, newCount: Int) {
        if (oldCount == newCount) return
        canAddMore = getCanAddMoreParticipants(newCount)
        mSelectedPhoneNumbers = chosen
    }
    fun pick(number: String) {
        if (!group) {
            onPick(number)
            return
        }
        // Two numbers are one when Android says so ("0612345678" and "+33612345678": T9.sameDigits, PhoneNumberUtils).
        val already = mSelectedPhoneNumbers.firstOrNull { T9.sameDigits(T9.clean(it), T9.clean(number), region) }
        // The user's own number is left out of a group.
        if (already == null && OwnNumbers.isOwn(number, ownNumbers)) {
            say("Your own number is left out")
            query = ""
            return
        }
        // Group (MMS) only: one SMS each sends no picture message, so the carrier's limit does not bind it.
        if (already == null && !each && !canAddMore) {
            say("Too many participants in a conversation")
            return
        }
        val oldCount = chosen.size
        chosen = if (already != null) chosen - already else chosen + number
        query = ""
        onContactChipsChanged(oldCount, chosen.size)
    }
    val found = remember(people, query) { People.search(people, query) }
    val typed = query.filter { it.isDigit() || it == '+' }.takeIf { it.count(Char::isDigit) >= 3 && query.none(Char::isLetter) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
    val chat: com.yaz.sms.core.chat.RichChat = koinInject()
    val chatOn = chat.enabled
    // Two ways to write to several people, as AOSP Messaging's "Group messaging" setting offers them
    // (InsertNewMessageAction.executeAction, https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/datamodel/action/InsertNewMessageAction.java:
    // one group MMS, or a broadcast SMS, one SMS to each); here chosen per group. Group (MMS) by default, as AOSP.
    // The choice marks the thread Android gives those people (BroadcastStore), read by PendingSends.
    // The group opened: too many people said (the carrier's limit, a group MMS), and a thread Android could not give
    // kept on this screen with its words, never a group MMS that was meant as one SMS each. A second tap while the
    // first is at work is ignored (mMonitor). It is this file's writeToThem, with AOSP's checks.
    // Not ported: writeToThem (this function replaces it), getRecipientParticipantDataForConversationCreation (the chips
    // are the numbers), GetOrCreateConversationAction (Telephony.Threads.getOrCreateThreadId; the conversation itself
    // opens through onPick), UiUtils.showToast (Toast).
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/ui/contact/ContactPickerFragment.java#maybeGetOrCreateConversation
    // https://raw.githubusercontent.com/213YaZ786/SMS/main/app/src/main/java/com/yaz/sms/feature/compose/NewMessageScreen.kt#writeToThem
    var mMonitor by remember { mutableStateOf<Job?>(null) }
    fun maybeGetOrCreateConversation() {
        val participants = mSelectedPhoneNumbers
        val oneEach = each && participants.size >= 2
        if (!oneEach && isTooManyParticipants(participants.size)) {
            say("Too many participants in a conversation")
        } else if (participants.isNotEmpty() && mMonitor == null) {
            mMonitor = scope.launch {
                if (participants.size >= 2) {
                    val store = BroadcastStore.of(context)
                    val threadId = withContext(Dispatchers.IO) {
                        runCatching { Telephony.Threads.getOrCreateThreadId(context, participants.toSet()) }.getOrNull()
                    }
                    if (threadId == null && oneEach) {
                        say("Could not start the group. Try again.")
                        mMonitor = null
                        return@launch
                    }
                    if (threadId != null) withContext(Dispatchers.IO) { if (oneEach) store.mark(threadId) else store.unmark(threadId) }
                }
                onPick(participants.joinToString(","))
                mMonitor = null
            }
        }
    }
    var inviting by remember { mutableStateOf(false) }
    if (inviting) InviteDialog(onDismiss = { inviting = false }) { address ->
        inviting = false
        onPick(address)
    }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    FloatingFrame(
        bottom = bottom,
        scroll = list,
        top = {
            FloatingTop(
                title = if (group) "New group" else "New message",
                leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) },
                trailing = {
                    if (group && chosen.isNotEmpty()) FloatingAction(AppIcons.Send, "Write to them", { maybeGetOrCreateConversation() })
                    else FloatingAction(AppIcons.Group, if (group) "One person" else "A group", {
                        val oldCount = chosen.size
                        group = !group
                        chosen = emptyList()
                        onContactChipsChanged(oldCount, 0)
                    })
                }
            )
            if (group && chosen.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    chosen.forEach { n ->
                        val name = people.firstOrNull { p -> p.numbers.any { it.number == n } }?.name ?: n
                        FloatingPane(shape = CircleShape, accent = true, onClick = {
                            val oldCount = chosen.size
                            chosen = chosen - n
                            onContactChipsChanged(oldCount, chosen.size)
                        }) {
                            Text(name.substringBefore(' '), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            if (group && chosen.size >= 2) {
                // Side by side when both names fit on one line in their half, else stacked full width: the
                // names measured with TextMeasurer in the current font and language, as PersonActions in this app
                // measures its labels (https://developer.android.com/develop/ui/compose/text/measure-text-layout);
                // the names wrap between words, never cut.
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    val modes = listOf("Group (MMS)" to false, "One SMS each" to true)
                    val style = MaterialTheme.typography.labelLarge
                    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val widest = modes.maxOf { measurer.measure(it.first, style, softWrap = false, maxLines = 1).size.width }
                    val sideBySide = with(density) { widest.toDp() } * 2 + 28.dp * 2 + 8.dp <= maxWidth
                    val pill: @Composable (Pair<String, Boolean>, Modifier) -> Unit = { (label, value), modifier ->
                        FloatingPane(shape = CircleShape, accent = each == value, modifier = modifier, onClick = { each = value }) {
                            Text(
                                label,
                                style = style,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                    if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modes.forEach { pill(it, Modifier.weight(1f)) }
                    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        modes.forEach { pill(it, Modifier.fillMaxWidth()) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                SearchPill(query, { query = it }, hint = "Name or number", modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(), floating = true)
            }
        }
    ) { padding ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Someone with no number: an encrypted chat started by a code or a link.
            if (!group && query.isBlank() && chatOn) item(key = "nonumber") {
                Line(null, null, null, "Someone without a number", "By a code or a link, encrypted", false) { inviting = true }
            }
            if (typed != null) {
                item(key = "typed") { Line(null, null, null, if (group) "Add $typed" else "Send to $typed", null, false) { pick(typed) } }
            }
            // A typed entry that is no number says so, as AOSP's "Invalid contact" toast does for a chip it cannot resolve
            // (onInvalidContactChipsPruned): under the field, nothing to send to. A name that finds nobody says so too.
            // Not ported: onInvalidContactChipsPruned's Toast (the line stays under the field), onEntryComplete (no done key here).
            if (typed == null && found.isEmpty() && query.isNotBlank()) {
                item(key = "invalid") {
                    val number = query.any { it.isDigit() || it == '+' }
                    Text(
                        if (number) "Not a valid number" else "No contact found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (number) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            if (!allowed) {
                item(key = "allow") {
                    EmptyZone(
                        title = "Find your contacts here",
                        message = "Or type a number above.",
                        icon = AppIcons.Person,
                        actionLabel = "Allow contacts",
                        onAction = { ask.launch(Manifest.permission.READ_CONTACTS) }
                    )
                }
            }
            found.forEach { person ->
                items(person.numbers, key = { "n/${person.id}/${it.number}" }) { n ->
                    Line(person.name, person.photo, person.look, person.name, n.number, chosen.any { T9.sameDigits(T9.clean(it), T9.clean(n.number), region) }) { pick(n.number) }
                }
            }
        }
    }
}

/** The carrier config key of the recipient limit (SmsManager.MMS_CONFIG_RECIPIENT_LIMIT) and the limit when it gives none (MmsConfig). */
private const val CONFIG_RECIPIENT_LIMIT = SmsManager.MMS_CONFIG_RECIPIENT_LIMIT
private const val CONFIG_RECIPIENT_LIMIT_DEFAULT = Int.MAX_VALUE

@Composable
private fun Line(name: String?, photo: String?, look: com.yaz.sms.core.dial.ContactLook.Look?, title: String, under: String?, chosen: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
    val shape = ShapeDefaults.ExtraLarge
    ZoneSurface(
        shape = shape,
        accent = chosen,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().clip(shape).clickable {
            haptics.tick()
            onClick()
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (name != null) {
                ContactAvatar(name, photo, 44.dp, look = look)
            } else {
                ZoneSurface(shape = CircleShape, accent = true, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Send, contentDescription = null) }
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                under?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
        }
    }
}

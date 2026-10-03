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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.yaz.sms.core.dial.People
import com.yaz.sms.data.contacts.PhoneBook
import com.yaz.sms.ui.component.ContactAvatar
import com.yaz.sms.ui.component.EmptyZone
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.feature.conversations.SectionsDock
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
fun NewMessageScreen(onBack: () -> Unit, onPick: (String) -> Unit, onSection: (com.yaz.sms.core.sms.Filter) -> Unit = {}) {
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
    fun pick(number: String) {
        if (!group) {
            onPick(number)
            return
        }
        chosen = if (number in chosen) chosen - number else chosen + number
        query = ""
    }
    val found = remember(people, query) { People.search(people, query) }
    val typed = query.filter { it.isDigit() || it == '+' }.takeIf { it.count(Char::isDigit) >= 3 && query.none(Char::isLetter) }
    // Room for the sections' pill, kept here as on the list.
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + com.yaz.sms.ui.component.DockClearance
    val messages: com.yaz.sms.data.sms.Messages = koinInject()
    val conversations by messages.conversations.collectAsState()
    val settingsStore: com.yaz.sms.data.settings.SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()

    FloatingFrame(
        bottom = bottom,
        overlay = {
            // The list's pill stays: a section takes back to the list on it.
            SectionsDock(
                filter = null,
                unread = conversations.any { it.unread > 0 && !com.yaz.sms.core.sms.Lists.isArchived(it, settings.archived) },
                onSelect = onSection
            )
        },
        top = {
            FloatingTop(
                title = if (group) "New group" else "New message",
                leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) },
                trailing = {
                    if (group && chosen.isNotEmpty()) FloatingAction(AppIcons.Send, "Write to them", { onPick(chosen.joinToString(",")) })
                    else FloatingAction(AppIcons.Group, if (group) "One person" else "A group", {
                        group = !group
                        chosen = emptyList()
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
                        FloatingPane(shape = CircleShape, accent = true, onClick = { chosen = chosen - n }) {
                            Text(name.substringBefore(' '), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                SearchPill(query, { query = it }, hint = "Name or number", modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(), floating = true)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (typed != null) {
                item(key = "typed") { Line(null, null, null, if (group) "Add $typed" else "Send to $typed", null, false) { pick(typed) } }
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
                    Line(person.name, person.photo, person.look, person.name, n.number, n.number in chosen) { pick(n.number) }
                }
            }
        }
    }
}

@Composable
private fun Line(name: String?, photo: String?, look: com.yaz.sms.core.dial.ContactLook.Look?, title: String, under: String?, chosen: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(22.dp)
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

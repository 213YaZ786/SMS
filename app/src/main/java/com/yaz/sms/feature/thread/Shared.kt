package com.yaz.sms.feature.thread

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaz.sms.core.mms.ContentType
import com.yaz.sms.core.link.LinkCheck
import com.yaz.sms.core.link.LinkCleaner
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.core.sms.Codes
import com.yaz.sms.core.sms.Finds
import com.yaz.sms.data.sms.Message
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.TitlePill
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What two people sent each other, as the Shared page lists it. */
internal sealed class Item(val date: Long) {
    class Picture(val part: MmsPart, date: Long) : Item(date)
    class File(val part: MmsPart, date: Long) : Item(date)
    class Link(val url: String, val doubtful: LinkCheck.Verdict?, date: Long) : Item(date)
    class Code(val code: String, date: Long) : Item(date)
    class Parcel(val number: String, date: Long) : Item(date)
    class Meeting(val at: LocalDateTime, val text: String, date: Long) : Item(date)
}

internal enum class Kind(val label: String, val icon: ImageVector) {
    PICTURES("Photos", AppIcons.Image),
    FILES("Files", AppIcons.AttachFile),
    LINKS("Links", AppIcons.Forward),
    DATES("Dates", AppIcons.Schedule),
    PARCELS("Parcels", AppIcons.Archive),
    CODES("Codes", AppIcons.Lock)
}

internal fun kindOf(item: Item) = when (item) {
    is Item.Picture -> Kind.PICTURES
    is Item.File -> Kind.FILES
    is Item.Link -> Kind.LINKS
    is Item.Meeting -> Kind.DATES
    is Item.Parcel -> Kind.PARCELS
    is Item.Code -> Kind.CODES
}

/** Everything worth finding again in [messages], newest first. */
internal fun gather(messages: List<Message>, stranger: (Message) -> Boolean): List<Item> = messages.sortedByDescending { it.date }.flatMap { m ->
    buildList {
        m.parts.forEach { part ->
            when {
                ContentType.isImageType(part.contentType) || ContentType.isVideoType(part.contentType) -> add(Item.Picture(part, m.date))
                ContentType.isAudioType(part.contentType) -> Unit
                !part.contentType.startsWith("text/") -> add(Item.File(part, m.date))
            }
        }
        links(m.body).distinct().forEach { url -> add(Item.Link(url, if (m.box == com.yaz.sms.data.sms.Box.RECEIVED) LinkCheck.check(url, stranger(m)) else null, m.date)) }
        Codes.find(m.body)?.let { add(Item.Code(it, m.date)) }
        Finds.parcel(m.body)?.let { add(Item.Parcel(it, m.date)) }
        Finds.appointment(m.body, Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()).toLocalDate())?.let { add(Item.Meeting(it, m.body, m.date)) }
    }
}

/**
 * What was shared with [name]: photos and videos, files, links, dates,
 * parcels and codes found in the conversation, by kind, newest first;
 * each acts as it does in the conversation (open, copy, add to calendar).
 */
@Composable
internal fun SharedPage(name: String, messages: List<Message>, stranger: (Message) -> Boolean, start: Kind? = null, onClose: () -> Unit) {
    val items = remember(messages) { gather(messages, stranger) }
    val kinds = remember(items) { Kind.entries.filter { k -> items.any { kindOf(it) == k } } }
    var only by remember { mutableStateOf(start) }
    val shown = items.filter { only == null || kindOf(it) == only }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (kinds.size > 1) 160.dp else 100.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (shown.isEmpty()) item {
                        Text("Nothing shared yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 40.dp))
                    }
                    // Photos three a line; everything else one a line.
                    val pictures = shown.filterIsInstance<Item.Picture>()
                    items(pictures.chunked(3)) { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                            line.forEach { PictureCell(it.part, Modifier.weight(1f)) }
                            repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    items(shown.filter { it !is Item.Picture }) { item -> Line(item) }
                }
                // The top floats over the list, in glass.
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FloatingAction(AppIcons.ArrowBack, "Back", onClose)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { TitlePill("Shared with $name") }
                        Spacer(Modifier.width(48.dp))
                    }
                    if (kinds.size > 1) FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        Chip("All", only == null) { only = null }
                        kinds.forEach { k -> Chip("${k.label} ${items.count { kindOf(it) == k }}", only == k) { only = k } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, chosen: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    FloatingPane(shape = CircleShape, accent = chosen, onClick = {
        haptics.tick()
        onClick()
    }) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

@Composable
internal fun PictureCell(part: MmsPart, modifier: Modifier) {
    var large by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val image by rememberPicture(part.uri, 360)
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable {
            if (ContentType.isImageType(part.contentType)) large = true else openOutside(context, part)
        },
        contentAlignment = Alignment.Center
    ) {
        val shown = image
        if (shown != null) Image(shown, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(if (ContentType.isVideoType(part.contentType)) AppIcons.Play else AppIcons.Image, contentDescription = null)
    }
    if (large) PictureViewer(part.uri, onClose = { large = false })
}

/** One thing shared, on a line of glass: what it is, when, and what a tap does. */
@Composable
internal fun Line(item: Item) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var risky by remember { mutableStateOf<Pair<String, LinkCheck.Verdict>?>(null) }
    risky?.let { (url, verdict) -> RiskyLinkDialog(url, verdict) { risky = null } }
    val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(item.date).atZone(ZoneId.systemDefault()))
    val (icon, title, under) = when (item) {
        is Item.File -> Triple(AppIcons.AttachFile, item.part.name ?: "File", day)
        is Item.Link -> Triple(if (item.doubtful != null) AppIcons.Warning else AppIcons.Forward, runCatching { java.net.URI(item.url).host }.getOrNull() ?: item.url, item.url)
        is Item.Code -> Triple(AppIcons.Lock, item.code, "Code · $day")
        is Item.Parcel -> Triple(AppIcons.Archive, item.number, "Parcel · $day")
        is Item.Meeting -> Triple(AppIcons.Schedule, DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(item.at), item.text)
        is Item.Picture -> Triple(AppIcons.Image, "Photo", day)
    }
    ZoneSurface(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        onClick = {
            haptics.tick()
            when (item) {
                is Item.File -> openOutside(context, item.part)
                is Item.Link -> if (item.doubtful != null) risky = item.url to item.doubtful
                    else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(LinkCleaner.clean(item.url))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                is Item.Code -> copy(context, "Code", item.code, sensitive = true)
                is Item.Parcel -> copy(context, "Parcel", item.number)
                is Item.Meeting -> runCatching {
                    val begin = item.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    context.startActivity(
                        Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000)
                            .putExtra(CalendarContract.Events.DESCRIPTION, item.text)
                    )
                }
                is Item.Picture -> Unit
            }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(icon, contentDescription = null, tint = if (item is Item.Link && item.doubtful != null) (if (item.doubtful.risk == LinkCheck.Risk.LISTED) MaterialTheme.colorScheme.error else cautionColor()) else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(under, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

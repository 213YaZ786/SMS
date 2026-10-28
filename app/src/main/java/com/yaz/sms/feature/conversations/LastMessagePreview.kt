package com.yaz.sms.feature.conversations

import android.content.Context
import android.media.MediaPlayer
import android.provider.Telephony
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.mms.ContentType
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.core.mms.mediaWord
import com.yaz.sms.core.security.MediaCheck
import com.yaz.sms.core.sms.Effects
import com.yaz.sms.core.sms.Markup
import com.yaz.sms.core.sms.Places
import com.yaz.sms.core.sms.ReactionGroups
import com.yaz.sms.core.sms.SmsReactions
import com.yaz.sms.data.sms.Conversation
import com.yaz.sms.feature.thread.ReactionChips
import com.yaz.sms.feature.thread.formatShort
import com.yaz.sms.feature.thread.isFilm
import com.yaz.sms.feature.thread.isLocationCard
import com.yaz.sms.feature.thread.isPicture
import com.yaz.sms.feature.thread.readLocation
import com.yaz.sms.feature.thread.rememberKind
import com.yaz.sms.feature.thread.rememberPicture
import com.yaz.sms.feature.thread.rememberVideoStill
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * What the list's last-message bubble shows besides words: a reaction as the message reacted to, a small
 * thumbnail for a picture or a film, an icon and the essential for the rest. The media and glyph logic is
 * Signal-Android's ConversationListItem.java (AGPL-3.0; the media prefix of the body is not in the words here,
 * a message's caption is kept apart in Conversation.caption), the rest ported from this app's own code,
 * each piece named on its function. The security rules stay those of the thread: a picture only through the
 * isolated decoder (SafeImages, by rememberPicture), a film's still from Android's media process
 * (rememberVideoStill), the real type from the first bytes (MediaCheck, by rememberKind), and from an unknown
 * number, while Settings "Show media from unknown numbers" is off, nothing of the message is read or decoded
 * (the thread's HeldTile / MediaCell [held]): an icon and the word of its declared type instead.
 */

/** The height of a thumbnail in the bubble. */
private val THUMB_HEIGHT = 56.dp

/** What the newest message is, when it is more than words. Pure: read from what the list already holds. */
internal sealed interface Lead {
    /** A reaction to a message, the emoji and what it reacts to. */
    class Reaction(val parsed: SmsReactions.Parsed) : Lead
    class Visual(val part: MmsPart) : Lead
    class Sound(val part: MmsPart) : Lead
    class Card(val part: MmsPart) : Lead
    class Spot(val part: MmsPart) : Lead
    class FileOf(val part: MmsPart) : Lead
    /** A place written in the words (a map link alone). */
    class SpotLink(val place: Places.Place) : Lead
    /** A link alone: its site's name. */
    class Site(val host: String) : Lead

    /** The words that follow it on the next line. */
    fun caption(c: Conversation): String = when (this) {
        is Visual, is Sound, is Card, is Spot, is FileOf -> c.caption
        is SpotLink, is Site, is Reaction -> ""
    }

    companion object {
        /**
         * Which lead the newest message of [c] has, null for plain words and for a draft. The parts come from
         * the conversation (read with MmsStore.parts for the snippet); the kind of each is its declared type,
         * as feature/thread/Media.kt's MediaTile chooses a tile (picture and film, sound, location card, contact
         * card, other file). A text alone: a reaction as SmsReactions parses it; a single map link as
         * Places.find reads it (Places.kt); a single link as its host.
         */
        fun of(c: Conversation): Lead? {
            if (c.draft != null) return null
            if (c.parts.isNotEmpty()) {
                val visual = c.parts.firstOrNull { ContentType.isImageType(it.contentType) || ContentType.isVideoType(it.contentType) }
                val sound = c.parts.firstOrNull { ContentType.isAudioType(it.contentType) }
                val part = visual ?: sound ?: c.parts.first()
                val type = part.contentType.lowercase()
                return when {
                    part === visual -> Visual(part)
                    part === sound -> Sound(part)
                    isLocationCard(part) -> Spot(part)
                    type.contains("vcard") -> Card(part)
                    else -> FileOf(part)
                }
            }
            if (!c.failed) SmsReactions.parse(c.snippet)?.takeIf { !it.removed }?.let { return Reaction(it) }
            val word = c.snippet.trim()
            if (word.isEmpty() || word.length > 2048 || word.any { it.isWhitespace() }) return null
            Places.find(word)?.let { return SpotLink(it) }
            if (word.startsWith("http://", ignoreCase = true) || word.startsWith("https://", ignoreCase = true)) {
                val host = runCatching { java.net.URI(word).host }.getOrNull()?.removePrefix("www.")?.filter { !it.isISOControl() }?.take(60)
                if (!host.isNullOrBlank()) return Site(host)
            }
            return null
        }
    }
}

/**
 * The lead of a bubble, above its words. Signal-Android's conversation list item (ConversationListItem.java,
 * AGPL-3.0, https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/conversationlist/ConversationListItem.java:
 * getThreadDisplayBody gives each kind a glyph and its words through emphasisAdded, createFinalBodyWithMediaIcon
 * puts the snippet media's thumbnail before the words): a thumbnail for a picture or a film, a glyph and the
 * essential (the voice message's length, the file's name, the place's name, the card's name, the link's site)
 * for the rest. [hold]: from an unknown number with Settings asking so, nothing is read or decoded. [who] is
 * "you" or the person, for TalkBack ("Photo from Pieter de Vries").
 */
@Composable
internal fun LeadView(lead: Lead, who: String, hold: Boolean, ink: Color, weight: FontWeight, modifier: Modifier = Modifier) {
    when (lead) {
        is Lead.Reaction -> Unit
        is Lead.Visual -> if (hold) {
            val film = ContentType.isVideoType(lead.part.contentType)
            LeadLine(if (film) AppIcons.Play else AppIcons.Image, mediaWord(lead.part.contentType), "${if (film) "Video" else "Photo"} from $who", ink, weight, modifier)
        } else VisualLead(lead.part, who, ink, weight, modifier)
        is Lead.Sound -> if (hold) LeadLine(AppIcons.Mic, "Voice message", "Voice message from $who", ink, weight, modifier)
        else SoundLead(lead.part, who, ink, weight, modifier)
        is Lead.Card -> if (hold) LeadLine(AppIcons.Person, "Contact card", "Contact card from $who", ink, weight, modifier)
        else CardLead(lead.part, who, ink, weight, modifier)
        is Lead.Spot -> if (hold) LeadLine(AppIcons.Place, "Location", "Location from $who", ink, weight, modifier)
        else SpotLead(lead.part, who, ink, weight, modifier)
        is Lead.FileOf -> {
            val name = lead.part.name?.filter { !it.isISOControl() }?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: mediaWord(lead.part.contentType)
            LeadLine(AppIcons.AttachFile, name, "File $name from $who", ink, weight, modifier)
        }
        is Lead.SpotLink -> {
            val name = lead.place.label?.filter { !it.isISOControl() }?.trim()?.take(60)?.takeIf { it.isNotEmpty() } ?: "Location"
            LeadLine(AppIcons.Place, name, "Location $name from $who", ink, weight, modifier)
        }
        is Lead.Site -> LeadLine(AppIcons.Link, lead.host, "Link to ${lead.host} from $who", ink, weight, modifier)
    }
}

/**
 * A glyph and one line of words, Signal's emphasisAdded with a Glyph (ConversationListItem.java, the glyph
 * before the words in the snippet), as WhatsApp's chat list shows what is not words. The icon's size and gap follow feature/thread/Media.kt's AttachmentRow (Element X's
 * TimelineItemAttachmentHeaderView, 8 dp between), smaller for a list line.
 */
@Composable
private fun LeadLine(icon: ImageVector, label: String, spoken: String, ink: Color, weight: FontWeight, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Icon(icon, contentDescription = null, tint = ink.takeOrElse { LocalContentColor.current }, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A picture or a film: its real kind from the first bytes first (feature/thread/Gallery.kt MediaCell: isPicture,
 * isFilm, rememberKind), then a thumbnail. As Signal's createFinalBodyWithMediaIcon (ConversationListItem.java:
 * the media is loaded at the thumbnail's size and drawn before the words; while it is not there, and when it
 * cannot be loaded (a null bitmap), the words stay: `return body`), the glyph and the word ("Photo", "Video")
 * show until the decoder has given the picture, and stay when it gives none. Signal's Glide is the isolated
 * decoder here (rememberPicture, SafeImages), a film's still comes from Android's media process
 * (rememberVideoStill). A declared picture whose bytes say otherwise is a plain file line.
 */
@Composable
private fun VisualLead(part: MmsPart, who: String, ink: Color, weight: FontWeight, modifier: Modifier) {
    val kind by rememberKind(part.uri)
    val side = with(LocalDensity.current) { 120.dp.roundToPx() }
    val declaredFilm = ContentType.isVideoType(part.contentType)
    // The words that stay when no thumbnail is there: Signal's createFinalBodyWithMediaIcon returns the body with
    // its media word when the bitmap is null (ConversationListItem.java); the word is this app's mediaWord.
    @Composable
    fun word() = LeadLine(if (declaredFilm) AppIcons.Play else AppIcons.Image, mediaWord(part.contentType), "${if (declaredFilm) "Video" else "Photo"} from $who", ink, weight, modifier)
    when {
        kind == null -> word()
        isPicture(part, kind) -> {
            val picture by rememberPicture(part.uri, side)
            val shown = picture
            if (shown == null) word() else Thumb(shown, film = false, length = null, spoken = "Photo from $who", modifier = modifier)
        }
        isFilm(part, kind) -> {
            val still by rememberVideoStill(part.uri, side)
            val frame = still?.frame
            if (frame == null) word() else Thumb(
                frame, film = true, length = still?.durationMs,
                spoken = "Video from $who" + (still?.durationMs?.let { ", ${formatShort(it)}" } ?: ""), modifier = modifier
            )
        }
        else -> {
            val name = part.name?.filter { !it.isISOControl() }?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "File"
            LeadLine(AppIcons.AttachFile, name, "File $name from $who", ink, weight, modifier)
        }
    }
}

/**
 * The thumbnail: [THUMB_HEIGHT] high, its ratio kept (within 3:4 and 3:1, the way Element X's
 * TimelineItemAspectRatioBox keeps a ratio within reach, feature/thread/Gallery.kt MediaAspectBox), rounded,
 * with the faint dark wash Signal puts over the snippet's thumbnail (ConversationListItem.java,
 * OverlayTransformation of transparent_black_08, rounded drawable); a film carries a small play mark and its
 * length on a pill (feature/thread/Gallery.kt FilmFace, Element X's VideoOverlay), smaller for a list.
 */
@Composable
private fun Thumb(frame: androidx.compose.ui.graphics.ImageBitmap, film: Boolean, length: Long?, spoken: String, modifier: Modifier = Modifier) {
    val ratio = frame.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height }?.coerceIn(0.75f, 3f) ?: 1f
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(THUMB_HEIGHT)
            .aspectRatio(ratio, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)))
        if (film) {
            Box(Modifier.size(22.dp).background(scrim, CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            length?.let {
                Text(
                    formatShort(it),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                        .background(scrim, RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/**
 * A voice message: a microphone and its length, read as feature/thread/Voice.kt VoiceTile reads it (Android's
 * MediaPlayer on the file, in the system's sandboxed media process, only once the first bytes say it is sound).
 * "Voice message" while the length is not known.
 */
@Composable
private fun SoundLead(part: MmsPart, who: String, ink: Color, weight: FontWeight, modifier: Modifier) {
    val context = LocalContext.current
    val kind by rememberKind(part.uri)
    val sound = kind == MediaCheck.Kind.AUDIO || kind == MediaCheck.Kind.VIDEO
    val length by produceState(0, part.uri, sound) {
        value = if (!sound) 0 else withContext(Dispatchers.IO) {
            runCatching { MediaPlayer.create(context, part.uri)?.let { p -> p.duration.also { p.release() } } }.getOrNull() ?: 0
        }
    }
    if (kind != null && !sound) {
        val name = part.name?.filter { !it.isISOControl() }?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "File"
        LeadLine(AppIcons.AttachFile, name, "File $name from $who", ink, weight, modifier)
    } else {
        val label = if (length > 0) formatShort(length.toLong()) else "Voice message"
        LeadLine(AppIcons.Mic, label, "Voice message from $who" + if (length > 0) ", ${formatShort(length.toLong())}" else "", ink, weight, modifier)
    }
}

/**
 * A contact card: a person and the name read from the card, as feature/thread/Media.kt MediaTile reads it (the
 * first FN line among the first 200). Read only once shown, so never from a number held.
 */
@Composable
private fun CardLead(part: MmsPart, who: String, ink: Color, weight: FontWeight, modifier: Modifier) {
    val context = LocalContext.current
    val name by produceState<String?>(null, part.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(part.uri)?.bufferedReader()?.use { r ->
                    r.lineSequence().take(200).firstOrNull { it.startsWith("FN:") || it.startsWith("FN;") }
                        ?.substringAfter(':')?.filter { !it.isISOControl() }?.trim()?.take(60)?.takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }
    }
    val label = name ?: "Contact card"
    LeadLine(AppIcons.Person, label, (if (name != null) "Contact card, $name" else "Contact card") + " from $who", ink, weight, modifier)
}

/**
 * A place shared as an iPhone's location card: a pin and the place's name, read by feature/thread/Media.kt's
 * readLocation (the first Places.CARD_MAX bytes) and Places.fromVLocation; "Location" when it has none.
 */
@Composable
private fun SpotLead(part: MmsPart, who: String, ink: Color, weight: FontWeight, modifier: Modifier) {
    val context = LocalContext.current
    val place by produceState<Places.Place?>(null, part.uri) {
        value = withContext(Dispatchers.IO) { readLocation(context, part.uri) }
    }
    val named = place?.label?.filter { !it.isISOControl() }?.trim()?.take(60)?.takeIf { it.isNotEmpty() }
    LeadLine(AppIcons.Place, named ?: "Location", (if (named != null) "Location, $named" else "Location") + " from $who", ink, weight, modifier)
}

/**
 * Whether the message a reaction reacts to was the user's own, read from the SMS of the thread (the newest 200)
 * with SmsReactions.quotes (Quik's findTargetMessage, text matched against the thread's messages). Null when no
 * message quotes it (a picture message's words are not looked in), the caller then takes the usual case: the
 * reaction is to the other side's message. Off the main thread.
 */
internal fun targetIsMine(context: Context, threadId: Long, quoted: String): Boolean? = runCatching {
    context.contentResolver.query(
        Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.BODY, Telephony.Sms.TYPE),
        "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}", arrayOf(threadId.toString()),
        "${Telephony.Sms.DATE} DESC LIMIT 200"
    )?.use { c ->
        var found: Boolean? = null
        while (found == null && c.moveToNext()) {
            val body = c.getString(0).orEmpty()
            if (SmsReactions.parse(body) != null) continue
            if (SmsReactions.quotes(body, quoted)) found = c.getInt(1) != Telephony.Sms.MESSAGE_TYPE_INBOX
        }
        found
    }
}.getOrNull()

/**
 * A reaction as the last event: the message reacted to, in the bubble of whoever wrote it (the accent for the
 * user's own), its words two lines at most, and the emoji as a chip on the bubble's corner, the conversation's
 * ReactionChips (feature/thread/ReactionChips.kt, Element X's reaction chip) with the user's own reaction in
 * the accent. A reaction to a picture or a film names it with an icon. The author of the words comes from the
 * thread's SMS ([targetIsMine]); until it is known, the other side's, as a reaction nearly always is.
 */
@Composable
internal fun ReactionBubble(
    c: Conversation,
    reaction: Lead.Reaction,
    who: String,
    unread: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val p = reaction.parsed
    val media = p.media
    val said = if (media != null) "" else Markup.plain(Effects.plain(p.quoted.trim().replace(Regex("\\s+"), " "))).take(400)
    val found by produceState<Boolean?>(null, c.threadId, c.date, p.quoted) {
        value = if (media != null) null else withContext(Dispatchers.IO) { targetIsMine(context, c.threadId, p.quoted) }
    }
    val targetMine = found ?: !c.fromMe
    val ink = when {
        targetMine -> Color.Unspecified
        unread -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val weight = if (unread) FontWeight.Medium else FontWeight.Normal
    val target = when (media) {
        "image" -> "a photo"
        "video" -> "a video"
        else -> said
    }
    val spoken = "$who reacted ${p.emoji} to: $target"
    Box(modifier.padding(bottom = 16.dp).clearAndSetSemantics { contentDescription = spoken }) {
        ZoneSurface(shape = shape, accent = targetMine) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 14.dp)) {
                if (media != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (media == "video") AppIcons.Play else AppIcons.Image, contentDescription = null, tint = ink.takeOrElse { LocalContentColor.current }, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(mediaWord(if (media == "video") "video/mp4" else "image/jpeg"), style = MaterialTheme.typography.bodyMedium, color = ink, fontWeight = weight)
                    }
                } else Text(
                    said,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    fontWeight = weight,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        ReactionChips(
            groups = listOf(ReactionGroups.Group(p.emoji, 1, isHighlighted = c.fromMe)),
            mine = true,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 16.dp)
        )
    }
}

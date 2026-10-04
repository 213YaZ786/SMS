package com.yaz.sms.core.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.yaz.sms.core.common.writeTextAtomically
import com.yaz.sms.core.dial.ContactLook
import com.yaz.sms.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

/**
 * Name and photo shared over the encrypted chat, as iOS shares them: the
 * user's own card (Android's "Me" contact: name, photo, look) goes with the
 * messages to the people they chat with; a card received is handed to the
 * Contacts app as an offer the user applies or ignores there. Looked at
 * when SMS opens, never on a timer; nothing is written to the contacts here.
 */
object Cards {

    /** The look rides in the status, behind an invisible mark the other SMS knows. */
    private const val LOOK = "⁣look:"

    @Serializable
    private data class Kept(val mine: String = "", val theirs: Map<String, String> = emptyMap(), val verified: Set<String> = emptySet())

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sync(context: Context, chat: RichChat, settings: SettingsStore) = withContext(Dispatchers.IO) {
        if (!settings.current.richChat) return@withContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext
        var kept = load(context)
        kept = publish(context, chat, settings.current.shareCard, kept)
        kept = receive(context, chat, kept)
        save(context, kept)
    }

    /** The user's card out, when it changed or the setting did. */
    private suspend fun publish(context: Context, chat: RichChat, on: Boolean, kept: Kept): Kept {
        if (!on) {
            if (kept.mine != "off") chat.shareCard(null, null, "")
            return kept.copy(mine = "off")
        }
        val name = profileName(context)
        val photo = profilePhoto(context)
        val look = profileLook(context)
        val hash = hash(name.orEmpty(), photo?.let { hash(it) }.orEmpty(), look.orEmpty())
        if (hash == kept.mine) return kept
        val file = photo?.let { File(context.cacheDir, "card.jpg").apply { writeBytes(it) } }
        chat.shareCard(name, file?.path, look?.let { LOOK + it }.orEmpty())
        file?.delete()
        return kept.copy(mine = hash)
    }

    /** Each proven person's card in, offered to the Contacts app when it changed. */
    private suspend fun receive(context: Context, chat: RichChat, kept: Kept): Kept {
        var theirs = kept.theirs
        var verified = kept.verified
        for (link in chat.links.value.values.filter { it.proven && it.chatId > 0 }) {
            val card = chat.cardOf(link.phone) ?: continue
            // A number proved over the chat: Contacts may say so on the person's page.
            if (link.phone !in verified && ContactsCards.verified(context, link.phone, true)) verified = verified + link.phone
            if (card.name.isBlank() && card.photo == null) continue
            val photo = card.photo?.let { shrink(context, File(it)) }
            val look = card.status.takeIf { it.startsWith(LOOK) }?.removePrefix(LOOK)?.takeIf { ContactLook.parse(it) != null }
            val hash = hash(card.name, photo?.let { hash(it) }.orEmpty(), look.orEmpty())
            if (theirs[link.phone] == hash) continue
            // A locked phone or a refusal: tried again at the next opening.
            if (ContactsCards.offer(context, link.phone, vcard(card.name, look), photo)) theirs = theirs + (link.phone to hash)
        }
        return kept.copy(theirs = theirs, verified = verified)
    }

    private fun profileName(context: Context): String? = runCatching {
        context.contentResolver.query(ContactsContract.Profile.CONTENT_URI, arrayOf(ContactsContract.Profile.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }.getOrNull()

    private fun profilePhoto(context: Context): ByteArray? = runCatching {
        ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, ContactsContract.Profile.CONTENT_URI, true)
            ?.use { BitmapFactory.decodeStream(it) }?.let(::jpeg)
    }.getOrNull()

    private fun profileLook(context: Context): String? = runCatching {
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, ContactsContract.Contacts.Data.CONTENT_DIRECTORY),
            arrayOf(ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactLook.MIMETYPE),
            null
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.length <= 4096 && ContactLook.parse(it) != null } else null }
    }.getOrNull()

    /** A photo at 720 pixels at most, as a JPEG under 400 kB. */
    private suspend fun shrink(context: Context, file: File): ByteArray? {
        if (!file.canRead()) return null
        // Someone else's photo: decoded in the isolated decoder.
        return runCatching { file.inputStream().use { com.yaz.sms.core.security.SafeImages.decode(context, it, 720) } }.getOrNull()?.let(::jpeg)
    }

    private fun jpeg(bitmap: Bitmap): ByteArray? {
        val scale = 720f / max(bitmap.width, bitmap.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        var quality = 88
        while (quality >= 40) {
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= 400 * 1024) return out.toByteArray()
            quality -= 12
        }
        return null
    }

    /** One vCard 3.0: the name, and the look in a line of its own the Contacts app reads. */
    internal fun vcard(name: String, look: String?): String = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        append("FN:").append(escape(name)).append("\r\n")
        look?.let { append("X-YAZ-LOOK:").append(escape(it)).append("\r\n") }
        append("END:VCARD\r\n")
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    private fun hash(vararg parts: String): String = hash(parts.joinToString("\u0000").toByteArray())
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun file(context: Context) = File(context.filesDir, "cards.json")
    private fun load(context: Context): Kept = runCatching { json.decodeFromString<Kept>(file(context).readText()) }.getOrDefault(Kept())
    private fun save(context: Context, kept: Kept) = runCatching { file(context).writeTextAtomically(json.encodeToString(kept)) }
}

/**
 * The Contacts app's door for cards (a provider behind a permission of
 * signature level: only apps signed with the same key reach it). Without
 * the Contacts app, nothing is sent anywhere.
 */
object ContactsCards {

    private val authorities = listOf("com.yaz.contacts.cards", "com.yaz.contacts.debug.cards")

    fun offer(context: Context, number: String, card: String, photo: ByteArray?): Boolean =
        call(context, "offer", Bundle().apply {
            putString("number", number)
            putString("card", card)
            photo?.let { putByteArray("photo", it) }
        })

    fun verified(context: Context, number: String, verified: Boolean): Boolean =
        call(context, "verified", Bundle().apply {
            putString("number", number)
            putBoolean("verified", verified)
        })

    private fun call(context: Context, method: String, extras: Bundle): Boolean {
        for (authority in authorities) {
            val answer = runCatching { context.contentResolver.call(Uri.parse("content://$authority"), method, null, extras) }.getOrNull() ?: continue
            return answer.getBoolean("ok")
        }
        return false
    }
}

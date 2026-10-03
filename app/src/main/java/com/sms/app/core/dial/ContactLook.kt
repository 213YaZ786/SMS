package com.sms.app.core.dial

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How a person looks and feels in the phone apps, as the Contacts app
 * keeps it in the contact itself (one data row of its own): their colour
 * and the vibration of their messages. Read here, written only there, so
 * nothing is kept twice.
 */
object ContactLook {

    /** Contacts' own row, the same in its debug build. */
    const val MIMETYPE = "vnd.android.cursor.item/vnd.com.contacts.app.look"

    /**
     * [color] as ARGB, null for the wallpaper's accent; [vibration] one of
     * SMS's signature names, null for the phone's; [bypass] their calls ring
     * through Do not disturb and silent; [tone] the sound of their messages
     * (a media or app resource address only); [letters], [font] and [emoji]
     * their monogram when they have no photo.
     */
    data class Look(
        val color: Int?,
        val vibration: String?,
        val bypass: Boolean = false,
        val tone: String? = null,
        val letters: String? = null,
        val font: String? = null,
        val emoji: String? = null
    )

    /** The Contacts app, release first. */
    private val packages = listOf("com.yaz.contacts", "com.yaz.contacts.debug")

    fun of(context: Context, contactId: Long): Look? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DATA1),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), MIMETYPE),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let(::parse) else null }
        }.getOrNull()
    }

    /** The look of whoever has [number], if they are in the contacts. */
    fun ofNumber(context: Context, number: String): Look? {
        if (number.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val id = runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull() ?: byLastDigits(context, number) ?: return null
        return of(context, id)
    }

    /** As ContactLookup: the same last nine digits when Android's own match misses (a number from abroad, no +). */
    private fun byLastDigits(context: Context, number: String): Long? {
        val digits = number.filter(Char::isDigit)
        if (digits.length < 9) return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                "${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ?",
                arrayOf("%" + digits.takeLast(9)),
                null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
    }

    /** Contacts' JSON (version 1); too long or not understood, nothing. */
    fun parse(json: String): Look? {
        if (json.length > 4096) return null
        return runCatching {
            val o = Json.parseToJsonElement(json).jsonObject
            fun text(key: String) = o[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val color = o["color"]?.jsonPrimitive?.intOrNull?.takeIf { it != 0 }
            // Only a sound Android keeps (media store) or an app's own resource.
            val tone = text("tone")?.takeIf { it.startsWith("content://media/") || it.startsWith("android.resource://") }
            Look(
                color = color,
                vibration = text("vibration"),
                bypass = o["bypass"]?.jsonPrimitive?.content == "true",
                tone = tone,
                letters = text("letters")?.take(2),
                font = text("font")?.takeIf { it in setOf("classic", "rounded", "serif", "mono", "bold") },
                emoji = text("emoji")?.take(16)
            )
        }.getOrNull()
    }

    /** Opens the person's page in the Contacts app; false when that app is not on the phone. */
    fun openInContacts(context: Context, contactId: Long): Boolean {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
        for (pkg in packages) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}

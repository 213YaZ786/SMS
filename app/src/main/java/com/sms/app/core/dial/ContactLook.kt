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

    /** [color] as ARGB, null for the wallpaper's accent; [vibration] one of SMS's signature names, null for the phone's. */
    data class Look(val color: Int?, val vibration: String?)

    /** The Contacts app, release first. */
    private val packages = listOf("com.contacts.app", "com.contacts.app.debug")

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
        }.getOrNull() ?: return null
        return of(context, id)
    }

    /** Contacts' JSON (version 1); too long or not understood, nothing. */
    fun parse(json: String): Look? {
        if (json.length > 4096) return null
        return runCatching {
            val o = Json.parseToJsonElement(json).jsonObject
            val color = o["color"]?.jsonPrimitive?.intOrNull?.takeIf { it != 0 }
            val vibration = o["vibration"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
            Look(color, vibration)
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

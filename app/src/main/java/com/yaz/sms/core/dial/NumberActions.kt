package com.yaz.sms.core.dial

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.provider.ContactsContract

/**
 * What can be done with a number, by the app the user chose for it: the
 * phone app calls, the messaging app writes, the contacts app keeps people.
 * Blocking writes Android's own list, which the default phone and
 * messaging apps may do and every app respects.
 */
object NumberActions {

    /** The phone app with [number] ready: the user presses Call there. */
    fun dial(context: Context, number: String) = open(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    /** Dialer's page of the calls with [number]: it only shows them. */
    fun showCalls(context: Context, number: String) = open(context, callsIntent(number))

    /** Whether Dialer is there to show the calls with a number. */
    fun canShowCalls(context: Context): Boolean = callsIntent("0").resolveActivity(context.packageManager) != null

    private fun callsIntent(number: String) = Intent(ACTION_SHOW_NUMBER, Uri.fromParts("tel", number, null))

    /** The action Dialer answers with a number's page, whichever app asks. */
    const val ACTION_SHOW_NUMBER = "com.dialer.app.action.SHOW_NUMBER"

    fun message(context: Context, number: String) = open(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))

    /** Android's contacts app, asking whether to make a new contact or add to one. */
    fun addContact(context: Context, number: String) = open(
        context,
        Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, number)
    )

    fun openContact(context: Context, contactId: Long) = open(
        context,
        Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString()))
    )

    /** A favourite or not, saved on the contact itself, so every app agrees. */
    fun star(context: Context, contactId: Long, on: Boolean): Boolean = runCatching {
        context.contentResolver.update(
            Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString()),
            ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (on) 1 else 0) },
            null, null
        ) > 0
    }.getOrDefault(false)

    fun copy(context: Context, number: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Phone number", number))
    }

    fun canBlock(context: Context): Boolean = runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    fun isBlocked(context: Context, number: String): Boolean =
        runCatching { BlockedNumberContract.isBlocked(context, number) }.getOrDefault(false)

    fun block(context: Context, number: String): Boolean = runCatching {
        context.contentResolver.insert(
            BlockedNumbers.CONTENT_URI,
            ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
        ) != null
    }.getOrDefault(false)

    fun unblock(context: Context, number: String): Boolean =
        runCatching { BlockedNumberContract.unblock(context, number) > 0 }.getOrDefault(false)

    private fun open(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

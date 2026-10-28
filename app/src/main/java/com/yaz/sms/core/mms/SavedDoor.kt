package com.yaz.sms.core.mms

import android.app.KeyguardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.yaz.sms.core.dial.Numbers
import com.yaz.sms.core.dial.PhoneEntry
import com.yaz.sms.core.dial.PrivateNames
import com.yaz.sms.core.dial.T9
import com.yaz.sms.data.contacts.PhoneBook
import com.yaz.sms.data.contacts.PhoneIndex
import kotlinx.coroutines.flow.first

/**
 * What the Files app may ask about the media this app saved: who sent each one and which
 * message it came from, so that Files says "From Marie" and reopens that message. The method
 * "origins" takes the media store row ids in "ids" and answers the ones SMS saved, with
 * nothing else: no message text. Built as the other doors of the suite, PeopleDoor and
 * InviteProvider, on ContentProvider.call
 * (https://developer.android.com/reference/android/content/ContentProvider#call(java.lang.String,%20java.lang.String,%20android.os.Bundle)):
 * only apps signed with the same key reach it (a permission of signature level, checked again
 * in call(): Android does not check it there), and it answers only while the phone is
 * unlocked, as the Contacts app's private door does (KeyguardManager.isDeviceLocked,
 * https://developer.android.com/reference/android/app/KeyguardManager#isDeviceLocked()).
 */
class SavedDoor : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (context.checkCallingPermission(permissionOf(context)) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Not allowed")
        if (method != METHOD) return null
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) return null
        return answer(
            SavedMedia.of(context).origins(idsOf(extras)) { SavedMedia.existing(context.contentResolver, it) },
            nameOf(context, contactsNow())
        )
    }

    /**
     * The phone book's entries, as the conversation screen reads them. The process may have been started
     * by this very call, before any screen asked the book to load: asked once here and waited for on this
     * binder thread, as InviteProvider waits for the engine, with runBlocking and withTimeoutOrNull
     * (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout-or-null.html).
     */
    private fun contactsNow(): List<PhoneEntry> {
        val book = org.koin.java.KoinJavaComponent.get<PhoneBook>(PhoneBook::class.java)
        if (!book.canRead()) return emptyList()
        if (book.entries.value.isEmpty() && !loadAsked) {
            loadAsked = true
            book.refresh()
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(3_000) { book.entries.first { it.isNotEmpty() } } }
        }
        return book.entries.value
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val METHOD = "origins"

        /** The book was asked to load by a call of this door: not waited for again (an empty book stays empty). */
        @Volatile private var loadAsked = false

        /** The most ids one question takes; the others are ignored. */
        const val MAX_IDS = 400

        /** The signature permission a caller must hold. */
        fun permissionOf(context: Context): String = context.packageName + ".permission.SAVED"

        /**
         * The row ids asked in "ids": a long array, the first [MAX_IDS] of it, real ids only. Anything
         * else under the key (another type, nothing) asks nothing; Bundle's typed getter gives null then.
         */
        fun idsOf(extras: Bundle?): List<Long> =
            (runCatching { extras?.getLongArray("ids") }.getOrNull() ?: LongArray(0)).take(MAX_IDS).filter { it > 0 }.distinct()

        /**
         * The name shown for a sender, as the conversation's title names the same address (ThreadScreen:
         * `index.find(T9.clean(to))?.name ?: Numbers.format(context, to)` over PhoneBook's entries in PhoneIndex),
         * so a number that fits two contacts by its last digits gets the same one in both. A private person
         * only through PrivateNames, as PhoneIndex.find asks it; else the number written the local way; never empty.
         */
        fun nameOf(context: Context, entries: List<PhoneEntry>): (String) -> String {
            val index = PhoneIndex(entries, Numbers.countryIso(context))
            val known = HashMap<String, String>()
            return { sender ->
                known.getOrPut(sender) {
                    (index.find(T9.clean(sender))?.name ?: PrivateNames.lookup(context, sender)?.name ?: Numbers.format(context, sender)).ifBlank { sender }
                }
            }
        }

        /** The reply: parallel arrays "ids", "names", "threads", "messages", one slot for each media saved by SMS. */
        fun answer(entries: List<SavedEntry>, name: (String) -> String): Bundle = Bundle().apply {
            putLongArray("ids", LongArray(entries.size) { entries[it].id })
            putStringArray("names", Array(entries.size) { name(entries[it].sender) })
            putLongArray("threads", LongArray(entries.size) { entries[it].thread })
            putStringArray("messages", Array(entries.size) { entries[it].uid })
        }
    }
}

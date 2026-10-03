package com.sms.app.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import com.sms.app.core.dial.PhoneEntry
import com.sms.app.core.dial.ContactLook
import com.sms.app.core.dial.T9
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every number saved in the phone's contacts, kept in memory for the
 * dialpad's search, read again when the contacts change. Nothing is copied
 * anywhere: Android's own contacts are the only store.
 */
class PhoneBook(private val context: Context, private val scope: CoroutineScope) {

    private val _entries = MutableStateFlow<List<PhoneEntry>>(emptyList())
    val entries: StateFlow<List<PhoneEntry>> = _entries.asStateFlow()

    private var watching = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Loads, and from then on follows the contacts. Does nothing without the permission. */
    fun refresh() {
        if (!canRead()) return
        if (!watching) {
            context.contentResolver.registerContentObserver(Phone.CONTENT_URI, true, observer)
            watching = true
        }
        scope.launch { _entries.value = withContext(Dispatchers.IO) { load() } }
    }

    private fun load(): List<PhoneEntry> = runCatching {
        // Every person's look in one read, so a list of faces asks nothing more.
        val looks = HashMap<Long, ContactLook.Look>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactLook.MIMETYPE),
                null
            )?.use { c -> while (c.moveToNext()) c.getString(1)?.let(ContactLook::parse)?.let { looks[c.getLong(0)] = it } }
        }
        val columns = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI, Phone.STARRED)
        context.contentResolver.query(Phone.CONTENT_URI, columns, null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")
            ?.use { c ->
                val seen = HashSet<String>()
                buildList {
                    while (c.moveToNext()) {
                        val number = c.getString(2) ?: continue
                        val digits = T9.clean(number)
                        // The same number saved twice in one contact, or in two
                        // linked accounts, is one line in the search.
                        val id = c.getLong(0)
                        if (!seen.add("$id/$digits")) continue
                        add(
                            PhoneEntry(
                                contactId = id,
                                name = c.getString(1) ?: number,
                                number = number,
                                digits = digits,
                                photo = c.getString(3),
                                starred = c.getInt(4) != 0,
                                look = looks[id]
                            )
                        )
                    }
                }
            }
    }.getOrNull().orEmpty()
}

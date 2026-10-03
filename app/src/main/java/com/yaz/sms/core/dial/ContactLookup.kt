package com.yaz.sms.core.dial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import androidx.core.content.ContextCompat

/**
 * Who a number belongs to, asked of Android's contacts directly, then of
 * the Contacts app's private ones: for the moments the app may not be
 * running yet, a call being screened or a missed call to announce.
 */
object ContactLookup {

    fun nameOf(context: Context, number: String): String? {
        if (number.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull() ?: byLastDigits(context, number) ?: PrivateNames.lookup(context, number)?.name
    }

    /**
     * Android matches numbers as the network's country writes them, so 06 12…
     * misses +33 6 12… abroad: the same last nine digits, as the lists of
     * the app compare them, then decide.
     */
    private fun byLastDigits(context: Context, number: String): String? {
        val digits = number.filter(Char::isDigit)
        if (digits.length < 9) return null
        return runCatching {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME),
                "${Phone.NORMALIZED_NUMBER} LIKE ?",
                arrayOf("%" + digits.takeLast(9)),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        }.getOrNull()
    }

    fun isContact(context: Context, number: String): Boolean = nameOf(context, number) != null
}

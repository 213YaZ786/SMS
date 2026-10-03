package com.yaz.sms.core.sms

import android.content.Context
import android.provider.Telephony
import com.yaz.sms.core.dial.PeopleDoor

/** The Contacts app's question about messages: answered from Android's SMS store, one to one. */
class MessagesDoor : PeopleDoor() {
    override fun exchanges(context: Context, since: Long, visit: (number: String, at: Long) -> Boolean) {
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c -> while (c.moveToNext()) if (!visit(c.getString(0).orEmpty(), c.getLong(1))) break }
        }
    }
}

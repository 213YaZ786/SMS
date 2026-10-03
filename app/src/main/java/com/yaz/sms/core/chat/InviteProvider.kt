package com.yaz.sms.core.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The encrypted chat's invite, for the Contacts app when two phones touch:
 * "invite" gives this phone's link (and its number when Android tells it to
 * the messaging app), "join" takes the other phone's, at the user's tap on
 * Save there. Only apps signed with the same key reach it (a permission of
 * signature level, checked again here: Android does not check call()).
 */
class InviteProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val permission = context.packageName + ".permission.INVITE"
        if (context.checkCallingPermission(permission) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Not allowed")
        val chat = org.koin.java.KoinJavaComponent.get<RichChat>(RichChat::class.java)
        return when (method) {
            "invite" -> Bundle().apply {
                // The engine may need a moment to start; Contacts waits on a thread of its own.
                val link = runBlocking { withTimeoutOrNull(15_000) { chat.inviteLink() } }
                if (link != null && link.length <= 2000) {
                    putString("link", link)
                    ownNumber(context)?.let { putString("number", it) }
                }
            }
            "join" -> {
                val link = extras?.getString("link")?.takeIf { it.length <= 2000 }
                val number = extras?.getString("number")?.takeIf { it.length <= 32 }
                val ok = link != null && number != null && runBlocking { withTimeoutOrNull(15_000) { chat.joinInPerson(link, number) } } == true
                Bundle().apply { putBoolean("ok", ok) }
            }
            else -> null
        }
    }

    /** This phone's number, as Android gives it to the messaging app; null when unknown. */
    private fun ownNumber(context: android.content.Context): String? = runCatching {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val sub = SmsManager.getDefaultSmsSubscriptionId()
        if (android.os.Build.VERSION.SDK_INT >= 33) subscriptions.getPhoneNumber(sub).takeIf { it.isNotBlank() }
        else @Suppress("DEPRECATION") subscriptions.getActiveSubscriptionInfo(sub)?.number?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

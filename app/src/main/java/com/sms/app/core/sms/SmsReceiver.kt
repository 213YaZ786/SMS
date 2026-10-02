package com.sms.app.core.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.sms.app.core.dial.ContactLookup
import com.sms.app.core.dial.SalesCalls
import com.sms.app.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * A new SMS, given by Android to the messaging app alone: the parts joined,
 * kept in the store (only this app may), then shown. Numbers the user
 * blocked never reach here, Android drops them first.
 */
class SmsReceiver : BroadcastReceiver(), KoinComponent {

    private val settings: SettingsStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val address = parts.first().displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val sentAt = parts.first().timestampMillis
        val sub = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val thread = Telephony.Threads.getOrCreateThreadId(app, address)
                app.contentResolver.insert(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, address)
                        put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, System.currentTimeMillis())
                        put(Telephony.Sms.DATE_SENT, sentAt)
                        put(Telephony.Sms.READ, 0)
                        put(Telephony.Sms.SEEN, 0)
                        put(Telephony.Sms.THREAD_ID, thread)
                        put(Telephony.Sms.SUBSCRIPTION_ID, sub)
                    }
                )
                // Sales senders the user chose to keep quiet: kept, not shown.
                val country = app.getSystemService(TelephonyManager::class.java)?.networkCountryIso
                val quiet = settings.current.quietSales && SalesCalls.isSalesCall(address, country) && !ContactLookup.isContact(app, address)
                if (!quiet) MessageNotifier(app).show(thread)
            } finally {
                done.finish()
            }
        }
    }
}

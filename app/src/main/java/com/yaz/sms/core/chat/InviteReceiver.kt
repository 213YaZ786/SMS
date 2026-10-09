package com.yaz.sms.core.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * An unseen data SMS on the app's port: another SMS user's invite. Only
 * the system delivers it; anything that is not an invite is ignored.
 */
class InviteReceiver : BroadcastReceiver(), KoinComponent {
    private val chat: RichChat by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION) return
        val parts: Array<SmsMessage> = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val from = parts.firstOrNull()?.originatingAddress ?: return
        val data = parts.mapNotNull { it.userData }.fold(ByteArray(0)) { a, b -> a + b }
        val hello = Hello.decode(data) ?: return
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                chat.onHello(from, hello)
            } finally {
                done.finish()
            }
        }
    }
}

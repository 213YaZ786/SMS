package com.sms.app.core.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * A picture message announced by the network. Downloading and showing
 * them comes in a later version; until then the user is told one arrived.
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        MessageNotifier(context.applicationContext).pictureMessage()
    }
}

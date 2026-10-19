package com.yaz.sms.core.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.yaz.sms.core.mms.MmsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What the network pushes about picture messages: an announcement is downloaded at once, a delivery report
 * is kept. Worked off the main thread, as AOSP Mms's PushReceiver does in its ReceivePushTask
 * (https://android.googlesource.com/platform/packages/apps/Mms/+/refs/heads/jb-mr1-dev/src/com/android/mms/transaction/PushReceiver.java).
 * Only the system may send it (BROADCAST_WAP_PUSH, in the manifest).
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra("data") ?: return
        val sub = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MmsTransport.download(app, pdu, sub)
            } finally {
                done.finish()
            }
        }
    }
}

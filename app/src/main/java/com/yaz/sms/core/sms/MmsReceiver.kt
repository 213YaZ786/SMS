package com.yaz.sms.core.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.yaz.sms.core.mms.MmsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Class that handles MMS WAP push intent from telephony: an announcement is downloaded at once, a delivery
 * report is kept. Worked off the main thread, as AOSP Mms's PushReceiver does in its ReceivePushTask, and
 * as AOSP Messaging's MmsWapPushDeliverReceiver hands the data on. Only the system may send it
 * (BROADCAST_WAP_PUSH, in the manifest, which also filters the type: AOSP's ContentType.MMS_MESSAGE check).
 * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/receiver/MmsWapPushDeliverReceiver.java
 */
// not ported: isSmsEnabled test of mmsReceived (the manifest's role holder is the only one that receives it)
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION == intent.action) {
            // Always convert negative subIds into -1
            var subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            if (subId < 0) subId = intent.getIntExtra(EXTRA_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            if (subId < 0) subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
            val data = intent.getByteArrayExtra(EXTRA_DATA)
            mmsReceived(context.applicationContext, subId, data)
        }
    }

    private fun mmsReceived(context: Context, subId: Int, data: ByteArray?) {
        // Check the data for validity
        if (data == null || data.isEmpty()) {
            Log.e(TAG, "mmsReceived: null or zero data")
            return
        }
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MmsTransport.download(context, data, subId)
            } catch (e: Throwable) {
                // A bad PDU never kills the process: the class only is logged, nothing of the message.
                Log.e(TAG, "mmsReceived: " + e.javaClass.simpleName)
            } finally {
                done.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MmsReceiver"
        const val EXTRA_SUBSCRIPTION = "subscription"
        const val EXTRA_DATA = "data"
    }
}

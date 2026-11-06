package com.yaz.sms.core.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * The replies chosen on an incoming call ("Can't talk now"), sent by the
 * phone app through Telecom. Only the system can start it.
 *
 * Respond to a special intent and send an SMS message without the user's intervention, as AOSP Messaging's
 * NoConfirmationSmsSendService: the work runs off the main thread (AOSP's IntentService has its own) and the
 * intent is redelivered if the process dies before it is done.
 */
class QuickReplyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread {
            try {
                onHandleIntent(intent)
            } finally {
                stopSelf(startId)
            }
        }.start()
        // Intent should be redelivered if the process gets killed before completing the job.
        return START_REDELIVER_INTENT
    }

    /**
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/NoConfirmationSmsSendService.java#onHandleIntent
     * Not ported: showUI (opening the app from a service is blocked by Android's background launch limits),
     * the conversation id and the self id (this app has no conversation ids of its own), requiresMms and the
     * subject (the reply is a short SMS), InsertNewMessageAction and UpdateMessageNotificationAction (SmsSender writes the message).
     */
    private fun onHandleIntent(intent: Intent) {
        Log.v(TAG, "QuickReplyService onHandleIntent")

        val action = intent.action
        if (TelephonyManager.ACTION_RESPOND_VIA_MESSAGE != action) {
            Log.v(TAG, "QuickReplyService onHandleIntent wrong action: $action")
            return
        }
        val extras = intent.extras
        if (extras == null) {
            Log.v(TAG, "Called to send SMS but no extras")
            return
        }

        // Get all possible extras from intent
        val message = getText(intent, Intent.EXTRA_TEXT)
        val subId = extras.getInt(EXTRA_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)

        // Get a comma-separated list of recipients
        val recipients = intent.data?.schemeSpecificPart.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

        if (recipients.isEmpty()) {
            Log.v(TAG, "Recipient(s) cannot be empty")
            return
        }
        if (message.isNullOrBlank()) {
            Log.v(TAG, "Message cannot be empty")
            return
        }
        recipients.forEach { SmsSender.send(this, it, message, subId) }
    }

    private fun getText(intent: Intent, textType: String): String? {
        val message = intent.getCharSequenceExtra(textType)?.toString()
        if (message == null) {
            val remoteInput = RemoteInput.getResultsFromIntent(intent)
            if (remoteInput != null) {
                val extra = remoteInput.getCharSequence(textType)
                if (extra != null) {
                    return extra.toString()
                }
            }
        }
        return message
    }

    private companion object {
        const val TAG = "QuickReply"
        const val EXTRA_SUBSCRIPTION = "subscription"
    }
}

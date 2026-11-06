package com.yaz.sms.core.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the sent status of every part of a multipart message sending: the parts still waiting and the
 * highest level of failure among them; a message is sent only when all its parts are out and none failed.
 * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/SmsSender.java#SendResult
 * Not ported: SmsSender.sendMessage's blocking wait with a timeout (the reports here arrive on their own,
 * the row stays in the outbox until they do).
 */
class SendResult(numOfParts: Int) {

    // Tracking the remaining pending parts in sending
    private var pendingParts = numOfParts

    // Tracking the highest level of failure among all parts
    private var mHighestFailureLevel = FAILURE_LEVEL_NONE

    init {
        require(numOfParts > 0)
    }

    // Update the sent status of one part
    fun setPartResult(resultCode: Int) {
        pendingParts--
        setHighestFailureLevel(resultCode)
    }

    fun hasPending(): Boolean = pendingParts > 0

    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/SmsSender.java#getHighestFailureLevel
    fun getHighestFailureLevel(): Int = mHighestFailureLevel

    private fun getFailureLevel(resultCode: Int): Int = when (resultCode) {
        Activity.RESULT_OK -> FAILURE_LEVEL_NONE
        SmsManager.RESULT_ERROR_NO_SERVICE -> FAILURE_LEVEL_TEMPORARY
        SmsManager.RESULT_ERROR_RADIO_OFF -> FAILURE_LEVEL_PERMANENT
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> FAILURE_LEVEL_PERMANENT
        else -> {
            Log.e(TAG, "SmsSender: Unexpected sent intent resultCode = $resultCode")
            FAILURE_LEVEL_PERMANENT
        }
    }

    private fun setHighestFailureLevel(resultCode: Int) {
        val level = getFailureLevel(resultCode)
        if (level > mHighestFailureLevel) {
            mHighestFailureLevel = level
        }
    }

    override fun toString(): String = "SendResult:Pending=$pendingParts,HighestFailureLevel=$mHighestFailureLevel"

    companion object {
        // Failure levels. For temporary failures, possibly we could retry the sending;
        // for permanent failures, we probably won't retry.
        const val FAILURE_LEVEL_NONE = 0
        const val FAILURE_LEVEL_TEMPORARY = 1
        const val FAILURE_LEVEL_PERMANENT = 2
        private const val TAG = "SmsSender"
    }
}

/** What the network says of a message sent: each part out, then delivered. */
class SmsStatusReceiver : BroadcastReceiver() {

    /**
     * The SMS sent and delivery intent receiver, as AOSP Messaging's SendStatusReceiver.onReceive: a sent
     * report goes to the tally of its message (setResult), a delivery report becomes the row's status.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/receiver/SendStatusReceiver.java#onReceive
     * Not ported: the MMS_SENT_ACTION and MMS_DOWNLOADED_ACTION branches (MmsResultReceiver, MmsTransport.onResult).
     */
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        // Only this app's own messages: the reports come with their place in the store.
        if (uri.authority != Telephony.Sms.CONTENT_URI.authority) return
        when (intent.action) {
            SmsSender.ACTION_SENT -> setResult(
                context, uri, resultCode,
                intent.getIntExtra(EXTRA_ERROR_CODE, NO_ERROR_CODE),
                intent.getIntExtra(SmsSender.EXTRA_PART, 0),
                intent.getIntExtra(SmsSender.EXTRA_PARTS, 1),
                intent.getIntExtra(SmsSender.EXTRA_SUB, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            )
            SmsSender.ACTION_DELIVERED -> {
                val pdu = intent.getByteArrayExtra("pdu") ?: return
                val status = runCatching { SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")).status }.getOrNull() ?: return
                // Bands of 3GPP TS 23.040 and the 3gpp2 error class, as AOSP does (DeliveryStatus).
                val value = DeliveryStatus.of(status, intent.getStringExtra("format"))
                runCatching { context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.STATUS, value) }, null, null) }
            }
        }
    }

    companion object {
        // Defined by platform, but no constant provided. See docs for SmsManager.sendTextMessage.
        const val EXTRA_ERROR_CODE = "errorCode"
        const val NO_ERROR_CODE = -1
        private const val TAG = "SmsStatus"

        /** The tally of every message with parts out: the key is the message's place in the store. */
        private val pendingMessageMap = ConcurrentHashMap<Uri, SendResult>()

        /** A message sent again as the same row starts a new tally. */
        fun forget(requestId: Uri) {
            pendingMessageMap.remove(requestId)
        }

        /**
         * One part's report, as AOSP Messaging's SmsSender.setResult: logged, the carrier's error code told
         * to the user, the part counted in the message's tally. The row turns failed at the first part that
         * fails (and the user is told once); it turns sent only when every part is out and none failed, and
         * never over a failure (a late OK part of a message whose early part failed leaves it failed).
         * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/SmsSender.java#SendResult
         */
        fun setResult(context: Context, requestId: Uri, resultCode: Int, errorCode: Int, partId: Int, parts: Int, subId: Int) {
            if (resultCode != Activity.RESULT_OK) {
                Log.e(TAG, "SmsSender: failure in sending message part. requestId=$requestId partId=$partId resultCode=$resultCode errorCode=$errorCode")
                if (errorCode != NO_ERROR_CODE) toast(context, getSendErrorToastMessage(context, subId, errorCode))
            } else {
                Log.v(TAG, "SmsSender: received sent result. requestId=$requestId partId=$partId resultCode=$resultCode")
            }
            var firstFailure = false
            var finished = false
            var clean = true
            synchronized(pendingMessageMap) {
                var result = pendingMessageMap[requestId]
                // The process died between two parts: the tally is lost, the last part closes the message.
                // The parts are numbered from 1 when there are several (AOSP's partId), 0 for a single one.
                val restored = result == null && partId > 1
                if (result == null) {
                    result = SendResult(parts.coerceAtLeast(1))
                    pendingMessageMap[requestId] = result
                }
                val before = result.getHighestFailureLevel()
                result.setPartResult(resultCode)
                firstFailure = before == SendResult.FAILURE_LEVEL_NONE && result.getHighestFailureLevel() > before
                finished = !result.hasPending() || (restored && partId == parts)
                clean = result.getHighestFailureLevel() == SendResult.FAILURE_LEVEL_NONE
                if (finished) pendingMessageMap.remove(requestId)
            }
            if (resultCode != Activity.RESULT_OK) {
                SmsSender.mark(context, requestId, Telephony.Sms.MESSAGE_TYPE_FAILED)
                if (firstFailure) MessageNotifier(context).failed(requestId)
            } else if (finished && clean) {
                // Only where the row is not failed: a message whose early part failed stays failed.
                SmsSender.mark(context, requestId, Telephony.Sms.MESSAGE_TYPE_SENT, unlessFailed = true)
            }
        }

        /** The carrier's own refusal, with its code, as AOSP Messaging's getSendErrorToastMessage says it. */
        private fun getSendErrorToastMessage(context: Context, subId: Int, errorCode: Int): String {
            val carrierName = runCatching {
                val telephony = context.getSystemService(TelephonyManager::class.java)
                (if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) telephony?.createForSubscriptionId(subId) else telephony)?.networkOperatorName
            }.getOrNull()
            return if (carrierName.isNullOrEmpty()) "Not sent: your carrier refused the message, error $errorCode."
            else "Not sent: $carrierName refused the message, error $errorCode."
        }

        private fun toast(context: Context, text: String) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        }
    }
}

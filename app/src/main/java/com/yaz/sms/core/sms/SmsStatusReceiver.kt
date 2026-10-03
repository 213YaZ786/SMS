package com.yaz.sms.core.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage

/** What the network says of a message sent: each part out, then delivered. */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        // Only this app's own messages: the reports come with their place in the store.
        if (uri.authority != Telephony.Sms.CONTENT_URI.authority) return
        when (intent.action) {
            SmsSender.ACTION_SENT -> {
                val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
                val parts = intent.getIntExtra(SmsSender.EXTRA_PARTS, 1)
                if (resultCode != Activity.RESULT_OK) {
                    SmsSender.mark(context, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
                    MessageNotifier(context).failed(uri)
                } else if (part == parts - 1) {
                    SmsSender.mark(context, uri, Telephony.Sms.MESSAGE_TYPE_SENT)
                }
            }
            SmsSender.ACTION_DELIVERED -> {
                val pdu = intent.getByteArrayExtra("pdu") ?: return
                val status = runCatching { SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")).status }.getOrNull() ?: return
                // 0 is "received by the phone" in GSM; anything else is kept as told.
                val value = if (status == 0) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED
                runCatching { context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.STATUS, value) }, null, null) }
            }
        }
    }
}

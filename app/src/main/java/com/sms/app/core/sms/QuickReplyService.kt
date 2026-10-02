package com.sms.app.core.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager

/**
 * The replies chosen on an incoming call ("Can't talk now"), sent by the
 * phone app through Telecom. Only the system can start it.
 */
class QuickReplyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val to = intent.data?.schemeSpecificPart.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (text.isNotBlank()) to.forEach { SmsSender.send(this, it, text) }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

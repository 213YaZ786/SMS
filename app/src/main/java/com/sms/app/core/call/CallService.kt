package com.sms.app.core.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.telecom.DisconnectCause
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.sms.app.R
import com.sms.app.core.dial.Numbers
import com.sms.app.feature.call.CallActivity

/**
 * Keeps the call's microphone (and camera, for a video call) while the
 * call screen is not in front, with the call's notification to go back to
 * it or hang up.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = CallBook.call.value ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (call.video) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        runCatching { startForeground(CallNotices.ID, CallNotices.ongoing(this, call), types) }
            .onFailure { startForeground(CallNotices.ID, CallNotices.ongoing(this, call), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) }
        return START_NOT_STICKY
    }
}

/** Hang up or decline from the notification. Not exported: only this app's notifications send it. */
class CallActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HANG_UP -> CallBook.connection?.hangUp(DisconnectCause.LOCAL)
            ACTION_DECLINE -> CallBook.connection?.hangUp(DisconnectCause.REJECTED)
        }
    }

    companion object {
        const val ACTION_HANG_UP = "com.sms.app.call.HANG_UP"
        const val ACTION_DECLINE = "com.sms.app.call.DECLINE"
    }
}

/** The calls' notifications: ringing, going on, missed. */
object CallNotices {
    const val ID = 4301
    private const val MISSED_ID = 4302
    private const val RINGING = "encrypted-calls"
    private const val ONGOING = "encrypted-call-ongoing"

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // The ringing itself is played by the app (a self-managed call), so the channel is silent.
        manager.createNotificationChannel(NotificationChannel(RINGING, "Encrypted calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
        manager.createNotificationChannel(NotificationChannel(ONGOING, "Encrypted call in progress", NotificationManager.IMPORTANCE_LOW))
    }

    fun screen(context: Context, answer: Boolean = false): Intent =
        Intent(context, CallActivity::class.java).setAction(if (answer) CallActivity.ACTION_ANSWER else CallActivity.ACTION_SHOW)

    private fun person(context: Context, phone: String) =
        Person.Builder().setName(com.sms.app.core.dial.ContactLookup.nameOf(context, phone) ?: Numbers.format(context, phone)).setImportant(true).build()

    private fun broadcast(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, CallActions::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun activity(context: Context, answer: Boolean, code: Int) = PendingIntent.getActivity(
        context, code, screen(context, answer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun incoming(context: Context, call: ChatCall) {
        channels(context)
        val notice = NotificationCompat.Builder(context, RINGING)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentText(if (call.video) "Encrypted video call" else "Encrypted call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(activity(context, answer = false, code = 1), true)
            .setContentIntent(activity(context, answer = false, code = 1))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(context, call.phone), broadcast(context, CallActions.ACTION_DECLINE, 2), activity(context, answer = true, code = 3)))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, notice) }
    }

    fun ongoing(context: Context, call: ChatCall): Notification {
        channels(context)
        return NotificationCompat.Builder(context, ONGOING)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentText(if (call.video) "Encrypted video call" else "Encrypted call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(call.since > 0)
            .setWhen(if (call.since > 0) call.since else System.currentTimeMillis())
            .setContentIntent(activity(context, answer = false, code = 1))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(context, call.phone), broadcast(context, CallActions.ACTION_HANG_UP, 4)))
            .build()
    }

    fun missed(context: Context, phone: String, video: Boolean) {
        channels(context)
        val notice = NotificationCompat.Builder(context, RINGING)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle(person(context, phone).name)
            .setContentText(if (video) "Missed encrypted video call" else "Missed encrypted call")
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(MISSED_ID, notice) }
    }

    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID) }
    }
}

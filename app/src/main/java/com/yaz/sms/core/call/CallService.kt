package com.yaz.sms.core.call

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
import com.yaz.sms.R
import com.yaz.sms.core.dial.Numbers

/**
 * Keeps the call's microphone (and camera, for a video call) while the
 * call screen is not in front, with the call's notification to go back to
 * it or hang up.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallBook.call.value ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val camera = CallBook.call.value?.video == true &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        runCatching { startForeground(CallNotices.ID, CallNotices.line(this), types) }
            .onFailure { runCatching { startForeground(CallNotices.ID, CallNotices.line(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) } }
        return START_NOT_STICKY
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
        manager.createNotificationChannel(NotificationChannel(ONGOING, "Encrypted call line", NotificationManager.IMPORTANCE_MIN))
    }

    /** What Android asks while the line keeps the microphone; Dialer shows the call itself. */
    fun line(context: Context): Notification {
        channels(context)
        return NotificationCompat.Builder(context, ONGOING)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentText("Encrypted call line")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun person(context: Context, phone: String) =
        Person.Builder().setName(com.yaz.sms.core.dial.ContactLookup.nameOf(context, phone) ?: Numbers.format(context, phone)).build()

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

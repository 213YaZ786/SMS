package com.sms.app.core.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.core.app.NotificationCompat
import com.sms.app.MainActivity
import com.sms.app.R
import com.sms.app.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Keeps the rich chat connected while the app is closed, so messages
 * arrive at once without any push service from Google. Android asks for
 * a small notification in exchange; it can be hidden in its settings.
 */
class ChatService : Service(), KoinComponent {

    private val chat: RichChat by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        CoroutineScope(Dispatchers.IO).launch { chat.start() }
        return START_STICKY
    }

    private fun notice(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("Rich chat connected")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val ID = 42

        /** Starts it when the chat is on and SMS is the messaging app. */
        fun startIfWanted(context: Context, settings: SettingsStore) {
            if (!settings.current.richChat) return
            val roles = context.getSystemService(RoleManager::class.java)
            if (!roles.isRoleHeld(RoleManager.ROLE_SMS)) return
            runCatching { context.startForegroundService(Intent(context, ChatService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatService::class.java))
        }
    }
}

/** After the phone restarts, the chat connects again. */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val settings: SettingsStore by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ChatService.startIfWanted(context, settings)
    }
}

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

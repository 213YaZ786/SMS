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
    private var watch: kotlinx.coroutines.Job? = null

    override fun onDestroy() {
        watch?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (watch == null) watch = CoroutineScope(Dispatchers.IO).launch {
            chat.start()
            // The relays looked at once a day (tend() keeps to that itself).
            while (true) {
                chat.tend()
                kotlinx.coroutines.delay(6 * 60 * 60 * 1000L)
            }
        }
        return START_STICKY
    }

    private fun notice(): Notification {
        channel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // As quiet as Android allows: no sound, no banner, shown again without a
        // word after a restart or an update, hidden on the lock screen.
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_chat_link)
            .setContentTitle("Encrypted chat connected")
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        const val CHANNEL = "chat_link"
        private const val ID = 42

        /** The connection's channel: no dot on the app's icon, no sound, nothing that looks like a message. */
        fun channel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Encrypted chat connection", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
            runCatching { manager.deleteNotificationChannel("connection") }
        }

        /** Android's page of that notification, where it can be turned off while the chat keeps receiving. */
        fun hideNotice(context: Context) {
            channel(context)
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

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

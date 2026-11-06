package com.yaz.sms.core.sms

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.core.mms.MmsTransport
import org.koin.core.context.GlobalContext
import com.yaz.sms.data.sms.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What the buttons of a message notification do. Not exported: only its own notifications reach it. */
class NotificationActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val thread = intent.getLongExtra(EXTRA_THREAD, -1L)
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        if (thread < 0) return
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        // Get the response text from the notification and send it as a reply, as Signal's RemoteReplyReceiver.onReceive:
                        // to the thread's recipient (every member of a group), the thread read and the notification dismissed
                        // only once it left; a reply that did not leave leaves the notification, put back as it was.
                        // https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/notifications/RemoteReplyReceiver.java#onReceive
                        val responseText = (RemoteInput.getResultsFromIntent(intent) ?: return@launch).getCharSequence(KEY_REPLY)?.toString().orEmpty()
                        if (responseText.isBlank()) return@launch
                        val members = GlobalContext.get().get<Messages>().addressesOf(thread)
                        val recipient = if (members.size > 1) members else listOf(address).filter { it.isNotBlank() }
                        // Over the rich chat when its link is proven or the group is ready, and never a plain message in its place;
                        // else a group in its thread as a picture message to all its members, one number as an SMS.
                        val chat = GlobalContext.get().get<RichChat>()
                        val delivered = recipient.isNotEmpty() && when {
                            recipient.size > 1 && chat.groupOf(recipient)?.left == true -> false
                            recipient.size > 1 && chat.groupReady(recipient) -> chat.send(recipient, responseText, emptyList(), null)
                            recipient.size == 1 && chat.linkFor(recipient[0]) != null -> chat.send(recipient, responseText, emptyList(), null)
                            recipient.size > 1 -> MmsTransport.send(app, recipient, responseText, emptyList(), -1, thread) != null
                            else -> SmsSender.send(app, recipient[0], responseText) != null
                        }
                        if (delivered) {
                            Messages.markRead(app, thread)
                            MessageNotifier(app).cancel(thread)
                        } else {
                            MessageNotifier(app).show(thread)
                            Handler(Looper.getMainLooper()).post { Toast.makeText(app, "Not sent", Toast.LENGTH_LONG).show() }
                        }
                    }
                    ACTION_READ -> {
                        Messages.markRead(app, thread)
                        MessageNotifier(app).cancel(thread)
                    }
                    ACTION_COPY -> {
                        val code = intent.getStringExtra(EXTRA_CODE) ?: return@launch
                        val clip = ClipData.newPlainText("Code", code).apply {
                            // Kept out of the clipboard's preview and history where Android offers it.
                            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        }
                        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                        Messages.markRead(app, thread)
                        MessageNotifier(app).cancel(thread)
                    }
                }
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.yaz.sms.REPLY"
        const val ACTION_READ = "com.yaz.sms.READ"
        const val ACTION_COPY = "com.yaz.sms.COPY"
        const val KEY_REPLY = "reply"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_CODE = "code"
    }
}

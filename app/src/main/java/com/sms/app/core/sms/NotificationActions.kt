package com.sms.app.core.sms

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.app.RemoteInput
import com.sms.app.core.chat.RichChat
import org.koin.core.context.GlobalContext
import com.sms.app.data.sms.Messages
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
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
                        if (text.isNotBlank() && address.isNotBlank()) {
                            // Over the rich chat when the number has it, else SMS.
                            val chat = GlobalContext.get().get<RichChat>()
                            if (chat.linkFor(address) == null || !chat.send(address, text, emptyList(), null)) SmsSender.send(app, address, text)
                        }
                        Messages.markRead(app, thread)
                        MessageNotifier(app).cancel(thread)
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
        const val ACTION_REPLY = "com.sms.app.REPLY"
        const val ACTION_READ = "com.sms.app.READ"
        const val ACTION_COPY = "com.sms.app.COPY"
        const val KEY_REPLY = "reply"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_CODE = "code"
    }
}

package com.sms.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sms.app.core.chat.Hello
import com.sms.app.core.chat.RichChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Debug builds only, never in a release: hands the app an invite as if it
 * came by data SMS, since the emulator cannot deliver one.
 * adb shell am broadcast -n com.sms.app.debug/com.sms.app.DebugHelloReceiver --es from +336... --es hex ...
 */
class DebugHelloReceiver : BroadcastReceiver(), KoinComponent {
    private val chat: RichChat by inject()
    override fun onReceive(context: Context, intent: Intent) {
        val from = intent.getStringExtra("from") ?: return
        val bytes = intent.getStringExtra("hex")?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: return
        val hello = Hello.decode(bytes) ?: return
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { chat.onHello(from, hello) } finally { done.finish() }
        }
    }
}

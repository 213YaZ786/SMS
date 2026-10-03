package com.yaz.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yaz.sms.core.chat.Hello
import com.yaz.sms.core.chat.RichChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Debug builds only, never in a release: hands the app an invite as if it
 * came by data SMS, since the emulator cannot deliver one.
 * adb shell am broadcast -n com.yaz.sms.debug/com.yaz.sms.DebugHelloReceiver --es from +336... --es hex ...
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

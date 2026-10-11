package com.yaz.sms

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.yaz.sms.core.sms.OpenRequest
import com.yaz.sms.core.sms.OpenRequests
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.navigation.SmsApp
import com.yaz.sms.ui.theme.AppSurface
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The main screen. It also answers other apps writing to someone and the
 * notifications: the conversation opens, any text only waits in it.
 */
class MainActivity : ComponentActivity() {

    private val requests: OpenRequests by inject()
    private val settings: SettingsStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a real launch: a recreated activity already took it.
        if (savedInstanceState == null) receive(intent)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) { com.yaz.sms.core.sms.Tapbacks.warm() }
        setContent {
            AppSurface { SmsApp() }
        }
        // The recent apps screen keeps a picture of the app: blank if the
        // user prefers, so the messages are not seen there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                settings.settings.map { it.hideInRecents }.distinctUntilChanged().collect { hide ->
                    setRecentsScreenshotEnabled(!hide)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Cards shared over the chat: the user's out, the others' to the Contacts app (when SMS opens, never on a timer).
        lifecycleScope.launch { com.yaz.sms.core.chat.Cards.sync(this@MainActivity, org.koin.java.KoinJavaComponent.get(com.yaz.sms.core.chat.RichChat::class.java), settings) }
    }

    /** singleTask: a request while the app runs arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        when (intent?.action) {
            ACTION_THREAD -> {
                val thread = intent.getLongExtra(EXTRA_THREAD, -1L).takeIf { it >= 0 }
                requests.open(OpenRequest(thread, intent.getStringExtra(EXTRA_ADDRESS), null))
            }
            // The Contacts app's "verified" mark: the conversation, on its encryption's keys. Shows, never acts.
            ACTION_SHOW_KEYS -> {
                val to = OpenRequests.addressesOf(intent.data?.schemeSpecificPart)
                if (to.isNotEmpty()) {
                    requests.keysFor.value = to.first()
                    requests.open(OpenRequest(null, to.joinToString(","), null))
                }
            }
            // A chat invite (its code read by the camera, or the link tapped): the user is asked first.
            Intent.ACTION_VIEW -> {
                val link = intent.dataString.orEmpty()
                if (com.yaz.sms.feature.compose.looksLikeInvite(link)) requests.invite.value = link
            }
            Intent.ACTION_SENDTO, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val to = OpenRequests.addressesOf(intent.data?.schemeSpecificPart)
                val text = OpenRequests.textOf(intent.getStringExtra("sms_body") ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
                // Pictures or films shared from another app fill the composer, as
                // AOSP Messaging's ShareIntentActivity takes them; copied now, while
                // the sender's one-time grant on them lasts.
                val streams = when (intent.action) {
                    Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
                    Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
                    else -> emptyList()
                }.take(5)
                if (streams.isNotEmpty()) {
                    val resolver = contentResolver
                    val cache = cacheDir
                    Thread {
                        val taken = streams.mapNotNull { uri ->
                            runCatching {
                                val type = resolver.getType(uri) ?: intent.type ?: return@mapNotNull null
                                val dir = java.io.File(cache, "shared-in").apply { mkdirs() }
                                val file = java.io.File(dir, "in-${System.nanoTime()}")
                                var kept = 0L
                                resolver.openInputStream(uri)?.use { input ->
                                    file.outputStream().use { out ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            kept += n
                                            // More than any MMS could carry: dropped whole.
                                            if (kept > 64L * 1024 * 1024) return@mapNotNull null
                                            out.write(buffer, 0, n)
                                        }
                                    }
                                } ?: return@mapNotNull null
                                com.yaz.sms.core.mms.Attachment(android.net.Uri.fromFile(file), type)
                            }.getOrNull()
                        }
                        requests.sharedMedia.value = taken
                    }.start()
                }
                // Several numbers (smsto:a,b) open their group.
                requests.open(OpenRequest(null, to.joinToString(",").ifEmpty { null }, text))
            }
        }
    }

    companion object {
        const val ACTION_THREAD = "com.yaz.sms.THREAD"
        /** Opens a number's conversation on its encryption's keys (for the Contacts app). */
        const val ACTION_SHOW_KEYS = "com.yaz.sms.action.SHOW_KEYS"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_ADDRESS = "address"
    }
}

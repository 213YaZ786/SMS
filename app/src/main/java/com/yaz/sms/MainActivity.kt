package com.yaz.sms

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.yaz.sms.core.chat.ChatService
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
        ChatService.startIfWanted(this, settings)
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
            Intent.ACTION_SENDTO, Intent.ACTION_SEND -> {
                val to = OpenRequests.addressesOf(intent.data?.schemeSpecificPart)
                val text = OpenRequests.textOf(intent.getStringExtra("sms_body") ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
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

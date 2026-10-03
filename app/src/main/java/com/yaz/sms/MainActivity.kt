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
        const val EXTRA_THREAD = "thread"
        const val EXTRA_ADDRESS = "address"
    }
}

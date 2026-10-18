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
import com.yaz.sms.core.sms.ShowMessage
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
    private val trash: com.yaz.sms.data.settings.TrashStore by inject()
    private val messages: com.yaz.sms.data.sms.Messages by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a real launch: a recreated activity already took it.
        if (savedInstanceState == null) receive(intent)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) { com.yaz.sms.core.sms.Tapbacks.warm() }
        // Conversations past their 30 days in the Trash are deleted as the app opens, at most once a
        // day, with no job and no alarm: Fossify Messages' checkAndDeleteOldRecycleBinMessages called
        // from MainActivity.onCreate (https://github.com/FossifyOrg/Messages, extensions/Context.kt),
        // Aves deleteExpiredTrash called when the source loads
        // (https://github.com/deckerst/aves/blob/develop/lib/model/source/media_store_source.dart);
        // the messages go up to their cutoff as AOSP Messaging's MmsUtils.deleteThread does.
        if (savedInstanceState == null) lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val gone = trash.sweep(System.currentTimeMillis()) { thread, cutoff -> messages.deleteUpTo(thread, cutoff) }
            if (gone.isNotEmpty()) {
                org.koin.java.KoinJavaComponent.get<com.yaz.sms.core.chat.RichChat>(com.yaz.sms.core.chat.RichChat::class.java).prune()
                settings.update { s -> s.copy(pinned = s.pinned - gone.toSet(), archived = s.archived - gone.toSet()) }
            }
        }
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
            // The Files app's "From Marie": a saved attachment's message, scrolled to and lit in its conversation.
            // Shows, never acts. The caller is the system's word (calling package, else the referrer it fills in),
            // a package of the suite signed with our key; an unknown caller simply opens. A thread or a message
            // that does not exist, or a message that is not in that thread, opens nothing.
            ShowMessage.ACTION -> {
                val asked = ShowMessage.of(intent) ?: return
                val caller = callingPackage ?: referrer?.takeIf { it.scheme == "android-app" }?.host
                if (!ShowMessage.allowed(this, caller)) return
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val found = messages.byUid(listOf(asked.uid))?.firstOrNull()
                    if (found != null && found.threadId == asked.thread) {
                        requests.open(OpenRequest(asked.thread, null, null, asked.uid))
                    }
                }
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
                    requests.sharedTo.value = to.joinToString(",").ifEmpty { null }
                    val resolver = contentResolver
                    val cache = cacheDir
                    Thread {
                        val taken = streams.mapNotNull { uri ->
                            runCatching {
                                // Only another app's content: AOSP Messaging's ShareIntentActivity
                                // refuses file:// (UriUtil.isFileUri), and none of our own providers
                                // (https://developer.android.com/privacy-and-security/risks/content-resolver,
                                // belongsToCurrentApplication), or a share could send our own files out.
                                if (uri.scheme != android.content.ContentResolver.SCHEME_CONTENT) return@mapNotNull null
                                val owner = packageManager.resolveContentProvider(uri.authority ?: return@mapNotNull null, 0)?.packageName
                                if (owner == null || owner == packageName) return@mapNotNull null
                                val claimed = resolver.getType(uri) ?: intent.type ?: return@mapNotNull null
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
                                // Sent under the type its bytes prove, as received parts are opened.
                                val copy = android.net.Uri.fromFile(file)
                                val type = com.yaz.sms.core.security.MediaCheck.typeFor(this, copy, claimed)
                                com.yaz.sms.core.mms.Attachment(copy, type)
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

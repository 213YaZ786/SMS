package com.yaz.sms

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.yaz.sms.core.sms.Lists
import com.yaz.sms.core.sms.OpenRequest
import com.yaz.sms.core.sms.OpenRequests
import com.yaz.sms.core.sms.ShowMessage
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.navigation.SmsApp
import com.yaz.sms.ui.theme.AppSurface
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
        // Messages left in the outbox or queued past ten minutes become failed, once as the app opens.
        if (savedInstanceState == null) lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { executeAction() } }
        // Conversations past their 30 days in the Trash are deleted as the app opens, at most once a
        // day, with no job and no alarm: Fossify Messages' checkAndDeleteOldRecycleBinMessages called
        // from MainActivity.onCreate (https://github.com/FossifyOrg/Messages, extensions/Context.kt),
        // Aves deleteExpiredTrash called when the source loads
        // (https://github.com/deckerst/aves/blob/develop/lib/model/source/media_store_source.dart);
        // the messages go up to their cutoff as AOSP Messaging's MmsUtils.deleteThread does.
        if (savedInstanceState == null) lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // A trashed conversation that came back (a message past its cutoff, DeleteConversationAction's
            // cutoffTimestamp) leaves the Trash first, so the sweep never deletes the old messages of a
            // conversation in use. If the store cannot be read, nothing is swept today.
            if (!messages.canRead()) return@launch
            val conversationId = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                messages.loaded.first { it }
                messages.conversations.value
            } ?: return@launch
            trash.restore(conversationId.filter { Lists.cameBack(it, trash.items.value) }.map { it.threadId })
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

    // not ported: unregister (no connectivity listener or alarm here)
    // not ported: queueActions (SmsSender sends, nothing is queued from here)
    // not ported: scheduleProcessPendingMessagesAction (no retry alarm: nothing runs in the background)
    // not ported: processFirstPendingMessage (one SIM loop of the action's start; this runs once as the app opens)
    // not ported: register (connectivity listener)
    // not ported: setRetry (no retry count)
    // not ported: getNextRetry (no retry count)
    // not ported: getHavePendingMessages (only asks if a retry is needed)
    // not ported: findNextMessageToDownload (picture messages are fetched by MmsTransport)
    // not ported: writeToParcel (the action is not parceled)
    // not ported: createFromParcel (the action is not parceled)
    // not ported: newArray (the action is not parceled)
    /**
     * ProcessPendingMessagesAction.executeAction: only the default SMS app acts on the pending messages.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/action/ProcessPendingMessagesAction.java#executeAction
     */
    private fun executeAction() {
        if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) {
            findNextMessageToSend(System.currentTimeMillis())
        } else {
            Log.v(TAG, "ProcessPendingMessagesAction: Not default SMS app")
        }
    }

    /**
     * findNextMessageToSend marks as failed the pending messages outside the resend window. Not ported: the choice
     * of the one message to send (sendingCnt, pendingCnt) and the inactive subscription test.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/action/ProcessPendingMessagesAction.java#findNextMessageToSend
     */
    private fun findNextMessageToSend(now: Long) {
        val values = ContentValues()
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
        val failedCnt = contentResolver.update(
            Telephony.Sms.CONTENT_URI, values,
            "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_OUTBOX}, ${Telephony.Sms.MESSAGE_TYPE_QUEUED}) AND ${Telephony.Sms.DATE} < ?",
            arrayOf((now - RESEND_TIMEOUT).toString())
        )
        Log.d(TAG, "ProcessPendingMessagesAction: $failedCnt failed messages")
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
                                // Except our own forwarded copies (the thread screen's forward-N files under cache/shared,
                                // through the mms FileProvider), so a forwarded picture arrives.
                                val forwarded = uri.authority == "$packageName.mms" && uri.pathSegments.firstOrNull() == "shared" &&
                                    uri.lastPathSegment.orEmpty().startsWith("forward-")
                                if (owner == null || (owner == packageName && !forwarded)) return@mapNotNull null
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

        private const val TAG = "MainActivity"

        /** A message still in the outbox or queued after this long failed: ten minutes. */
        private const val RESEND_TIMEOUT = 10 * 60 * 1000L
    }
}

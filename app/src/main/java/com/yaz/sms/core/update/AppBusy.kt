package com.yaz.sms.core.update

import android.content.Context
import android.provider.Telephony
import com.yaz.sms.core.call.CallBook
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether SMS must not be replaced now, asked by the update kit when the user
 * leaves the app (UpdatePrompt's busy): an encrypted call up, a message going
 * out, a voice message recorded or played. Read off the main thread.
 *
 * Sources: the system's outbox of messages not yet sent
 * (https://developer.android.com/reference/android/provider/Telephony.Sms.Outbox and
 * https://developer.android.com/reference/android/provider/Telephony.Mms.Outbox,
 * the box SmsStore.saveSending and MmsStore write to), and the update kit's
 * own contract (Modules/shared/update, Updates.arm).
 */
object AppBusy {

    private val voices = AtomicInteger(0)

    /** A voice message starts being recorded or played. */
    fun voiceStarted() {
        voices.incrementAndGet()
    }

    /** It stopped; never below zero. */
    fun voiceStopped() {
        voices.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun busy(context: Context): Boolean =
        CallBook.call.value != null || voices.get() > 0 || com.yaz.sms.ui.component.SafePlayer.anyPlaying() || sending(context)

    /**
     * A message in the outbox. Only the last ten minutes count: a row stuck
     * there for good must not keep the app from ever updating.
     */
    private fun sending(context: Context): Boolean {
        val since = System.currentTimeMillis() - RECENT_MS
        fun any(uri: android.net.Uri, date: Long) = runCatching {
            context.contentResolver.query(uri, arrayOf("_id"), "date > ?", arrayOf(date.toString()), null)?.use { it.count > 0 }
        }.getOrNull() ?: false
        // The SMS table keeps dates in milliseconds, the MMS table in seconds.
        return any(Telephony.Sms.Outbox.CONTENT_URI, since) || any(Telephony.Mms.Outbox.CONTENT_URI, since / 1000)
    }

    private const val RECENT_MS = 10 * 60 * 1000L
}

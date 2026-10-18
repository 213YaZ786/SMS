package com.yaz.sms.core.sms

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.yaz.sms.core.mms.SavedEntries

/**
 * A sister app's request to show one message (the Files app, from a saved attachment): the
 * conversation opens and scrolls to the message. It shows, never acts. Extras: "thread" (Long)
 * and "message" (String uid, "sms/7" or "mms/45"). The intent is explicit, the activity having no
 * filter for it, as com.yaz.sms.THREAD; the extras are read with the typed getters of Intent,
 * which give the default for another type
 * (https://developer.android.com/reference/android/content/Intent#getLongExtra(java.lang.String,%20long)),
 * and kept only in the shape Message.uid writes.
 */
data class ShowMessage(val thread: Long, val uid: String) {

    companion object {
        const val ACTION = "com.yaz.sms.action.SHOW_MESSAGE"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_MESSAGE = "message"

        /** The request an intent carries, or null when an extra is missing, of another type, or not a thread and a message uid. */
        fun of(intent: Intent?): ShowMessage? {
            if (intent?.action != ACTION) return null
            return runCatching {
                val thread = intent.getLongExtra(EXTRA_THREAD, -1L)
                val uid = intent.getStringExtra(EXTRA_MESSAGE)
                if (thread < 0 || !SavedEntries.isUid(uid)) null else ShowMessage(thread, uid!!)
            }.getOrNull()
        }

        /**
         * Whether the caller may ask: unknown (the system gave no package), or a package of the suite
         * signed with this app's key. The suite's Sisters lists Dialer, SMS and Contacts only, so Files
         * is judged here by the same comparison of signatures as Sisters.isOurs
         * (PackageManager.checkSignatures,
         * https://developer.android.com/reference/android/content/pm/PackageManager#checkSignatures(java.lang.String,%20java.lang.String)).
         */
        fun allowed(context: Context, caller: String?): Boolean {
            if (caller == null) return true
            if (caller == context.packageName) return true
            // A debug build also takes `adb shell am start`, whose caller is the shell, so the door can be driven on an emulator.
            if (com.yaz.sms.BuildConfig.DEBUG && caller == "com.android.shell") return true
            if (!caller.startsWith("com.yaz.")) return false
            return runCatching { context.packageManager.checkSignatures(context.packageName, caller) == PackageManager.SIGNATURE_MATCH }
                .getOrDefault(false)
        }
    }
}

package com.yaz.sms.navigation

import android.net.Uri

object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val THREAD = "thread?t={t}&a={a}&x={x}"
    const val NEW = "new?x={x}"

    /** A conversation, by its thread when known, else by the number; [text] waits in it. */
    fun thread(threadId: Long?, address: String?, text: String? = null) =
        "thread?t=${threadId ?: -1}&a=" + Uri.encode(address.orEmpty()) + "&x=" + Uri.encode(text.orEmpty())

    /** Choosing who to write to; [text] then waits in the conversation. */
    fun new(text: String? = null) = "new?x=" + Uri.encode(text.orEmpty())
}

package com.yaz.sms.core.dial

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Names the user keeps in the Contacts app only (private contacts, sealed
 * there and absent from Android's contacts), asked of it by number when
 * Android's contacts have none. Held in memory while the app runs, never
 * written anywhere. The Contacts app answers only while the phone is
 * unlocked, and only to apps signed with the same key.
 */
object PrivateNames {

    data class Private(val name: String, val look: ContactLook.Look?)

    /** A private person in the lists: a name and a look, nothing to open in Android's contacts. */
    const val CONTACT_ID = -2L

    private val authorities = listOf("com.yaz.contacts.private", "com.yaz.contacts.debug.private")
    private val known = ConcurrentHashMap<String, Private>()
    private val missed = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newSingleThreadExecutor()

    /** Grows when a name arrives, so a screen showing numbers looks again (read it in composition). */
    val version = mutableIntStateOf(0)

    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Off the main thread: a notification, a call being screened. */
    fun lookup(context: Context, number: String): Private? {
        val key = key(number) ?: return null
        known[key]?.let { return it }
        if (SystemClock.elapsedRealtime() - (missed[key] ?: Long.MIN_VALUE / 2) < RETRY_MS) return null
        return ask(context, number, key)
    }

    /** In a screen: what is known now; an unknown number is asked in the background. */
    fun cached(number: String): Private? {
        val key = key(number) ?: return null
        known[key]?.let { return it }
        val context = app ?: return null
        val now = SystemClock.elapsedRealtime()
        if (now - (missed[key] ?: Long.MIN_VALUE / 2) < RETRY_MS) return null
        // Marked as asked at once, so a list asks each number once.
        missed[key] = now
        pool.execute { if (ask(context, number, key) != null) version.intValue++ }
        return null
    }

    private fun ask(context: Context, number: String, key: String): Private? {
        for (authority in authorities) {
            val answer = runCatching {
                context.contentResolver.call(Uri.parse("content://$authority"), "lookup", null, Bundle().apply { putString("number", number) })
            }.getOrNull() ?: continue
            val name = answer.getString("name")?.takeIf { it.isNotBlank() }
            if (name == null) break
            val found = Private(name, answer.getString("look")?.let(ContactLook::parse))
            known[key] = found
            missed.remove(key)
            return found
        }
        // Not private, or the phone locked: asked again a little later.
        missed[key] = SystemClock.elapsedRealtime()
        return null
    }

    private fun key(number: String): String? {
        val digits = number.filter(Char::isDigit)
        return if (digits.length < 7) null else digits.takeLast(9)
    }

    private const val RETRY_MS = 60_000L
}

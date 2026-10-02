package com.sms.app.core.chat

import android.content.Context
import com.sms.app.core.common.writeTextAtomically
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The public chatmail relays and how fast they answer from here. The list
 * is read again each week from chatmail.at/relays, the chatmail project's
 * own page, so it follows relays that come and go; the one below is only
 * the first one and the fallback.
 */
object Relays {

    private val BuiltIn = listOf("nine.testrun.org", "d.gaufr.es", "mehl.cloud", "chatmail.email", "e2ee.im", "chat.vim.wtf", "chatmail.au")
    private const val PAGE = "https://chatmail.at/relays"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000
    private val Host = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    private fun file(context: Context) = File(context.filesDir, "relays.txt")

    /** The relays known now: the last list read, else the built-in one. */
    fun known(context: Context): List<String> =
        runCatching { file(context).readLines().drop(1).filter { Host.matches(it) } }.getOrNull()?.takeIf { it.size >= 3 } ?: BuiltIn

    /** Reads the list again when it is a week old: one request to chatmail.at. */
    fun refresh(context: Context) {
        val saved = file(context)
        val at = runCatching { saved.readLines().firstOrNull()?.toLong() }.getOrNull() ?: 0L
        if (System.currentTimeMillis() - at < WEEK) return
        val page = runCatching {
            (URL(PAGE).openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                try {
                    if (responseCode != 200) null else inputStream.use { String(it.readNBytes(512 * 1024)) }
                } finally {
                    disconnect()
                }
            }
        }.getOrNull() ?: return
        // Each relay is a highlighted link to its own site.
        val hosts = Regex("<a href=\"https://([^/\"]+)/?\" class=\"hilite\"").findAll(page).map { it.groupValues[1].lowercase() }
            .filter { Host.matches(it) }.distinct().toList()
        if (hosts.size < 3) return
        runCatching { saved.writeTextAtomically((listOf(System.currentTimeMillis().toString()) + hosts).joinToString("\n")) }
    }

    /**
     * The time a TLS greeting with [host] takes on the port messages come
     * through (IMAPS, 993), in milliseconds, or null when it does not answer
     * within three seconds or its certificate fails.
     */
    fun answerTime(host: String): Long? = runCatching {
        val start = System.nanoTime()
        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(host, 993), 3000)
            socket.soTimeout = 3000
            socket.startHandshake()
            // Its certificate must name it, as the engine itself demands.
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) return null
        }
        (System.nanoTime() - start) / 1_000_000
    }.getOrNull()
}

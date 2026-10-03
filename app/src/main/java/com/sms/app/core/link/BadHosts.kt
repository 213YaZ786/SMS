package com.sms.app.core.link

import com.sms.app.core.common.readAtMost
import android.content.Context
import com.sms.app.core.common.writeTextAtomically
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The public list of hosts that serve harmful files, from abuse.ch's
 * URLhaus (CC0): a dozen kilobytes fetched once a day, and every link is
 * looked up in it on the phone; no link ever leaves it. Off with the
 * setting, the list is deleted.
 */
object BadHosts {

    private const val SOURCE = "https://urlhaus.abuse.ch/downloads/hostfile/"
    private const val DAY = 24 * 60 * 60 * 1000L
    private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

    @Volatile private var hosts: Set<String> = emptySet()
    @Volatile private var loaded = false
    private val _version = MutableStateFlow(0)

    /** Moves each time the list changes, so links already shown are checked again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun file(context: Context) = File(context.filesDir, "bad-hosts.txt")

    /** Whether [host] is on the list (the list read from the phone the first time). */
    fun listed(context: Context, host: String): Boolean {
        if (!loaded) load(context)
        return host in hosts
    }

    private fun load(context: Context) {
        hosts = runCatching { file(context).readLines().drop(1).toHashSet() }.getOrDefault(emptySet())
        loaded = true
    }

    /** Fetches the list when the one on the phone is a day old: off the main thread. */
    fun refresh(context: Context) {
        val saved = file(context)
        val at = runCatching { saved.bufferedReader().use { it.readLine()?.toLong() } }.getOrNull() ?: 0L
        if (System.currentTimeMillis() - at < DAY) {
            if (!loaded) load(context)
            return
        }
        val page = runCatching {
            (URL(SOURCE).openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                try {
                    if (responseCode != 200) null else inputStream.use { String(it.readAtMost(2 * 1024 * 1024)) }
                } finally {
                    disconnect()
                }
            }
        }.getOrNull() ?: return
        // "127.0.0.1<tab>host" lines; comments and anything not a plain host name are left out.
        val found = page.lineSequence()
            .filter { !it.startsWith("#") }
            .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.lowercase() }
            .filter { HOST.matches(it) }
            .toHashSet()
        if (found.isEmpty()) return
        runCatching { saved.writeTextAtomically((listOf(System.currentTimeMillis().toString()) + found).joinToString("\n")) }
        hosts = found
        loaded = true
        _version.value++
    }

    /** The setting turned off: the list goes. */
    fun forget(context: Context) {
        file(context).delete()
        hosts = emptySet()
        loaded = true
        _version.value++
    }
}

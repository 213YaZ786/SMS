package com.sms.app.core.chat

import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** How fast a relay answers from here. */
object Relays {

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

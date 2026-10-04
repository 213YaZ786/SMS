package com.yaz.sms.core.link

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.yaz.sms.core.common.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * What a link leads to, fetched by this phone only when the user taps
 * Preview: the page's title, site and picture as the page declares them
 * (Open Graph, else its title). Over https only, without cookies, a page
 * read up to 512 kB and a picture up to 2 MB; kept in memory while the app
 * runs, never written.
 */
object LinkPreview {

    data class Preview(val url: String, val title: String, val site: String, val description: String?, val picture: Bitmap?)

    private val cache = LruCache<String, Preview>(40)

    fun cached(url: String): Preview? = cache.get(url)

    suspend fun fetch(context: android.content.Context, url: String): Preview? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        val (page, at) = get(url, 512 * 1024, html = true) ?: return@withContext null
        val html = String(page, charsetOf(page))
        val meta = metaTags(html)
        val title = (meta["og:title"] ?: meta["twitter:title"] ?: titleTag(html))?.let(::clean)?.take(200)
            ?: return@withContext null
        val site = meta["og:site_name"]?.let(::clean)?.take(60) ?: at.host.removePrefix("www.")
        val description = (meta["og:description"] ?: meta["description"])?.let(::clean)?.take(300)
        val picture = (meta["og:image"] ?: meta["og:image:url"] ?: meta["twitter:image"])
            ?.let { runCatching { URL(at, clean(it)) }.getOrNull() }
            ?.takeIf { it.protocol == "https" }
            ?.let { get(it.toString(), 2 * 1024 * 1024, html = false)?.first }
            // A page's picture is anyone's file: decoded in the isolated decoder.
            ?.let { com.yaz.sms.core.security.SafeImages.decode(context, it, 720) }
        Preview(url, title, site, description, picture).also { cache.put(url, it) }
    }

    /** One https page, three redirects at most and each to https; the bytes and where they came from. */
    private fun get(url: String, limit: Int, html: Boolean): Pair<ByteArray, URL>? = runCatching {
        var at = URL(url)
        repeat(4) {
            if (at.protocol != "https" || !public(at.host)) return null
            val connection = (at.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LinkPreview")
                setRequestProperty("Accept", if (html) "text/html" else "image/*")
            }
            try {
                when (connection.responseCode) {
                    in 300..399 -> at = URL(at, connection.getHeaderField("Location") ?: return null)
                    200 -> {
                        val type = connection.contentType.orEmpty()
                        if (html && !type.contains("html")) return null
                        if (!html && !type.startsWith("image/")) return null
                        return connection.inputStream.use { it.readAtMost(limit) } to at
                    }
                    else -> return null
                }
            } finally {
                connection.disconnect()
            }
        }
        null
    }.getOrNull()

    /** A host on the internet: never this phone, the home network or a link-local address, which a link could aim at. */
    private fun public(host: String): Boolean = runCatching {
        java.net.InetAddress.getAllByName(host).none {
            it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress ||
                it.isMulticastAddress || (it is java.net.Inet6Address && (it.address[0].toInt() and 0xfe) == 0xfc)
        }
    }.getOrDefault(false)

    /** The page's own charset when its head names one, else UTF-8. */
    private fun charsetOf(page: ByteArray): java.nio.charset.Charset = runCatching {
        val head = String(page, 0, minOf(page.size, 2048), Charsets.ISO_8859_1)
        Regex("charset=[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.let { java.nio.charset.Charset.forName(it) }
    }.getOrNull() ?: Charsets.UTF_8

    /** The page's meta tags by property or name, the first of each. */
    internal fun metaTags(html: String): Map<String, String> {
        val head = html.substringBefore("</head>", html.take(200_000))
        val out = HashMap<String, String>()
        for (tag in Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(head)) {
            val attrs = Regex("([a-zA-Z:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").findAll(tag.value)
                .associate { it.groupValues[1].lowercase() to (it.groupValues[3].ifEmpty { it.groupValues[4] }) }
            val key = (attrs["property"] ?: attrs["name"])?.lowercase() ?: continue
            val value = attrs["content"]?.takeIf { it.isNotBlank() } ?: continue
            out.putIfAbsent(key, value)
        }
        return out
    }

    private fun titleTag(html: String): String? =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** Entities and spaces of a page's text, as read. */
    internal fun clean(text: String): String =
        android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
}

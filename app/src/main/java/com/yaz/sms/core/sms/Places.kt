package com.yaz.sms.core.sms

import java.net.URLDecoder

/**
 * A place shared in a message, as the map apps of the major platforms
 * write one: geo: links, Google Maps, Apple Maps, OpenStreetMap, Waze,
 * HERE, Yandex and the coordinates SMS writes itself. Only links that carry
 * the coordinates: a short link would have to be opened to know where it
 * leads, and is left as a link.
 */
object Places {

    data class Place(val lat: Double, val lon: Double, val label: String? = null) {
        /** For the user's own map app. */
        fun geo(): String = "geo:$lat,$lon?q=$lat,$lon" + (label?.let { "(" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") + ")" } ?: "")
    }

    private const val NUM = "(-?\\d{1,3}(?:\\.\\d+)?)"
    private val patterns = listOf(
        Regex("geo:$NUM,$NUM(?:[^\\s]*?q=([^\\s&]+))?", RegexOption.IGNORE_CASE),
        Regex("https?://(?:www\\.)?google\\.[a-z.]+/maps[^\\s]*?@$NUM,$NUM", RegexOption.IGNORE_CASE),
        Regex("https?://(?:maps\\.google\\.[a-z.]+|(?:www\\.)?google\\.[a-z.]+/maps)[^\\s]*?[?&](?:q|query|ll|destination)=$NUM(?:,|%2C)\\s*$NUM", RegexOption.IGNORE_CASE),
        Regex("https?://maps\\.apple\\.com/[^\\s]*?[?&](?:ll|q|sll|coordinate)=$NUM(?:,|%2C)$NUM(?:[^\\s]*?[?&]q=([^\\s&]+))?", RegexOption.IGNORE_CASE),
        Regex("https?://(?:www\\.)?openstreetmap\\.org/[^\\s]*?mlat=$NUM&mlon=$NUM", RegexOption.IGNORE_CASE),
        Regex("https?://(?:www\\.)?openstreetmap\\.org/[^\\s]*?#map=\\d+/$NUM/$NUM", RegexOption.IGNORE_CASE),
        Regex("https?://(?:www\\.)?waze\\.com/[^\\s]*?ll=$NUM(?:,|%2C)$NUM", RegexOption.IGNORE_CASE),
        Regex("https?://(?:share\\.|wego\\.)?here\\.com/[^\\s]*?/$NUM,$NUM", RegexOption.IGNORE_CASE),
        Regex("https?://yandex\\.[a-z.]+/maps[^\\s]*?[?&](?:ll|pt)=$NUM(?:,|%2C)$NUM", RegexOption.IGNORE_CASE)
    )

    /** Most of an iPhone's location card that is read: the real ones are under a kilobyte. */
    const val CARD_MAX = 8 * 1024
    private const val CARD_LINES = 200
    private const val CARD_LINE = 2048
    private const val NAME_MAX = 60

    /**
     * The place an iPhone shares, its `Current Location.loc.vcf` (type text/x-vlocation): a vCard whose URL is
     * the Apple Maps link, the comma between the coordinates escaped (`ll=48.8584\,2.2945`). Read as AirMessage
     * Android reads it (FileDisplayMetadata.LocationDetailed, Apache-2.0,
     * https://github.com/airmessage/airmessage-android/blob/0758ea0273f942881fd8de605aa349b40a1c404c/app/src/main/java/me/tagavari/airmessage/messaging/FileDisplayMetadata.kt):
     * the name is FN; the map link is the URL of the group labelled `map url` by an X-ABLabel (older iOS), else
     * the URL that has no group (newer iOS). AirMessage then splits the link's `ll`; here the link goes through
     * [find], which takes only coordinates in range, so a link with a street in place of them gives none. The
     * vCard folding of RFC 6350 section 3.2 is undone first. [card] is text the caller cut to [CARD_MAX]; the
     * lines read, their length and the name are bounded again here. Null when there is no such link.
     */
    fun fromVLocation(card: String): Place? {
        var name: String? = null
        val urls = ArrayList<Pair<String?, String>>()
        val labelled = HashSet<String>()
        // Unfold: a line starting with a space or a tab goes on the one before it.
        val lines = ArrayList<String>()
        for (raw in card.take(CARD_MAX).split('\n')) {
            val line = raw.trimEnd('\r')
            if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
                val last = lines.last()
                if (last.length < CARD_LINE) lines[lines.lastIndex] = last + line.substring(1)
            } else {
                if (lines.size >= CARD_LINES) break
                lines += line
            }
        }
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).substringBefore(';')
            val group = if ('.' in key) key.substringBefore('.') else null
            val property = key.substringAfter('.').uppercase()
            val value = line.substring(colon + 1).take(CARD_LINE)
            when (property) {
                "FN" -> if (name == null) name = unescape(value)
                "URL" -> urls += group to unescape(value)
                "X-ABLABEL" -> if (group != null && value == "map url") labelled += group
            }
        }
        val link = (if (labelled.isNotEmpty()) urls.firstOrNull { it.first in labelled } else urls.firstOrNull { it.first == null })?.second
            ?: return null
        val place = find(link) ?: return null
        val title = name?.filter { !it.isISOControl() }?.trim()?.take(NAME_MAX)?.takeIf { it.isNotEmpty() }
        return Place(place.lat, place.lon, title ?: place.label)
    }

    /** A vCard value's escapes: `\,` `\;` `\\` and `\n`. */
    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val n = value[i + 1]
                append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    fun find(body: String): Place? {
        for ((i, p) in patterns.withIndex()) {
            val m = p.find(body) ?: continue
            var lat = m.groupValues[1].toDoubleOrNull() ?: continue
            var lon = m.groupValues[2].toDoubleOrNull() ?: continue
            // Yandex writes the longitude first.
            if (i == patterns.lastIndex) lat = lon.also { lon = lat }
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) continue
            val label = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?.takeIf { it.isNotBlank() && !Regex("^-?\\d").containsMatchIn(it) }
            return Place(lat, lon, label)
        }
        return null
    }
}

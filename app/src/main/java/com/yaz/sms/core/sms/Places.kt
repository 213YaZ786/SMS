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

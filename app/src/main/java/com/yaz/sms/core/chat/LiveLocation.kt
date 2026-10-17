package com.yaz.sms.core.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Live location over the encrypted chat: what is shared, for how long, how often the phone is read
 * and which received point is the latest. The sharing itself stays in chatmail core
 * (send_locations_to_chat, set_location, get_locations; deltachat-jsonrpc api.rs v2.62.0).
 */
object LiveLocation {

    /** How long to share: Delta Chat Android's list (ShareLocationDialog.java, array share_location_durations). */
    data class Duration(val seconds: Int, val label: String)

    /** 30 minutes, 1, 2, 6 and 24 hours, in the order of Delta Chat Android's ShareLocationDialog. */
    val durations = listOf(
        Duration(30 * 60, "30 minutes"),
        Duration(60 * 60, "1 hour"),
        Duration(2 * 60 * 60, "2 hours"),
        Duration(6 * 60 * 60, "6 hours"),
        Duration(24 * 60 * 60, "24 hours")
    )

    /** Time between two readings, in ms: Delta Chat Android's LOCATION_INTERVAL (v1.58.0 LocationBackgroundService.java). */
    const val INTERVAL_MS = 1000L

    /** Distance between two readings, in metres: Delta Chat Android's LOCATION_DISTANCE (same file). */
    const val DISTANCE_M = 25f

    /** A last known fix is used at once when it is not older than this: Delta Chat Android's 10 minutes (same file, onCreate). */
    const val LAST_FIX_MS = 10 * 60 * 1000L

    /** The engine's id for the user among a chat's contacts (also in RichChat). */
    const val SELF = 1

    /** Received points are read this far back: the longest sharing period above. */
    const val WINDOW_S = 24 * 60 * 60L

    /** A received point may be this much ahead of this phone's clock (two phones' clocks differ); later ones are refused. */
    const val SKEW_S = 5 * 60L

    /** When a sharing started at [startMs] for [seconds] ends (core: locations_send_until = now + seconds, location.rs send_to_chat). */
    fun endsAt(startMs: Long, seconds: Int): Long = startMs + seconds.coerceAtLeast(0) * 1000L

    /** The end as the person's clock reads it ("14:30", "2:30 PM"). */
    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(ms))

    /** A coordinate pair that can be a place on Earth; (0, 0) is the core's own "no fix" (location.rs set). */
    fun valid(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    /** One received point: who sent it, where, and when (milliseconds). */
    data class Spot(val contactId: Int, val lat: Double, val lon: Double, val at: Long)

    /**
     * The newest valid point of each other person, from get_locations' answer (JsonrpcLocation:
     * contactId, isIndependent, latitude, longitude, timestamp in seconds). The core also returns
     * every "independent" point whatever its date, and the user's own points: both are left out. A
     * point out of range, not a number, dated before [sinceMs] or later than the clock allows is
     * refused, and an answer that is not a list gives nothing.
     */
    fun latest(answer: kotlinx.serialization.json.JsonElement?, nowMs: Long, sinceMs: Long = nowMs - WINDOW_S * 1000): List<Spot> {
        val list = (answer as? JsonArray) ?: return emptyList()
        val nowS = Math.floorDiv(nowMs, 1000L)
        val sinceS = Math.floorDiv(sinceMs, 1000L)
        val best = HashMap<Int, Spot>()
        for (item in list) {
            val o = item as? JsonObject ?: continue
            val who = (o["contactId"] as? JsonPrimitive)?.intOrNull ?: continue
            if (who == SELF || who <= 0) continue
            if ((o["isIndependent"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val lat = (o["latitude"] as? JsonPrimitive)?.doubleOrNull ?: continue
            val lon = (o["longitude"] as? JsonPrimitive)?.doubleOrNull ?: continue
            if (!valid(lat, lon)) continue
            val ts = (o["timestamp"] as? JsonPrimitive)?.longOrNull ?: continue
            if (ts < sinceS || ts <= 0 || ts > nowS + SKEW_S) continue
            val known = best[who]
            if (known == null || ts * 1000 > known.at) best[who] = Spot(who, lat, lon, ts * 1000)
        }
        return best.values.sortedWith(compareByDescending<Spot> { it.at }.thenBy { it.contactId })
    }

    /** "Updated 5 min ago": how long ago a point was, never negative. */
    fun updated(nowMs: Long, atMs: Long): String {
        val minutes = ((nowMs - atMs).coerceAtLeast(0)) / 60_000
        return "Updated " + when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} d ago"
        }
    }
}

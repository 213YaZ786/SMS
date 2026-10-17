package com.yaz.sms.core.chat

import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durations of Delta Chat Android's ShareLocationDialog, the end of a sharing, and the choice of
 * the latest point of each person in an answer of the engine's get_locations (JsonrpcLocation).
 */
class LiveLocationTest {

    private val now = 1_800_000_000_000L
    private val nowS = now / 1000

    private fun answer(vararg items: String) = Json.parseToJsonElement(items.joinToString(",", "[", "]"))

    private fun point(contact: Any, lat: Any, lon: Any, ts: Any, independent: Boolean = false) =
        """{"locationId":1,"isIndependent":$independent,"latitude":$lat,"longitude":$lon,"accuracy":10.0,"timestamp":$ts,"contactId":$contact,"msgId":0,"chatId":12}"""

    @Test
    fun durationsAreDeltaChatsFive() {
        assertEquals(listOf(1800, 3600, 7200, 21600, 86400), LiveLocation.durations.map { it.seconds })
        assertEquals(listOf("30 minutes", "1 hour", "2 hours", "6 hours", "24 hours"), LiveLocation.durations.map { it.label })
    }

    @Test
    fun readingRateIsDeltaChatsOneSecondAndTwentyFiveMetres() {
        assertEquals(1000L, LiveLocation.INTERVAL_MS)
        assertEquals(25f, LiveLocation.DISTANCE_M, 0f)
    }

    @Test
    fun theEndIsTheStartPlusTheDuration() {
        assertEquals(now + 1800_000L, LiveLocation.endsAt(now, 1800))
        assertEquals(now + 86_400_000L, LiveLocation.endsAt(now, 86_400))
        // A negative duration never ends before it began.
        assertEquals(now, LiveLocation.endsAt(now, -5))
    }

    @Test
    fun theEndReadsAsAClock() {
        // 1 800 000 000 s is 08:00 in UTC.
        assertEquals("08:00", LiveLocation.clock(now, ZoneId.of("UTC"), Locale.FRANCE))
        assertEquals("09:00", LiveLocation.clock(now, ZoneId.of("Europe/Paris"), Locale.FRANCE))
        assertEquals("8:00 AM", LiveLocation.clock(now, ZoneId.of("UTC"), Locale.US).replace(' ', ' ').replace(' ', ' '))
    }

    @Test
    fun coordinatesMustBeAPlace() {
        assertTrue(LiveLocation.valid(48.8566, 2.3522))
        assertTrue(LiveLocation.valid(-90.0, 180.0))
        assertFalse(LiveLocation.valid(90.0001, 0.0))
        assertFalse(LiveLocation.valid(0.0, -180.5))
        assertFalse(LiveLocation.valid(Double.NaN, 2.0))
        assertFalse(LiveLocation.valid(2.0, Double.POSITIVE_INFINITY))
        assertFalse(LiveLocation.valid(0.0, 0.0))
    }

    @Test
    fun theNewestPointOfEachPersonWins() {
        val spots = LiveLocation.latest(
            answer(
                point(5, 48.0, 2.0, nowS - 600),
                point(5, 48.5, 2.5, nowS - 60),
                point(5, 47.0, 1.0, nowS - 3000),
                point(7, 10.0, 20.0, nowS - 300)
            ),
            now
        )
        assertEquals(listOf(5, 7), spots.map { it.contactId })
        assertEquals(48.5, spots[0].lat, 0.0)
        assertEquals(2.5, spots[0].lon, 0.0)
        assertEquals((nowS - 60) * 1000, spots[0].at)
        assertEquals(10.0, spots[1].lat, 0.0)
    }

    @Test
    fun emptyOrNotAListGivesNothing() {
        assertEquals(emptyList<LiveLocation.Spot>(), LiveLocation.latest(answer(), now))
        assertEquals(emptyList<LiveLocation.Spot>(), LiveLocation.latest(null, now))
        assertEquals(emptyList<LiveLocation.Spot>(), LiveLocation.latest(Json.parseToJsonElement("{}"), now))
        assertEquals(emptyList<LiveLocation.Spot>(), LiveLocation.latest(Json.parseToJsonElement("\"x\""), now))
        assertEquals(emptyList<LiveLocation.Spot>(), LiveLocation.latest(Json.parseToJsonElement("[1,null,\"a\",[]]"), now))
    }

    @Test
    fun valuesOutOfRangeAreRefused() {
        val spots = LiveLocation.latest(
            answer(
                point(5, 91.0, 2.0, nowS - 10),
                point(6, 48.0, 181.0, nowS - 10),
                point(7, -90.5, 0.5, nowS - 10),
                point(8, 0.0, 0.0, nowS - 10),
                point(9, 1e308, 1e308, nowS - 10),
                point(10, 12.5, 40.25, nowS - 10)
            ),
            now
        )
        assertEquals(listOf(10), spots.map { it.contactId })
    }

    @Test
    fun notANumberIsRefused() {
        val spots = LiveLocation.latest(
            answer(
                point(5, "\"NaN\"", 2.0, nowS - 10),
                point(6, 48.0, "\"Infinity\"", nowS - 10),
                point(7, "\"abc\"", "\"def\"", nowS - 10),
                point(8, "null", 2.0, nowS - 10),
                point(9, 2.0, 3.0, "\"NaN\""),
                point(10, 2.0, 3.0, "null"),
                point(11, 2.0, 3.0, nowS - 10)
            ),
            now
        )
        assertEquals(listOf(11), spots.map { it.contactId })
    }

    @Test
    fun datesInTheFutureOrTooOldAreRefused() {
        val spots = LiveLocation.latest(
            answer(
                point(5, 48.0, 2.0, nowS + 3600),
                point(6, 48.0, 2.0, Long.MAX_VALUE),
                point(7, 48.0, 2.0, 0),
                point(8, 48.0, 2.0, -50),
                point(9, 48.0, 2.0, nowS - 25 * 3600),
                point(10, 48.0, 2.0, nowS + 60),
                point(11, 48.0, 2.0, nowS - 23 * 3600)
            ),
            now
        )
        assertEquals(listOf(10, 11), spots.map { it.contactId }.sorted())
    }

    @Test
    fun aFuturePointNeverBeatsARealOne() {
        val spots = LiveLocation.latest(
            answer(point(5, 1.0, 1.0, nowS - 30), point(5, 66.0, 66.0, nowS + 10 * 3600)),
            now
        )
        assertEquals(1, spots.size)
        assertEquals(1.0, spots[0].lat, 0.0)
    }

    @Test
    fun theUsersOwnAndIndependentPointsAreLeftOut() {
        val spots = LiveLocation.latest(
            answer(
                point(1, 48.0, 2.0, nowS - 10),
                point(0, 48.0, 2.0, nowS - 10),
                point(-3, 48.0, 2.0, nowS - 10),
                point("\"x\"", 48.0, 2.0, nowS - 10),
                // The core returns a one-off place whatever its date.
                point(6, 48.0, 2.0, nowS - 10, independent = true),
                point(7, 48.0, 2.0, nowS - 10)
            ),
            now
        )
        assertEquals(listOf(7), spots.map { it.contactId })
    }

    @Test
    fun howLongAgo() {
        assertEquals("Updated just now", LiveLocation.updated(now, now - 20_000))
        assertEquals("Updated just now", LiveLocation.updated(now, now + 5 * 60_000))
        assertEquals("Updated 1 min ago", LiveLocation.updated(now, now - 60_000))
        assertEquals("Updated 59 min ago", LiveLocation.updated(now, now - 59 * 60_000))
        assertEquals("Updated 2 h ago", LiveLocation.updated(now, now - 150 * 60_000))
        assertEquals("Updated 3 d ago", LiveLocation.updated(now, now - 3 * 24 * 3_600_000L))
    }
}

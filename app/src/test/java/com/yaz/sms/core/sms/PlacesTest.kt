package com.yaz.sms.core.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {

    private fun at(body: String) = Places.find(body)?.let { it.lat to it.lon }

    @Test
    fun mapApps() {
        assertEquals(48.8584 to 2.2945, at("geo:48.8584,2.2945?q=48.8584,2.2945"))
        assertEquals(48.8584 to 2.2945, at("Here https://www.google.com/maps/place/Tour+Eiffel/@48.8584,2.2945,17z"))
        assertEquals(48.8584 to 2.2945, at("https://maps.google.com/?q=48.8584,2.2945"))
        assertEquals(48.8584 to 2.2945, at("https://maps.apple.com/?ll=48.8584,2.2945&q=Dropped%20Pin"))
        assertEquals(48.8584 to 2.2945, at("https://www.openstreetmap.org/?mlat=48.8584&mlon=2.2945#map=17/48.8584/2.2945"))
        assertEquals(48.8584 to 2.2945, at("https://waze.com/ul?ll=48.8584,2.2945&navigate=yes"))
        assertEquals(55.7539 to 37.6208, at("https://yandex.ru/maps/?ll=37.6208,55.7539&z=16"))
        assertEquals("Dropped Pin", Places.find("https://maps.apple.com/?ll=48.8584,2.2945&q=Dropped%20Pin")?.label)
    }

    /*
     * An iPhone's location card, the two shapes AirMessage Android documents in FileDisplayMetadata.LocationDetailed
     * (Apache-2.0, https://github.com/airmessage/airmessage-android/blob/0758ea0273f942881fd8de605aa349b40a1c404c/
     * app/src/main/java/me/tagavari/airmessage/messaging/FileDisplayMetadata.kt; it ships no test): the newer one
     * with the link alone, the older one with the link in a group its X-ABLabel calls "map url".
     */
    private val newCard = "BEGIN:VCARD\r\nVERSION:3.0\r\nPRODID:-//Apple Inc.//iPhone OS 14.3//EN\r\nN:;Current Location;;;\r\n" +
        "FN:Current Location\r\nURL;type=pref:http://maps.apple.com/?ll=48.858400\\,2.294500&q=48.858400\\,2.294500\r\nEND:VCARD\r\n"

    private val oldCard = "BEGIN:VCARD\nVERSION:3.0\nN:;Tour Eiffel;;;\nFN:Tour Eiffel\n" +
        "item1.ADR;type=WORK;type=pref:;;Champ de Mars;Paris;;75007;France\nitem1.X-ABADR:fr\n" +
        "item2.URL;type=pref:http://www.toureiffel.paris\nitem2.X-ABLabel:_\$!<HomePage>!\$_\n" +
        "item3.URL:https://maps.apple.com/?ll=48.858400\\,2.294500&q=Tour%20Eiffel\nitem3.X-ABLabel:map url\nEND:VCARD\n"

    @Test
    fun anIphoneLocationCard() {
        val place = Places.fromVLocation(newCard)!!
        assertEquals(48.8584 to 2.2945, place.lat to place.lon)
        assertEquals("Current Location", place.label)
        assertEquals("geo:48.8584,2.2945?q=48.8584,2.2945(Current%20Location)", place.geo())
    }

    @Test
    fun anOlderCardKeepsItsLinkInTheLabelledGroup() {
        val place = Places.fromVLocation(oldCard)!!
        assertEquals(48.8584 to 2.2945, place.lat to place.lon)
        assertEquals("Tour Eiffel", place.label)
        // The homepage of the place is no map link; without the label it is not taken for one.
        assertNull(Places.fromVLocation(oldCard.replace("map url", "homepage")))
    }

    @Test
    fun foldedLinesAndNegativeCoordinates() {
        val card = "BEGIN:VCARD\r\nFN:Sydney\r\nURL;type=pref:http://maps.apple.com/?ll=-33.868800\\,151\r\n .209300&q=x\r\nEND:VCARD"
        val place = Places.fromVLocation(card)!!
        assertEquals(-33.8688 to 151.2093, place.lat to place.lon)
    }

    @Test
    fun aCardThatIsNoLocationStaysAFile() {
        assertNull(Places.fromVLocation(""))
        // A contact card: no link at all.
        assertNull(Places.fromVLocation("BEGIN:VCARD\nVERSION:3.0\nFN:Ana Diaz\nTEL:+33611112222\nEND:VCARD"))
        // A link to a street, not to coordinates.
        assertNull(Places.fromVLocation("BEGIN:VCARD\nFN:Home\nURL:https://maps.apple.com/?q=1%20Infinite%20Loop\nEND:VCARD"))
        // A link to another site.
        assertNull(Places.fromVLocation("BEGIN:VCARD\nFN:Home\nURL:https://example.org/?ll=48.85,2.29\nEND:VCARD"))
    }

    @Test
    fun badNumbersGiveNothing() {
        fun card(ll: String) = "BEGIN:VCARD\nFN:X\nURL:https://maps.apple.com/?ll=$ll\nEND:VCARD"
        assertNull(Places.fromVLocation(card("abc\\,def")))
        assertNull(Places.fromVLocation(card("91\\,0")))
        assertNull(Places.fromVLocation(card("0\\,181")))
        assertNull(Places.fromVLocation(card("0\\,0")))
        assertNull(Places.fromVLocation(card("NaN\\,NaN")))
        assertNull(Places.fromVLocation(card("1e999\\,1e999")))
        assertNull(Places.fromVLocation(card("1".repeat(5000) + "\\," + "2".repeat(5000))))
    }

    @Test
    fun aHugeFileIsReadOnlyInPart() {
        val started = System.nanoTime()
        // Ten megabytes of nothing, a link far past the limit, a million folded lines, a million lines.
        assertNull(Places.fromVLocation("A".repeat(10_000_000)))
        assertNull(Places.fromVLocation("X".repeat(Places.CARD_MAX) + "\nURL:https://maps.apple.com/?ll=48.85\\,2.29"))
        assertNull(Places.fromVLocation("FN:A\n" + "\n ".repeat(1_000_000) + "\nURL:https://maps.apple.com/?ll=48.85\\,2.29"))
        assertNull(Places.fromVLocation("\n".repeat(1_000_000) + "URL:https://maps.apple.com/?ll=48.85\\,2.29"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
    }

    @Test
    fun theNameIsPlainAndShort() {
        val long = "N".repeat(500)
        val place = Places.fromVLocation("BEGIN:VCARD\nFN:Caf\u0000e\u0007 $long\nURL:https://maps.apple.com/?ll=48.85\\,2.29\nEND:VCARD")!!
        assertEquals(60, place.label!!.length)
        assertTrue(place.label!!.startsWith("Cafe N"))
    }

    @Test
    fun notAPlace() {
        assertNull(Places.find("https://maps.app.goo.gl/abc123"))
        assertNull(Places.find("Meet at 12,30"))
        assertNull(Places.find("geo:123.0,2.0"))
    }
}

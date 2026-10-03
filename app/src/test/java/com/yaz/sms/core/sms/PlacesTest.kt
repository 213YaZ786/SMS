package com.yaz.sms.core.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun notAPlace() {
        assertNull(Places.find("https://maps.app.goo.gl/abc123"))
        assertNull(Places.find("Meet at 12,30"))
        assertNull(Places.find("geo:123.0,2.0"))
    }
}

package com.sms.app.core.sms

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FindsTest {

    private val today = LocalDate.of(2026, 10, 2)

    @Test
    fun parcels() {
        assertEquals("6A12345678901", Finds.parcel("Votre colis 6A12345678901 sera livré demain"))
        assertEquals("LB123456789FR", Finds.parcel("Suivi de votre envoi LB123456789FR"))
        assertEquals("1Z999AA10123456784", Finds.parcel("Your package 1Z999AA10123456784 is on its way"))
        assertNull(Finds.parcel("Mon code est 123456789012"))
    }

    @Test
    fun appointments() {
        assertEquals(LocalDateTime.of(2026, 10, 12, 18, 30), Finds.appointment("RDV le 12/10 à 18h30", today))
        assertEquals(LocalDateTime.of(2026, 10, 3, 14, 0), Finds.appointment("On se voit demain à 14h", today))
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 15), Finds.appointment("Rendez-vous le 5 novembre à 9h15", today))
        assertEquals(LocalDateTime.of(2026, 10, 3, 18, 0), Finds.appointment("See you tomorrow at 6pm", today))
        assertNull(Finds.appointment("Merci beaucoup !", today))
        assertNull(Finds.appointment("Il est 18h30", today))
    }
}

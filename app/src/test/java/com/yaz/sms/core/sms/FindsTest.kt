package com.yaz.sms.core.sms

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
        assertEquals(LocalDateTime.of(2026, 10, 12, 18, 30), Finds.appointment("RDV le 12/10 à 18h30", today, monthFirst = false))
        assertEquals(LocalDateTime.of(2026, 10, 3, 14, 0), Finds.appointment("On se voit demain à 14h", today))
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 15), Finds.appointment("Rendez-vous le 5 novembre à 9h15", today))
        assertEquals(LocalDateTime.of(2026, 10, 3, 18, 0), Finds.appointment("See you tomorrow at 6pm", today))
        assertNull(Finds.appointment("Merci beaucoup !", today))
        assertNull(Finds.appointment("Il est 18h30", today))
    }

    @Test
    fun parcelsInOtherLanguages() {
        assertEquals("LB123456789ES", Finds.parcel("Su paquete LB123456789ES está en camino"))
        assertEquals("00340434161234567890", Finds.parcel("Ihre Sendung 00340434161234567890 wird heute zugestellt"))
        assertEquals("RR123456789IT", Finds.parcel("Il tuo pacco RR123456789IT è in consegna"))
        assertEquals("3SABCD1234567", Finds.parcel("Uw pakket 3SABCD1234567 wordt morgen bezorgd"))
    }

    @Test
    fun appointmentsInOtherLanguages() {
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), Finds.appointment("Cita el 12 de octubre a las 9:00", today))
        assertEquals(LocalDateTime.of(2026, 10, 3, 14, 30), Finds.appointment("Termin morgen um 14:30 Uhr", today))
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), Finds.appointment("Termin am 14. Oktober um 10 Uhr", today))
        assertEquals(LocalDateTime.of(2026, 10, 3, 18, 0), Finds.appointment("Appuntamento domani alle 18:00", today))
        assertEquals(LocalDateTime.of(2026, 10, 20, 15, 0), Finds.appointment("Consulta dia 20 de outubro às 15h", today))
        assertEquals(LocalDateTime.of(2026, 10, 15, 11, 0), Finds.appointment("Afspraak op 15 oktober om 11:00", today))
    }

    @Test
    fun theOrderOfTheDateFollowsThePlace() {
        assertEquals(LocalDateTime.of(2026, 3, 10, 16, 0).plusYears(1), Finds.appointment("Dentist 3/10 at 4pm", today, monthFirst = true))
        assertEquals(LocalDateTime.of(2026, 10, 3, 16, 0), Finds.appointment("Dentiste 3/10 à 16h", today, monthFirst = false))
        assertEquals(LocalDateTime.of(2026, 12, 25, 10, 0), Finds.appointment("Party 12/25 at 10am", today, monthFirst = false))
        assertEquals(LocalDateTime.of(2026, 10, 21, 9, 30), Finds.appointment("Appointment October 21 at 9:30am", today))
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 0), Finds.appointment("Visit 2026-11-04 08:00", today))
    }

    @Test
    fun morningIsNotTomorrow() {
        assertNull(Finds.appointment("Guten Morgen! Alles gut um 9:00?", today))
        assertEquals(LocalDateTime.of(2026, 10, 2, 9, 0), Finds.appointment("Hoy por la mañana a las 9:00", today))
    }

    @Test
    fun monthsInEveryLanguageThePhoneKnows() {
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), Finds.appointment("Randevu 14 Ekim saat 10:00", today))
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), Finds.appointment("Wizyta 14 października o 10:00", today))
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), Finds.appointment("Möte 14 oktober kl 10:00", today))
    }
}

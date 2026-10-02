package com.sms.app.core.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodesTest {

    @Test
    fun findsTheCodeInUsualMessages() {
        assertEquals("482913", Codes.find("Your verification code is 482913. Do not share it."))
        assertEquals("7731", Codes.find("Votre code de connexion : 7731"))
        assertEquals("123456", Codes.find("Your code is 123-456"))
        assertEquals("90210", Codes.find("OTP 90210 valid 5 minutes"))
    }

    @Test
    fun leavesOrdinaryMessagesAlone() {
        assertNull(Codes.find("See you at 1830 near the station"))
        assertNull(Codes.find("Rendez-vous le 12/10 à 18:30"))
    }

    @Test
    fun ignoresAmountsAndPhoneNumbers() {
        assertNull(Codes.find("Your code: pay 1500€ before Friday"))
        assertNull(Codes.find("Verification: call +33612345678"))
    }
}

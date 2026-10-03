package com.yaz.sms.core.sms

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

    @Test
    fun findsCodesInOtherLanguages() {
        assertEquals("482913", Codes.find("Tu código de verificación es 482913"))
        assertEquals("5521", Codes.find("Ihr Bestätigungscode lautet 5521"))
        assertEquals("773310", Codes.find("Il tuo codice è 773310"))
        assertEquals("118822", Codes.find("Seu código de verificação: 118822"))
        assertEquals("4410", Codes.find("Je verificatiecode is 4410"))
        assertEquals("651209", Codes.find("Ваш код: 651209"))
        assertEquals("903311", Codes.find("【银行】验证码903311，5分钟内有效"))
        assertEquals("284731", Codes.find("आपका OTP 284731 है"))
    }

    @Test
    fun ignoresAmountsInOtherCurrencies() {
        assertNull(Codes.find("Your code: pay ₹1500 today"))
        assertNull(Codes.find("Code promo: 2500¥"))
    }
}

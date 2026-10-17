package com.yaz.sms.core.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases of AOSP ExtServices' NotificationOtpDetectionHelperTest (Apache-2.0,
 * https://android.googlesource.com/platform/packages/modules/ExtServices/+/refs/heads/main/java/tests/src/android/ext/services/notification/NotificationOtpDetectionHelperTest.kt):
 * length, non Roman letters, a number required, dates, phone numbers, dashes, start and end,
 * the lookahead, the context words in a sentence and their reach, several false positives.
 * Its notification plumbing and text classifier mocks do not apply here: a code is looked for
 * in a text with a context word ahead ("code " is put in front where the case has none).
 */
class CodesTest {

    private fun has(text: String) = Codes.find(text) != null

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
    fun findsCodesInTheSevenLanguagesOfTheApp() {
        assertEquals("305917", Codes.find("Votre code de vérification Amazon est 305917. Ne le partagez avec personne."))
        assertEquals("305917", Codes.find("Tu código de seguridad es 305917."))
        assertEquals("305917", Codes.find("Ihr Sicherheitscode lautet 305917."))
        assertEquals("305917", Codes.find("Il tuo codice di conferma è 305917."))
        assertEquals("305917", Codes.find("Seu código de confirmação é 305917."))
        assertEquals("305917", Codes.find("Uw bevestigingscode is 305917."))
        assertEquals("305917", Codes.find("305917 est votre code de connexion"))
        assertEquals("1234", Codes.find("Mot de passe à usage unique : 1234"))
        assertEquals("557733", Codes.find("Dein Passwort für die Anmeldung: 557733"))
        assertEquals("557733", Codes.find("Tu clave de acceso es 557733"))
    }

    @Test
    fun ignoresAmountsInOtherCurrencies() {
        assertNull(Codes.find("Your code: pay ₹1500 today"))
        assertNull(Codes.find("Code promo: 2500¥"))
    }

    @Test
    fun aCouponIsNoCode() {
        assertNull(Codes.find("Code promo SOLDES20 valable jusqu'à dimanche"))
        assertNull(Codes.find("Use promo code SUMMER25 at checkout"))
        assertNull(Codes.find("Cupón descuento: VERANO25"))
        assertEquals("482913", Codes.find("Code promo SOLDES20. Votre code de connexion: 482913"))
    }

    // ExtServices: testContainsOtp_length
    @Test
    fun length() {
        assertTrue(has("code 123G5"))
        assertTrue(has("code 1235"))
        assertTrue(has("code 123456F8"))
        assertFalse(has("code 123G"))
        assertFalse(has("code 123"))
        assertFalse(has("code 123T56789"))
        assertTrue(has("code 123 456"))
        assertFalse(has("code 12 345"))
    }

    // ExtServices: testContainsOtp_acceptsNonRomanAlphabeticalChars
    @Test
    fun nonRomanLetters() {
        assertTrue(has("code 123ķ4"))
        assertTrue(has("code 123Ŀ4"))
        assertFalse(has("code 123码456"))
    }

    // ExtServices: testContainsOtp_mustHaveNumber
    @Test
    fun mustHaveANumber() {
        assertFalse(has("code TEFHXES"))
    }

    // ExtServices: testContainsOtp_dateExclusion
    @Test
    fun datesAreNoCodes() {
        assertFalse(has("code 01-01-2001"))
        assertFalse(has("code 1-1-2001"))
        assertFalse(has("code 1-1-01"))
        assertTrue(has("1-1-01 is the date of your code T3425"))
        assertTrue(has("your code 54-234-3 was sent on 1-1-01"))
        assertTrue(has("code 34-58-30"))
        assertTrue(has("code 12-1-3089"))
    }

    // ExtServices: testContainsOtp_phoneExclusion
    @Test
    fun phoneNumbersAreNoCodes() {
        assertFalse(has("code (888) 8888888"))
        assertFalse(has("code 888 888 8888"))
        assertFalse(has("code (888) 888-8888"))
        assertFalse(has("code 888-888-8888"))
        assertFalse(has("code (888)-888-8888"))
    }

    // ExtServices: testContainsOtp_dashes
    @Test
    fun dashes() {
        assertTrue(has("code G-3d523"))
        assertTrue(has("code G-FD-745"))
        assertFalse(has("code 6--7893"))
        assertFalse(has("code ------"))
    }

    // ExtServices: testContainsOtp_startAndEnd
    @Test
    fun startAndEnd() {
        assertFalse(has("your code isG-345821"))
        assertFalse(has("your code is G-345821for real"))
        assertFalse(has("your code is 4 G-345821"))
        assertFalse(has("your code is G-345821 3"))
        assertFalse(has("your code is4:G-345821"))
        assertFalse(has("you code is 'G-345821-'"))
        assertFalse(has("you code is 'G-345821_'"))
        assertFalse(has("your code is G-345821\$"))
        assertTrue(has("your code is:G-345821"))
        assertTrue(has("your code is \nG-345821"))
        assertTrue(has("your code is 'G-345821'"))
        assertTrue(has("your code is \"G-345821\""))
        assertTrue(has("your code is [G-345821"))
        assertTrue(has("your code is码G-345821"))
        assertTrue(has("you code is G-345821."))
        assertTrue(has("you code is (G-345821)"))
        assertTrue(has("you code is [G-345821]"))
        assertTrue(has("your code is码G-345821码"))
    }

    // ExtServices: testContainsOtp_lookaheadMustBeOtpChar
    @Test
    fun lookaheadMustBeACodeCharacter() {
        assertTrue(has("code g4zy75"))
        assertFalse(has("code GVRXY 2"))
    }

    // ExtServices: testContainsOtp_threeDontMatch_withoutLanguageSpecificRegex
    @Test
    fun threeLowercaseLettersAreNoCode() {
        assertFalse(has("code 34agb"))
    }

    // ExtServices: testContainsOtp_englishSpecificRegex
    @Test
    fun contextWordsAndTheirReach() {
        val falsePositive = "This is a false positive 4543"
        assertFalse(has(falsePositive))
        for (word in listOf("login", "log in", "2fa", "authenticate", "auth", "authentication", "tan", "password",
            "passcode", "two factor", "two-factor", "2factor", "2 factor", "pin", "one time", "LOGIN", "logIn", "LoGiN")) {
            assertTrue("$word should be a context word", has("$word $falsePositive"))
        }
        for (notWord in listOf("pins", "gaping", "backspin")) assertFalse(has("$notWord $falsePositive"))
        assertTrue(has("context word: code. This sentence has the actual value of 434343"))
        assertTrue(has("your code is \n 34343"))
        assertFalse(has("context word: code. One sentence. actual value 34343"))
        assertFalse(has("34343 is a number. This number is a code"))
        assertFalse(has("context word: code. ${"f".repeat(60)} This sentence has the actual value of 434343"))
        assertFalse(has("34343 ${"f".repeat(60)} code"))
    }

    // ExtServices: testContainsOtp_multipleFalsePositives
    @Test
    fun severalFalsePositives() {
        val otp = "code 1543 code"
        val longFp = "888-777-6666"
        val shortFp = "34ess"
        val manyLong = "$longFp something something $longFp"
        val manyShort = "$shortFp something something $shortFp"
        assertFalse(has(manyLong))
        assertFalse(has(manyShort))
        assertTrue(has("$otp $manyLong"))
        assertTrue(has("$manyLong $otp"))
        assertTrue(has("$longFp $otp $longFp"))
        assertTrue(has("$otp $manyShort"))
        assertTrue(has("$shortFp $otp $shortFp"))
    }

    @Test
    fun aLongMessageIsReadOnlyToItsStart() {
        // ExtServices: MAX_SENSITIVE_TEXT_LEN, 600 characters.
        assertNull(Codes.find("x".repeat(700) + " code 482913"))
        assertEquals("482913", Codes.find("code 482913 " + "x".repeat(700)))
        assertNull(Codes.find(""))
    }
}

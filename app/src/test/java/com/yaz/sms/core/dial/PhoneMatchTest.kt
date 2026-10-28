package com.yaz.sms.core.dial

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.sms.data.contacts.PhoneIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Two numbers are the same only when Android says so, in the phone's country: the cases of libphonenumber's
 * own PhoneNumberUtilTest.testIsNumberMatchMatches/NonMatches (https://github.com/google/libphonenumber/blob/master/java/libphonenumber/test/com/google/i18n/phonenumbers/PhoneNumberUtilTest.java),
 * run through PhoneNumberUtils.areSamePhoneNumber under Robolectric (https://robolectric.org/).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class PhoneMatchTest {

    private fun entry(id: Long, name: String, number: String) = PhoneEntry(id, name, number, T9.clean(number), null, false)

    @Test fun otherCountrySameTailIsAnotherNumber() {
        assertFalse(T9.sameDigits("+33612345678", "+34612345678", "FR"))
        assertFalse(T9.sameDigits("+34612345678", "+33612345678", "ES"))
    }

    @Test fun nationalAndInternationalAreOneNumber() {
        assertTrue(T9.sameDigits("0612345678", "+33612345678", "FR"))
        assertTrue(T9.sameDigits("+33612345678", "0612345678", "FR"))
        assertTrue(T9.sameDigits("+447700900123", "07700900123", "GB"))
    }

    @Test fun theInternationalPrefixIsRead() {
        assertTrue(T9.sameDigits("0033612345678", "+33612345678", "FR"))
    }

    @Test fun equalDigitsAndShortNumbers() {
        assertTrue(T9.sameDigits("+33612345678", "33612345678", "FR"))
        assertFalse(T9.sameDigits("", "", "FR"))
        assertFalse(T9.sameDigits("12345", "54321", "FR"))
    }

    // Same cases as above (libphonenumber's PhoneNumberUtilTest), with no country given.
    @Test fun withoutACountryInternationalNumbersAreJudgedAndANationalOneKeepsTheTail() {
        assertFalse(T9.sameDigits("+33612345678", "+34612345678"))
        assertTrue(T9.sameDigits("0612345678", "+33612345678"))
    }

    @Test fun garbageIsNotANumber() {
        assertFalse(T9.sameDigits("+999999999999999999999", "0999999999999999999999", "FR"))
        assertFalse(T9.sameDigits("0612345678", "+33612345678", ""))
    }

    @Test fun theIndexNamesTheFrenchContactForTheFrenchNumberOnly() {
        val marie = entry(1, "Marie-Christine", "+33 6 12 34 56 78")
        val index = PhoneIndex(listOf(marie), "FR")
        assertEquals("Marie-Christine", index.find("+33612345678")?.name)
        assertEquals("Marie-Christine", index.find("0612345678")?.name)
        assertNull(index.find("+34612345678"))
    }

    @Test fun twoCountriesSharingATailEachFindTheirOwn() {
        val marie = entry(1, "Marie-Christine", "+33 6 12 34 56 78")
        val carmen = entry(2, "Carmen", "+34 612 345 678")
        for (book in listOf(listOf(marie, carmen), listOf(carmen, marie))) {
            val index = PhoneIndex(book, "FR")
            assertEquals("Marie-Christine", index.find("+33612345678")?.name)
            assertEquals("Carmen", index.find("+34612345678")?.name)
            assertEquals("Marie-Christine", index.find("0612345678")?.name)
            assertTrue(index.matches(carmen, "+34612345678"))
            assertFalse(index.matches(carmen, "+33612345678"))
        }
    }
}

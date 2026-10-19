package com.yaz.sms.core.dial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesCallsTest {

    private fun yes(number: String, country: String?) =
        assertTrue("$number read in $country should be a sales call", SalesCalls.isSalesCall(number, country))

    private fun no(number: String, country: String?) =
        assertFalse("$number read in $country should pass", SalesCalls.isSalesCall(number, country))

    @Test
    fun frenchSalesRangesAreKnownInEveryForm() {
        assertTrue(SalesCalls.isSalesCall("01 62 12 34 56", "fr"))
        assertTrue(SalesCalls.isSalesCall("+33 9 48 12 34 56", "us"))
        assertTrue(SalesCalls.isSalesCall("0033162123456", null))
        assertTrue(SalesCalls.isSalesCall("0947612345", "fr"))
    }

    @Test
    fun otherFrenchNumbersPass() {
        assertFalse(SalesCalls.isSalesCall("0612345678", "fr"))
        assertFalse(SalesCalls.isSalesCall("0161123456", "fr"))
        assertFalse(SalesCalls.isSalesCall("0162", "fr"))
        assertFalse(SalesCalls.isSalesCall("0947412345", "fr"))
    }

    @Test
    fun networkFormsKeepingTheTrunkZeroAreTheSameNumber() {
        assertTrue(SalesCalls.isSalesCall("+330568123456", null))
        assertTrue(SalesCalls.isSalesCall("+33 (0)5 68 12 34 56", "us"))
        assertTrue(SalesCalls.isSalesCall(" +33568123456", null))
        assertTrue(SalesCalls.isSalesCall("00330568123456", null))
        assertTrue(SalesCalls.isSalesCall("0568123456;ext=12", "fr"))
        assertFalse(SalesCalls.isSalesCall("+330668123456", null))
    }

    @Test
    fun spanishSalesNumbersStartWith400() {
        assertTrue(SalesCalls.isSalesCall("+34 400 123 456", null))
        assertTrue(SalesCalls.isSalesCall("400123456", "es"))
        assertFalse(SalesCalls.isSalesCall("600123456", "es"))
    }

    @Test
    fun aNationalNumberIsReadAsItsCountryDialsIt() {
        assertFalse(SalesCalls.isSalesCall("0162123456", "de"))
        assertFalse(SalesCalls.isSalesCall("400123456", "fr"))
        assertFalse(SalesCalls.isSalesCall("+1 400 123 4567", "us"))
    }

    @Test
    fun aBlankNetworkFallsBackToTheSim() {
        assertTrue(SalesCalls.isSalesCall("0948491234", SalesCalls.firstCountry("", "fr", "US")))
        assertTrue(SalesCalls.isSalesCall("0162123456", SalesCalls.firstCountry(null, null, "FR")))
        assertFalse(SalesCalls.isSalesCall("0162123456", SalesCalls.firstCountry("", "", "")))
    }

    @Test
    fun indiaPromotionalSeriesIs140() {
        assertTrue(SalesCalls.isSalesCall("+91 140 123 4567", null))
        assertTrue(SalesCalls.isSalesCall("1401234567", "in"))
        assertFalse(SalesCalls.isSalesCall("9812345678", "in"))
    }

    @Test
    fun everyFrenchRangeIsKnownInNationalAndInternationalForm() {
        val prefixes = listOf(
            "162", "163", "270", "271", "377", "378", "424", "425", "568", "569", "948", "949",
            "9475", "9476", "9477", "9478", "9479"
        )
        for (p in prefixes) {
            val national = "0" + p.padEnd(9, '7')
            yes(national, "fr")
            yes("+33" + national.drop(1), null)
            yes("+33 (0)" + national.drop(1), "gb")
            yes("0033" + national.drop(1), "es")
        }
    }

    @Test
    fun justOutsideEachFrenchRangePasses() {
        for (p in listOf("161", "164", "269", "272", "376", "379", "423", "426", "567", "570", "947", "950")) {
            no("0" + p.padEnd(9, '1'), "fr")
            no("+33" + p.padEnd(9, '1'), null)
        }
        // 0947 then 0 to 4 is mobile-like geographic use, only 9475 to 9479 are overseas ranges.
        for (d in '0'..'4') no("0947${d}12345", "fr")
        // The 937 to 939 ranges are not call-centre ranges (ARCEP).
        for (p in listOf("937", "938", "939")) no("0" + p.padEnd(9, '1'), "fr")
        no("0612345678", "fr")
        no("0712345678", "fr")
        no("+33612345678", null)
    }

    @Test
    fun frenchFormsWithSeparators() {
        yes("01 62 12 34 56", "fr")
        yes("01-62-12-34-56", "fr")
        yes("01.62.12.34.56", "fr")
        yes("(01) 62 12 34 56", "fr")
        yes("+33 1 62 12 34 56", null)
        yes("+33-1-62-12-34-56", null)
        yes("0270123456;ext=1234", "fr")
        yes("+33270123456;ext=9", null)
        yes("+33 2 70 12 34 56", "us")
        yes("0033 2 70 12 34 56", null)
        yes("00 33 2 70 12 34 56", null)
        yes("0270123456", "FR")
    }

    @Test
    fun frenchNumbersTooShortOrTooLongPass() {
        no("016212345", "fr")
        no("01621234567", "fr")
        no("+3316212345", null)
        no("+331621234567", null)
        no("0", "fr")
        no("", "fr")
        no("+33", null)
        no("0033", null)
        no(";ext=1", "fr")
    }

    @Test
    fun frenchDigitsInAnotherCountryPass() {
        no("+44 162 123 4567", null)
        no("+34 162123456", null)
        no("+49 162123456", null)
        no("+1 270 123 4567", null)
        no("+39 0270123456", null)
        no("0270123456", "de")
        no("0270123456", "gb")
        no("0270123456", "us")
        no("0270123456", null)
        no("0270123456", "")
    }

    @Test
    fun spainAllFormsAndNeighbours() {
        yes("400123456", "es")
        yes("+34 400 123 456", "fr")
        yes("+34400123456", null)
        yes("0034400123456", null)
        yes("0034 400 12 34 56", "us")
        yes("+34 400-123-456", null)
        yes("400 123 456;ext=7", "es")
        yes("+34400999999", null)
        yes("400000000", "es")
        no("399123456", "es")
        no("401123456", "es")
        no("500123456", "es")
        no("600123456", "es")
        no("900123456", "es")
        no("912345678", "es")
        no("40012345", "es")
        no("4001234567", "es")
        no("+3440012345", null)
        no("+34 4001234567", null)
        no("400123456", null)
        no("400123456", "pe")
        no("400123456", "in")
        no("+33 400123456", null)
        no("+44 400123456", null)
        no("+351 400123456", null)
    }

    @Test
    fun indiaAllFormsAndNeighbours() {
        yes("1401234567", "in")
        yes("01401234567", "in")
        yes("+91 140 123 4567", null)
        yes("+91 1401234567", "fr")
        yes("+911401234567", null)
        yes("00911401234567", null)
        yes("+91-140-1234567", null)
        yes("+91 (0) 140 1234567", null)
        yes("1409999999", "in")
        yes("1400000000", "in")
        yes("+911401234567;ext=3", null)
        // The 1600 series is for banks and governments: never blocked.
        no("1600123456", "in")
        no("+91 1600 123 456", null)
        no("+91 160 1234567", null)
        no("1391234567", "in")
        no("1411234567", "in")
        no("9812345678", "in")
        no("+91 98123 45678", null)
        no("140123456", "in")
        no("14012345678", "in")
        no("+91140123456", null)
        no("+9114012345678", null)
        no("1401234567", "fr")
        no("1401234567", null)
        no("+34 1401234567", null)
        no("+44 1401234567", null)
        no("+1 140 123 4567", null)
    }

    @Test
    fun chileUnsolicitedAdvertisingIs809() {
        yes("809123456", "cl")
        yes("8091234567", "cl")
        yes("+56 809 123 456", null)
        yes("+56 809 123 4567", "us")
        yes("+56809123456", null)
        yes("0056809123456", null)
        yes("+56-809-123-456", null)
        yes("+56809123456;ext=2", null)
        // 600 is for calls the person asked for, 800 is toll free: neither is a sales range.
        no("600123456", "cl")
        no("6001234567", "cl")
        no("800123456", "cl")
        no("808123456", "cl")
        no("810123456", "cl")
        no("80912345", "cl")
        no("80912345678", "cl")
        no("912345678", "cl")
        no("+56 9 1234 5678", null)
        no("+56 2 2123 4567", null)
        no("809123456", "fr")
        no("809123456", "es")
        no("809123456", null)
        no("+34 809123456", null)
        no("+51 809123456", null)
        no("+1 809 123 4567", null)
    }

    @Test
    fun peruAdvertisingSeriesIs500() {
        yes("500123456", "pe")
        yes("+51 500 123 456", null)
        yes("+51500123456", "us")
        yes("0051500123456", null)
        yes("+51-500-123-456", null)
        yes("500 123 456;ext=11", "pe")
        yes("500999999", "pe")
        yes("500000000", "pe")
        no("499123456", "pe")
        no("501123456", "pe")
        no("900123456", "pe")
        no("+51 912 345 678", null)
        no("+51 1 234 5678", null)
        no("5001234", "pe")
        no("50012345678", "pe")
        no("+5150012345", null)
        no("+51 50012345678", null)
        no("500123456", "fr")
        no("500123456", "es")
        no("500123456", "cl")
        no("500123456", null)
        no("+1 500 123 4567", null)
        no("+44 500123456", null)
        no("+34 500123456", null)
    }

    @Test
    fun countriesWithoutAReservedRangePass() {
        // Germany, UK, USA, Canada, Netherlands, Belgium, Portugal, Italy, Brazil: no range reserved
        // to sales calls in the regulator's text (Italy's 0844 is defined but unused).
        for (c in listOf("de", "gb", "us", "ca", "nl", "be", "pt", "it", "br", "mx", "ar", "co", "ch", "au")) {
            no("0844123456", c)
            no("03031234567", c)
            no("1401234567", c)
            no("400123456", c)
            no("809123456", c)
            no("500123456", c)
            no("0162123456", c)
        }
        no("+49 162 1234567", null)
        no("+44 7700 900123", null)
        no("+1 212 555 0100", null)
        no("+39 0844 123456", null)
        no("+55 0303 1234567", null)
        no("+55 303 1234567", null)
    }

    @Test
    fun countriesListedAreTheOnesWithRanges() {
        assertEquals(setOf("fr", "es", "in", "cl", "pe"), SalesCalls.countries)
    }

    @Test
    fun hostileInputsDoNotThrowAndPass() {
        for (s in listOf("", " ", "+", "abc", "00", "0000", "*#06#", "٠١")) {
            no(s, "fr")
            no(s, null)
        }
        no("1".repeat(200), "fr")
        no("+33" + "1".repeat(200), null)
    }
}

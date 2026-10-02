package com.sms.app.core.dial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesCallsTest {

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
    fun indiaPromotionalSeriesIs140() {
        assertTrue(SalesCalls.isSalesCall("+91 140 123 4567", null))
        assertTrue(SalesCalls.isSalesCall("1401234567", "in"))
        assertFalse(SalesCalls.isSalesCall("9812345678", "in"))
    }
}

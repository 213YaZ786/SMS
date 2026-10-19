package com.yaz.sms.core.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cases of AOSP Messaging's MmsUtils.cleanseMmsSubject (empty, or equal ignoring case to one of its
 * empty_subject_strings, res/values/arrays.xml; https://android.googlesource.com/platform/packages/apps/Messaging/
 * +/refs/heads/main/src/com/android/messaging/sms/MmsUtils.java). AOSP ships no test of it.
 */
class MmsSubjectTest {

    @Test fun carrierFillersAreNoSubject() {
        listOf("no subject", "No Subject", "NO SUBJECT", "nosubject", "NoSubject").forEach { assertNull(it, MmsSubject.cleanse(it)) }
    }

    @Test fun emptyIsNoSubject() {
        assertNull(MmsSubject.cleanse(null))
        assertNull(MmsSubject.cleanse(""))
        assertNull(MmsSubject.cleanse("   "))
        assertNull(MmsSubject.cleanse("\n\t"))
        assertNull(MmsSubject.cleanse("  no subject \n"))
    }

    @Test fun aRealSubjectStays() {
        assertEquals("Lunch on Friday", MmsSubject.cleanse("Lunch on Friday"))
        assertEquals("no subject today", MmsSubject.cleanse("no subject today"))
        assertEquals("Déjeuner ☕", MmsSubject.cleanse(" Déjeuner ☕ "))
    }

    @Test fun aHostileSubjectIsPlainAndShort() {
        assertEquals("a b c", MmsSubject.cleanse("a\u0000b\nc"))
        val cut = MmsSubject.cleanse("x".repeat(100_000))!!
        assertEquals(200, cut.length)
        assertEquals('…', cut.last())
    }
}

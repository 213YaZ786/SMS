package com.yaz.sms.core.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases in the given / when / then form of Signal Android's TranscodingConfigTest (app/src/test/.../video), on
 * the progress state ported from Notification.ProgressStyle (setProgress, getProgress, getProgressMax,
 * setProgressIndeterminate, isProgressIndeterminate)
 * https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/app/Notification.java#ProgressStyle
 */
class MmsProgressStyleTest {

    @Test
    fun givenSmsOrSmallMms_whenIAsk_thenNoNotice() {
        assertFalse(MmsProgressStyle.isWanted(isMms = false, transcoding = true))
        assertFalse(MmsProgressStyle.isWanted(isMms = true, transcoding = false))
        assertTrue(MmsProgressStyle.isWanted(isMms = true, transcoding = true))
    }

    @Test
    fun givenTranscodeFigures_whenIMapThem_thenZeroToNinety() {
        assertEquals(0, MmsProgressStyle().setTranscodeProgress(0).getProgress())
        assertEquals(45, MmsProgressStyle().setTranscodeProgress(50).getProgress())
        assertEquals(90, MmsProgressStyle().setTranscodeProgress(100).getProgress())
        assertEquals(90, MmsProgressStyle().setTranscodeProgress(250).getProgress())
        assertEquals(0, MmsProgressStyle().setTranscodeProgress(-5).getProgress())
    }

    @Test
    fun givenNoFigureYet_whenIStart_thenIndeterminateUntilTheFirstFigure() {
        val style = MmsProgressStyle()
        assertTrue(style.isProgressIndeterminate())
        style.setTranscodeProgress(null)
        assertTrue(style.isProgressIndeterminate())
        style.setTranscodeProgress(10)
        assertFalse(style.isProgressIndeterminate())
        assertEquals(9, style.getProgress())
    }

    @Test
    fun givenThreeTries_whenEachRestartsAtZero_thenTheBarNeverGoesBack() {
        val style = MmsProgressStyle()
        var last = 0
        for (percent in listOf(0, 40, 100, 0, 30, 100, 5, 100)) {
            style.setTranscodeProgress(percent)
            assertTrue(style.getProgress() >= last)
            last = style.getProgress()
        }
        style.setSending()
        assertEquals(90, style.getProgress())
        style.remove(success = true)
        assertEquals(style.getProgressMax(), style.getProgress())
    }

    @Test
    fun givenSuccessOrFailure_whenItEnds_thenTheBarIsRemoved() {
        val sent = MmsProgressStyle().apply { setSending(); remove(success = true) }
        assertTrue(sent.mRemoved)
        val lost = MmsProgressStyle().apply { setTranscodeProgress(20); remove(success = false) }
        assertTrue(lost.mRemoved)
        assertFalse(lost.isProgressIndeterminate())
        lost.setTranscodeProgress(100)
        assertEquals(18, lost.getProgress())
    }
}

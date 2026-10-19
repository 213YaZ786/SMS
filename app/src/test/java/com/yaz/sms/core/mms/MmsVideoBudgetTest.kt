package com.yaz.sms.core.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases in the given / when / then form of Signal Android's TranscodingConfigTest (app/src/test/.../video). */
class MmsVideoBudgetTest {

    @Test
    fun givenShortFilmAndRoomyBudget_whenIPlan_thenIExpectTopOfTheLadder() {
        val plan = MmsVideoBudget.plan(durationMs = 5_000, limitBytes = 1_000_000, width = 1920, height = 1080)!!
        assertEquals(1_000_000, plan.videoBitRate)
        assertEquals(480, plan.shortSide)
        assertEquals(MmsVideoBudget.AUDIO_BIT_RATE, plan.audioBitRate)
    }

    @Test
    fun givenTightBudget_whenIPlan_thenTheEstimateStaysUnderTheLimit() {
        for (limit in longArrayOf(300_000, 600_000, 1_000_000, 1_800_000)) {
            for (seconds in 1..60) {
                val plan = MmsVideoBudget.plan(seconds * 1000L, limit, 1280, 720) ?: continue
                val bytes = (plan.videoBitRate + plan.audioBitRate) / 8.0 * seconds
                assertTrue("limit $limit, $seconds s: $bytes", bytes <= limit)
            }
        }
    }

    @Test
    fun givenLowRate_whenIPlan_thenThePictureGetsSmaller() {
        val rich = MmsVideoBudget.plan(10_000, 1_000_000, 1920, 1080)!!
        val poor = MmsVideoBudget.plan(10_000, 300_000, 1920, 1080)!!
        assertTrue(poor.videoBitRate < rich.videoBitRate)
        assertTrue(poor.shortSide < rich.shortSide)
    }

    @Test
    fun givenSmallFilm_whenIPlan_thenThePictureIsNeverMadeLarger() {
        val plan = MmsVideoBudget.plan(5_000, 1_000_000, 320, 240)!!
        assertTrue(plan.shortSide <= 240)
    }

    @Test
    fun givenTinyFilm_whenIPlan_thenItKeepsItsOwnSize() {
        assertEquals(96, MmsVideoBudget.plan(5_000, 1_000_000, 128, 96)!!.shortSide)
    }

    @Test
    fun givenTooLongFilm_whenIPlan_thenIExpectNothing() {
        assertNull(MmsVideoBudget.plan(10 * 60_000, 300_000, 1280, 720))
    }

    @Test
    fun givenNothingToWorkWith_whenIPlan_thenIExpectNothing() {
        assertNull(MmsVideoBudget.plan(0, 300_000, 1280, 720))
        assertNull(MmsVideoBudget.plan(5_000, 0, 1280, 720))
        assertNull(MmsVideoBudget.plan(5_000, 300_000, 0, 720))
        assertNull(MmsVideoBudget.plan(5_000, 300_000, 1280, 0))
    }

    @Test
    fun givenPortraitFilm_whenIPlan_thenTheShortSideIsTheWidth() {
        val plan = MmsVideoBudget.plan(5_000, 1_000_000, 1080, 1920)!!
        assertEquals(480, plan.shortSide)
    }

    @Test
    // The retry cases below are in the given / when / then form of Signal's TranscodingConfigTest
    // (https://github.com/signalapp/Signal-Android/blob/main/app/src/test/java/org/thoughtcrime/securesms/video/TranscodingConfigTest.kt).
    fun givenEncoderThatOvershoots_whenIRetry_thenRateAndPictureGoDown() {
        // The case seen on the emulator: 20 s, 1280x720, limit 1,046,528 bytes; the encoder gave 278,690 bit/s of
        // picture and about 190 kbit/s of sound, 1,175,956 bytes.
        val first = MmsVideoBudget.plan(20_000, 1_046_528, 1280, 720)!!
        assertEquals(240, first.shortSide)
        val second = MmsVideoBudget.retry(first, 20_000, 1_046_528, 1_175_956, 278_690, 190_000, 720)!!
        assertTrue(second.videoBitRate < first.videoBitRate)
        assertTrue(second.shortSide < first.shortSide)
        // What the encoder delivers per bit asked, applied to the room left under the limit, lands under it.
        val delivered = 278_690.0 / first.videoBitRate
        val bytes = (second.videoBitRate * delivered + 190_000) / 8 * 20
        assertTrue("$bytes", bytes <= 1_046_528)
    }

    @Test
    fun givenNoMeasure_whenIRetry_thenTheOvershootScalesTheRate() {
        val first = MmsVideoBudget.plan(10_000, 1_000_000, 1280, 720)!!
        val second = MmsVideoBudget.retry(first, 10_000, 1_000_000, 1_250_000, 0, 0, 720)!!
        assertTrue(second.videoBitRate <= first.videoBitRate * 0.8 / 1.1 + 1)
    }

    @Test
    fun givenSoundTakingTheWholeBudget_whenIRetry_thenIExpectNothing() {
        val first = MmsVideoBudget.plan(20_000, 1_046_528, 1280, 720)!!
        assertNull(MmsVideoBudget.retry(first, 20_000, 1_046_528, 1_175_956, 278_690, 400_000, 720))
    }

    @Test
    fun givenLowestPicture_whenIRetry_thenItStays() {
        val plan = MmsVideoBudget.Plan(100_000, MmsVideoBudget.AUDIO_BIT_RATE, 144)
        assertEquals(144, MmsVideoBudget.retry(plan, 10_000, 500_000, 600_000, 0, 0, 720)!!.shortSide)
    }
}

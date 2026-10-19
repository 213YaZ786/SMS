package com.yaz.sms.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phrases cut from a sound by the detector's probabilities, on the cases of whisper.cpp's streaming example
 * (a phrase closes after a second of silence, a phrase is never longer than ten seconds) and of Transcribro (three
 * seconds of silence end the dictation). The sound is coded by its value so what a phrase holds can be read.
 */
class VadSegmenterTest {

    private val window = 512
    private val windowMs = 32

    private fun ms(n: Int) = n / windowMs

    /** [n] windows of [value] with the probability [p]. */
    private fun feed(s: VadSegmenter, n: Int, p: Float, value: Float): List<VadSegmenter.Event> =
        s.feed(FloatArray(n * window) { value }, FloatArray(n) { p })

    private fun List<VadSegmenter.Event>.phrases() = filterIsInstance<VadSegmenter.Event.Phrase>()
    private fun List<VadSegmenter.Event>.stops() = filterIsInstance<VadSegmenter.Event.Stop>()

    @Test
    fun nothingSaidEndsAfterTenSecondsOnce() {
        val s = VadSegmenter()
        assertTrue(feed(s, ms(9_000), 0.01f, 0f).isEmpty())
        val events = feed(s, ms(2_000), 0.01f, 0f)
        assertEquals(listOf(VadSegmenter.Reason.NO_SPEECH), events.stops().map { it.reason })
        assertTrue(feed(s, ms(5_000), 0.01f, 0f).isEmpty())
    }

    @Test
    fun aPhraseClosesAfterASecondOfSilenceAndTheDictationEndsAfterThree() {
        val s = VadSegmenter()
        val speech = feed(s, ms(2_000), 0.9f, 1f)
        assertTrue(speech.first() is VadSegmenter.Event.Speech)
        assertTrue(speech.phrases().isEmpty())
        // 1 s of silence: the phrase, with its silence
        val closed = feed(s, ms(1_024), 0.05f, 0f)
        assertEquals(1, closed.phrases().size)
        assertTrue(closed.phrases().single().endsUtterance)
        assertEquals(0, closed.stops().size)
        // 2 s more: 3 s in all
        val ended = feed(s, ms(2_048), 0.05f, 0f)
        assertEquals(listOf(VadSegmenter.Reason.SILENCE), ended.stops().map { it.reason })
        assertTrue(ended.phrases().isEmpty())
    }

    @Test
    fun aPauseShorterThanASecondDoesNotCutThePhrase() {
        val s = VadSegmenter()
        feed(s, ms(1_000), 0.9f, 1f)
        assertTrue(feed(s, ms(800), 0.05f, 0f).phrases().isEmpty())
        assertTrue(feed(s, ms(1_000), 0.9f, 1f).phrases().isEmpty())
        val closed = feed(s, ms(1_024), 0.05f, 0f)
        assertEquals(1, closed.phrases().size)
        // speech, pause, speech and the closing silence are all in one phrase
        assertTrue(closed.phrases().single().pcm.size >= (ms(1_000) * 2 + ms(800)) * window)
    }

    @Test
    fun aClickIsNotAPhrase() {
        val s = VadSegmenter()
        feed(s, 2, 0.9f, 1f) // 64 ms
        val closed = feed(s, ms(1_100), 0.05f, 0f)
        assertTrue(closed.phrases().isEmpty())
        assertTrue(s.flush().isEmpty())
    }

    @Test
    fun theSoundBeforeTheSpeechIsKeptAsItsLeadIn() {
        val s = VadSegmenter()
        feed(s, ms(2_000), 0.01f, 0f)
        feed(s, ms(1_000), 0.9f, 1f)
        val phrase = feed(s, ms(1_024), 0.05f, 0f).phrases().single()
        val lead = phrase.pcm.takeWhile { it == 0f }.size
        // 500 ms of lead-in, as the settings say, then the speech
        assertEquals(500 * 16, lead)
        assertEquals(ms(1_000) * window, phrase.pcm.count { it == 1f })
    }

    @Test
    fun aPhraseIsNeverLongerThanTenSecondsAndTheSpeechGoesOn() {
        val s = VadSegmenter()
        val events = feed(s, ms(25_000), 0.9f, 1f)
        val phrases = events.phrases()
        assertEquals(2, phrases.size)
        assertTrue(phrases.none { it.endsUtterance })
        assertTrue(phrases.all { it.pcm.size <= 10_000 * 16 + window })
        // the rest is held until the speaker is silent
        val rest = feed(s, ms(1_024), 0.05f, 0f).phrases().single()
        assertTrue(rest.endsUtterance)
    }

    @Test
    fun endingTheDictationKeepsTheSpeechHeld() {
        val s = VadSegmenter()
        assertTrue(s.flush().isEmpty())
        feed(s, ms(1_500), 0.9f, 1f)
        val flushed = s.flush().phrases()
        assertEquals(1, flushed.size)
        assertTrue(flushed.single().endsUtterance)
        assertFalse(s.flush().any())
    }

    // Same protocol as the tests above (fed sound coded by value, probabilities by window; the cases of stream.cpp's
    // VAD mode https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/stream/stream.cpp): where a phrase begins
    @Test
    fun aPhraseKnowsWhereItBeginsInTheWholeSoundLeadInIncluded() {
        val s = VadSegmenter()
        feed(s, ms(2_000), 0.01f, 0f)
        feed(s, ms(1_000), 0.9f, 1f)
        val first = feed(s, ms(1_024), 0.05f, 0f).phrases().single()
        // 2 s of silence fed before the speech, 500 ms of it kept as the lead-in
        assertEquals((ms(2_000) * window - 500 * 16).toLong(), first.startSample)
        // the first sample of the phrase is that one: the lead-in is silent, the speech follows it
        assertEquals(500 * 16, first.pcm.takeWhile { it == 0f }.size)
        // a second phrase, after a quiet stretch short of the three seconds: counted from the very first sample fed
        feed(s, ms(1_500), 0.01f, 0f)
        val fedBefore = (ms(2_000) + ms(1_000) + ms(1_024) + ms(1_500)).toLong() * window
        feed(s, ms(1_000), 0.9f, 1f)
        val second = feed(s, ms(1_024), 0.05f, 0f).phrases().single()
        assertEquals(fedBefore - 500 * 16, second.startSample)
    }

    // Same protocol (stream.cpp's length_ms, a phrase never longer than ten seconds): the pieces follow one another
    @Test
    fun aPhraseCutAtTheLongestLengthIsFollowedByOneThatBeginsWhereItEnded() {
        val s = VadSegmenter()
        val phrases = feed(s, ms(25_000), 0.9f, 1f).phrases()
        assertEquals(2, phrases.size)
        // no lead-in on the first: the speech is the first thing fed
        assertEquals(0L, phrases[0].startSample)
        assertEquals(phrases[0].pcm.size.toLong(), phrases[1].startSample)
        val rest = feed(s, ms(1_024), 0.05f, 0f).phrases().single()
        assertEquals(phrases[1].startSample + phrases[1].pcm.size, rest.startSample)
    }

    // Same protocol (Transcribro's silences that end a dictation, as the tests above): the mode that never ends on them
    @Test
    fun neverStopsIgnoresSilenceAndNothingSaid() {
        val s = VadSegmenter(VadSegmenter.Params(neverStops = true))
        // a minute of nothing: no stop
        assertTrue(feed(s, ms(60_000), 0.01f, 0f).isEmpty())
        // speech, then silence: the phrase closes after a second, no stop, not after a minute more either
        feed(s, ms(1_000), 0.9f, 1f)
        val closed = feed(s, ms(1_024), 0.05f, 0f)
        assertEquals(1, closed.phrases().size)
        assertEquals(0, closed.stops().size)
        assertTrue(feed(s, ms(60_000), 0.01f, 0f).isEmpty())
        // and it still hears what comes after
        feed(s, ms(1_000), 0.9f, 1f)
        val again = feed(s, ms(1_024), 0.05f, 0f)
        assertEquals(1, again.phrases().size)
        assertEquals(0, again.stops().size)
    }

    @Test
    fun soundAndProbabilitiesMustMatch() {
        val s = VadSegmenter()
        try {
            s.feed(FloatArray(window * 3), FloatArray(2))
            throw AssertionError("a mismatch was accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}

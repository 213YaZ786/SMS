package com.yaz.sms.data.settings

import com.yaz.sms.data.sms.Box
import com.yaz.sms.data.sms.Message
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the spam file, after SpamBlocker's BayesTable and the labelling of its LocalAI.kt
 * (MIT, https://github.com/aj3423/SpamBlocker, db/BayesTable.kt, ui/setting/quick/LocalAI.kt:
 * a record found by its hash is changed, none is added), with the caps of StarsTest's kind.
 */
class SpamsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun message(id: Long, body: String, box: Box = Box.RECEIVED, date: Long = id * 1000) =
        Message(id, 1, "+33799887766", body, date, box, read = true, delivered = false, subId = 0)

    @Test
    fun theHashIsTheTimeAndTheText() {
        assertEquals("1000_hello".hashCode(), Spams.hash(1000, "hello"))
        assertFalse(Spams.hash(1000, "hello") == Spams.hash(1001, "hello"))
    }

    @Test
    fun aMessageNotThereIsAdded() {
        val samples = Spams.label(emptyList(), 1, "free prize", spam = true)
        assertEquals(listOf(SpamSample(1, true, "free prize")), samples)
    }

    @Test
    fun aMessageOfTheOtherClassIsChangedInPlace() {
        val samples = Spams.label(listOf(SpamSample(1, true, "a b"), SpamSample(2, false, "c d")), 1, "a b", spam = false)
        assertEquals(listOf(SpamSample(1, false, "a b"), SpamSample(2, false, "c d")), samples)
    }

    @Test
    fun aMessageOfTheSameClassChangesNothing() {
        val before = listOf(SpamSample(1, true, "a b"))
        assertEquals(before, Spams.label(before, 1, "a b", spam = true))
    }

    @Test
    fun anEmptyTextIsNotASample() {
        assertEquals(emptyList<SpamSample>(), Spams.label(emptyList(), 1, "  ", spam = true))
    }

    @Test
    fun aLongTextIsCutAndTheCountCappedKeepingTheNewest() {
        val long = Spams.label(emptyList(), 1, "x".repeat(10_000), spam = true)
        assertEquals(Spams.MAX_TEXT, long.single().text.length)
        var samples = emptyList<SpamSample>()
        repeat(Spams.MAX_SAMPLES + 25) { samples = Spams.label(samples, it, "word$it", spam = it % 2 == 0) }
        assertEquals(Spams.MAX_SAMPLES, samples.size)
        assertEquals(25, samples.first().hash)
        assertEquals(Spams.MAX_SAMPLES + 24, samples.last().hash)
    }

    @Test
    fun theSenderIsReportedClearedOrForgotten() {
        var senders = Spams.sender(emptyMap(), "799887766", true)
        assertEquals(mapOf("799887766" to true), senders)
        senders = Spams.sender(senders, "799887766", false)
        assertEquals(mapOf("799887766" to false), senders)
        senders = Spams.sender(senders, "799887766", null)
        assertEquals(emptyMap<String, Boolean>(), senders)
    }

    @Test
    fun aKeyThatIsNoKeyAndTooManySendersLeaveItAsItIs() {
        assertEquals(emptyMap<String, Boolean>(), Spams.sender(emptyMap(), "", true))
        assertEquals(emptyMap<String, Boolean>(), Spams.sender(emptyMap(), "k".repeat(65), true))
        val full = (0 until Spams.MAX_SENDERS).associate { "k$it" to true }
        assertEquals(full, Spams.sender(full, "another", true))
        // A sender already there can still change.
        assertEquals(false, Spams.sender(full, "k1", false)["k1"])
    }

    @Test
    fun onlyReceivedMessagesWithWordsTeach() {
        val got = Spams.received(
            listOf(message(1, "hello"), message(2, "mine", Box.SENT), message(3, "  "), message(4, "free prize"))
        )
        assertEquals(listOf(Spams.hash(1000, "hello") to "hello", Spams.hash(4000, "free prize") to "free prize"), got)
    }

    @Test
    fun aReportTakesTheNewestMessagesOnly() {
        val many = (1L..100L).map { message(it, "text $it") }
        val got = Spams.received(many)
        assertEquals(Spams.MAX_PER_REPORT, got.size)
        assertEquals("text 100", got.last().second)
    }

    @Test
    fun aFileReadBackIsCleanedAndCapped() {
        assertEquals(SpamFile(), Spams.parse("not json", json))
        assertEquals(SpamFile(), Spams.parse("", json))
        val file = json.encodeToString(
            SpamFile.serializer(),
            SpamFile(listOf(SpamSample(1, true, " "), SpamSample(2, true, "x".repeat(5000))), mapOf("" to true, "ok" to false))
        )
        val back = Spams.parse(file, json)
        assertEquals(1, back.samples.size)
        assertEquals(Spams.MAX_TEXT, back.samples.single().text.length)
        assertEquals(mapOf("ok" to false), back.senders)
        assertTrue(Spams.parse("""{"samples":[],"senders":{},"future":1}""", json).samples.isEmpty())
    }

    @Test
    fun trainingTheModelFromTheSamplesCountsThem() {
        val samples = listOf(SpamSample(1, true, "free prize claim"), SpamSample(2, false, "see you tonight"))
        assertEquals(2, Spams.train(samples).totalSamples)
        assertEquals(0, Spams.train(emptyList()).totalSamples)
    }
}

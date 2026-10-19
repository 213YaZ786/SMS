package com.yaz.sms.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words read back from the bridge's lines "t0<TAB>t1<TAB>text", on the listing of whisper.cpp's README
 * "Word-level timestamp (experimental)" (-ml 1): an empty first segment, one word per segment with a leading space, a
 * lone comma https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/README.md
 */
class WhisperWordsTest {

    private fun parse(text: String) = Whisper.parseWords(text.toByteArray(Charsets.UTF_8))

    // Same protocol: the README's own listing (jfk.wav) in milliseconds
    @Test
    fun theReadmeListingGivesItsWordsWithTheirTimes() {
        val words = parse(
            "0\t320\t\n320\t370\t And\n370\t690\t so\n690\t850\t my\n850\t1590\t fellow\n" +
                "1590\t2850\t Americans\n2850\t3300\t ,\n3300\t4140\t ask\n",
        )
        assertEquals(listOf("And", "so", "my", "fellow", "Americans,", "ask"), words.map { it.text })
        assertEquals(320L, words.first().startMs)
        // the comma's segment is joined to the word before it, whose end moves to the comma's
        assertEquals(1590L, words[4].startMs)
        assertEquals(3300L, words[4].endMs)
    }

    // Same protocol: lines that do not read, an end before its start, no sound at all, text that is not Latin
    @Test
    fun linesThatDoNotReadAreSkippedAndAnEndNeverPrecedesItsStart() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("\n\n").isEmpty())
        val words = parse("x\t10\t one\n10\t\t two\n500\t400\t three\n600\t700\t\n800\t900\t مرحبا\n")
        assertEquals(listOf("three", "مرحبا"), words.map { it.text })
        assertEquals(500L, words[0].endMs)
    }

    // Same protocol: a word that holds a tab or a line break in whisper's text reaches here with a space instead
    @Test
    fun theTextKeepsItsInnerSpaces() {
        val words = parse("0\t100\t a b\n")
        assertEquals("a b", words.single().text)
    }
}

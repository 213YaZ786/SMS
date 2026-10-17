package com.yaz.sms.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases follow zulip-flutter's tests of the same rules, one for each: a flag added then
 * removed, a deleted message losing its star, the starred narrow listing what carries the flag
 * (https://github.com/zulip/zulip-flutter/blob/main/test/model/message_test.dart and
 * test/model/narrow_test.dart), in the style of the app's other tests (AtomicWriteTest).
 */
class StarsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a message is starred then unstarred`() {
        val starred = Stars.toggle(emptyMap(), "sms/7", 3L)
        assertEquals(mapOf("sms/7" to 3L), starred)
        assertEquals(emptyMap<String, Long>(), Stars.toggle(starred, "sms/7", 3L))
    }

    @Test
    fun `an sms and a picture message of the same number are two stars`() {
        val both = Stars.toggle(Stars.toggle(emptyMap(), "sms/7", 3L), "mms/7", 3L)
        assertEquals(setOf("sms/7", "mms/7"), both.keys)
    }

    @Test
    fun `a uid that is not one is never starred`() {
        assertEquals(emptyMap<String, Long>(), Stars.toggle(emptyMap(), "", 1L))
        assertEquals(emptyMap<String, Long>(), Stars.toggle(emptyMap(), "sms/", 1L))
        assertEquals(emptyMap<String, Long>(), Stars.toggle(emptyMap(), "sms/1; DROP", 1L))
        assertEquals(emptyMap<String, Long>(), Stars.toggle(emptyMap(), "../../x", 1L))
        assertEquals(emptyMap<String, Long>(), Stars.toggle(emptyMap(), "sms/" + "9".repeat(40), 1L))
    }

    @Test
    fun `the set stops growing at its cap but a star can still come off`() {
        val full = (0 until Stars.MAX).associate { "sms/$it" to 1L }
        assertEquals(full, Stars.toggle(full, "sms/${Stars.MAX}", 1L))
        assertEquals(Stars.MAX - 1, Stars.toggle(full, "sms/0", 1L).size)
    }

    @Test
    fun `a deleted message loses its star`() {
        val stars = mapOf("sms/1" to 1L, "mms/2" to 1L)
        assertEquals(mapOf("mms/2" to 1L), Stars.remove(stars, "sms/1"))
        assertEquals(stars, Stars.remove(stars, "sms/99"))
    }

    @Test
    fun `only the stars of messages still there are kept`() {
        val stars = mapOf("sms/1" to 1L, "sms/2" to 1L, "mms/3" to 2L)
        // sms/1 is gone; sms/2 was deleted and its number reused in another thread; mms/3 is there.
        val answered = mapOf("sms/2" to 9L, "mms/3" to 2L)
        assertEquals(mapOf("mms/3" to 2L), Stars.alive(stars, answered))
    }

    @Test
    fun `nothing answered by the store drops every star`() {
        assertEquals(emptyMap<String, Long>(), Stars.alive(mapOf("sms/1" to 1L), emptyMap()))
    }

    @Test
    fun `the starred of one conversation`() {
        val stars = mapOf("sms/1" to 1L, "sms/2" to 2L, "mms/3" to 1L)
        assertEquals(setOf("sms/1", "mms/3"), Stars.inThread(stars, 1L))
        assertTrue(Stars.inThread(stars, 5L).isEmpty())
    }

    @Test
    fun `a file read back keeps what is valid`() {
        val text = """{"items":{"sms/1":4,"bad":5,"mms/2":-1,"mms/3":6},"later":true}"""
        assertEquals(mapOf("sms/1" to 4L, "mms/3" to 6L), Stars.parse(text, json))
    }

    @Test
    fun `a file cut short, empty or of another shape gives no stars`() {
        assertEquals(emptyMap<String, Long>(), Stars.parse("""{"items":{"sms/1":""", json))
        assertEquals(emptyMap<String, Long>(), Stars.parse("", json))
        assertEquals(emptyMap<String, Long>(), Stars.parse("[1,2]", json))
    }

    @Test
    fun `a huge file is capped`() {
        val items = (0 until Stars.MAX + 50).joinToString(",") { "\"sms/$it\":1" }
        assertEquals(Stars.MAX, Stars.parse("""{"items":{$items}}""", json).size)
    }
}

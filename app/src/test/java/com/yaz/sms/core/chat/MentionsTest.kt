package com.yaz.sms.core.chat

import com.yaz.sms.core.chat.Mentions.Found
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from Signal's tests (AGPL-3.0, https://github.com/signalapp/Signal-Android):
 * app/src/test/java/org/thoughtcrime/securesms/database/MentionUtilTest.kt (vanillaUpdate,
 * nextToEachOtherUpdate, overlapUpdate, overflowingRangeIsIgnored),
 * .../database/MentionUtilTest_updateBodyAndMentionsWithPlaceholders.java (the cases built by text and
 * mention, emoji included); the rest follows ComposeText's findQueryStart and MentionDeleter on the
 * same footing (Signal has no test of its own for them).
 */
class MentionsTest {

    // MentionUtilTest.vanillaUpdate
    @Test
    fun vanillaUpdate() {
        val (body, mentions) = Mentions.replace("T test", listOf(Found(0, 1, "T"))) { "RecipientId::1" }
        assertEquals("RecipientId::1 test", body)
        assertEquals(listOf(Found(0, 14, "RecipientId::1")), mentions)
    }

    // MentionUtilTest.nextToEachOtherUpdate
    @Test
    fun nextToEachOtherUpdate() {
        val found = listOf(Found(0, 3, "ONE"), Found(3, 6, "TWO"))
        val (body, _) = Mentions.replace("ONETWO test", found) { "RecipientId::" + (if (it.name == "ONE") 1 else 2) }
        assertEquals("RecipientId::1RecipientId::2 test", body)
    }

    // MentionUtilTest.overlapUpdate
    @Test
    fun overlapUpdate() {
        val found = listOf(Found(0, 3, "a"), Found(1, 6, "b"))
        val (body, mentions) = Mentions.replace("T test", found) { "RecipientId::" + (if (it.name == "a") 1 else 2) }
        assertEquals("RecipientId::1est", body)
        assertEquals(1, mentions.size)
    }

    // MentionUtilTest.overflowingRangeIsIgnored
    @Test
    fun overflowingRangeIsIgnored() {
        val (body, mentions) = Mentions.replace("hi there", listOf(Found(1, Int.MAX_VALUE, "x"))) { "RecipientId::1" }
        assertEquals("hi there", body)
        assertTrue(mentions.isEmpty())
    }

    // ..._updateBodyAndMentionsWithPlaceholders: no body, no mentions, mentions out of the text
    @Test
    fun emptyAndOutOfRange() {
        assertEquals(null to emptyList<Found>(), Mentions.replace(null, emptyList()) { "￼" })
        assertEquals("no mentions" to emptyList<Found>(), Mentions.replace("no mentions", emptyList()) { "￼" })
        assertEquals("message" to emptyList<Found>(), Mentions.replace("message", listOf(Found(30, 35, "x"))) { "￼" })
    }

    // ..._updateBodyAndMentionsWithPlaceholders: the builder cases, each mention given the placeholder
    @Test
    fun placeholdersForEveryShape() {
        val cases = listOf(
            listOf("@test", " start"),
            listOf("middle ", "@test", " middle"),
            listOf("end end ", "@test"),
            listOf("@foo", " ", "@barbaz"),
            listOf("what what ", "@foo", " ", "@barbaz", " more text"),
            listOf("@no", "@spaces", "@atall"),
            listOf("@test", " start 🤘🤘"),
            listOf("middle 🤡👍🏾 👨🏼‍🤝‍👨🏽 ", "@a", " 👍🏾 middle 👩‍👩‍👦‍👦"),
            listOf("start ", "@emoji 🩳", " middle ", "@emoji 🩳", " end")
        )
        cases.forEach { pieces ->
            val body = StringBuilder()
            val expected = StringBuilder()
            val found = mutableListOf<Found>()
            val want = mutableListOf<Found>()
            pieces.forEach { piece ->
                if (piece.startsWith("@")) {
                    found += Found(body.length, body.length + piece.length, piece.drop(1))
                    want += Found(expected.length, expected.length + 1, "￼".drop(0))
                    body.append(piece)
                    expected.append("￼")
                } else {
                    body.append(piece)
                    expected.append(piece)
                }
            }
            val (text, mentions) = Mentions.replace(body.toString(), found) { "￼" }
            assertEquals(expected.toString(), text)
            assertEquals(want.map { it.start to it.end }, mentions.map { it.start to it.end })
        }
    }

    @Test
    fun queryAfterAnAt() {
        assertEquals(Mentions.Query(3, "an"), Mentions.queryAt("hi @an", 6))
        assertEquals(Mentions.Query(0, ""), Mentions.queryAt("@", 1))
        assertEquals(Mentions.Query(0, "A"), Mentions.queryAt("@Anna", 2))
        // A space ends the word; no @ means no query; the cursor at the start has none.
        assertNull(Mentions.queryAt("hi @Ann Lee", 11))
        assertNull(Mentions.queryAt("hello", 5))
        assertNull(Mentions.queryAt("@Anna", 0))
        assertNull(Mentions.queryAt("", 0))
        // An address is no mention.
        assertNull(Mentions.queryAt("write anna@mail", 15))
        // The cursor out of the text is held to it.
        assertEquals(Mentions.Query(0, "x"), Mentions.queryAt("@x", 99))
    }

    @Test
    fun longQueryIsNoSearch() {
        val long = "@" + "a".repeat(Mentions.MAX_QUERY)
        assertEquals(Mentions.Query(0, "a".repeat(Mentions.MAX_QUERY)), Mentions.queryAt(long, long.length))
        val longer = long + "a"
        assertNull(Mentions.queryAt(longer, longer.length))
    }

    @Test
    fun searchByPrefix() {
        val names = listOf("Zoe", "Anna Lee", "anton", "Bob", "Anna Lee")
        assertEquals(listOf("anton", "Anna Lee").sortedBy { it.lowercase() }, Mentions.search(names, "an"))
        assertEquals(listOf("Anna Lee"), Mentions.search(names, "ANNA L"))
        assertEquals(listOf("Anna Lee", "anton", "Bob", "Zoe"), Mentions.search(names, ""))
        assertEquals(2, Mentions.search(names, "", limit = 2).size)
        assertTrue(Mentions.search(names, "q").isEmpty())
        assertTrue(Mentions.search(emptyList(), "a").isEmpty())
    }

    @Test
    fun insertingAMention() {
        assertEquals("hi @Anna Lee " to 13, Mentions.insert("hi @an", 6, "Anna Lee"))
        assertEquals("@Bob  there" to 5, Mentions.insert("@ there", 1, "Bob"))
        // No query at the cursor: nothing changes.
        assertEquals("hello" to 5, Mentions.insert("hello", 5, "Bob"))
    }

    @Test
    fun findsMembersNamed() {
        val names = listOf("Anna", "Anna Lee")
        val text = "hi @Anna Lee and @anna, bye"
        assertEquals(listOf(Found(3, 12, "Anna Lee"), Found(17, 22, "anna")), Mentions.find(text, names))
        // Another word that begins like a name, an address, a name without @: none.
        assertTrue(Mentions.find("@Annabel", names).isEmpty())
        assertTrue(Mentions.find("mail@Anna", names).isEmpty())
        assertTrue(Mentions.find("Anna Lee", names).isEmpty())
        assertTrue(Mentions.find("@", names).isEmpty())
        assertTrue(Mentions.find("@Anna", emptyList()).isEmpty())
        assertEquals(listOf(Found(0, 5, "Anna")), Mentions.find("@Anna", names))
        // Next to each other, and at the end.
        assertEquals(2, Mentions.find("@Anna @Anna Lee", names).size)
    }

    @Test
    fun namesTheUser() {
        assertTrue(Mentions.mentions("see you, @Margaret Smith!", listOf("Margaret Smith")))
        assertFalse(Mentions.mentions("Margaret Smith is here", listOf("Margaret Smith")))
        assertFalse(Mentions.mentions("@Margaret", listOf("Margaret Smith")))
        assertFalse(Mentions.mentions("@Margaret Smith", emptyList()))
        assertFalse(Mentions.mentions("", listOf("Margaret Smith")))
    }

    @Test
    fun aMentionCutInTwoGoesWhole() {
        val names = listOf("Anna Lee")
        // One letter of the name taken away: the whole name goes, the @ stays, the rest of the text with it.
        assertEquals("hi @ ok" to 4, Mentions.afterEdit("hi @Anna Lee ok", "hi @Anna Le ok", names))
        // From the middle of the name.
        assertEquals("hi @ ok" to 4, Mentions.afterEdit("hi @Anna Lee ok", "hi @Ana Lee ok", names))
        // Taking away the space behind it, the whole mention at once, or the @ alone touches nothing.
        assertNull(Mentions.afterEdit("hi @Anna Lee ok", "hi @Anna Leeok", names))
        assertNull(Mentions.afterEdit("hi @Anna Lee ok", "hi  ok", names))
        assertNull(Mentions.afterEdit("hi @Anna Lee ok", "hi Anna Lee ok", names))
        // Typing is no cut.
        assertNull(Mentions.afterEdit("hi @Anna", "hi @Annab", names))
        assertNull(Mentions.afterEdit("same", "same", names))
    }
}

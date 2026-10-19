package com.yaz.sms.core.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Element X Android's own cases (features/messages/impl/src/test/.../timeline/model/AggregatedReactionTest.kt:
 * "reaction display key is shortened" and "reaction count and isHighlighted are computed correctly"), then the
 * two ways this app hands the reactions over: one entry per person and emoji, or a count per emoji.
 */
class ReactionGroupsTest {

    @Test
    fun `reaction display key is shortened`() {
        val group = ReactionGroups.Group("1234567890123456790", 1, false)
        assertEquals("1234567890123456…", group.displayKey)
    }

    @Test
    fun `an emoji is shown whole`() {
        assertEquals("❤️", ReactionGroups.Group("❤️", 1, false).displayKey)
    }

    @Test
    fun `count and highlight are computed`() {
        val group = ReactionGroups.ofCounts(listOf("1234567890123456790"), mapOf("1234567890123456790" to 3), listOf("1234567890123456790")).single()
        assertEquals(3, group.count)
        assertTrue(group.isHighlighted)
    }

    @Test
    fun `two hearts become one group of two`() {
        val groups = ReactionGroups.ofList(listOf("❤️", "😂", "❤️"), emptyList())
        assertEquals(listOf("❤️" to 2, "😂" to 1), groups.map { it.key to it.count })
        assertFalse(groups.any { it.isHighlighted })
    }

    @Test
    fun `the user's own group is highlighted, the others not`() {
        val groups = ReactionGroups.ofList(listOf("❤️", "❤️", "👍"), listOf("❤️"))
        assertEquals(listOf(true, false), groups.map { it.isHighlighted })
        assertEquals(2, groups.first().count)
    }

    @Test
    fun `groups keep the order each emoji first came in`() {
        val groups = ReactionGroups.ofList(listOf("😂", "❤️", "😂", "👍", "❤️"), emptyList())
        assertEquals(listOf("😂", "❤️", "👍"), groups.map { it.key })
    }

    @Test
    fun `an own reaction missing from the list still counts once`() {
        val groups = ReactionGroups.ofList(emptyList(), listOf("🙏"))
        assertEquals(listOf(ReactionGroups.Group("🙏", 1, true)), groups)
    }

    @Test
    fun `nothing gives no group`() {
        assertTrue(ReactionGroups.ofList(emptyList(), emptyList()).isEmpty())
        assertTrue(ReactionGroups.ofCounts(emptyList(), emptyMap(), emptyList()).isEmpty())
        assertTrue(ReactionGroups.ofList(listOf(""), listOf("")).isEmpty())
    }

    @Test
    fun `a chat count below one or unknown shows one`() {
        val groups = ReactionGroups.ofCounts(listOf("👍", "😮"), mapOf("👍" to 0), emptyList())
        assertEquals(listOf(1, 1), groups.map { it.count })
    }

    @Test
    fun `a chat emoji listed twice is one group`() {
        assertEquals(1, ReactionGroups.ofCounts(listOf("👍", "👍"), mapOf("👍" to 2), emptyList()).size)
    }

    @Test
    fun `a very long count is kept whole`() {
        assertEquals(1234, ReactionGroups.ofCounts(listOf("👍"), mapOf("👍" to 1234), emptyList()).single().count)
    }
}

package com.yaz.sms.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of Element Web's recent.ts (https://github.com/element-hq/element-web, apps/web/src/emojipicker/recent.ts):
 * a use adds one to the total and puts the emoji first, the most used come by a stable sort on the total (recent
 * order between equal totals), the list kept to a limit. Its own test file (recent.test.ts) only covers its account
 * data merge, so these cases follow the code's comments; the pattern of the app's other tests (StarsTest).
 */
class ReactionHistoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun uses(vararg emoji: String) = emoji.fold(emptyList<ReactionUse>()) { acc, e -> ReactionHistory.add(acc, e) }

    @Test
    fun `a first use counts one`() {
        assertEquals(listOf(ReactionUse("😂", 1)), uses("😂"))
    }

    @Test
    fun `a repeated use adds one and goes first`() {
        val list = uses("😂", "👍", "😂")
        assertEquals(listOf(ReactionUse("😂", 2), ReactionUse("👍", 1)), list)
    }

    @Test
    fun `the most used comes first`() {
        val list = uses("👍", "🎉", "🎉", "🎉", "🔥", "🔥")
        assertEquals(listOf("🎉", "🔥", "👍"), ReactionHistory.mostUsed(list))
    }

    @Test
    fun `equal totals keep the recent first`() {
        val list = uses("👍", "🎉", "🔥")
        assertEquals(listOf("🔥", "🎉", "👍"), ReactionHistory.mostUsed(list))
    }

    @Test
    fun `the bar with no history is Unicode's ranking`() {
        // Left to right: the most frequent at the right, next to "+".
        assertEquals(listOf("😮", "👍", "😭", "🙏", "❤️", "😂"), ReactionHistory.bar(emptyList()))
    }

    @Test
    fun `the user's most used take the first slots, the defaults fill the rest in order`() {
        val list = uses("🎉", "🎉", "🔥")
        assertEquals(listOf("😭", "🙏", "❤️", "😂", "🔥", "🎉"), ReactionHistory.bar(list))
    }

    @Test
    fun `a default already used is not shown twice`() {
        val list = uses("🙏", "🙏", "👍")
        assertEquals(listOf("😮", "😭", "❤️", "😂", "👍", "🙏"), ReactionHistory.bar(list))
    }

    @Test
    fun `more than six used keep the six most used`() {
        val list = uses("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "7️⃣")
        val bar = ReactionHistory.bar(list)
        assertEquals(6, bar.size)
        assertEquals("7️⃣", bar.last())
    }

    // Element Web's recent.ts (https://github.com/element-hq/element-web/blob/develop/apps/web/src/emojipicker/recent.ts):
    // add() then get() after each use, the order changing at once.
    @Test
    fun `each use replaces one and reorders the bar at once`() {
        var list = ReactionHistory.add(emptyList(), "😎")
        assertEquals(listOf("👍", "😭", "🙏", "❤️", "😂", "😎"), ReactionHistory.bar(list))
        list = ReactionHistory.add(ReactionHistory.add(list, "👍"), "👍")
        assertEquals(listOf("😭", "🙏", "❤️", "😂", "😎", "👍"), ReactionHistory.bar(list))
    }

    @Test
    fun `the list is capped`() {
        val many = (1..150).fold(emptyList<ReactionUse>()) { acc, i -> ReactionHistory.add(acc, "e$i") }
        assertEquals(ReactionHistory.STORAGE_LIMIT, many.size)
        assertEquals("e150", many.first().emoji)
    }

    @Test
    fun `an empty or huge text is not counted`() {
        assertTrue(ReactionHistory.add(emptyList(), "").isEmpty())
        assertTrue(ReactionHistory.add(emptyList(), "x".repeat(500)).isEmpty())
    }

    @Test
    fun `a file read back drops what is wrong`() {
        val text = """{"items":[{"emoji":"👍","total":3},{"emoji":"","total":2},{"emoji":"🎉","total":0},{"emoji":"👍","total":9}]}"""
        assertEquals(listOf(ReactionUse("👍", 3)), ReactionHistory.parse(text, json))
        assertTrue(ReactionHistory.parse("not json", json).isEmpty())
        assertTrue(ReactionHistory.parse("", json).isEmpty())
    }
}

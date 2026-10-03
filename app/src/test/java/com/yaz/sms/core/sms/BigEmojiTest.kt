package com.yaz.sms.core.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BigEmojiTest {

    @Test
    fun onlyEmojiAreBig() {
        assertEquals(listOf("😂"), BigEmoji.read("😂"))
        assertEquals(listOf("❤️", "🔥"), BigEmoji.read(" ❤️ 🔥 "))
        assertEquals(listOf("👍🏽", "🇫🇷", "👨‍👩‍👧"), BigEmoji.read("👍🏽🇫🇷👨‍👩‍👧"))
        assertEquals(listOf("1️⃣", "⭐", "☀️"), BigEmoji.read("1️⃣⭐☀️"))
    }

    @Test
    fun anythingElseIsNot() {
        assertNull(BigEmoji.read("ok 👍"))
        assertNull(BigEmoji.read("😂😂😂😂"))
        assertNull(BigEmoji.read("→"))
        assertNull(BigEmoji.read("©"))
        assertNull(BigEmoji.read("1"))
        assertNull(BigEmoji.read("   "))
        assertNull(BigEmoji.read(""))
    }

    @Test
    fun namesFollowNoto() {
        assertEquals("1f602", BigEmoji.names("😂").first())
        assertEquals(listOf("2764_fe0f", "2764"), BigEmoji.names("❤️").take(2))
        assertEquals("1f44d_1f3fd", BigEmoji.names("👍🏽").first())
    }
}

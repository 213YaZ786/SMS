package com.yaz.sms.core.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PollsTest {

    @Test
    fun writtenAndReadBack() {
        val text = Polls.write("Where tonight?", listOf("Pizza", " Sushi ", "", "Tacos"))
        assertEquals("📊 Where tonight?\n1️⃣ Pizza\n2️⃣ Sushi\n3️⃣ Tacos", text)
        assertEquals(Polls.Poll("Where tonight?", listOf("Pizza", "Sushi", "Tacos")), Polls.read(text))
    }

    @Test
    fun keycapsWithoutSelectorCountToo() {
        assertEquals(listOf("A", "B"), Polls.read("📊 Q\n1⃣ A\n2⃣ B")?.options)
    }

    @Test
    fun notAPoll() {
        assertNull(Polls.read("📊 Only a question"))
        assertNull(Polls.read("📊 Q\n1️⃣ A"))
        assertNull(Polls.read("📊 Q\n2️⃣ A\n1️⃣ B"))
        assertNull(Polls.read("📊 Q\n1️⃣ A\nhello\n2️⃣ B"))
        assertNull(Polls.read("Q\n1️⃣ A\n2️⃣ B"))
    }

    @Test
    fun votesAreCounted() {
        assertEquals(listOf(2, 0, 1), Polls.tally(mapOf("1️⃣" to 2, "3⃣" to 1, "❤️" to 4), 3))
        assertEquals(-1, Polls.choiceOf("❤️"))
        assertEquals(1, Polls.choiceOf("2️⃣"))
    }
}

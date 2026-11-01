package com.yaz.sms.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A family and a flag at the cut point, on Robolectric for Android's ICU (the pattern of KeyboardEmojiTest and
 * UTS #51's sequences, https://unicode.org/reports/tr51/#Emoji_Sequences). The family (man, woman, girl joined by
 * ZWJ) is 8 chars, the flag of France 4. Cases of Signal's BreakIteratorCompat take(),
 * https://github.com/signalapp/Signal-Android/blob/main/core/util/src/main/java/org/signal/core/util/BreakIteratorCompat.java
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [36])
class GraphemesTest {
    private val family = "👨‍👩‍👧"
    private val flag = "🇫🇷"

    @Test
    fun `a family at the cut goes whole`() {
        assertEquals("ab", ("ab" + family + "cd").takeWhole(5))
        assertEquals("ab" + family, ("ab" + family + "cd").takeWhole(2 + family.length))
    }

    @Test
    fun `a flag at the cut goes whole`() {
        assertEquals("ab", ("ab" + flag + "cd").takeWhole(4))
        assertEquals("ab" + flag, ("ab" + flag + "cd").takeWhole(2 + flag.length))
    }

    @Test
    fun `a short text is kept`() {
        assertEquals("abc", "abc".takeWhole(10))
        assertEquals("", "abc".takeWhole(0))
    }

    @Test
    fun `the end of a text never starts inside an emoji`() {
        assertEquals("cd", ("ab" + family + "cd").takeLastWhole(5))
        assertEquals(flag + "cd", ("ab" + flag + "cd").takeLastWhole(flag.length + 2))
        assertEquals("cd", ("ab" + flag + "cd").takeLastWhole(4))
    }
}

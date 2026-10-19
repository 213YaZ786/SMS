package com.yaz.sms.feature.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The cases of UTS #51's emoji sequences (https://unicode.org/reports/tr51/#Emoji_Sequences: a flag from two regional
 * indicators, a keycap, a skin tone modifier, a ZWJ family) and of UAX #29's grapheme cluster rule that keeps them
 * whole (https://www.unicode.org/reports/tr29/), run on Robolectric for Android's ICU; the pattern of the app's
 * other Robolectric tests (BubbleScreenshotTest).
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [36])
class KeyboardEmojiTest {

    @Test
    fun `a single emoji is the pick`() {
        assertEquals("😂", KeyboardEmoji.firstEmoji("😂"))
        assertEquals("❤️", KeyboardEmoji.firstEmoji("❤️"))
    }

    @Test
    fun `the first of several is the pick`() {
        assertEquals("👍", KeyboardEmoji.firstEmoji("👍😂"))
    }

    @Test
    fun `a sequence stays whole`() {
        assertEquals("🇫🇷", KeyboardEmoji.firstEmoji("🇫🇷"))
        assertEquals("👍🏽", KeyboardEmoji.firstEmoji("👍🏽"))
        assertEquals("👨‍👩‍👧", KeyboardEmoji.firstEmoji("👨‍👩‍👧"))
        assertEquals("1️⃣", KeyboardEmoji.firstEmoji("1️⃣"))
    }

    @Test
    fun `letters, digits and nothing are no pick`() {
        assertNull(KeyboardEmoji.firstEmoji(""))
        assertNull(KeyboardEmoji.firstEmoji("   "))
        assertNull(KeyboardEmoji.firstEmoji("a"))
        assertNull(KeyboardEmoji.firstEmoji("7"))
        assertNull(KeyboardEmoji.firstEmoji("#"))
        assertNull(KeyboardEmoji.firstEmoji("é😂"))
    }

    @Test
    fun `a space before the emoji is skipped`() {
        assertEquals("🙏", KeyboardEmoji.firstEmoji(" 🙏"))
    }

    @Test
    fun `a very long text is read at its start only`() {
        assertEquals("😮", KeyboardEmoji.firstEmoji("😮" + "x".repeat(100_000)))
    }

    @Test
    fun `only the keyboard's packages count, the debug one in a debug build only`() {
        assertTrue(KeyboardEmoji.isKeyboardPackage("com.yaz.keyboard", false))
        assertFalse(KeyboardEmoji.isKeyboardPackage("com.yaz.keyboard.debug", false))
        assertTrue(KeyboardEmoji.isKeyboardPackage("com.yaz.keyboard.debug", true))
        assertTrue(KeyboardEmoji.isKeyboardPackage("com.yaz.keyboard", true))
        assertFalse(KeyboardEmoji.isKeyboardPackage("com.evil.keyboard", true))
        assertFalse(KeyboardEmoji.isKeyboardPackage("com.google.android.inputmethod.latin", true))
        assertFalse(KeyboardEmoji.isKeyboardPackage(null, true))
    }

    @Test
    fun `the current keyboard's package comes from its component name`() {
        assertEquals("com.yaz.keyboard.debug", KeyboardEmoji.packageOf("com.yaz.keyboard.debug/helium314.keyboard.latin.LatinIME"))
        assertNull(KeyboardEmoji.packageOf(null))
        assertNull(KeyboardEmoji.packageOf("no slash"))
    }
}

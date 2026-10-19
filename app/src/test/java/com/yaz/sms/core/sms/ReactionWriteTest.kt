package com.yaz.sms.core.sms

import com.yaz.sms.data.settings.ReactionFormat
import com.yaz.sms.data.sms.Box
import com.yaz.sms.data.sms.Message
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reaction of the user sent as the iPhone's words has to read back as that reaction, here and on the phone it goes to. The
 * protocol is Kotozute's ComposeTapbackTest and AttachmentTapbackTest (AGPL-3.0,
 * https://github.com/wanderwildwood/kotozute/blob/b22787dde63c46f246845d01faac91f93306bad0/data/src/test/java/com/wanderwildwood/kotozute/repository/ComposeTapbackTest.kt
 * and .../AttachmentTapbackTest.kt): each of the six tapbacks and its removal, any other emoji in the generic
 * sentence, a quote inside the message, a picture and a film; here against [Tapbacks]'s parser for every language
 * it knows. The format choice follows Quik's pull request #846 (https://github.com/quik-sms/quik/pull/846,
 * resolveFormat: Automatic mirrors the last reaction received in the thread, Google's when none; Google Messages;
 * iOS), which ships no test of it.
 */
class ReactionWriteTest {

    private val classic = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")
    private val en = Locale.ENGLISH

    private fun locale(code: String): Locale = when {
        '_' in code -> Locale(code.substringBefore('_'), code.substringAfter('_'))
        else -> Locale(code)
    }

    private fun apple(emoji: String, quoted: String, removed: Boolean = false, media: String? = null, locale: Locale = en) =
        Tapbacks.write(emoji, quoted, removed, media, locale)

    @Test
    fun theSixTapbacksInEnglishAreApplesOwnWords() {
        assertEquals("Loved “See you at 6”", apple("❤️", "See you at 6"))
        assertEquals("Liked “See you at 6”", apple("👍", "See you at 6"))
        assertEquals("Disliked “See you at 6”", apple("👎", "See you at 6"))
        assertEquals("Laughed at “See you at 6”", apple("😂", "See you at 6"))
        assertEquals("Emphasized “See you at 6”", apple("‼️", "See you at 6"))
        assertEquals("Questioned “See you at 6”", apple("❓", "See you at 6"))
        assertEquals("Removed a heart from “See you at 6”", apple("❤️", "See you at 6", removed = true))
        assertEquals("Removed a like from “See you at 6”", apple("👍", "See you at 6", removed = true))
        assertEquals("Removed an exclamation from “See you at 6”", apple("‼️", "See you at 6", removed = true))
    }

    @Test
    fun anyOtherEmojiUsesTheGenericSentence() {
        assertEquals("Reacted 😮 to “hello”", apple("😮", "hello"))
        assertEquals("Removed 😮 from “hello”", apple("😮", "hello", removed = true))
        assertEquals("Reacted 🙏 to an image", apple("🙏", "", media = "image"))
        assertEquals("Removed 😢 from a movie", apple("😢", "", removed = true, media = "video"))
    }

    @Test
    fun aPictureAndAFilmQuoteNothing() {
        assertEquals("Loved an image", apple("❤️", "ignored", media = "image"))
        assertEquals("Liked a movie", apple("👍", "ignored", media = "video"))
        assertEquals("Removed a like from a movie", apple("👍", "ignored", removed = true, media = "video"))
        assertEquals("Laughed at an image", apple("😂", "", media = "image"))
    }

    @Test
    fun everyTapbackReadsBackAsItsOwnReaction() {
        classic.forEach { emoji ->
            for (removed in listOf(false, true)) {
                val text = apple(emoji, "see you at 6", removed)
                assertEquals(text, SmsReactions.Parsed(emoji, "see you at 6", removed), SmsReactions.parse(text))
            }
        }
    }

    @Test
    fun aQuoteInsideTheMessageSurvives() {
        val text = apple("👍", "she said “no” twice")
        assertEquals("Liked “she said “no” twice”", text)
        assertEquals("she said “no” twice", SmsReactions.parse(text)!!.quoted)
    }

    @Test
    fun whatWeSendReadsBackAsAReactionToThePicture() {
        classic.forEach { emoji ->
            for (removed in listOf(false, true)) {
                for (media in listOf("image", "video")) {
                    val text = apple(emoji, "", removed, media)
                    assertEquals(text, SmsReactions.Parsed(emoji, "", removed, media), SmsReactions.parse(text))
                }
            }
        }
    }

    /** Every language Apple's wording is known in: what the phone writes in it, it reads back. */
    @Test
    fun everyLanguageReadsBack() {
        val codes = listOf(
            "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "en", "en_AU", "en_GB", "es", "es_419", "fi", "fr", "fr_CA", "gu", "he", "hi", "hr",
            "hu", "id", "it", "ja", "kk", "kn", "ko", "lt", "ml", "mr", "ms", "nl", "no", "or", "pa", "pl", "pt", "pt_PT", "ro", "ru", "sk", "sl",
            "sv", "ta", "te", "th", "tr", "uk", "ur", "vi", "zh_CN", "zh_HK", "zh_TW"
        )
        // Apple's own wording gives two intents one sentence in places (Odia's like and love of a picture; Portuguese's
        // "Gostou de" is the love of Brazil and the like of Portugal): there no parser can tell, and the text is only
        // required to be read as a reaction. The round trip of Kotozute's ComposeTapbackTest
        // (https://github.com/wanderwildwood/kotozute/blob/b22787dde63c46f246845d01faac91f93306bad0/data/src/test/java/com/wanderwildwood/kotozute/repository/ComposeTapbackTest.kt)
        // over every language: a sent text is [code, emoji, removed, media, text].
        val sent = ArrayList<List<Any?>>()
        codes.forEach { code ->
            val where = locale(code)
            (classic + "😮").forEach { emoji ->
                for (removed in listOf(false, true)) {
                    sent += listOf(code, emoji, removed, null, Tapbacks.write(emoji, "À demain, 18h", removed, null, where))
                    for (media in listOf("image", "video")) sent += listOf(code, emoji, removed, media, Tapbacks.write(emoji, "", removed, media, where))
                }
            }
        }
        val intents = sent.groupBy({ it[4] as String }, { "${it[1]}|${it[2]}|${it[3]}" }).mapValues { it.value.toSet() }
        val wrong = ArrayList<String>()
        sent.forEach { s ->
            val text = s[4] as String
            val back = SmsReactions.parse(text)
            val same = back != null && back.emoji == s[1] && back.removed == s[2] && back.media == s[3] &&
                (s[3] != null || back.quoted == "À demain, 18h")
            if (!same && (back == null || intents.getValue(text).size == 1)) wrong += "${s[0]} ${s[1]} ${s[2]} ${s[3]}: $text -> $back"
        }
        assertTrue(wrong.take(400).joinToString("\n") + "\n(${wrong.size} wrong)", wrong.isEmpty())
    }

    @Test
    fun theLanguageIsThePhonesAndEnglishWhenAFormIsMissing() {
        assertEquals("A ajouté un « J’adore » à « À demain ».", apple("❤️", "À demain", locale = Locale.FRANCE))
        assertEquals("a attribué la mention « Adore » à « À demain »", apple("❤️", "À demain", locale = Locale("fr", "CA")))
        assertEquals("„Bis morgen“ ein Herz hinzugefügt", apple("❤️", "Bis morgen", locale = Locale.GERMANY))
        assertEquals("Le encantó “Hasta mañana”", apple("❤️", "Hasta mañana", locale = Locale("es", "MX")))
        assertEquals("Le encanta “Hasta mañana”", apple("❤️", "Hasta mañana", locale = Locale("es", "ES")))
        assertEquals("Emphasised “See you”", apple("‼️", "See you", locale = Locale("en", "AU")))
        // en_AU holds only its own spelling: the rest is English.
        assertEquals("Loved “See you”", apple("❤️", "See you", locale = Locale("en", "AU")))
        assertEquals("Loved “Bye”", apple("❤️", "Bye", locale = Locale("xx", "YY")))
        assertEquals("比心：再见", apple("❤️", "再见", locale = Locale.SIMPLIFIED_CHINESE))
        assertEquals("喜愛「再見」", apple("❤️", "再見", locale = Locale("zh", "HK")))
        assertEquals("Satte et hjerte ved «Ha det»", apple("❤️", "Ha det", locale = Locale("nb", "NO")))
        assertEquals("הוסיף/ה לב ל: ״להתראות״", apple("❤️", "להתראות", locale = Locale("iw", "IL")))
    }

    // Apple's sentences of the languages that quote with gershayim or not at all, or put quote marks round the emoji,
    // as Tapbacks' forms hold them (generated from iOS's IMSharedUtilities); read back as Kotozute's ComposeTapbackTest
    // reads what it sends (https://github.com/wanderwildwood/kotozute, AGPL-3.0).
    @Test
    fun languagesWithoutTheCommonQuoteMarksAreRead() {
        assertEquals(SmsReactions.Parsed("❤️", "再见", false), SmsReactions.parse("比心：再见"))
        assertEquals(SmsReactions.Parsed("👍", "再见", true), SmsReactions.parse("收回了赞：再见"))
        assertEquals(SmsReactions.Parsed("❤️", "להתראות", false), SmsReactions.parse("הוסיף/ה לב ל: ״להתראות״"))
        assertEquals(SmsReactions.Parsed("😮", "À demain", false), SmsReactions.parse("Reagiu com “😮” a “À demain”"))
        // A picture still wins over the open words of Punjabi's like: "<picture> is liked".
        assertEquals(SmsReactions.Parsed("❤️", "", false, "image"), SmsReactions.parse("ਚਿੱਤਰ ਨੂੰ ਬਹੁਤ ਪਸੰਦ ਕੀਤਾ ਗਿਆ"))
        // Ordinary words without a quote mark stay words.
        assertNull(SmsReactions.parse("再见"))
        assertNull(SmsReactions.parse("see you at six, 比心 maybe"))
    }

    @Test
    fun hostileWordsAreOnlyQuoted() {
        // The message's own %@ or $1 is text, never a place to fill again.
        assertEquals("Loved “100%@ \$1 %2\$@”", apple("❤️", "100%@ \$1 %2\$@"))
        val long = "x".repeat(100_000)
        assertTrue(apple("❤️", long).length < 100_100)
        assertEquals("Loved “”", apple("❤️", ""))
    }

    @Test
    fun theComposedTextIsTheStylesAndCutShort() {
        val long = "word ".repeat(100)
        val google = SmsReactions.compose(SmsReactions.Style.GOOGLE, "😂", long, false, locale = en)
        assertEquals(SmsReactions.Style.GOOGLE, SmsReactions.styleOf(google))
        val apple = SmsReactions.compose(SmsReactions.Style.APPLE, "😂", long, false, locale = en)
        assertEquals(SmsReactions.Style.APPLE, SmsReactions.styleOf(apple))
        assertTrue(apple, apple.startsWith("Laughed at “word word"))
        assertTrue(apple, apple.endsWith("…”"))
        // A picture is always in Apple's words, whatever the style.
        assertEquals("Loved an image", SmsReactions.compose(SmsReactions.Style.GOOGLE, "❤️", "", false, "image", en))
    }

    private fun msg(id: Long, body: String, box: Box = Box.RECEIVED) = Message(id, 1, "+33611110001", body, id * 1000, box, true, false, 1)

    private val googleReaction = SmsReactions.write("😂", "See you soon", false)

    @Test
    fun theKindOfAReceivedReactionIsRecognised() {
        assertEquals(SmsReactions.Style.GOOGLE, SmsReactions.styleOf(googleReaction))
        assertEquals(SmsReactions.Style.APPLE, SmsReactions.styleOf("Loved “See you soon”"))
        assertEquals(SmsReactions.Style.APPLE, SmsReactions.styleOf("Liked a movie"))
        assertEquals(null, SmsReactions.styleOf("See you soon"))
        assertEquals(null, SmsReactions.styleOf(""))
    }

    @Test
    fun automaticMirrorsTheLastReactionReceived() {
        val auto = ReactionFormat.AUTOMATIC
        // None yet: Google's.
        assertEquals(SmsReactions.Style.GOOGLE, SmsReactions.styleFor(auto, listOf(msg(1, "Hello"))))
        assertEquals(SmsReactions.Style.GOOGLE, SmsReactions.styleFor(auto, emptyList()))
        // An iPhone reacted last.
        assertEquals(SmsReactions.Style.APPLE, SmsReactions.styleFor(auto, listOf(msg(1, "Hello"), msg(2, "Loved “Hello”"))))
        // Google's, then the iPhone's later: the newest wins, and the other way round.
        assertEquals(SmsReactions.Style.APPLE, SmsReactions.styleFor(auto, listOf(msg(1, googleReaction), msg(2, "Liked a movie"))))
        assertEquals(SmsReactions.Style.GOOGLE, SmsReactions.styleFor(auto, listOf(msg(1, "Loved “Hello”"), msg(2, googleReaction))))
        // The user's own reactions say nothing of what the other phone reads.
        assertEquals(SmsReactions.Style.GOOGLE, SmsReactions.styleFor(auto, listOf(msg(1, "Hello"), msg(2, "Loved “Hello”", Box.SENT))))
    }

    @Test
    fun aChosenFormatIsKept() {
        val list = listOf(msg(1, "Loved “Hello”"))
        assertEquals(SmsReactions.Style.GOOGLE, SmsReactions.styleFor(ReactionFormat.GOOGLE, list))
        assertEquals(SmsReactions.Style.APPLE, SmsReactions.styleFor(ReactionFormat.IPHONE, emptyList()))
    }

    @Test
    fun theOwnReactionSentReadsBackWhateverTheStyle() {
        listOf(SmsReactions.Style.GOOGLE, SmsReactions.Style.APPLE).forEach { style ->
            val text = SmsReactions.compose(style, "😂", "See you soon", false, locale = en)
            val list = listOf(msg(1, "See you soon", Box.RECEIVED), msg(2, text, Box.SENT))
            val folded = SmsReactions.fold(list)
            assertNotNull(folded.mine["sms/1"])
            assertEquals(listOf("sms/1"), folded.messages.map { it.uid })
            assertEquals(mapOf("sms/1" to listOf("😂")), folded.mine)
        }
    }
}

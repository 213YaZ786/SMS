package com.yaz.sms.core.sms

import com.yaz.sms.data.sms.Box
import com.yaz.sms.data.sms.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsReactionsTest {

    private val h = " "
    private val add = "​"
    private val take = "‌"

    @Test
    fun googleInAnyLanguage() {
        assertEquals(SmsReactions.Parsed("😂", "See you soon", false), SmsReactions.parse("${h}Reacted $add😂$add to “${h}See you soon$h”$h"))
        assertEquals(SmsReactions.Parsed("😂", "À demain", false), SmsReactions.parse("${h}A réagi avec $add😂$add à « ${h}À demain$h »$h"))
        assertEquals(SmsReactions.Parsed("👍", "See you soon", true), SmsReactions.parse("${h}Removed $take👍$take from “${h}See you soon$h”$h"))
    }

    @Test
    fun iphoneEnglish() {
        assertEquals(SmsReactions.Parsed("❤️", "See you soon", false), SmsReactions.parse("Loved “See you soon”"))
        assertEquals(SmsReactions.Parsed("😂", "See you soon", false), SmsReactions.parse("Laughed at “See you soon”"))
        assertEquals(SmsReactions.Parsed("❤️", "See you soon", true), SmsReactions.parse("Removed a heart from “See you soon”"))
        assertEquals(SmsReactions.Parsed("👀", "See you soon", false), SmsReactions.parse("Reacted 👀 to “See you soon”"))
    }

    @Test
    fun iphoneOtherLanguages() {
        assertEquals(SmsReactions.Parsed("❤️", "À demain", false), SmsReactions.parse("A ajouté un « J’adore » à « À demain »."))
        assertEquals(SmsReactions.Parsed("😂", "À demain", false), SmsReactions.parse("A réagi avec 😂 à « À demain »"))
        assertEquals(SmsReactions.Parsed("❤️", "Bis morgen", false), SmsReactions.parse("„Bis morgen“ ein Herz hinzugefügt"))
        assertEquals(SmsReactions.Parsed("👀", "また明日", false), SmsReactions.parse("“\u200aまた明日\u200a”に\u200a👀\u200aでリアクションしました"))
        assertEquals(SmsReactions.Parsed("👀", "내일 봐", true), SmsReactions.parse("‘내일 봐’에서 👀 표시 제거함"))
    }

    @Test
    fun plainMessagesStay() {
        assertNull(SmsReactions.parse("See you soon"))
        assertNull(SmsReactions.parse("He said “no” again"))
        assertNull(SmsReactions.parse("Reacted with a sticker to “See you soon”"))
    }

    private fun msg(id: Long, body: String, box: Box) = Message(id, 1, "+33600000001", body, id * 1000, box, true, false, 1)

    @Test
    fun foldedUnderTheirMessage() {
        val list = listOf(
            msg(1, "See you soon", Box.SENT),
            msg(2, "Loved “See you soon”", Box.RECEIVED),
            msg(3, "Loved “Not in this conversation”", Box.RECEIVED),
            msg(4, "${h}Reacted $add😂$add to “${h}See you$h”$h", Box.RECEIVED)
        )
        val folded = SmsReactions.fold(list)
        assertEquals(listOf(1L, 3L, 4L), folded.messages.map { it.id })
        assertEquals(mapOf("sms/1" to listOf("❤️")), folded.reactions)
    }

    @Test
    fun takenBack() {
        val list = listOf(
            msg(1, "See you soon", Box.SENT),
            msg(2, "Liked “See you soon”", Box.RECEIVED),
            msg(3, "Removed a like from “See you soon”", Box.RECEIVED)
        )
        val folded = SmsReactions.fold(list)
        assertEquals(listOf(1L), folded.messages.map { it.id })
        assertEquals(emptyMap<String, List<String>>(), folded.reactions)
    }

    @Test
    fun cutShort() {
        val long = "This is a rather long message that Google cut short when quoting it back"
        val list = listOf(msg(1, long, Box.SENT), msg(2, "${h}Reacted $add❤️$add to “${h}This is a rather long message…$h”$h", Box.RECEIVED))
        assertEquals(mapOf("sms/1" to listOf("❤️")), SmsReactions.fold(list).reactions)
    }
}

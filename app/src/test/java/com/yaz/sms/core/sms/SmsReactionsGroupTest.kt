package com.yaz.sms.core.sms

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.sms.core.mms.MmsPart
import com.yaz.sms.data.sms.Box
import com.yaz.sms.data.sms.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tapbacks of an iPhone in a group text, which is a picture message holding only words. The protocol is
 * Kotozute's AttachmentTapbackTest (AGPL-3.0,
 * https://github.com/wanderwildwood/kotozute/blob/b22787dde63c46f246845d01faac91f93306bad0/data/src/test/java/com/wanderwildwood/kotozute/repository/AttachmentTapbackTest.kt):
 * the tapbacks and their removals on a picture and a video, then ordinary text left alone; and Quik's
 * reparse of SMS and of MMS with a text part alike
 * (https://github.com/quik-sms/quik, data/.../EmojiReactionRepositoryImpl.kt). The Apple words are the ones
 * of [Tapbacks].
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [36])
class SmsReactionsGroupTest {

    private val ana = "+33611110001"
    private val ben = "+33611110002"

    private fun sms(id: Long, body: String, box: Box = Box.RECEIVED, from: String = ana) =
        Message(id, 1, from, body, id * 1000, box, true, false, 1)

    private fun mms(id: Long, body: String, box: Box = Box.RECEIVED, from: String = ana, parts: List<MmsPart> = emptyList()) =
        Message(id, 1, from, body, id * 1000, box, true, false, 1, mms = true, parts = parts)

    private fun photo() = MmsPart(Uri.parse("content://mms/part/9"), "image/jpeg", null)

    @Test
    fun aTapbackInAPictureMessageWithWordsOnlyFoldsUnderItsMessage() {
        val list = listOf(
            sms(1, "See you soon", Box.SENT),
            mms(2, "Loved “See you soon”"),
            mms(3, "Laughed at “See you soon”", from = ben)
        )
        val folded = SmsReactions.fold(list)
        assertEquals(listOf("sms/1"), folded.messages.map { it.uid })
        assertEquals(mapOf("sms/1" to listOf("❤️", "😂")), folded.reactions)
    }

    @Test
    fun theTargetMayBeAPictureMessageToo() {
        val list = listOf(
            mms(1, "Dinner at eight", Box.RECEIVED, ben),
            mms(2, "Liked “Dinner at eight”")
        )
        val folded = SmsReactions.fold(list)
        assertEquals(listOf("mms/1"), folded.messages.map { it.uid })
        assertEquals(mapOf("mms/1" to listOf("👍")), folded.reactions)
    }

    @Test
    fun eachPersonKeepsTheirOwnAndTakesItBack() {
        val list = listOf(
            sms(1, "See you soon", Box.SENT),
            mms(2, "Liked “See you soon”"),
            mms(3, "Liked “See you soon”", from = ben),
            mms(4, "Removed a like from “See you soon”")
        )
        val folded = SmsReactions.fold(list)
        assertEquals(mapOf("sms/1" to listOf("👍")), folded.reactions)
    }

    @Test
    fun theUsersOwnReactionSentAsAGroupTextFoldsAndIsHis() {
        val list = listOf(
            mms(1, "See you soon"),
            mms(2, SmsReactions.write("😂", "See you soon", false), Box.SENT, from = "")
        )
        val folded = SmsReactions.fold(list)
        assertEquals(listOf("mms/1"), folded.messages.map { it.uid })
        assertEquals(mapOf("mms/1" to listOf("😂")), folded.mine)
    }

    @Test
    fun aPictureMessageWithMediaIsAMessageOfItsOwn() {
        val list = listOf(
            sms(1, "See you soon", Box.SENT),
            mms(2, "Loved “See you soon”", parts = listOf(photo()))
        )
        val folded = SmsReactions.fold(list)
        assertEquals(listOf("sms/1", "mms/2"), folded.messages.map { it.uid })
        assertEquals(emptyMap<String, List<String>>(), folded.reactions)
    }

    @Test
    fun aTapbackOnAPictureInAGroupFoldsUnderTheNewestPicture() {
        val list = listOf(
            mms(1, "", parts = listOf(photo())),
            mms(2, "Loved an image", from = ben)
        )
        val folded = SmsReactions.fold(list)
        assertEquals(listOf("mms/1"), folded.messages.map { it.uid })
        assertEquals(mapOf("mms/1" to listOf("❤️")), folded.reactions)
        val taken = SmsReactions.fold(list + mms(3, "Removed a heart from an image", from = ben))
        assertEquals(emptyMap<String, List<String>>(), taken.reactions)
    }

    @Test
    fun aMovieIsNamedAMovie() {
        val video = MmsPart(Uri.parse("content://mms/part/10"), "video/mp4", null)
        val list = listOf(mms(1, "", parts = listOf(video)), mms(2, "Liked a movie"))
        assertEquals(mapOf("mms/1" to listOf("👍")), SmsReactions.fold(list).reactions)
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        assertNull(SmsReactions.parse("I loved an image"))
        assertNull(SmsReactions.parse("Loved an image of the river"))
        assertNull(SmsReactions.parse("Removed an image"))
        val list = listOf(mms(1, "I loved an image"), mms(2, "Loved “Not a message here”"))
        assertEquals(list.map { it.uid }, SmsReactions.fold(list).messages.map { it.uid })
    }
}

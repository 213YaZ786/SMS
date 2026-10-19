package com.yaz.sms.core.mms

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The body of an outgoing picture message, against AOSP Messaging's MmsUtils.makePduBody
 * (https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/sms/MmsUtils.java,
 * Apache-2.0). AOSP ships no test of it; each expected SMIL below is the text of its sSmil* templates with the
 * `%s` filled in as String.format does, and the names are its format strings.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [36])
class MmsBodyTest {

    private val textRegion = "<region id=\"Text\" top=\"0\" left=\"0\" height=\"100%\" width=\"100%\"/>"
    private val imageRegion = "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"100%\" width=\"100%\"/>"
    private val imageRegion80 = "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>"
    private val textRegion20 = "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>"

    private fun smil(layout: String, body: String) = "<smil><head><layout>$layout</layout></head><body>$body</body></smil>"
    private fun imagePar(src: String) = "<par dur=\"5000ms\"><img src=\"$src\" region=\"Image\" /></par>"
    private fun textPar(src: String) = "<par dur=\"5000ms\"><text src=\"$src\" region=\"Text\" /></par>"

    @Test
    fun textOnlyHasTheTextRegion() {
        assertEquals(
            smil("<root-layout/>$textRegion", textPar("text.000000.txt")),
            MmsBody.smil(emptyList(), "text.000000.txt")
        )
    }

    @Test
    fun aPictureAloneTakesTheWholeScreen() {
        val picture = MmsBody.Piece(MmsBody.Kind.IMAGE, "image000000.jpg")
        assertEquals(smil("<root-layout/>$imageRegion", imagePar("image000000.jpg")), MmsBody.smil(listOf(picture), null))
    }

    @Test
    fun aPictureWithWordsLeavesThemTheBottomFifth() {
        val picture = MmsBody.Piece(MmsBody.Kind.IMAGE, "image000000.jpg")
        assertEquals(
            smil("<root-layout/>$imageRegion80$textRegion20", imagePar("image000000.jpg") + textPar("text.000001.txt")),
            MmsBody.smil(listOf(picture), "text.000001.txt")
        )
    }

    @Test
    fun aFilmAndASoundSayHowLongTheyPlay() {
        val film = MmsBody.Piece(MmsBody.Kind.VIDEO, "video000000.mp4", 12_345)
        val sound = MmsBody.Piece(MmsBody.Kind.AUDIO, "recording000001.amr", 3_210)
        assertEquals(
            smil("<root-layout/>$imageRegion", "<par dur=\"12345ms\"><video src=\"video000000.mp4\" dur=\"12345ms\" region=\"Image\" /></par>" +
                "<par dur=\"3210ms\"><audio src=\"recording000001.amr\" dur=\"3210ms\" /></par>"),
            MmsBody.smil(listOf(film, sound), null)
        )
    }

    @Test
    fun soundsAndCardsHaveNoRegionOfTheirOwn() {
        val card = MmsBody.Piece(MmsBody.Kind.VCARD, "contact000000.vcf")
        val sound = MmsBody.Piece(MmsBody.Kind.AUDIO, "recording000001.amr", 3_210)
        val refs = "<par dur=\"5000ms\"><ref src=\"contact000000.vcf\" /></par><par dur=\"3210ms\"><audio src=\"recording000001.amr\" dur=\"3210ms\" /></par>"
        assertEquals(smil("<root-layout/>", refs), MmsBody.smil(listOf(card, sound), null))
        // With words, AOSP uses the text layout (sSmilNonVisualAttachmentsWithText is sSmilTextOnly).
        assertEquals(smil("<root-layout/>$textRegion", refs + textPar("text.000002.txt")), MmsBody.smil(listOf(card, sound), "text.000002.txt"))
    }

    @Test
    fun aFileAloneIsDescribedAsWords() {
        // MmsUtils flags neither visual nor non visual for "other": the text layout, whatever the words.
        val other = MmsBody.Piece(MmsBody.Kind.OTHER, "other000000.dat")
        assertEquals(smil("<root-layout/>$textRegion", "<par dur=\"5000ms\"><ref src=\"other000000.dat\" /></par>"), MmsBody.smil(listOf(other), null))
    }

    @Test
    fun namesAreAospsOwn() {
        assertEquals("image000000.jpg", MmsBody.srcName(MmsBody.Kind.IMAGE, 0, "jpg"))
        assertEquals("image000001.gif", MmsBody.srcName(MmsBody.Kind.IMAGE, 1, "gif"))
        assertEquals("image000002.jpg", MmsBody.srcName(MmsBody.Kind.IMAGE, 2, null))
        assertEquals("image000002.gif", MmsBody.srcName(MmsBody.Kind.IMAGE, 2, null, gif = true))
        assertEquals("video000003.mp4", MmsBody.srcName(MmsBody.Kind.VIDEO, 3, null))
        assertEquals("video000003.3gp", MmsBody.srcName(MmsBody.Kind.VIDEO, 3, "3gp"))
        assertEquals("contact000004.vcf", MmsBody.srcName(MmsBody.Kind.VCARD, 4, "vcf"))
        assertEquals("recording000005.amr", MmsBody.srcName(MmsBody.Kind.AUDIO, 5, null))
        assertEquals("recording000005.m4a", MmsBody.srcName(MmsBody.Kind.AUDIO, 5, "m4a"))
        assertEquals("other000006.dat", MmsBody.srcName(MmsBody.Kind.OTHER, 6, "pdf"))
        assertEquals("text.000007.txt", MmsBody.textName(7))
        assertEquals("image000000", MmsBody.contentId("image000000.jpg"))
        assertEquals("smil", MmsBody.contentId("smil"))
    }

    @Test
    fun namesKeepAsciiDigitsInAnyLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("image000012.jpg", MmsBody.srcName(MmsBody.Kind.IMAGE, 12, "jpg"))
            assertEquals("text.000012.txt", MmsBody.textName(12))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun kindsFollowTheContentType() {
        assertEquals(MmsBody.Kind.IMAGE, MmsBody.kindOf("image/gif"))
        assertEquals(MmsBody.Kind.VIDEO, MmsBody.kindOf("video/3gpp"))
        assertEquals(MmsBody.Kind.VCARD, MmsBody.kindOf("text/x-vCard"))
        assertEquals(MmsBody.Kind.VCARD, MmsBody.kindOf("text/x-vcard"))
        assertEquals(MmsBody.Kind.AUDIO, MmsBody.kindOf("audio/mp4"))
        assertEquals(MmsBody.Kind.AUDIO, MmsBody.kindOf("application/ogg"))
        assertEquals(MmsBody.Kind.OTHER, MmsBody.kindOf("application/pdf"))
    }

    @Test
    fun theBodyIsTheSmilThenTheAttachmentsThenTheWords() {
        val gif = "GIF89a".toByteArray() + ByteArray(40)
        val body = MmsBody.build(
            listOf(
                MmsBody.Item("image/gif", gif, "gif"),
                MmsBody.Item("image/jpeg", ByteArray(10) { 7 }, "jpg"),
                MmsBody.Item("text/x-vcard", "BEGIN:VCARD\r\nEND:VCARD".toByteArray(), "vcf")
            ),
            "See you"
        )!!
        assertEquals(5, body.partsNum)
        val names = (0 until 5).map { String(body.getPart(it).contentLocation) }
        assertEquals(listOf("smil.xml", "image000000.gif", "image000001.jpg", "contact000002.vcf", "text.000003.txt"), names)
        assertEquals("application/smil", String(body.getPart(0).contentType))
        // mmslib's setContentId puts the angle brackets the header needs.
        assertEquals("<smil>", String(body.getPart(0).contentId))
        // A GIF stays a GIF, byte for byte, under its own type.
        assertEquals("image/gif", String(body.getPart(1).contentType))
        assertArrayEquals(gif, body.getPart(1).data)
        assertEquals("<image000001>", String(body.getPart(2).contentId))
        assertNull(body.getPart(2).name)
        assertNull(body.getPart(2).filename)
        // The words are UTF-8 text, last but for the SMIL's place.
        assertEquals("text/plain", String(body.getPart(4).contentType))
        assertEquals(com.yaz.sms.core.mms.pdu.CharacterSets.UTF_8, body.getPart(4).charset)
        assertEquals("See you", String(body.getPart(4).data, Charsets.UTF_8))
        val smil = String(body.getPart(0).data)
        assertTrue(smil.contains("src=\"image000000.gif\""))
        assertTrue(smil.contains("<text src=\"text.000003.txt\" region=\"Text\" />"))
    }

    @Test
    fun wordsAloneAndNothing() {
        val words = MmsBody.build(emptyList(), "Hi")!!
        assertEquals(listOf("smil.xml", "text.000000.txt"), (0 until words.partsNum).map { String(words.getPart(it).contentLocation) })
        assertNull(MmsBody.build(emptyList(), "  "))
        assertNull(MmsBody.build(emptyList(), ""))
    }

    @Test
    fun onlyTheSignatureMakesAGif() {
        assertTrue(MmsBody.isGif("GIF87a....".toByteArray()))
        assertTrue(MmsBody.isGif("GIF89a....".toByteArray()))
        assertFalse(MmsBody.isGif("GIF90a....".toByteArray()))
        assertFalse(MmsBody.isGif("<html>GIF89a".toByteArray()))
        assertFalse(MmsBody.isGif("GIF89a".toByteArray()))
        assertFalse(MmsBody.isGif(ByteArray(0)))
        assertFalse(MmsBody.isGif(byteArrayOf(0x47, 0x49)))
    }
}

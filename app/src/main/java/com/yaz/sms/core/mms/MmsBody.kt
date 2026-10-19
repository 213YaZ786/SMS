package com.yaz.sms.core.mms

import java.util.Locale

/**
 * How the body of an outgoing picture message is laid out, from AOSP Messaging's MmsUtils.makePduBody
 * (https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/sms/MmsUtils.java,
 * Apache-2.0; LineageOS keeps the same file): the parts in the order of the attachments with the text
 * last, each named by what it is and its number, the whole described by a SMIL part put first. A receiver
 * that follows the SMIL (older phones, carriers' gateways) shows the pieces in that order and place; one
 * that does not (an iPhone, Google Messages) reads the parts and their names. Pure, so that it is tried
 * without a phone ([MmsBodyTest]).
 */
internal object MmsBody {

    /** The kinds MmsUtils.makePduBody tells apart, by content type. */
    enum class Kind { IMAGE, VIDEO, VCARD, AUDIO, OTHER }

    /** One attachment as the SMIL describes it: [src] is the part's content location, [durationMs] a video's or a sound's. */
    class Piece(val kind: Kind, val src: String, val durationMs: Int = DEFAULT_DURATION)

    /** MmsUtils.DEFAULT_DURATION: how long a picture, a card or the text stays on the screen of a SMIL player. */
    const val DEFAULT_DURATION = 5000

    fun kindOf(contentType: String): Kind = when {
        ContentType.isImageType(contentType) -> Kind.IMAGE
        ContentType.isVideoType(contentType) -> Kind.VIDEO
        ContentType.isVCardType(contentType) -> Kind.VCARD
        ContentType.isAudioType(contentType) -> Kind.AUDIO
        else -> Kind.OTHER
    }

    /**
     * The name of the attachment number [index] (from 0): MmsUtils.makePduBody's `image%06d.%s`,
     * `video%06d.%s`, `contact%06d.vcf`, `recording%06d.%s` and `other%06d.dat`, the extension of the
     * type when Android knows one, else gif or jpg for a picture, mp4 for a video, amr for a sound.
     * The digits are always ASCII (AOSP formats with the phone's locale, which writes Arabic digits in
     * some of them).
     */
    fun srcName(kind: Kind, index: Int, extension: String?, gif: Boolean = false): String {
        val n = String.format(Locale.ROOT, "%06d", index)
        return when (kind) {
            Kind.IMAGE -> "image$n." + (extension ?: if (gif) "gif" else "jpg")
            Kind.VIDEO -> "video$n." + (extension ?: "mp4")
            Kind.VCARD -> "contact$n.vcf"
            Kind.AUDIO -> "recording$n." + (extension ?: "amr")
            Kind.OTHER -> "other$n.dat"
        }
    }

    /** The text part's name: `text.%06d.txt`, numbered after the attachments. */
    fun textName(index: Int): String = "text." + String.format(Locale.ROOT, "%06d", index) + ".txt"

    /** MmsUtils.setPartContentLocationAndId: the id is the name without its extension. */
    fun contentId(src: String): String = src.substringBeforeLast('.', src)

    private fun par(inner: String, duration: Int = DEFAULT_DURATION) = "<par dur=\"${duration}ms\">$inner</par>"

    /** The `<par>` of one attachment: MmsUtils' sSmilImagePart, sSmilVideoPart, sSmilAudioPart and sSmilPart. */
    private fun par(piece: Piece): String = when (piece.kind) {
        Kind.IMAGE -> par("<img src=\"${piece.src}\" region=\"Image\" />")
        Kind.VIDEO -> par("<video src=\"${piece.src}\" dur=\"${piece.durationMs}ms\" region=\"Image\" />", piece.durationMs)
        Kind.AUDIO -> par("<audio src=\"${piece.src}\" dur=\"${piece.durationMs}ms\" />", piece.durationMs)
        Kind.VCARD, Kind.OTHER -> par("<ref src=\"${piece.src}\" />")
    }

    /**
     * The SMIL of a message: MmsUtils' four layouts, chosen as getSmilTemplate does. With a picture or a
     * film the Image region takes the whole screen, or its top 80% when there are words, which take the
     * bottom 20%; with only sounds and cards no region but the words'; a card or a file alone is described
     * as words only, since MmsUtils sets neither flag for them. [text] is the name of the text part, or
     * null for none.
     */
    fun smil(pieces: List<Piece>, text: String?): String {
        val visual = pieces.any { it.kind == Kind.IMAGE || it.kind == Kind.VIDEO }
        val sound = pieces.any { it.kind == Kind.VCARD || it.kind == Kind.AUDIO }
        val body = buildString {
            pieces.forEach { append(par(it)) }
            if (text != null) append(par("<text src=\"$text\" region=\"Text\" />"))
        }
        val textRegion = "<region id=\"Text\" top=\"0\" left=\"0\" height=\"100%\" width=\"100%\"/>"
        val layout = when {
            visual && text != null ->
                "<root-layout/><region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
                    "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>"
            visual -> "<root-layout/><region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"100%\" width=\"100%\"/>"
            sound && text == null -> "<root-layout/>"
            else -> "<root-layout/>$textRegion"
        }
        return "<smil><head><layout>$layout</layout></head><body>$body</body></smil>"
    }

    /** One attachment ready to go: its content type, its bytes, the extension Android knows for the type, how long it plays. */
    class Item(val type: String, val data: ByteArray, val extension: String?, val durationMs: Int = DEFAULT_DURATION)

    /**
     * The body of the message: [items] in order, each named by [srcName], then the text part ([textName]),
     * then the SMIL part put first (MmsUtils.addSmilPart: content id "smil", location "smil.xml", type
     * application/smil). Null when there is neither an item nor words. Each part carries its name as content
     * location and the name without its extension as content id (MmsUtils.setPartContentLocationAndId); AOSP
     * gives them no Name or Filename.
     */
    fun build(items: List<Item>, text: String): com.yaz.sms.core.mms.pdu.PduBody? {
        if (items.isEmpty() && text.isBlank()) return null
        val body = com.yaz.sms.core.mms.pdu.PduBody()
        val pieces = ArrayList<Piece>()
        items.forEachIndexed { index, item ->
            val kind = kindOf(item.type)
            val src = srcName(kind, index, item.extension, item.type.equals(ContentType.IMAGE_GIF, ignoreCase = true))
            body.addPart(part(item.type, src, item.data, 0))
            pieces += Piece(kind, src, item.durationMs)
        }
        val textSrc = if (text.isNotBlank()) textName(items.size) else null
        if (textSrc != null) {
            body.addPart(part(ContentType.TEXT_PLAIN, textSrc, text.toByteArray(Charsets.UTF_8), com.yaz.sms.core.mms.pdu.CharacterSets.UTF_8))
        }
        body.addPart(0, com.yaz.sms.core.mms.pdu.PduPart().apply {
            setContentId("smil".toByteArray())
            setContentLocation("smil.xml".toByteArray())
            setContentType(ContentType.APP_SMIL.toByteArray())
            setData(smil(pieces, textSrc).toByteArray())
        })
        return body
    }

    private fun part(type: String, location: String, data: ByteArray, charset: Int) = com.yaz.sms.core.mms.pdu.PduPart().apply {
        setContentType(type.toByteArray())
        setContentLocation(location.toByteArray())
        setContentId(contentId(location).toByteArray())
        if (charset != 0) setCharset(charset)
        setData(data)
    }

    /** Whether [data] opens as a GIF (GIF87a or GIF89a, the signature of the format). */
    fun isGif(data: ByteArray): Boolean =
        data.size > 6 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() &&
            data[3] == '8'.code.toByte() && (data[4] == '7'.code.toByte() || data[4] == '9'.code.toByte()) && data[5] == 'a'.code.toByte()
}

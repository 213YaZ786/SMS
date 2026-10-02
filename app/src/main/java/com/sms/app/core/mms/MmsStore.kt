package com.sms.app.core.mms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.TelephonyManager
import com.sms.app.core.dial.T9
import com.sms.app.core.mms.pdu.CharacterSets
import com.sms.app.core.mms.pdu.EncodedStringValue
import com.sms.app.core.mms.pdu.PduBody
import com.sms.app.core.mms.pdu.PduHeaders
import com.sms.app.core.mms.pdu.RetrieveConf

/** One part of a picture message: its text, or a picture, video or sound kept in Android's store. */
data class MmsPart(val uri: Uri, val contentType: String, val name: String?)

/**
 * Picture messages in Android's own store, which only the messaging app
 * writes: the message, its parts (text, pictures, videos, sounds) and who
 * it is from and to.
 */
object MmsStore {

    /** A message downloaded from the network, kept in the inbox. Its thread, or null. */
    fun saveReceived(context: Context, conf: RetrieveConf, subId: Int): Long? = runCatching {
        val from = conf.from?.string.orEmpty()
        val to = conf.to.orEmpty().map { it.string }
        val cc = conf.cc.orEmpty().map { it.string }
        val self = selfNumbers(context)
        val others = (listOf(from) + to + cc)
            .filter { it.isNotBlank() && self.none { me -> T9.sameDigits(T9.clean(me), T9.clean(it)) } }
            .distinct()
        val thread = Telephony.Threads.getOrCreateThreadId(context, others.toSet().ifEmpty { setOf(from) })
        val now = System.currentTimeMillis() / 1000
        val uri = context.contentResolver.insert(
            Telephony.Mms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.DATE, now)
                put(Telephony.Mms.DATE_SENT, conf.date.takeIf { it > 0 } ?: now)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
                put(Telephony.Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
                put(Telephony.Mms.MMS_VERSION, conf.mmsVersion)
                conf.messageId?.let { put(Telephony.Mms.MESSAGE_ID, String(it)) }
                conf.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, String(it)) }
                conf.contentType?.let { put(Telephony.Mms.CONTENT_TYPE, String(it)) }
                conf.subject?.let { put(Telephony.Mms.SUBJECT, it.string) }
                put(Telephony.Mms.SUBSCRIPTION_ID, subId)
            }
        ) ?: return null
        val id = ContentUris.parseId(uri)
        writeParts(context, id, conf.body)
        writeAddress(context, id, from, PduHeaders.FROM)
        to.forEach { writeAddress(context, id, it, PduHeaders.TO) }
        cc.forEach { writeAddress(context, id, it, PduHeaders.CC) }
        thread
    }.getOrNull()

    /** A message about to go out, kept in the outbox until the network answers. */
    fun saveOutgoing(context: Context, recipients: List<String>, body: PduBody, subId: Int): Uri? = runCatching {
        val thread = Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
        val uri = context.contentResolver.insert(
            Telephony.Mms.Outbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_SEND_REQ)
                put(Telephony.Mms.MMS_VERSION, PduHeaders.CURRENT_MMS_VERSION)
                put(Telephony.Mms.CONTENT_TYPE, ContentType.MMS_MULTIPART_MIXED)
                put(Telephony.Mms.SUBSCRIPTION_ID, subId)
            }
        ) ?: return null
        val id = ContentUris.parseId(uri)
        writeParts(context, id, body)
        writeAddress(context, id, "insert-address-token", PduHeaders.FROM)
        recipients.forEach { writeAddress(context, id, it, PduHeaders.TO) }
        uri
    }.getOrNull()

    fun setBox(context: Context, uri: Uri, box: Int) {
        runCatching { context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, box) }, null, null) }
    }

    /** The text of a message and its other parts, the presentation (SMIL) left out. */
    fun parts(context: Context, id: Long): Pair<String, List<MmsPart>> {
        val text = StringBuilder()
        val media = ArrayList<MmsPart>()
        runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT, Telephony.Mms.Part.NAME, Telephony.Mms.Part.FILENAME),
                "${Telephony.Mms.Part.MSG_ID} = ?", arrayOf(id.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(1).orEmpty().lowercase()
                    when {
                        type == ContentType.TEXT_PLAIN -> {
                            val t = c.getString(2).orEmpty()
                            if (t.isNotBlank()) text.append(if (text.isEmpty()) t else "\n$t")
                        }
                        type == ContentType.APP_SMIL || type.startsWith("application/smil") -> Unit
                        else -> media += MmsPart(Uri.parse("content://mms/part/${c.getLong(0)}"), type, c.getString(3) ?: c.getString(4))
                    }
                }
            }
        }
        return text.toString() to media
    }

    /** Who sent a received message. */
    fun sender(context: Context, id: Long): String? = runCatching {
        context.contentResolver.query(
            Uri.parse("content://mms/$id/addr"),
            arrayOf(Telephony.Mms.Addr.ADDRESS),
            "${Telephony.Mms.Addr.TYPE} = ?", arrayOf(PduHeaders.FROM.toString()), null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun writeParts(context: Context, id: Long, body: PduBody?) {
        body ?: return
        val partsUri = Uri.parse("content://mms/$id/part")
        for (i in 0 until body.partsNum) {
            val part = body.getPart(i)
            val type = part.contentType?.let { String(it) }?.lowercase() ?: continue
            val values = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, id)
                put(Telephony.Mms.Part.CONTENT_TYPE, type)
                if (part.charset != 0) put(Telephony.Mms.Part.CHARSET, part.charset)
                part.name?.let { put(Telephony.Mms.Part.NAME, String(it)) }
                part.filename?.let { put(Telephony.Mms.Part.FILENAME, String(it)) }
                part.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, String(it)) }
                part.contentLocation?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, String(it)) }
                if (type == ContentType.TEXT_PLAIN || type == ContentType.APP_SMIL) {
                    val charset = if (part.charset != 0) part.charset else CharacterSets.UTF_8
                    put(Telephony.Mms.Part.TEXT, EncodedStringValue(charset, part.data ?: ByteArray(0)).string)
                }
            }
            val partUri = context.contentResolver.insert(partsUri, values) ?: continue
            if (type != ContentType.TEXT_PLAIN && type != ContentType.APP_SMIL) {
                val data = part.data ?: continue
                context.contentResolver.openOutputStream(partUri)?.use { it.write(data) }
            }
        }
    }

    private fun writeAddress(context: Context, id: Long, address: String, type: Int) {
        if (address.isBlank()) return
        context.contentResolver.insert(
            Uri.parse("content://mms/$id/addr"),
            ContentValues().apply {
                put(Telephony.Mms.Addr.ADDRESS, address)
                put(Telephony.Mms.Addr.TYPE, type)
                put(Telephony.Mms.Addr.CHARSET, CharacterSets.UTF_8)
                put(Telephony.Mms.Addr.MSG_ID, id)
            }
        )
    }

    /** This phone's own numbers, when Android tells them, to leave them out of a group. */
    @Suppress("DEPRECATION")
    private fun selfNumbers(context: Context): List<String> = runCatching {
        listOfNotNull(context.getSystemService(TelephonyManager::class.java)?.line1Number?.takeIf { it.isNotBlank() })
    }.getOrDefault(emptyList())
}

/** What a part is, in a word, where its picture cannot be shown. */
fun mediaWord(contentType: String?): String = when {
    contentType == null -> "Message"
    ContentType.isImageType(contentType) -> "Photo"
    ContentType.isVideoType(contentType) -> "Video"
    ContentType.isAudioType(contentType) -> "Audio"
    contentType.contains("vcard") -> "Contact card"
    else -> "Attachment"
}

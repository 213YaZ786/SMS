package com.yaz.sms.core.mms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.yaz.sms.core.mms.pdu.CharacterSets
import com.yaz.sms.core.mms.pdu.EncodedStringValue
import com.yaz.sms.core.mms.pdu.PduBody
import com.yaz.sms.core.mms.pdu.PduHeaders
import com.yaz.sms.core.mms.pdu.PduParser
import com.yaz.sms.core.mms.pdu.RetrieveConf
import com.yaz.sms.core.mms.pdu.SendConf

/** One part of a picture message: its text, or a picture, video or sound kept in Android's store. */
data class MmsPart(
    val uri: Uri,
    val contentType: String,
    val name: String?,
    /** The message it came from, set on a received message's parts so that saving it can say who sent it (SavedMedia). */
    val origin: SavedOrigin? = null
)

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
        // Its people without oneself, as AOSP Messaging's PduPersister (see OwnNumbers).
        val others = OwnNumbers.peopleOf(from, to + cc, OwnNumbers.of(context), OwnNumbers.knownFor(context, subId))
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

    /**
     * The network's announcement of a picture message, kept in the inbox
     * to download now or by hand, as AOSP Mms persists a NotificationInd
     * (PduPersister.persist): its thread and row id.
     */
    fun saveNotification(context: Context, ind: com.yaz.sms.core.mms.pdu.NotificationInd, subId: Int): Pair<Long, Long>? = runCatching {
        val from = ind.from?.string.orEmpty()
        val location = ind.contentLocation?.let { String(it) } ?: return null
        // Announced twice by the network: the same message is kept once.
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID),
            "${Telephony.Mms.MESSAGE_TYPE} = ${PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND} AND ${Telephony.Mms.CONTENT_LOCATION} = ?",
            arrayOf(location), null
        )?.use { c -> if (c.moveToFirst()) return c.getLong(1) to c.getLong(0) }
        val thread = Telephony.Threads.getOrCreateThreadId(context, setOf(from.ifBlank { "unknown" }))
        val uri = context.contentResolver.insert(
            Telephony.Mms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
                put(Telephony.Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
                put(Telephony.Mms.MMS_VERSION, PduHeaders.CURRENT_MMS_VERSION)
                put(Telephony.Mms.CONTENT_LOCATION, location)
                ind.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, String(it)) }
                if (ind.expiry > 0) put(Telephony.Mms.EXPIRY, ind.expiry)
                if (ind.messageSize > 0) put(Telephony.Mms.MESSAGE_SIZE, ind.messageSize)
                put(Telephony.Mms.SUBSCRIPTION_ID, subId)
            }
        ) ?: return null
        val id = ContentUris.parseId(uri)
        if (from.isNotBlank()) writeAddress(context, id, from, PduHeaders.FROM)
        thread to id
    }.getOrNull()

    /** Where a kept announcement's message waits: its location, SIM and transaction. */
    fun notification(context: Context, id: Long): Triple<String, Int, ByteArray?>? = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id),
            arrayOf(Telephony.Mms.CONTENT_LOCATION, Telephony.Mms.SUBSCRIPTION_ID, Telephony.Mms.TRANSACTION_ID),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            Triple(c.getString(0) ?: return null, c.getInt(1), c.getString(2)?.toByteArray())
        }
    }.getOrNull()

    /** Who a sent picture message went to, from its address rows. */
    fun recipients(context: Context, id: Long): List<String> = runCatching {
        val out = ArrayList<String>()
        context.contentResolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getInt(1)
                val address = c.getString(0).orEmpty()
                if ((type == PduHeaders.TO || type == PduHeaders.CC) && address.isNotBlank() && address != "insert-address-token") out += address
            }
        }
        out.distinct()
    }.getOrDefault(emptyList())

    fun delete(context: Context, id: Long) {
        runCatching { context.contentResolver.delete(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id), null, null) }
    }

    /** The SIM a message rode on. */
    fun subOf(context: Context, id: Long): Int = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id),
            arrayOf(Telephony.Mms.SUBSCRIPTION_ID), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
    }.getOrNull() ?: android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()

    /** A message about to go out, kept in the outbox until the network answers. */
    fun saveOutgoing(context: Context, recipients: List<String>, body: PduBody, subId: Int, inThread: Long? = null, deliveryReport: Boolean = false): Uri? = runCatching {
        val thread = inThread ?: Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
        val uri = context.contentResolver.insert(
            Telephony.Mms.Outbox.CONTENT_URI,
            ContentValues().apply {
                // The report asked for stays in the row, as AOSP Messaging's PduPersister persists the Send.req's
                // X-Mms-Delivery-Report and X-Mms-Read-Report (OCTET_COLUMN_NAME_MAP: d_rpt, rr); a report that comes
                // back is taken only for a message that asked for one (MmsDelivery.received).
                if (deliveryReport) {
                    put(Telephony.Mms.DELIVERY_REPORT, PduHeaders.VALUE_YES)
                    put(Telephony.Mms.READ_REPORT, PduHeaders.VALUE_NO)
                }
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

    /**
     * What the network answered to a sent picture message (its M-Send.conf, in the sent result's EXTRA_MMS_DATA):
     * the response status and the Message-ID the delivery report will name, kept in the row. AOSP Messaging
     * ProcessSentMessageAction (EXTRA_MMS_DATA, MmsSender.parseSendConf: the response parsed, anything but a
     * SendConf dropped) and MmsUtils.updateSentMmsMessageStatus (RESPONSE_STATUS and MESSAGE_ID written, the
     * Message-ID as PduPersister.toIsoString) in https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/.
     * A response that is not small, not a SendConf or without a Message-ID changes nothing.
     */
    fun saveSendConf(context: Context, uri: Uri, response: ByteArray?) {
        if (response == null || response.isEmpty() || response.size > 16 * 1024) return
        runCatching {
            val conf = PduParser(response, true).parse() as? SendConf ?: return
            val values = ContentValues(2)
            values.put(Telephony.Mms.RESPONSE_STATUS, conf.responseStatus)
            conf.messageId?.takeIf { it.isNotEmpty() && it.size <= 256 }?.let { values.put(Telephony.Mms.MESSAGE_ID, isoString(it)) }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    /** A Message-ID as text, as AOSP Messaging's PduPersister.toIsoString (ISO-8859-1). */
    fun isoString(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    /** A sent picture message, as a delivery report needs to know it. */
    class SentMms(val id: Long, val thread: Long, val box: Int, val reportAsked: Boolean, val sub: Int)

    /**
     * The one sent message with this Message-ID: AOSP Mms PushReceiver.findThreadId selects the Send.req rows
     * (m_type 128) of the m_id and goes on only when there is exactly one. The Message-ID is bound, never written
     * into the query.
     */
    fun sentWithId(context: Context, messageId: String): SentMms? = runCatching {
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.DELIVERY_REPORT, Telephony.Mms.SUBSCRIPTION_ID),
            "${Telephony.Mms.MESSAGE_ID} = ? AND ${Telephony.Mms.MESSAGE_TYPE} = ${PduHeaders.MESSAGE_TYPE_SEND_REQ}",
            arrayOf(messageId), null
        )?.use { c ->
            if (c.count != 1 || !c.moveToFirst()) return null
            SentMms(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3) == PduHeaders.VALUE_YES, c.getInt(4))
        }
    }.getOrNull()

    /** One kept delivery report: its row and the status it holds. */
    class Report(val row: Long, val status: Int)

    /**
     * The delivery reports kept for these Message-IDs (the pdu rows of m_type 134, TelephonyProvider MmsProvider
     * MMS_REPORT_STATUS joins them to the sent message by m_id), in one query per few hundred.
     */
    fun reports(context: Context, messageIds: Collection<String>): Map<String, List<Report>> {
        val out = HashMap<String, ArrayList<Report>>()
        messageIds.distinct().chunked(400).forEach { chunk ->
            runCatching {
                context.contentResolver.query(
                    Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.MESSAGE_ID, Telephony.Mms.STATUS),
                    "${Telephony.Mms.MESSAGE_TYPE} = ${PduHeaders.MESSAGE_TYPE_DELIVERY_IND} AND ${Telephony.Mms.MESSAGE_ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.toTypedArray(), null
                )?.use { c ->
                    while (c.moveToNext()) out.getOrPut(c.getString(1).orEmpty()) { ArrayList() } += Report(c.getLong(0), c.getInt(2))
                }
            }
        }
        return out
    }

    /** Who a kept report is about: the TO address of its row (the MMS_REPORT_STATUS join: addr of type 151). */
    fun reportRecipient(context: Context, row: Long): String? = runCatching {
        context.contentResolver.query(
            Uri.parse("content://mms/$row/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS),
            "${Telephony.Mms.Addr.TYPE} = ?", arrayOf(PduHeaders.TO.toString()), null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * Keeps a delivery report: AOSP Mms PushReceiver persists the DeliveryInd in the inbox and gives it the sent
     * message's thread. Read and seen, so that it counts as no unread message; the provider's thread triggers
     * (PDU_UPDATE_THREAD_CONSTRAINTS) take only m_type 128, 130 and 132, so the thread's date, snippet and
     * count stay. The recipient is the sent message's own address for them, which [reportRecipient] gives back.
     */
    fun saveReport(context: Context, sent: SentMms, messageId: String, recipient: String, status: Int, date: Long): Boolean = runCatching {
        val now = System.currentTimeMillis() / 1000
        val uri = context.contentResolver.insert(
            Telephony.Mms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, sent.thread)
                put(Telephony.Mms.DATE, now)
                put(Telephony.Mms.DATE_SENT, date.takeIf { it > 0 } ?: now)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_DELIVERY_IND)
                put(Telephony.Mms.MMS_VERSION, PduHeaders.CURRENT_MMS_VERSION)
                put(Telephony.Mms.MESSAGE_ID, messageId)
                put(Telephony.Mms.STATUS, status)
                put(Telephony.Mms.SUBSCRIPTION_ID, sent.sub)
            }
        ) ?: return false
        writeAddress(context, ContentUris.parseId(uri), recipient, PduHeaders.TO)
        true
    }.getOrDefault(false)

    /** A later report about the same recipient replaces the status of the kept one. */
    fun updateReport(context: Context, row: Long, status: Int): Boolean = runCatching {
        context.contentResolver.update(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, row),
            ContentValues().apply { put(Telephony.Mms.STATUS, status) }, null, null
        ) > 0
    }.getOrDefault(false)

    /**
     * A picture or file that came over the rich chat, kept in the inbox: its
     * thread and id. In a group, [others] are the rest of its members, so it
     * joins the group's conversation; then a text alone comes this way too.
     */
    fun saveReceivedParts(context: Context, phone: String, text: String, mime: String?, data: java.io.File?, others: List<String> = emptyList()): Pair<Long, Long>? = runCatching {
        val thread = Telephony.Threads.getOrCreateThreadId(context, (listOf(phone) + others).toSet())
        val uri = context.contentResolver.insert(
            Telephony.Mms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
                put(Telephony.Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
                put(Telephony.Mms.CONTENT_TYPE, ContentType.MMS_MULTIPART_MIXED)
            }
        ) ?: return null
        val id = ContentUris.parseId(uri)
        writeParts(context, id, bodyOf(text, mime, data))
        writeAddress(context, id, phone, PduHeaders.FROM)
        others.forEach { writeAddress(context, id, it, PduHeaders.TO) }
        thread to id
    }.getOrNull()

    /** A message going out over the rich chat to one number or a group, kept in the outbox: its id. */
    fun saveSendingParts(context: Context, phones: List<String>, text: String, mime: String?, data: java.io.File?): Long? =
        saveOutgoing(context, phones, bodyOf(text, mime, data), -1)?.let { ContentUris.parseId(it) }

    /** The text and a file, the file read as a stream when written, never whole in memory. */
    private fun bodyOf(text: String, mime: String?, data: java.io.File?) = PduBody().apply {
        if (text.isNotBlank()) addPart(com.yaz.sms.core.mms.pdu.PduPart().apply {
            setContentType(ContentType.TEXT_PLAIN.toByteArray())
            setCharset(CharacterSets.UTF_8)
            setContentLocation("text0.txt".toByteArray())
            setData(text.toByteArray(Charsets.UTF_8))
        })
        if (mime != null && data != null) addPart(com.yaz.sms.core.mms.pdu.PduPart().apply {
            setContentType(mime.toByteArray())
            setContentLocation("file0".toByteArray())
            setDataUri(Uri.fromFile(data))
        })
    }

    fun setBox(context: Context, uri: Uri, box: Int) {
        runCatching { context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, box) }, null, null) }
    }

    /** The text of a message and its other parts, the presentation (SMIL) left out. */
    fun parts(context: Context, id: Long): Pair<String, List<MmsPart>> = parts(context, listOf(id))[id] ?: ("" to emptyList())

    /** The same for many messages, read in one query per few hundred: a conversation of photos opens at once. */
    fun parts(context: Context, ids: Collection<Long>): Map<Long, Pair<String, List<MmsPart>>> {
        val text = HashMap<Long, StringBuilder>()
        val media = HashMap<Long, ArrayList<MmsPart>>()
        ids.distinct().chunked(500).forEach { chunk ->
            runCatching {
                context.contentResolver.query(
                    Uri.parse("content://mms/part"),
                    arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT, Telephony.Mms.Part.NAME, Telephony.Mms.Part.FILENAME, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_LOCATION),
                    "${Telephony.Mms.Part.MSG_ID} IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray(), Telephony.Mms.Part._ID
                )?.use { c ->
                    while (c.moveToNext()) {
                        val mid = c.getLong(5)
                        val type = c.getString(1).orEmpty().lowercase()
                        when {
                            type == ContentType.TEXT_PLAIN -> {
                                val t = c.getString(2).orEmpty()
                                if (t.isNotBlank()) text.getOrPut(mid) { StringBuilder() }.let { if (it.isEmpty()) it.append(t) else it.append('\n').append(t) }
                            }
                            type == ContentType.APP_SMIL || type.startsWith("application/smil") -> Unit
                            else -> media.getOrPut(mid) { ArrayList() } += MmsPart(Uri.parse("content://mms/part/${c.getLong(0)}"), type, c.getString(3) ?: c.getString(4) ?: c.getString(6))
                        }
                    }
                }
            }
        }
        return ids.associateWith { (text[it]?.toString().orEmpty()) to (media[it] ?: emptyList()) }
    }

    /**
     * The subjects of picture messages, cleansed (MmsSubject), only those that have one to show: the
     * conversation list names the newest message's subject over its snippet, as AOSP Messaging's
     * ConversationListItemView.setSubject does. One query per few hundred rows.
     */
    fun subjects(context: Context, ids: Collection<Long>): Map<Long, String> {
        val out = HashMap<Long, String>()
        ids.distinct().chunked(500).forEach { chunk ->
            runCatching {
                context.contentResolver.query(
                    Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.SUBJECT),
                    "${Telephony.Mms._ID} IN (${chunk.joinToString(",") { "?" }}) AND ${Telephony.Mms.SUBJECT} IS NOT NULL",
                    chunk.map { it.toString() }.toTypedArray(), null
                )?.use { c ->
                    while (c.moveToNext()) MmsSubject.cleanse(c.getString(1))?.let { out[c.getLong(0)] = it }
                }
            }
        }
        return out
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
                val data = part.data
                val from = part.dataUri
                when {
                    data != null -> context.contentResolver.openOutputStream(partUri)?.use { it.write(data) }
                    from != null -> context.contentResolver.openInputStream(from)?.use { input ->
                        context.contentResolver.openOutputStream(partUri)?.use { input.copyTo(it) }
                    }
                }
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
}

/** What a part is, in a word, where its picture cannot be shown. */
fun mediaWord(contentType: String?): String = when {
    contentType == null -> "Message"
    ContentType.isImageType(contentType) -> "Photo"
    ContentType.isVideoType(contentType) -> "Video"
    ContentType.isAudioType(contentType) -> "Voice message"
    contentType.contains("vlocation") -> "Location"
    contentType.contains("vcard") -> "Contact card"
    else -> "Attachment"
}

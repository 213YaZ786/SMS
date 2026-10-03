package com.yaz.sms.core.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Telephony
import android.telephony.CarrierConfigManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import com.yaz.sms.core.mms.pdu.AcknowledgeInd
import com.yaz.sms.core.mms.pdu.CharacterSets
import com.yaz.sms.core.mms.pdu.EncodedStringValue
import com.yaz.sms.core.mms.pdu.NotificationInd
import com.yaz.sms.core.mms.pdu.PduBody
import com.yaz.sms.core.mms.pdu.PduComposer
import com.yaz.sms.core.mms.pdu.PduHeaders
import com.yaz.sms.core.mms.pdu.PduParser
import com.yaz.sms.core.mms.pdu.PduPart
import com.yaz.sms.core.mms.pdu.RetrieveConf
import com.yaz.sms.core.mms.pdu.SendReq
import com.yaz.sms.core.sms.MessageNotifier
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Something to send with a picture message: a picture, a video or a sound, as picked. */
data class Attachment(val uri: Uri, val contentType: String)

/**
 * Picture messages over the network, through Android's own MMS service:
 * the message is written as a PDU file this app shares with it alone
 * (FileProvider, not exported), and Android reports back here.
 */
object MmsTransport {

    const val ACTION_SENT = "com.yaz.sms.MMS_SENT"
    const val ACTION_DOWNLOADED = "com.yaz.sms.MMS_DOWNLOADED"
    private const val EXTRA_FILE = "file"
    private const val EXTRA_SUB = "sub"
    private const val EXTRA_TRANSACTION = "transaction"
    private const val EXTRA_FROM = "from"
    /** What carriers take when they say nothing: 300 KB. */
    private const val DEFAULT_MAX = 300 * 1024

    /** Writes and sends a picture message to [recipients]: text and attachments in one. */
    fun send(context: Context, recipients: List<String>, text: String, attachments: List<Attachment>, subId: Int): Uri? {
        val app = context.applicationContext
        val sub = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) subId else SubscriptionManager.getDefaultSmsSubscriptionId()
        val budget = maxSize(app, sub) - 2 * 1024 - text.toByteArray().size
        val body = PduBody()
        if (text.isNotBlank()) body.addPart(part(ContentType.TEXT_PLAIN, "text0.txt", text.toByteArray(Charsets.UTF_8), CharacterSets.UTF_8))
        val share = if (attachments.isEmpty()) budget else budget / attachments.size
        attachments.forEachIndexed { i, a ->
            val data = if (ContentType.isImageType(a.contentType)) shrinkImage(app, a.uri, share) else read(app, a.uri, share)
            if (data != null) {
                val type = if (ContentType.isImageType(a.contentType)) ContentType.IMAGE_JPEG else a.contentType
                val ext = if (type == ContentType.IMAGE_JPEG) "jpg" else type.substringAfter('/')
                body.addPart(part(type, "part$i.$ext", data, 0))
            }
        }
        if (body.partsNum == 0) return null
        val req = SendReq().apply {
            recipients.forEach { addTo(EncodedStringValue(it)) }
            date = System.currentTimeMillis() / 1000
            setBody(body)
            setContentType(ContentType.MMS_MULTIPART_MIXED.toByteArray())
            messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray()
            expiry = 7L * 24 * 60 * 60
            runCatching {
                priority = PduHeaders.PRIORITY_NORMAL
                deliveryReport = PduHeaders.VALUE_NO
                readReport = PduHeaders.VALUE_NO
            }
            messageSize = (0 until body.partsNum).sumOf { (body.getPart(it).data?.size ?: 0).toLong() }
        }
        val uri = MmsStore.saveOutgoing(app, recipients, body, sub) ?: return null
        val sent = runCatching {
            val bytes = PduComposer(app, req).make() ?: error("compose")
            val file = file(app, "send-${uri.lastPathSegment}")
            file.writeBytes(bytes)
            manager(app, sub).sendMultimediaMessage(
                app, share(app, file), null, null,
                report(app, ACTION_SENT, uri, file, sub, null, null)
            )
        }.isSuccess
        if (!sent) MmsStore.setBox(app, uri, Telephony.Mms.MESSAGE_BOX_FAILED)
        return uri
    }

    /**
     * A picture message announced by the network: downloaded at once
     * through Android's MMS service; [onDownloaded] keeps it.
     */
    fun download(context: Context, pdu: ByteArray, subId: Int) {
        val app = context.applicationContext
        val ind = runCatching { PduParser(pdu, true).parse() as? NotificationInd }.getOrNull() ?: return
        val location = ind.contentLocation?.let { String(it) } ?: return
        val sub = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) subId else SubscriptionManager.getDefaultSmsSubscriptionId()
        val file = file(app, "get-${System.nanoTime()}")
        runCatching {
            manager(app, sub).downloadMultimediaMessage(
                app, location, share(app, file), null,
                report(app, ACTION_DOWNLOADED, Uri.parse(location), file, sub, ind.transactionId, ind.from?.string)
            )
        }.onFailure { MessageNotifier(app).pictureFailed(ind.from?.string) }
    }

    /** What Android says once a picture message went out or came in. */
    internal fun onResult(context: Context, intent: Intent, resultCode: Int) {
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File)
        val sub = intent.getIntExtra(EXTRA_SUB, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        try {
            when (intent.action) {
                ACTION_SENT -> {
                    val uri = intent.data ?: return
                    if (uri.authority != "mms") return
                    MmsStore.setBox(context, uri, if (resultCode == Activity.RESULT_OK) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED)
                }
                ACTION_DOWNLOADED -> {
                    val from = intent.getStringExtra(EXTRA_FROM)
                    val bytes = file?.takeIf { resultCode == Activity.RESULT_OK && it.exists() }?.readBytes()
                    val conf = bytes?.let { runCatching { PduParser(it, true).parse() as? RetrieveConf }.getOrNull() }
                    val thread = conf?.let { MmsStore.saveReceived(context, it, sub) }
                    if (thread == null) {
                        MessageNotifier(context).pictureFailed(from)
                        return
                    }
                    MessageNotifier(context).show(thread)
                    // Tells the network the message arrived, as MMS asks.
                    intent.getByteArrayExtra(EXTRA_TRANSACTION)?.let { acknowledge(context, it, sub) }
                }
            }
        } finally {
            file?.delete()
        }
    }

    private fun acknowledge(context: Context, transaction: ByteArray, sub: Int) {
        runCatching {
            val ack = AcknowledgeInd(PduHeaders.CURRENT_MMS_VERSION, transaction)
            val bytes = PduComposer(context, ack).make() ?: return
            val file = file(context, "ack-${System.nanoTime()}")
            file.writeBytes(bytes)
            manager(context, sub).sendMultimediaMessage(context, share(context, file), null, null, null)
        }
    }

    private fun part(type: String, location: String, data: ByteArray, charset: Int) = PduPart().apply {
        setContentType(type.toByteArray())
        setContentLocation(location.toByteArray())
        setContentId("<$location>".toByteArray())
        setName(location.toByteArray())
        if (charset != 0) setCharset(charset)
        setData(data)
    }

    /** A picture made small enough for the carrier: smaller sides, then lower quality, as needed. */
    private fun shrinkImage(context: Context, uri: Uri, budget: Int): ByteArray? = runCatching {
        var side = 1600
        while (side >= 320) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = side.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            for (quality in intArrayOf(85, 70, 55, 40)) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= budget) return@runCatching out.toByteArray()
            }
            side = side * 3 / 4
        }
        null
    }.getOrNull()

    /** A video or a sound as it is, when it fits. */
    private fun read(context: Context, uri: Uri, budget: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.size <= budget }
    }.getOrNull()

    private fun maxSize(context: Context, sub: Int): Int = runCatching {
        context.getSystemService(CarrierConfigManager::class.java)?.getConfigForSubId(sub)
            ?.getInt(CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT)?.takeIf { it > 0 }
    }.getOrNull() ?: DEFAULT_MAX

    private fun manager(context: Context, sub: Int): SmsManager =
        context.getSystemService(SmsManager::class.java).let { if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(sub) else it }

    private fun file(context: Context, name: String): File =
        File(context.cacheDir, "mms").apply { mkdirs() }.let { File(it, "$name.pdu") }

    private fun share(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)

    /** Mutable because Android adds its result; explicit, so only this app gets it. */
    private fun report(context: Context, action: String, uri: Uri, file: File, sub: Int, transaction: ByteArray?, from: String?): PendingIntent =
        PendingIntent.getBroadcast(
            context, file.name.hashCode(),
            Intent(context, MmsResultReceiver::class.java).setAction(action).setData(uri)
                .putExtra(EXTRA_FILE, file.path).putExtra(EXTRA_SUB, sub)
                .putExtra(EXTRA_TRANSACTION, transaction).putExtra(EXTRA_FROM, from),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

/** Android's answer about a picture message sent or downloaded. Not exported. */
class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = resultCode
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MmsTransport.onResult(app, intent, code)
            } finally {
                done.finish()
            }
        }
    }
}

package com.yaz.sms.core.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
    private const val EXTRA_ROW = "row"
    /** What carriers take when they say nothing: 300 KB. */
    private const val DEFAULT_MAX = 300 * 1024

    /** Writes and sends a picture message to [recipients]: text and attachments in one. */
    fun send(context: Context, recipients: List<String>, text: String, attachments: List<Attachment>, subId: Int, inThread: Long? = null): Uri? {
        val app = context.applicationContext
        val sub = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) subId else SubscriptionManager.getDefaultSmsSubscriptionId()
        val budget = maxSize(app, sub) - 2 * 1024 - text.toByteArray().size
        // The body as AOSP Messaging's MmsUtils.makePduBody lays it out (MmsBody): the attachments in order, named
        // by what they are, the text after them, and a SMIL part describing them put first.
        val items = ArrayList<MmsBody.Item>()
        val share = if (attachments.isEmpty()) budget else budget / attachments.size
        attachments.forEach { a ->
            val claimed = a.contentType
            var type = claimed
            val data: ByteArray?
            if (ContentType.isImageType(claimed)) {
                // A GIF stays a GIF when it fits the carrier's size (MmsUtils.getResizedImageAsPart keeps image/gif
                // for a GIF); the signature is checked, a file that claims to be one is decoded like any picture.
                // Any other picture goes out as a JPEG shrunk to fit.
                val gif = if (claimed.equals(ContentType.IMAGE_GIF, ignoreCase = true) || ContentType.isUnspecified(claimed))
                    read(app, a.uri, share)?.takeIf(MmsBody::isGif) else null
                data = gif ?: shrinkImage(app, a.uri, share)
                type = if (gif != null) ContentType.IMAGE_GIF else ContentType.IMAGE_JPEG
            } else {
                data = read(app, a.uri, share)
            }
            if (data != null) {
                val kind = MmsBody.kindOf(type)
                // A film or a sound says how long it plays, which the SMIL needs (MmsUtils.getMediaDurationMs).
                val length = if (kind == MmsBody.Kind.VIDEO || kind == MmsBody.Kind.AUDIO) durationMs(app, a.uri) else MmsBody.DEFAULT_DURATION
                items += MmsBody.Item(type, data, ContentType.getExtensionFromMimeType(type), length)
            }
        }
        val body = MmsBody.build(items, text) ?: return null
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
        val uri = MmsStore.saveOutgoing(app, recipients, body, sub, inThread) ?: return null
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
        val sub = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) subId else SubscriptionManager.getDefaultSmsSubscriptionId()
        // The announcement is kept first, as AOSP Mms keeps a NotificationInd: a
        // download that fails or waits leaves a message the user can download by hand.
        val (thread, id) = MmsStore.saveNotification(app, ind, sub) ?: return
        val prefs = com.yaz.sms.data.settings.SettingsStore(app).current
        val roaming = runCatching {
            app.getSystemService(android.telephony.TelephonyManager::class.java)
                ?.createForSubscriptionId(sub)?.isNetworkRoaming == true
        }.getOrDefault(false)
        if (!(prefs.autoMmsDownload && (!roaming || prefs.autoMmsRoaming)) || !downloadStored(app, id)) {
            // Waiting for a tap: the conversation says a picture message is there.
            MessageNotifier(app).show(thread)
        }
    }

    /**
     * Downloads the picture message a kept announcement points to, as AOSP
     * Messaging's RedownloadMmsAction re-asks for one; the row is replaced
     * by the message once it arrives.
     */
    fun downloadStored(context: Context, mmsId: Long): Boolean {
        val app = context.applicationContext
        val (location, sub, transaction) = MmsStore.notification(app, mmsId) ?: return false
        val file = file(app, "get-${System.nanoTime()}")
        downloading.value += mmsId
        return runCatching {
            // The row to replace rides in the result's own intent, as AOSP Messaging's
            // MmsSender.downloadMms puts its message's extras in the downloaded intent:
            // the app may be gone and back by the time the network answers.
            manager(app, sub).downloadMultimediaMessage(
                app, location, share(app, file), null,
                report(app, ACTION_DOWNLOADED, Uri.parse(location), file, sub, transaction, MmsStore.sender(app, mmsId), mmsId)
            )
            true
        }.getOrElse {
            downloading.value -= mmsId
            false
        }
    }

    /** The announcements being fetched right now, for their tiles to say so. */
    val downloading = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())

    /** A failed picture message sent again, as AOSP Messaging's ResendMessageAction: the old row leaves once the new one is on its way. */
    fun resend(context: Context, mmsId: Long): Boolean {
        val app = context.applicationContext
        val (text, media) = MmsStore.parts(app, mmsId)
        val recipients = MmsStore.recipients(app, mmsId)
        if (recipients.isEmpty()) return false
        val sub = MmsStore.subOf(app, mmsId)
        val sent = send(app, recipients, text, media.map { Attachment(it.uri, it.contentType) }, sub)
        if (sent != null) MmsStore.delete(app, mmsId)
        return sent != null
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
                    val row = intent.getLongExtra(EXTRA_ROW, -1).takeIf { it >= 0 }
                    row?.let { downloading.value -= it }
                    val bytes = file?.takeIf { resultCode == Activity.RESULT_OK && it.exists() }?.readBytes()
                    val conf = bytes?.let { runCatching { PduParser(it, true).parse() as? RetrieveConf }.getOrNull() }
                    val thread = conf?.let { MmsStore.saveReceived(context, it, sub) }
                    if (thread == null) {
                        // The announcement stays: its tile offers the download again.
                        MessageNotifier(context).pictureFailed(from)
                        return
                    }
                    // The announcement leaves: the message itself stands in the conversation now.
                    row?.let { MmsStore.delete(context, it) }
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

    /**
     * How long a film or a sound plays, in milliseconds, as AOSP Messaging's MmsUtils.getMediaDurationMs reads it
     * (a MediaMetadataRetriever on the part, METADATA_KEY_DURATION;
     * https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/sms/MmsUtils.java);
     * the default of a SMIL player when it cannot be read.
     */
    private fun durationMs(context: Context, uri: Uri): Int {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.coerceIn(1L, 24L * 60 * 60 * 1000)?.toInt() ?: MmsBody.DEFAULT_DURATION
        } catch (e: Exception) {
            MmsBody.DEFAULT_DURATION
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** A picture made small enough for the carrier: smaller sides, then lower quality, as needed. */
    private fun shrinkImage(context: Context, uri: Uri, budget: Int): ByteArray? = runCatching {
        // A picture chosen on the phone may be any file: decoded once, in the
        // isolated decoder, then made smaller here from plain pixels. Sending
        // already runs off the main thread.
        val full = kotlinx.coroutines.runBlocking { com.yaz.sms.core.security.SafeImages.decode(context, uri, 1600) } ?: return@runCatching null
        var side = 1600
        while (side >= 320) {
            val scale = side.toFloat() / maxOf(full.width, full.height)
            val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true) else full
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
    private fun report(context: Context, action: String, uri: Uri, file: File, sub: Int, transaction: ByteArray?, from: String?, row: Long = -1): PendingIntent =
        PendingIntent.getBroadcast(
            context, file.name.hashCode(),
            Intent(context, MmsResultReceiver::class.java).setAction(action).setData(uri)
                .putExtra(EXTRA_FILE, file.path).putExtra(EXTRA_SUB, sub)
                .putExtra(EXTRA_TRANSACTION, transaction).putExtra(EXTRA_FROM, from).putExtra(EXTRA_ROW, row),
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

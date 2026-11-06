package com.yaz.sms.core.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
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
import com.yaz.sms.core.mms.pdu.DeliveryInd
import com.yaz.sms.core.mms.pdu.EncodedStringValue
import com.yaz.sms.core.mms.pdu.GenericPdu
import com.yaz.sms.core.mms.pdu.NotificationInd
import com.yaz.sms.core.mms.pdu.NotifyRespInd
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
    /** The network's answer to a response we sent it (NotifyRespInd, AcknowledgeInd): only its temporary file is cleaned. */
    const val ACTION_RESPONDED = "com.yaz.sms.MMS_RESPONDED"
    private const val EXTRA_AUTO = "auto"
    private const val EXTRA_FILE = "file"
    private const val EXTRA_SUB = "sub"
    private const val EXTRA_TRANSACTION = "transaction"
    private const val EXTRA_FROM = "from"
    private const val EXTRA_ROW = "row"
    /** What carriers take when they say nothing: 300 KB. */
    private const val DEFAULT_MAX = 300 * 1024
    /** The most a failed message keeps of an attachment that did not fit. */
    private const val KEPT_MAX = 100L * 1024 * 1024

    /** Writes and sends a picture message to [recipients]: text and attachments in one. */
    fun send(context: Context, recipients: List<String>, text: String, attachments: List<Attachment>, subId: Int, inThread: Long? = null): Uri? {
        val app = context.applicationContext
        val sub = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) subId else SubscriptionManager.getDefaultSmsSubscriptionId()
        val budget = maxSize(app, sub) - 2 * 1024 - text.toByteArray().size
        // The body as AOSP Messaging's MmsUtils.makePduBody lays it out (MmsBody): the attachments in order, named
        // by what they are, the text after them, and a SMIL part describing them put first.
        val items = ArrayList<MmsBody.Item>()
        val unfit = ArrayList<Attachment>()
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
            } else if (ContentType.isVideoType(claimed)) {
                // A film goes as it is when it fits; a bigger one is turned into an MP4 (H.264, AAC) that
                // fits the carrier's size (MmsVideo, Media3 Transformer), or dropped as before when it cannot be.
                val whole = read(app, a.uri, share)
                if (whole != null) {
                    data = whole
                } else {
                    data = MmsVideo.fit(app, a.uri, share)
                    if (data != null) type = ContentType.VIDEO_MP4
                }
            } else {
                data = read(app, a.uri, share)
            }
            if (data != null) {
                val kind = MmsBody.kindOf(type)
                // A film or a sound says how long it plays, which the SMIL needs (MmsUtils.getMediaDurationMs).
                val length = if (kind == MmsBody.Kind.VIDEO || kind == MmsBody.Kind.AUDIO) durationMs(app, a.uri) else MmsBody.DEFAULT_DURATION
                items += MmsBody.Item(type, data, ContentType.getExtensionFromMimeType(type), length)
            } else {
                unfit += a
            }
        }
        // An attachment that could not be made to fit never makes the message vanish: it is kept as a failed message
        // that holds the attachment as picked (a part read from its Uri), so the thread shows "Not sent" and the
        // message goes again from its menu (AOSP Messaging keeps a failed message and its parts for ResendMessageAction,
        // https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/datamodel/action/ResendMessageAction.java).
        val body = MmsBody.build(items, text) ?: (if (unfit.isNotEmpty()) PduBody() else return null)
        unfit.forEach { a ->
            if (!keepable(app, a.uri)) return@forEach
            body.addPart(PduPart().apply {
                setContentType(a.contentType.toByteArray())
                setContentLocation("unsent${body.partsNum}".toByteArray())
                setDataUri(a.uri)
            })
        }
        val req = SendReq().apply {
            recipients.forEach { addTo(EncodedStringValue(it)) }
            date = System.currentTimeMillis() / 1000
            setBody(body)
            setContentType(ContentType.MMS_MULTIPART_MIXED.toByteArray())
            messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray()
            expiry = 7L * 24 * 60 * 60
            runCatching {
                priority = PduHeaders.PRIORITY_NORMAL
                // A delivery report is always asked for, as the SMS path asks: AOSP Messaging's createMmsSendReq sets
                // X-Mms-Delivery-Report from isDeliveryReportRequired (its delivery reports preference, on by default
                // for SMS), https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/sms/MmsUtils.java
                deliveryReport = PduHeaders.VALUE_YES
                readReport = PduHeaders.VALUE_NO
            }
            messageSize = (0 until body.partsNum).sumOf { (body.getPart(it).data?.size ?: 0).toLong() }
        }
        val uri = MmsStore.saveOutgoing(app, recipients, body, sub, inThread, deliveryReport = true) ?: return null
        if (unfit.isNotEmpty()) {
            MmsStore.setBox(app, uri, Telephony.Mms.MESSAGE_BOX_FAILED)
            val what = if (unfit.all { ContentType.isVideoType(it.contentType) }) "video" else "attachment"
            // A Toast from the sending thread through the main looper, as ThreadScreen's own "Not sent yet" notice.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(app, "Not sent: the $what is too large for MMS.", android.widget.Toast.LENGTH_LONG).show()
            }
            return uri
        }
        var rawStatus = PDU_HEADER_VALUE_UNDEFINED
        val sent = runCatching {
            val bytes = PduComposer(app, req).make() ?: error("compose")
            // A PDU over the carrier's maximum is refused before it leaves (MmsSender.writePduToTempFile).
            if (bytes.size > maxSize(app, sub)) {
                rawStatus = RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG
                error("too big")
            }
            val file = file(app, "send-${uri.lastPathSegment}")
            file.writeBytes(bytes)
            manager(app, sub).sendMultimediaMessage(
                app, share(app, file), null, null,
                report(app, ACTION_SENT, uri, file, sub, null, null)
            )
        }.isSuccess
        if (!sent) processResult(app, uri, MMS_REQUEST_MANUAL_RETRY, rawStatus, sub, SmsManager.MMS_ERROR_UNSPECIFIED, 0)
        return uri
    }

    /**
     * A picture message announced by the network: downloaded at once
     * through Android's MMS service; [onDownloaded] keeps it.
     */
    fun download(context: Context, pdu: ByteArray, subId: Int) {
        val app = context.applicationContext
        // What the network pushed, told apart by its type as AOSP Mms PushReceiver does: a delivery report goes to
        // MmsDelivery, an announcement is downloaded, anything else is dropped.
        val pushed = runCatching { PduParser(pdu, true).parse() }.getOrNull()
        if (pushed is DeliveryInd) {
            MmsDelivery.received(app, pushed)
            return
        }
        val ind = pushed as? NotificationInd ?: return
        val sub = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) subId else SubscriptionManager.getDefaultSmsSubscriptionId()
        // The announcement is kept first, as AOSP Mms keeps a NotificationInd: a
        // download that fails or waits leaves a message the user can download by hand.
        val (thread, id) = MmsStore.saveNotification(app, ind, sub) ?: return
        val prefs = com.yaz.sms.data.settings.SettingsStore(app).current
        val roaming = runCatching {
            app.getSystemService(android.telephony.TelephonyManager::class.java)
                ?.createForSubscriptionId(sub)?.isNetworkRoaming == true
        }.getOrDefault(false)
        val automatic = prefs.autoMmsDownload && (!roaming || prefs.autoMmsRoaming)
        if (!automatic || !downloadStored(app, id, auto = true)) {
            // Not downloaded automatically (or the download could not even start): the network is told the
            // message stays for later with a DEFERRED NotifyRespInd, as AOSP Messaging's ProcessDownloadedMmsAction
            // sendDeferredRespStatus does.
            val location = ind.contentLocation?.let { String(it) }
            val transaction = ind.transactionId
            if (location != null && transaction != null) runCatching {
                sendNotifyResponseForMmsDownload(app, sub, transaction, location, PduHeaders.STATUS_DEFERRED)
            }
            // Waiting for a tap: the conversation says a picture message is there.
            MessageNotifier(app).show(thread)
        }
    }

    /**
     * Downloads the picture message a kept announcement points to, as AOSP
     * Messaging's RedownloadMmsAction re-asks for one; the row is replaced
     * by the message once it arrives.
     */
    fun downloadStored(context: Context, mmsId: Long, auto: Boolean = false): Boolean {
        val app = context.applicationContext
        val (location, sub, transaction) = MmsStore.notification(app, mmsId) ?: return false
        // An announcement past its expiry (the one the network gave, kept with the row) is gone from the network.
        val expiry = MmsStore.expiryOf(app, mmsId)
        if (expiry > 0 && expiry < System.currentTimeMillis() / 1000) {
            if (!auto) android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(app, "This picture message can no longer be downloaded.", android.widget.Toast.LENGTH_LONG).show()
            }
            return false
        }
        val file = file(app, "get-${System.nanoTime()}")
        downloading.value += mmsId
        return runCatching {
            // The row to replace rides in the result's own intent, as AOSP Messaging's
            // MmsSender.downloadMms puts its message's extras in the downloaded intent:
            // the app may be gone and back by the time the network answers.
            manager(app, sub).downloadMultimediaMessage(
                app, location, share(app, file), null,
                report(app, ACTION_DOWNLOADED, Uri.parse(location), file, sub, transaction, MmsStore.sender(app, mmsId), mmsId, auto)
            )
            true
        }.getOrElse {
            downloading.value -= mmsId
            false
        }
    }

    /** The announcements being fetched right now, for their tiles to say so. */
    val downloading = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())

    /**
     * A failed picture message sent again, as AOSP Messaging's ResendMessageAction.executeAction: only a message
     * that can be resent (failed) goes, with a new date, in the same conversation. The old row leaves only once
     * the new one was written and left the outbox cleanly; if the new one failed at once it goes instead and the
     * old one stays. False when nothing is on its way.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/action/ResendMessageAction.java#executeAction
     */
    fun resend(context: Context, mmsId: Long): Boolean {
        val app = context.applicationContext
        // Check message can be resent
        if (MmsStore.boxOf(app, ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)) != Telephony.Mms.MESSAGE_BOX_FAILED) return false
        val (text, media) = MmsStore.parts(app, mmsId)
        val recipients = MmsStore.recipients(app, mmsId)
        if (recipients.isEmpty()) return false
        val sub = MmsStore.subOf(app, mmsId)
        val sent = send(app, recipients, text, media.map { Attachment(it.uri, it.contentType) }, sub, MmsStore.threadOf(app, mmsId)) ?: return false
        val box = MmsStore.boxOf(app, sent)
        if (box == null || box == Telephony.Mms.MESSAGE_BOX_FAILED) {
            // It did not leave: the new row goes, the old one stays to be tried again.
            MmsStore.delete(app, ContentUris.parseId(sent))
            return false
        }
        MmsStore.delete(app, mmsId)
        return true
    }

    /** The request succeeded. */
    private const val MMS_REQUEST_SUCCEEDED = 0
    /** The request failed, to be tried again (a transient failure). */
    private const val MMS_REQUEST_AUTO_RETRY = 1
    /** The request failed, the user may try again. */
    private const val MMS_REQUEST_MANUAL_RETRY = 2
    /** The request failed for good. */
    private const val MMS_REQUEST_NO_RETRY = 3
    private const val PDU_HEADER_VALUE_UNDEFINED = 0
    /** Ours (AOSP's MessageData holds its own): a message sent that the carrier's size limit refused. */
    private const val RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG = -2

    /** The status of a download, the retrieve status the network gave and the conversation the message was stored in. */
    private class StatusPlusUri(val status: Int, val rawStatus: Int, val thread: Long?)

    // Process different result code from platform MMS service
    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsSender.java#L284-L304
    private fun getErrorResultStatus(resultCode: Int, httpStatusCode: Int): Int {
        require(resultCode != Activity.RESULT_OK)
        return when (resultCode) {
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS, SmsManager.MMS_ERROR_IO_ERROR -> MMS_REQUEST_AUTO_RETRY
            SmsManager.MMS_ERROR_INVALID_APN, SmsManager.MMS_ERROR_CONFIGURATION_ERROR,
            SmsManager.MMS_ERROR_NO_DATA_NETWORK, SmsManager.MMS_ERROR_UNSPECIFIED -> MMS_REQUEST_MANUAL_RETRY
            SmsManager.MMS_ERROR_HTTP_FAILURE -> if (httpStatusCode == 404) MMS_REQUEST_NO_RETRY else MMS_REQUEST_AUTO_RETRY
            else -> MMS_REQUEST_MANUAL_RETRY
        }
    }

    /**
     * A downloaded message is checked before it is kept, as AOSP Messaging's MmsUtils does: its retrieve status
     * is OK (kept), transient (the announcement stays, to be tried again) or permanent (the announcement
     * stays, it can no longer be downloaded); the status is written to the announcement's row. Only
     * a message stored whole is answered to the network: NotifyRespInd RETRIEVED after an automatic download,
     * AcknowledgeInd after a manual one. The message is stored before the network is told it arrived (AOSP
     * tells it first), so a message that could not be written is never lost: the announcement stays.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsUtils.java#L1648-L1696
     * Not ported: insertReceivedMmsMessage (MmsStore.saveReceived, a new inbox row beside the announcement, which goes after)
     */
    private fun insertDownloadedMessageAndSendResponse(
        context: Context, notificationUri: Uri, subId: Int, transactionId: String?, contentLocation: String,
        autoDownload: Boolean, retrieveConf: RetrieveConf
    ): StatusPlusUri {
        val notificationTransactionId = transactionId?.toByteArray(Charsets.UTF_8)
        var status: Int
        val retrieveStatus = retrieveConf.retrieveStatus
        if (retrieveStatus == PduHeaders.RETRIEVE_STATUS_OK) {
            status = MMS_REQUEST_SUCCEEDED
        } else if (retrieveStatus >= PduHeaders.RETRIEVE_STATUS_ERROR_TRANSIENT_FAILURE &&
            retrieveStatus < PduHeaders.RETRIEVE_STATUS_ERROR_PERMANENT_FAILURE) {
            status = MMS_REQUEST_AUTO_RETRY
        } else {
            // else not meant to retry download
            status = MMS_REQUEST_NO_RETRY
            android.util.Log.e("MmsTransport", "MmsUtils: failed to retrieve message; retrieveStatus: $retrieveStatus")
        }
        MmsStore.updateRetrieveStatus(context, notificationUri, retrieveStatus)
        var thread: Long? = null
        if (status == MMS_REQUEST_SUCCEEDED) {
            // Insert downloaded message into telephony
            thread = MmsStore.saveReceived(context, retrieveConf, subId)
            if (thread == null) {
                status = MMS_REQUEST_MANUAL_RETRY
            } else if (autoDownload) {
                // Send response of the notification
                notificationTransactionId?.let {
                    runCatching { sendNotifyResponseForMmsDownload(context, subId, it, contentLocation, PduHeaders.STATUS_RETRIEVED) }
                }
            } else {
                (retrieveConf.transactionId ?: notificationTransactionId)?.let {
                    runCatching { sendAcknowledgeForMmsDownload(context, subId, it, contentLocation) }
                }
            }
        }
        // Do nothing for MMS_REQUEST_AUTO_RETRY and MMS_REQUEST_NO_RETRY.
        return StatusPlusUri(status, retrieveStatus, thread)
    }

    /**
     * What the network answered to a sent message (its M-Send.conf) becomes a request status: OK is a success,
     * a transient failure (0xC0 up to the permanent ones) is worth trying again, anything else is a refusal.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsUtils.java#L1804-L1833
     */
    private fun updateSentMmsMessageStatus(respStatus: Int): Int {
        val status: Int
        if (respStatus == PduHeaders.RESPONSE_STATUS_OK) {
            status = MMS_REQUEST_SUCCEEDED
        } else if (respStatus >= PduHeaders.RESPONSE_STATUS_ERROR_TRANSIENT_FAILURE &&
            respStatus < PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_FAILURE) {
            // Only RESPONSE_STATUS_ERROR_TRANSIENT_FAILURE and RESPONSE_STATUS_ERROR_TRANSIENT_NETWORK_PROBLEM are used
            // in the M-Send.conf, the others work the same way (OMA-MMS-ENC-V1_2, 7.2.37).
            status = MMS_REQUEST_AUTO_RETRY
        } else {
            // else permanent failure
            status = MMS_REQUEST_MANUAL_RETRY
            android.util.Log.e("MmsTransport", "MmsUtils: failed to send message; respStatus = " + String.format("0x%X", respStatus))
        }
        return status
    }

    /**
     * Update message status to reflect success or failure, as AOSP Messaging's ProcessSentMessageAction.processResult:
     * a success turns the row sent; a failure turns it failed and tells the user why, in a notice (MessageNotifier.failed,
     * BugleNotifications.update) and a short sentence (BugleActionToasts.onSendMessageOrManualDownloadActionCompleted).
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/action/ProcessSentMessageAction.java#L201-L289
     * Not ported: the retry window (a transient failure is failed at once, there is no automatic retry here),
     * the emergency number notice (an emergency call is not made from this app), ProcessPendingMessagesAction.
     */
    private fun processResult(context: Context, messageUri: Uri, status: Int, rawStatus: Int, subId: Int, resultCode: Int, httpStatusCode: Int) {
        val failed: Boolean
        if (status == MMS_REQUEST_SUCCEEDED) {
            MmsStore.setBox(context, messageUri, Telephony.Mms.MESSAGE_BOX_SENT)
            failed = false
        } else {
            MmsStore.setBox(context, messageUri, Telephony.Mms.MESSAGE_BOX_FAILED)
            failed = true
        }
        if (failed) {
            android.util.Log.e("MmsTransport", "ProcessSentMessageAction: Done sending MMS message $messageUri; status is $status, resultCode $resultCode, http $httpStatusCode, raw $rawStatus")
            MessageNotifier(context).failed(messageUri)
            val reason = getFailureReason(rawStatus, resultCode, httpStatusCode)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Not sent: $reason.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The reason a message did not go, short, from what Android and the carrier said. */
    private fun getFailureReason(rawStatus: Int, resultCode: Int, httpStatusCode: Int): String = when {
        rawStatus == RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG -> "the message is too large for your carrier"
        rawStatus == PduHeaders.RESPONSE_STATUS_ERROR_CONTENT_NOT_ACCEPTED ||
            rawStatus == PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_CONTENT_NOT_ACCEPTED -> "the message is too large for your carrier"
        resultCode == SmsManager.MMS_ERROR_HTTP_FAILURE && httpStatusCode == 413 -> "the message is too large for your carrier"
        resultCode == SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS || resultCode == SmsManager.MMS_ERROR_NO_DATA_NETWORK ||
            resultCode == SmsManager.MMS_ERROR_IO_ERROR -> "there is no connection"
        resultCode == SmsManager.MMS_ERROR_INVALID_APN || resultCode == SmsManager.MMS_ERROR_CONFIGURATION_ERROR ->
            "the MMS settings of your carrier are missing"
        else -> "your carrier did not take it"
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
                    // The result of the platform, as AOSP Messaging's ProcessSentMessageAction.executeAction reads it:
                    // the network's answer carries the Message-ID the delivery report will name (EXTRA_MMS_DATA) and
                    // its response status; a failure comes with the HTTP status and the size of the PDU sent.
                    val httpStatusCode = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
                    var status = MMS_REQUEST_MANUAL_RETRY
                    var rawStatus = PDU_HEADER_VALUE_UNDEFINED
                    if (resultCode == Activity.RESULT_OK) {
                        val respStatus = MmsStore.saveSendConf(context, uri, intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA))
                        status = if (respStatus == null) MMS_REQUEST_SUCCEEDED else updateSentMmsMessageStatus(respStatus)
                        if (respStatus != null) rawStatus = respStatus
                    } else {
                        status = getErrorResultStatus(resultCode, httpStatusCode)
                        // Check for MMS messages that failed because they exceeded the maximum size,
                        // indicated by an I/O error from the platform.
                        if (resultCode == SmsManager.MMS_ERROR_IO_ERROR && (file?.length() ?: 0L) > maxSize(context, sub)) {
                            rawStatus = RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG
                        }
                    }
                    processResult(context, uri, status, rawStatus, sub, resultCode, httpStatusCode)
                }
                // The network took (or refused) a response we sent it: the temporary file goes below.
                ACTION_RESPONDED -> Unit
                ACTION_DOWNLOADED -> {
                    val from = intent.getStringExtra(EXTRA_FROM)
                    val row = intent.getLongExtra(EXTRA_ROW, -1).takeIf { it >= 0 }
                    row?.let { downloading.value -= it }
                    val autoDownload = intent.getBooleanExtra(EXTRA_AUTO, false)
                    val contentLocation = intent.data?.toString()
                    val transactionId = intent.getByteArrayExtra(EXTRA_TRANSACTION)
                    val downloadedData = file?.takeIf { resultCode == Activity.RESULT_OK && it.exists() }?.readBytes()
                    val retrieveConf = downloadedData?.let { runCatching { PduParser(it, true).parse() as? RetrieveConf }.getOrNull() }
                    // The status of the download, as AOSP Messaging's ProcessDownloadedMmsAction.doBackgroundWork reads it
                    // (the platform's error, an unreadable answer, or the RetrieveConf's own retrieve status) and
                    // processBackgroundResponse acts on it: a failed automatic download sends the DEFERRED response.
                    // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/action/ProcessDownloadedMmsAction.java#processBackgroundResponse
                    // not ported: processBackgroundFailure (a failure here ends in the same announcement kept)
                    val result = if (resultCode != Activity.RESULT_OK) {
                        StatusPlusUri(getErrorResultStatus(resultCode, intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)), PDU_HEADER_VALUE_UNDEFINED, null)
                    } else if (retrieveConf != null && row != null && contentLocation != null) {
                        insertDownloadedMessageAndSendResponse(
                            context, ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, row), sub,
                            transactionId?.let { String(it, Charsets.UTF_8) }, contentLocation, autoDownload, retrieveConf
                        )
                    } else {
                        // Invalid response PDU, or failed to read download file
                        StatusPlusUri(MMS_REQUEST_MANUAL_RETRY, PDU_HEADER_VALUE_UNDEFINED, null)
                    }
                    val status = result.status
                    val messageUri = result.thread
                    // Do post-processing on downloaded message
                    if (messageUri != null) {
                        // The announcement leaves: the message itself stands in the conversation now.
                        row?.let { MmsStore.delete(context, it) }
                        MessageNotifier(context).show(messageUri)
                        return
                    }
                    // The announcement stays and nothing empty is stored: its tile offers the download again.
                    // If we were trying to auto-download but have failed need to send the deferred response
                    val needToSendDeferredResp = autoDownload && status != MMS_REQUEST_NO_RETRY
                    if (needToSendDeferredResp && contentLocation != null && transactionId != null) {
                        runCatching { sendNotifyResponseForMmsDownload(context, sub, transactionId, contentLocation, PduHeaders.STATUS_DEFERRED) }
                    }
                    if (status == MMS_REQUEST_NO_RETRY) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, "This picture message can no longer be downloaded.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    MessageNotifier(context).pictureFailed(from)
                }
            }
        } finally {
            file?.delete()
        }
    }

    /**
     * Send NotifyRespInd (response to mms auto download).
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsSender.java#L87-L102
     *
     * @param subId subscription to use to send the response
     * @param transactionId The transaction id of the MMS message
     * @param contentLocation The url of the MMS message
     * @param status The status to send with the NotifyRespInd
     */
    fun sendNotifyResponseForMmsDownload(context: Context, subId: Int, transactionId: ByteArray, contentLocation: String, status: Int) {
        // Create the M-NotifyResp.ind
        val notifyRespInd = NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION, transactionId, status)
        val messageUri = Uri.parse(contentLocation)
        // Pack M-NotifyResp.ind and send it
        sendMms(context, subId, messageUri, if (getNotifyWapMMSC(context, subId)) contentLocation else null, notifyRespInd)
    }

    /**
     * Send AcknowledgeInd (response to mms manual download). Failures are ignored by the caller.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsSender.java#L112-L129
     */
    fun sendAcknowledgeForMmsDownload(context: Context, subId: Int, transactionId: ByteArray, contentLocation: String) {
        val selfNumber = OwnNumbers.of(context).firstOrNull()
        // Create the M-Acknowledge.ind
        val acknowledgeInd = AcknowledgeInd(PduHeaders.CURRENT_MMS_VERSION, transactionId)
        if (selfNumber != null) acknowledgeInd.from = EncodedStringValue(selfNumber)
        val messageUri = Uri.parse(contentLocation)
        // Sending
        sendMms(context, subId, messageUri, if (getNotifyWapMMSC(context, subId)) contentLocation else null, acknowledgeInd)
    }

    /**
     * Send a generic PDU: written to a temporary file the MMS service reads, which is deleted when it answers.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/sms/MmsSender.java#L141-L166
     * Not ported: writePduToTempFile (the file and the size check are in this function and in send)
     */
    private fun sendMms(context: Context, subId: Int, messageUri: Uri, locationUrl: String?, pdu: GenericPdu) {
        // Write PDU to temporary file to send to platform
        val pduBytes = PduComposer(context, pdu).make() ?: error("Failed to compose PDU")
        val tempFile = file(context, "resp-${System.nanoTime()}")
        tempFile.writeBytes(pduBytes)
        try {
            // Construct PendingIntent that will notify us when message sending is complete
            val sentPendingIntent = report(context, ACTION_RESPONDED, messageUri, tempFile, subId, null, null)
            // Send the message
            manager(context, subId).sendMultimediaMessage(context, share(context, tempFile), locationUrl, null, sentPendingIntent)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /** Whether the carrier wants the response sent to the message's own URL (its "enabledNotifyWapMMSC" setting, off by default). */
    private fun getNotifyWapMMSC(context: Context, subId: Int): Boolean = runCatching {
        context.getSystemService(CarrierConfigManager::class.java)?.getConfigForSubId(subId)?.getBoolean("enabledNotifyWapMMSC", false)
    }.getOrNull() ?: false

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

    /**
     * Whether a file that could not be fitted is small enough to be kept in the failed message (100 MB): a bigger one
     * stays out, so that the message is still listed as failed and the disk is not filled by a copy. The length is
     * read as AOSP Messaging's UriUtil does, from the asset file descriptor
     * (https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/util/UriUtil.java).
     */
    private fun keepable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length in 0..KEPT_MAX } ?: false
    }.getOrDefault(false)

    /**
     * A video or a sound as it is, when it fits; read in blocks and given up as soon as it is over the budget, so a
     * film of hundreds of megabytes is never held whole (kotlin.io's InputStream.copyTo loop, stopped at the budget).
     */
    private fun read(context: Context, uri: Uri, budget: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > budget) return@use null
            }
            out.toByteArray()
        }
    }.getOrNull()

    internal fun maxSize(context: Context, sub: Int): Int = runCatching {
        context.getSystemService(CarrierConfigManager::class.java)?.getConfigForSubId(sub)
            ?.getInt(CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT)?.takeIf { it > 0 }
    }.getOrNull() ?: DEFAULT_MAX

    private fun manager(context: Context, sub: Int): SmsManager =
        context.getSystemService(SmsManager::class.java).let { if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(sub) else it }

    private fun file(context: Context, name: String): File =
        File(context.cacheDir, "mms").apply { mkdirs() }.let { File(it, "$name.pdu") }

    private fun share(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)

    /** Mutable because Android adds its result; explicit, so only this app gets it. */
    private fun report(context: Context, action: String, uri: Uri, file: File, sub: Int, transaction: ByteArray?, from: String?, row: Long = -1, auto: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            context, file.name.hashCode(),
            Intent(context, MmsResultReceiver::class.java).setAction(action).setData(uri)
                .putExtra(EXTRA_FILE, file.path).putExtra(EXTRA_SUB, sub)
                .putExtra(EXTRA_TRANSACTION, transaction).putExtra(EXTRA_FROM, from).putExtra(EXTRA_ROW, row).putExtra(EXTRA_AUTO, auto),
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

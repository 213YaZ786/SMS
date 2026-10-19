package com.yaz.sms.core.mms

import android.content.Context
import com.yaz.sms.core.dial.T9
import com.yaz.sms.core.mms.pdu.DeliveryInd
import com.yaz.sms.core.mms.pdu.PduHeaders

/**
 * Delivery reports of picture messages: the report the network sends back (an M-Delivery.ind in a
 * WAP push) is matched to the sent message by its Message-ID and kept as a row of its own, as AOSP Mms
 * keeps it; a sent message is delivered once every recipient reports it so.
 *
 * Sources, all Apache-2.0:
 * - AOSP Mms PushReceiver.ReceivePushTask and findThreadId (a DeliveryInd is matched to a Send.req of
 *   the same m_id, exactly one row, else skipped; then persisted in the inbox with the sent message's
 *   thread), https://android.googlesource.com/platform/packages/apps/Mms/+/refs/heads/jb-mr1-dev/src/com/android/mms/transaction/PushReceiver.java
 * - AOSP Mms DeliveryReportActivity (one status per recipient, compared as numbers, 0 = no report yet),
 *   https://android.googlesource.com/platform/packages/apps/Mms/+/refs/heads/jb-mr1-dev/src/com/android/mms/ui/DeliveryReportActivity.java
 * - TelephonyProvider MmsProvider MMS_REPORT_STATUS (the report rows are the pdu rows of m_type 134 with the
 *   sent message's m_id, their addr rows of type TO the recipients; read here in one query for many
 *   messages instead of one per message) and MmsSmsDatabaseHelper cleanup_delivery_and_read_report (they
 *   leave with their Send.req), https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/refs/heads/main/src/com/android/providers/telephony/MmsProvider.java
 */
object MmsDelivery {

    enum class State { PENDING, DELIVERED, FAILED }

    /** The longest Message-ID taken from the network: carriers use a few dozen characters. */
    private const val MAX_ID = 256

    /**
     * What an X-Mms-Status says, read as OMA-TS-MMS-ENC (X-Mms-Status) defines the values: Retrieved is delivered;
     * Rejected, Expired, Unreachable and Unrecognised are failures; Deferred (the recipient will fetch it later),
     * Forwarded (sent on, not read), Indeterminate and anything unknown are still pending. AOSP Mms's
     * DeliveryReportActivity.getMmsReportStatusText reads the same octets but counts Forwarded as received and
     * Deferred as failed; here they are pending.
     */
    fun stateOf(status: Int): State = when (status) {
        PduHeaders.STATUS_RETRIEVED -> State.DELIVERED
        PduHeaders.STATUS_REJECTED, PduHeaders.STATUS_EXPIRED,
        PduHeaders.STATUS_UNREACHABLE, PduHeaders.STATUS_UNRECOGNIZED -> State.FAILED
        else -> State.PENDING
    }

    /** The same address: equal, or the same number written two ways (AOSP's PhoneNumberUtils.compare in DeliveryReportActivity). */
    fun same(a: String, b: String): Boolean {
        if (a == b) return a.isNotEmpty()
        // "+33612345678/TYPE=PLMN" keeps its digits only.
        val x = T9.clean(a)
        val y = T9.clean(b)
        return x.isNotEmpty() && y.isNotEmpty() && T9.sameDigits(x, y)
    }

    /**
     * Which recipient of the sent message a report is about: the one its To names; a message to a single
     * person takes the report whatever way the carrier wrote the address. Null when it names nobody the message
     * went to, so that a report from the network about someone else changes nothing.
     */
    fun recipientFor(to: List<String>, recipients: List<String>): String? {
        for (address in to) recipients.firstOrNull { same(it, address) }?.let { return it }
        return recipients.singleOrNull()
    }

    /** A message to a group is delivered once every one of its recipients is (DeliveryReportActivity lists one status per recipient). */
    fun allDelivered(recipients: List<String>, states: Map<String, State>): Boolean =
        recipients.isNotEmpty() && recipients.all { states[it] == State.DELIVERED }

    /**
     * Keeps the report of a received M-Delivery.ind. True when a sent message of ours took it. A Message-ID that
     * matches nothing (or not exactly one message, or one that did not ask for a report or never left) changes
     * nothing: AOSP Mms PushReceiver skips a report whose Send.req is not found.
     */
    fun received(context: Context, ind: DeliveryInd): Boolean {
        val raw = ind.messageId?.takeIf { it.isNotEmpty() && it.size <= MAX_ID } ?: return false
        val messageId = MmsStore.isoString(raw)
        val sent = MmsStore.sentWithId(context, messageId) ?: return false
        if (!sent.reportAsked || sent.box != android.provider.Telephony.Mms.MESSAGE_BOX_SENT) return false
        val recipients = MmsStore.recipients(context, sent.id)
        val to = ind.to.orEmpty().map { it.string }
        val who = recipientFor(to, recipients) ?: return false
        val rows = MmsStore.reports(context, listOf(messageId))[messageId].orEmpty()
        val known = rows.firstOrNull { MmsStore.reportRecipient(context, it.row) == who }
        if (known == null) return MmsStore.saveReport(context, sent, messageId, who, ind.status, ind.date)
        // Delivered stays delivered whatever arrives after (a late Deferred).
        if (stateOf(known.status) == State.DELIVERED) return true
        return MmsStore.updateReport(context, known.row, ind.status)
    }

    // A delivered message stays so: asked once per row and Message-ID (Android reuses a deleted row's number).
    private val settled = android.util.LruCache<String, Boolean>(2000)

    /**
     * Of the sent messages [candidates] (row id to Message-ID), those every recipient of which reported delivered.
     * One query for all the reports; the recipients of a report are read only for a group.
     */
    fun delivered(context: Context, candidates: Map<Long, String>): Set<Long> {
        if (candidates.isEmpty()) return emptySet()
        val done = HashSet<Long>()
        val todo = HashMap<Long, String>()
        candidates.forEach { (id, messageId) ->
            if (settled.get("$id/$messageId") != null) done += id else todo[id] = messageId
        }
        if (todo.isEmpty()) return done
        val reports = MmsStore.reports(context, todo.values)
        todo.forEach { (id, messageId) ->
            val rows = reports[messageId] ?: return@forEach
            val recipients = MmsStore.recipients(context, id)
            val states = HashMap<String, State>()
            if (recipients.size == 1) {
                // Only reports about the one recipient are ever kept for it.
                states[recipients[0]] = if (rows.any { stateOf(it.status) == State.DELIVERED }) State.DELIVERED else State.PENDING
            } else {
                rows.forEach { row ->
                    val who = MmsStore.reportRecipient(context, row.row) ?: return@forEach
                    val state = stateOf(row.status)
                    if (states[who] != State.DELIVERED) states[who] = state
                }
            }
            if (allDelivered(recipients, states)) {
                done += id
                settled.put("$id/$messageId", true)
            }
        }
        return done
    }
}

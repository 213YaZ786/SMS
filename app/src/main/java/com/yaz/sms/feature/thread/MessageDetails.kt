package com.yaz.sms.feature.thread

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.dial.Numbers
import com.yaz.sms.data.sms.Box as MessageBox
import com.yaz.sms.data.sms.Message
import com.yaz.sms.ui.component.ZoneAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The message's details, as AOSP Messaging's MessageDetailsDialog tells
 * them (type, from or to, sent and received times, size, the SIM):
 * read from Android's own row when the sheet opens.
 */
@Composable
fun MessageDetailsDialog(m: Message, onClose: () -> Unit) {
    val context = LocalContext.current
    val extra by produceState<Extra?>(null, m.uid) {
        value = withContext(Dispatchers.IO) { readExtra(context, m) }
    }
    val received = m.box == MessageBox.RECEIVED
    ZoneAlertDialog(
        onDismissRequest = onClose,
        confirmButton = { com.yaz.sms.ui.component.BoldButton(filled = true, onClick = onClose) { Text("Close") } },
        title = { Text("Message details") },
        text = {
            Column {
                DetailRow("Type", (if (m.mms) "Picture message (MMS)" else "Text message (SMS)") + if (m.rich) ", encrypted chat" else "")
                if (received) DetailRow("From", Numbers.format(context, m.address))
                else extra?.to?.takeIf { it.isNotEmpty() }?.let { to ->
                    DetailRow("To", to.joinToString { Numbers.format(context, it) })
                }
                val sent = extra?.sent
                if (sent != null && sent > 0) DetailRow("Sent", fullTime(context, sent))
                DetailRow(if (received) "Received" else "Written", fullTime(context, m.date))
                extra?.size?.takeIf { it > 0 }?.let { DetailRow("Size", sizeWord(it)) }
                extra?.sim?.let { DetailRow("SIM", it) }
                if (!received) DetailRow(
                    "Status",
                    when {
                        m.box == MessageBox.FAILED -> "Not sent"
                        m.box == MessageBox.SENDING -> "Sending"
                        m.seen -> "Seen"
                        m.delivered -> "Delivered"
                        else -> "Sent"
                    }
                )
            }
        }
    )
}

/** The moment written whole, as Bugle's Dates.getMessageDetailsTimeString (DateUtils). */
private fun fullTime(context: Context, millis: Long): String =
    android.text.format.DateUtils.formatDateTime(
        context, millis,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or
            android.text.format.DateUtils.FORMAT_SHOW_YEAR or android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY
    )

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$label:  ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private class Extra(val sent: Long?, val size: Long?, val sim: String?, val to: List<String>)

/** What the list row does not carry: the sent time, the size, the SIM's name. */
private fun readExtra(context: Context, m: Message): Extra {
    var sent: Long? = null
    var size: Long? = null
    runCatching {
        if (m.mms) {
            context.contentResolver.query(
                ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, m.id),
                arrayOf(Telephony.Mms.DATE_SENT, Telephony.Mms.MESSAGE_SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    sent = c.getLong(0).takeIf { it > 0 }?.times(1000)
                    size = c.getLong(1)
                }
            }
            if (size == null || size == 0L) {
                // The parts' own bytes when the row does not say.
                size = m.parts.sumOf { p ->
                    runCatching {
                        context.contentResolver.openInputStream(p.uri)?.use { it.available().toLong() } ?: 0L
                    }.getOrDefault(0L)
                }
            }
        } else {
            context.contentResolver.query(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, m.id),
                arrayOf(Telephony.Sms.DATE_SENT), null, null, null
            )?.use { c -> if (c.moveToFirst()) sent = c.getLong(0).takeIf { it > 0 } }
        }
    }
    val sim = runCatching {
        if (m.subId < 0) null
        else context.getSystemService(android.telephony.SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == m.subId }
            ?.displayName?.toString()
    }.getOrNull()
    val to = if (m.box == MessageBox.RECEIVED) emptyList()
    else if (m.mms) com.yaz.sms.core.mms.MmsStore.recipients(context, m.id)
    else listOf(m.address).filter { it.isNotBlank() }
    return Extra(sent, size, sim, to)
}

private fun sizeWord(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

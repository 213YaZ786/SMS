package com.sms.app.feature.thread

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sms.app.core.chat.EncryptionInfo
import com.sms.app.core.chat.RichChat
import com.sms.app.ui.component.FloatingPane
import com.sms.app.ui.component.ZoneAlertDialog
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.icon.AppIcons
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import org.koin.compose.koinInject

/**
 * The conversation's tools behind one round button: four dots that
 * breathe one after the other, so it reads as something that opens.
 */
@Composable
fun ToolsButton(onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val t by rememberInfiniteTransition(label = "tools").animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "t")
    val accent = MaterialTheme.colorScheme.primary
    FloatingPane(shape = CircleShape, onClick = {
        haptics.tick()
        onClick()
    }) {
        Canvas(Modifier.size(48.dp)) {
            val gap = 7.dp.toPx()
            val base = 2.6.dp.toPx()
            val c = Offset(size.width / 2, size.height / 2)
            listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(1f, 1f), Offset(-1f, 1f)).forEachIndexed { i, d ->
                // Each dot swells in turn, then all rest a while.
                val phase = ((t * 1.6f - i * 0.12f) % 1f + 1f) % 1f
                val beat = if (phase < 0.25f) sin(phase / 0.25f * PI).toFloat() else 0f
                drawCircle(accent, radius = base * (1f + 0.45f * beat), center = c + Offset(d.x * gap, d.y * gap))
            }
        }
    }
}

/**
 * How the chat with [phone] is protected: what is encrypted and how, both
 * keys' fingerprints to compare on the two phones, and the relays.
 */
@Composable
fun EncryptionDialog(phone: String, onDismiss: () -> Unit) {
    val chat: RichChat = koinInject()
    val info by produceState<EncryptionInfo?>(null, phone) { value = chat.encryptionInfo(phone) }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Encryption") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Messages and calls with them are end-to-end encrypted: only your two phones can read or hear them.")
                Text(
                    "Messages: OpenPGP keys (Autocrypt). Calls: DTLS-SRTP. The relays only pass sealed messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                info?.let { i ->
                    if (i.mine != null && i.theirs != null) {
                        Text("To be sure it is them, compare these on both phones:", style = MaterialTheme.typography.labelLarge)
                        Fingerprint("Yours", i.mine)
                        Fingerprint("Theirs", i.theirs)
                    }
                    if (i.relays.isNotEmpty()) Text(
                        "Relays: " + i.relays.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
private fun Fingerprint(whose: String, print: String) {
    Column {
        Text(whose, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Five groups a line, as the engine writes them.
        print.split(' ').chunked(5).forEach { line ->
            Text(line.joinToString(" "), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * Notifications of this conversation off: for an hour, until tomorrow
 * morning, or until turned back on; [until] when they are off already.
 */
@Composable
fun SilenceDialog(until: Long?, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    val now = System.currentTimeMillis()
    val morning = LocalDate.now().plusDays(1).atTime(LocalTime.of(8, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.NotificationsOff, contentDescription = null) },
        title = { Text(if (until != null) "Silenced" else "Silence") },
        text = {
            Column {
                if (until != null) Text(
                    if (until == Long.MAX_VALUE) "No notifications from this conversation." else "No notifications until ${android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(java.util.Date(until))}.",
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                listOfNotNull(
                    "For an hour" to now + 60 * 60 * 1000L,
                    "Until tomorrow morning" to morning,
                    "Until I turn it back on" to Long.MAX_VALUE,
                    if (until != null) "Notifications back on" to null else null
                ).forEach { (label, at) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (at == null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        modifier = Modifier.fillMaxWidth().clickable {
                            haptics.tick()
                            onPick(at)
                            onDismiss()
                        }.padding(vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Searching the conversation: a pill of glass under the top, the field,
 * how many messages hold the words, and the way from one to the next.
 */
@Composable
fun SearchBar(query: String, onQuery: (String) -> Unit, found: Int, at: Int, onOlder: () -> Unit, onNewer: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    FloatingPane(shape = CircleShape, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (query.isEmpty()) Text("Search this conversation", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    query,
                    onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            if (query.isNotBlank()) Text(
                if (found == 0) "None" else "${at + 1}/$found",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            RoundIcon(AppIcons.ArrowUp, "Older", enabled = found > 0, onClick = onOlder)
            RoundIcon(AppIcons.ArrowDown, "Newer", enabled = found > 0, onClick = onNewer)
            RoundIcon(AppIcons.Close, "Close search", enabled = true, onClick = onClose)
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier.size(40.dp).clickable(enabled = enabled, onClickLabel = label) {
            haptics.tick()
            onClick()
        },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
    }
}

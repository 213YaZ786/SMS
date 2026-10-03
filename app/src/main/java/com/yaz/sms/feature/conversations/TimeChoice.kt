package com.yaz.sms.feature.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.sms.Timed
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.rememberHaptics

/** When: in an hour, this evening, tomorrow morning. */
@Composable
fun TimeChoice(title: String, onPick: (Long) -> Unit, onDismiss: () -> Unit, explain: String? = null) {
    val haptics = rememberHaptics()
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        DayAndTime(onPick = { at -> choosing = false; onPick(at) }, onDismiss = { choosing = false })
        return
    }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                explain?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                Timed.choices().forEach { (label, at) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable {
                            haptics.done()
                            onPick(at)
                        }.padding(vertical = 12.dp)
                    )
                }
                // Any day and time: the day first, then the hour.
                Text(
                    "Choose a day and time",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable {
                        haptics.tick()
                        choosing = true
                    }.padding(vertical = 12.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A day, then an hour of it: Material's own pickers, never in the past. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DayAndTime(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    val now = remember { java.time.ZonedDateTime.now() }
    val today = remember { java.time.LocalDate.now().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
    val dayState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = today,
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today
        }
    )
    val timeState = androidx.compose.material3.rememberTimePickerState(
        initialHour = (now.hour + 1) % 24,
        initialMinute = 0,
        is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
    )
    var day by remember { mutableStateOf<Long?>(null) }
    if (day == null) androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { haptics.tick(); day = dayState.selectedDateMillis ?: today }) { Text("Next") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { androidx.compose.material3.DatePicker(dayState) }
    else ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("At what time?") },
        text = { androidx.compose.material3.TimePicker(timeState) },
        confirmButton = {
            TextButton(onClick = {
                val date = java.time.Instant.ofEpochMilli(day!!).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                val at = date.atTime(timeState.hour, timeState.minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at <= System.currentTimeMillis() + 60_000) haptics.reject() else { haptics.done(); onPick(at) }
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = { day = null }) { Text("Back") } }
    )
}

package com.sms.app.feature.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sms.app.core.sms.Timed
import com.sms.app.ui.component.ZoneAlertDialog
import com.sms.app.ui.component.rememberHaptics

/** When: in an hour, this evening, tomorrow morning. */
@Composable
fun TimeChoice(title: String, onPick: (Long) -> Unit, onDismiss: () -> Unit, explain: String? = null) {
    val haptics = rememberHaptics()
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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

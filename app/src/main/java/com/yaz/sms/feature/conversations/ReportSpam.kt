package com.yaz.sms.feature.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yaz.sms.ui.component.ZoneAlertDialog

/**
 * The confirmation of Report spam, as Google Messages asks it on a conversation
 * (https://support.google.com/messages/answer/9061432: "Report spam", with the number blocked
 * through a box that is checked): the choice to block sits in the dialog, checked, shown only
 * when this app may block (the default SMS app). The box is a toggleable row, the Compose recipe of
 * a checkbox whose label is part of its target
 * (https://developer.android.com/develop/ui/compose/components/checkbox).
 */
@Composable
fun ReportSpamDialog(name: String, canBlock: Boolean, onDismiss: () -> Unit, onConfirm: (block: Boolean) -> Unit) {
    var block by rememberSaveable { mutableStateOf(true) }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report spam?") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("$name goes to Spam.")
                if (canBlock) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .toggleable(value = block, role = Role.Checkbox, onValueChange = { block = it })
                ) {
                    Checkbox(checked = block, onCheckedChange = null)
                    Text("Block this number")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(canBlock && block) }) { Text("Report spam") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

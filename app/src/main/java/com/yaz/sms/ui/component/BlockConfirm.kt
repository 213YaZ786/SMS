package com.yaz.sms.ui.component

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.yaz.sms.ui.icon.AppIcons

/**
 * Blocking asks once: a blocked number no longer rings, and texts from it no
 * longer arrive. Ported from Dialer's BlockDialog (feature/recents/RecentsScreen.kt
 * of this suite) as it was, into the phone kit so SMS and Contacts ask the same;
 * the confirmation of AOSP Dialer's DialogFragmentForBlockingNumber, a title with
 * the number and a message
 * (https://android.googlesource.com/platform/packages/apps/Dialer/+/refs/heads/main/java/com/android/dialer/blockreportspam/BlockReportSpamDialogs.java,
 * Apache-2.0). The blocking itself stays in each app: [onBlock] does it.
 * Built on ZoneAlertDialog, the clear glass dialog of the base kit.
 */
@Composable
fun BlockConfirm(who: String, onDismiss: () -> Unit, onBlock: () -> Unit) {
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Block, contentDescription = null) },
        title = { Text("Block $who?") },
        text = { Text("Calls and texts from this number will no longer reach you.") },
        confirmButton = { BoldButton(filled = true, onClick = onBlock) { Text("Block") } },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

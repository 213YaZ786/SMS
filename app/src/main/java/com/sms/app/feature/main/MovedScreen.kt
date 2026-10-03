package com.sms.app.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sms.app.core.handover.Handover
import com.sms.app.ui.component.BoldButton
import com.sms.app.ui.component.LoadingMark
import com.sms.app.ui.component.rememberHaptics

/** This SMS gave everything to the new one: where to go now. */
@Composable
fun MovedScreen() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)
    ) {
        LoadingMark(size = 140.dp)
        Spacer(Modifier.height(20.dp))
        Text("SMS has moved", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your messages, chats and settings are in the new SMS. This one can go.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        BoldButton(filled = true, onClick = { haptics.done(); Handover.openNew(context) }) { Text("Open the new SMS") }
        Spacer(Modifier.height(12.dp))
        BoldButton(onClick = { haptics.tick(); context.packageName.let { pkg ->
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } }) { Text("Remove this one") }
    }
}

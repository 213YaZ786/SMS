package com.sms.app.feature.thread

import android.Manifest
import android.content.pm.PackageManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sms.app.ui.component.FloatingPane
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.icon.AppIcons

/**
 * The message being written, floating over the conversation: the text in
 * a pane of glass that grows with it, the SIM it goes from when there are
 * two, how many SMS it makes once it is long, and Send on its own pane.
 */
@Composable
fun Composer(initial: String, modifier: Modifier, onSend: (String, Int) -> Boolean) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var text by rememberSaveable { mutableStateOf(initial) }
    val sims = remember { activeSims(context) }
    var simIndex by rememberSaveable { mutableIntStateOf(sims.indexOfFirst { it.subscriptionId == SubscriptionManager.getDefaultSmsSubscriptionId() }.coerceAtLeast(0)) }
    val length = remember(text) { if (text.isBlank()) null else SmsMessage.calculateLength(text, false) }
    val canSend = text.isNotBlank()
    val lift by animateFloatAsState(if (canSend) 1f else 0.86f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "send")

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
        // Shown once the message is more than one SMS, or nearly so.
        length?.let { (parts, _, left) ->
            if (parts > 1 || left < 20) {
                FloatingPane(shape = CircleShape) {
                    Text(
                        if (parts > 1) "$parts SMS · $left left" else "$left left",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            if (sims.size > 1) {
                FloatingPane(shape = CircleShape, onClick = {
                    haptics.tick()
                    simIndex = (simIndex + 1) % sims.size
                }, modifier = Modifier.size(52.dp)) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Text("SIM ${simIndex + 1}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            FloatingPane(shape = RoundedCornerShape(26.dp), modifier = Modifier.weight(1f)) {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 15.dp)) {
                    if (text.isEmpty()) Text("Message", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().widthIn(min = 40.dp)
                    )
                }
            }
            FloatingPane(
                shape = CircleShape,
                accent = canSend,
                onClick = {
                    if (!canSend) return@FloatingPane
                    val sub = sims.getOrNull(simIndex)?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    if (onSend(text, sub)) {
                        haptics.done()
                        text = ""
                    } else {
                        haptics.reject()
                    }
                },
                modifier = Modifier.size(52.dp).graphicsLayer {
                    scaleX = lift
                    scaleY = lift
                }
            ) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(canSend, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "send") { ready ->
                        Icon(
                            AppIcons.Send,
                            contentDescription = "Send",
                            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** The SIMs in use, when the phone may be asked; one or none otherwise. */
private fun activeSims(context: android.content.Context): List<SubscriptionInfo> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return runCatching { context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())
}

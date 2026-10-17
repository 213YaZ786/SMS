package com.yaz.sms.feature.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.chat.LiveLocation
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.core.sms.Places
import com.yaz.sms.ui.component.DialogPane
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons

/**
 * How long to share the live location: Delta Chat Android's list of durations in a dialog of glass
 * (ShareLocationDialog.java, array share_location_durations). Choosing starts the sharing.
 */
@Composable
internal fun LiveDurationSheet(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        DialogPane {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text("Share live location", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                Text(
                    "Updates while the app is open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)
                )
                LiveLocation.durations.forEach { d ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Share for ${d.label}") {
                            haptics.firm()
                            onPick(d.seconds)
                        }.padding(horizontal = 24.dp, vertical = 14.dp)
                    ) {
                        Icon(AppIcons.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(d.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

/**
 * While the live location is shared: a pill of glass in the thread with the end time and Stop
 * (Delta Chat Android shows a notification with "Stop sharing location" for it,
 * LocationStreamingService.buildNotification; here it is in the screen, nothing is in the background).
 */
@Composable
internal fun LivePill(until: Long, onStop: () -> Unit) {
    val haptics = rememberHaptics()
    FloatingPane(shape = RoundedCornerShape(26.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Icon(AppIcons.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                "Sharing live location · until " + LiveLocation.clock(until),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            Text(
                "Stop",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clip(CircleShape).clickable(onClickLabel = "Stop sharing live location") {
                    haptics.firm()
                    onStop()
                }.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * The live location of someone in the conversation: the place card (a tap opens the user's own map
 * app at the latest point, nothing is fetched) saying how long ago it moved. In a group the name
 * tells whose it is.
 */
@Composable
internal fun LiveCard(spot: RichChat.LiveSpot, group: Boolean, now: Long) {
    val title = if (group && spot.name.isNotBlank()) "Live location · ${spot.name}" else "Live location"
    PlaceCard(Places.Place(spot.lat, spot.lon, title), subtitle = LiveLocation.updated(now, spot.at))
}

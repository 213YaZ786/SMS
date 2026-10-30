package com.yaz.sms.feature.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
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
    com.yaz.sms.ui.component.ChoiceDialog(
        title = "Share live location",
        icon = AppIcons.MyLocation,
        text = "Updates while the app is open.",
        options = LiveLocation.durations.map { d -> d.seconds to d.label },
        onPick = onPick,
        onDismiss = onDismiss
    )
}

/**
 * While the live location is shared: a pill of glass in the thread with the end time and Stop
 * (Delta Chat Android shows a notification with "Stop sharing location" for it,
 * LocationStreamingService.buildNotification; here it is in the screen, nothing is in the background).
 */
@Composable
internal fun LivePill(until: Long, onStop: () -> Unit) {
    val haptics = rememberHaptics()
    // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
    FloatingPane(shape = ShapeDefaults.ExtraLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Icon(AppIcons.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                "Sharing live location until " +LiveLocation.clock(until),
                style = MaterialTheme.typography.labelLarge,
                // Wraps between words, the pill grows with it (the 200 % font, a longer language).
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
    val title = if (group && spot.name.isNotBlank()) "Live location of ${spot.name}" else "Live location"
    PlaceCard(Places.Place(spot.lat, spot.lon, title), subtitle = LiveLocation.updated(now, spot.at))
}

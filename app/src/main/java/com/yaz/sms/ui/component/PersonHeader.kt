package com.yaz.sms.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The head of a person's page, the same in Dialer, SMS and Contacts: the face
 * at the start, then a block to its end centred on the face with the name and
 * the number; each app adds its own line ([extra]) and badges.
 *
 * Ported from Dialer's NumberScreen header (this suite), itself taken from
 * AOSP Dialer's call details header: contact_container.xml, the photo, then
 * contact_name, phone_number and network toEndOf the photo
 * (https://android.googlesource.com/platform/packages/apps/Dialer/+/refs/heads/main/java/com/android/dialer/calldetails/res/layout/contact_container.xml,
 * Apache-2.0). One header shape for every app, each adding its own entries,
 * as AOSP Contacts' QuickContactActivity keeps one header over its entries
 * (https://android.googlesource.com/platform/packages/apps/Contacts/+/refs/heads/main/src/com/android/contacts/quickcontact/QuickContactActivity.java,
 * Apache-2.0) and as iOS shares one contact card across Phone, Messages and
 * Contacts. Row mirrors in right-to-left.
 *
 * [face] draws the person at the size it is given (ContactAvatar, 80 dp);
 * when [onFace] is given the face is a button, "Open in Contacts".
 * [number] is shown under the name when it is there and differs from it.
 *
 * The [badges] stand beside the name when the header is 520 dp wide or more
 * and the font is at most 130 %, under the text (wrapping) otherwise. The
 * choice follows the header's own width, not the screen's, since each page
 * caps its column: Android's window size classes end the compact width at
 * 600 dp (https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes),
 * measured here with BoxWithConstraints
 * (https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonHeader(
    name: String,
    number: String?,
    face: @Composable (Dp) -> Unit,
    modifier: Modifier = Modifier,
    onFace: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
    badges: (@Composable () -> Unit)? = null
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Read here: inside the Row, the Row's scope hides this box's maxWidth.
        val headerWidth = maxWidth
        val sideBadges = badges != null && headerWidth >= 520.dp && LocalDensity.current.fontScale <= 1.3f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(FACE)
                    .then(
                        if (onFace != null) Modifier.clip(CircleShape).clickable(indication = null, interactionSource = null, role = Role.Button, onClickLabel = "Open in Contacts", onClick = onFace)
                        else Modifier
                    )
            ) { face(FACE) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(
                    name,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Start,
                    // A number as the name is cut in its middle, so both ends stay readable (MiddleEllipsis acts on one line only).
                    maxLines = if (looksLikeNumber(name)) 1 else 2,
                    overflow = if (looksLikeNumber(name)) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis
                )
                if (!number.isNullOrBlank() && number != name) {
                    Text(
                        number,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                }
                extra?.invoke()
                if (badges != null && !sideBadges) {
                    FlowRow(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) { badges() }
                }
            }
            if (badges != null && sideBadges) {
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.widthIn(max = headerWidth * 0.34f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { badges() }
            }
        }
    }
}

/**
 * A small capsule of a person's page, an icon and a word, put in [PersonHeader]'s
 * badges. Glass like PersonActionTile (this suite), as a pill 48 dp high, since a
 * clickable pill under 48 dp spills over its neighbours.
 */
@Composable
fun PersonBadge(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit
) {
    val haptics = rememberHaptics()
    ZoneSurface(
        shape = CircleShape,
        accent = accent,
        modifier = modifier.heightIn(min = 48.dp),
        onClick = {
            haptics.tick()
            onClick()
        }
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val FACE = 80.dp

/** A name made only of what a phone number is made of. */
private fun looksLikeNumber(name: String): Boolean =
    name.any { it.isDigit() } && name.all { it.isDigit() || it in "+ -().#*" }

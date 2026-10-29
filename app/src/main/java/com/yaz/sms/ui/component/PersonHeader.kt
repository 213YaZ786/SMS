package com.yaz.sms.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The head of a person's page, the same in Dialer, SMS and Contacts: the face
 * at the start, then a block to its end centred on the face with the name and
 * the number; each app adds its own line ([extra]) and badges under them.
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
 */
@Composable
fun PersonHeader(
    name: String,
    number: String?,
    face: @Composable (Dp) -> Unit,
    modifier: Modifier = Modifier,
    onFace: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
    badges: (@Composable RowScope.() -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            if (badges != null) {
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = badges
                )
            }
        }
    }
}

private val FACE = 80.dp

/** A name made only of what a phone number is made of. */
private fun looksLikeNumber(name: String): Boolean =
    name.any { it.isDigit() } && name.all { it.isDigit() || it in "+ -().#*" }

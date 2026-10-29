package com.yaz.sms.ui.component

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.dial.NumberActions
import com.yaz.sms.ui.icon.AppIcons

/**
 * The actions on a person's number, one row of equal glass tiles, the same in
 * Dialer, SMS and Contacts: Call, Message, Contact ("Contact" when the person
 * is saved, "Add contact" when not), Copy. Under 360 dp the row wraps to two.
 *
 * Each tile takes an optional lambda: the app's own way, or, when null, the
 * default by intent, handed to the app that owns it (NumberActions: ACTION_DIAL
 * tel:, ACTION_SENDTO smsto:, ACTION_VIEW of the contact or ACTION_INSERT_OR_EDIT
 * with the number, the clipboard). A tile whose intent no app resolves is hidden,
 * as AOSP Contacts hides an entry: PhoneCapabilityTester.isIntentRegistered
 * (https://android.googlesource.com/platform/packages/apps/Contacts/+/refs/heads/main/src/com/android/contacts/util/PhoneCapabilityTester.java,
 * Apache-2.0), used by QuickContactActivity for its entries
 * (https://android.googlesource.com/platform/packages/apps/Contacts/+/refs/heads/main/src/com/android/contacts/quickcontact/QuickContactActivity.java).
 * A hidden or private number ([number] null or blank) shows nothing.
 * Tiles come from Dialer's NumberParts.ActionTile (this suite): 88 dp glass,
 * icon over word. iOS shares one contact card across Phone, Messages and
 * Contacts, each app adding its own sections: [more] appends the app's own
 * tiles (Dialer's Block).
 *
 * [contactId] is the contact's id when saved, null when not; 0 or less is a
 * private person of the Contacts app, who has nothing to open in Android's
 * contacts (the Contact tile is then left to [onContact]).
 *
 * Android 11+ package visibility: the app needs the matching `<queries>` intents
 * in its manifest (DIAL tel, SENDTO smsto, VIEW and INSERT_OR_EDIT of a contact)
 * or those tiles are hidden.
 */
@Composable
fun PersonActions(
    number: String?,
    contactId: Long?,
    modifier: Modifier = Modifier,
    onCall: (() -> Unit)? = null,
    onMessage: (() -> Unit)? = null,
    onContact: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    more: (@Composable RowScope.() -> Unit)? = null
) {
    if (number.isNullOrBlank()) return
    val context = LocalContext.current
    val saved = contactId != null
    val tiles = remember(number, contactId, onCall, onMessage, onContact, onCopy) {
        buildList {
            if (onCall != null || isIntentRegistered(context, dialIntent(number))) {
                add(Tile(AppIcons.Call, "Call", "Call") { onCall?.invoke() ?: NumberActions.dial(context, number) })
            }
            if (onMessage != null || isIntentRegistered(context, messageIntent(number))) {
                add(Tile(AppIcons.Message, "Message", "Message") { onMessage?.invoke() ?: NumberActions.message(context, number) })
            }
            if (saved) {
                val id = contactId ?: 0L
                if (onContact != null || (id > 0 && isIntentRegistered(context, openIntent(id)))) {
                    add(Tile(AppIcons.Person, "Contact", "Contact") { onContact?.invoke() ?: NumberActions.openContact(context, id) })
                }
            } else if (onContact != null || isIntentRegistered(context, addIntent(number))) {
                add(Tile(AppIcons.PersonAdd, "Add contact", "Add contact") { onContact?.invoke() ?: NumberActions.addContact(context, number) })
            }
            add(Tile(AppIcons.Copy, "Copy", "Copy number") { onCopy?.invoke() ?: NumberActions.copy(context, number) })
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perRow = if (maxWidth < 360.dp) 2 else Int.MAX_VALUE
        val rows = tiles.chunked(perRow)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEachIndexed { index, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { PersonActionTile(it.icon, it.label, Modifier.weight(1f), description = it.description, onClick = it.onClick) }
                    // Wide: the app's tiles stand in the same row; narrow: in a row of their own.
                    if (more != null && perRow == Int.MAX_VALUE && index == rows.lastIndex) more()
                }
            }
            if (more != null && perRow != Int.MAX_VALUE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { more() }
            }
        }
    }
}

private class Tile(val icon: ImageVector, val label: String, val description: String, val onClick: () -> Unit)

/**
 * One action under a person: a tile of glass of its own, icon over word.
 * Dialer's NumberParts.ActionTile (this suite) as it is; public so the `more`
 * tiles of an app's [PersonActions] look the same.
 */
@Composable
fun PersonActionTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    description: String = label,
    onClick: () -> Unit
) {
    val haptics = rememberHaptics()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "sink")
    ZoneSurface(
        shape = RoundedCornerShape(26.dp),
        modifier = modifier
            .height(88.dp)
            .graphicsLayer {
                scaleX = sink
                scaleY = sink
            }
            .clip(RoundedCornerShape(26.dp))
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = description) {
                haptics.tick()
                onClick()
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = tint ?: MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// The intents of NumberActions, to ask whether an app answers them.
private fun dialIntent(number: String) = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))

private fun messageIntent(number: String) = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))

private fun openIntent(id: Long) =
    Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString()))

private fun addIntent(number: String) =
    Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
        .putExtra(ContactsContract.Intents.Insert.PHONE, number)

/** AOSP Contacts' PhoneCapabilityTester.isIntentRegistered, with its MATCH_DEFAULT_ONLY. */
private fun isIntentRegistered(context: Context, intent: Intent): Boolean =
    runCatching { context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() }
        .getOrDefault(false)

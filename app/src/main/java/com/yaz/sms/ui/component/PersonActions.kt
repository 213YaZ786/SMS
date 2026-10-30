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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.yaz.sms.core.dial.NumberActions
import com.yaz.sms.ui.icon.AppIcons

/**
 * The actions on a person's number, equal glass tiles, the same in
 * Dialer, SMS and Contacts: Call, Message, Contact ("Contact" when the person
 * is saved, "Add contact" when not), Copy. The tiles flow and balance by the
 * width, the font and the language (see the layout below), never a fixed row.
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
 * Contacts, each app adding its own sections: [extra] appends the app's own
 * tiles (Dialer's Block) to the same flow.
 *
 * [contactId] is the contact's id when saved, null when not; 0 or less is a
 * private person of the Contacts app, who has nothing to open in Android's
 * contacts (the Contact tile is then left to [onContact]).
 * [contact] false hides the Contact and Add contact tiles, for the page that is the contact itself.
 *
 * Android 11+ package visibility: the app needs the matching `<queries>` intents
 * in its manifest (DIAL tel, SENDTO smsto, VIEW and INSERT_OR_EDIT of a contact)
 * or those tiles are hidden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonActions(
    number: String?,
    contactId: Long?,
    contact: Boolean = true,
    modifier: Modifier = Modifier,
    onCall: (() -> Unit)? = null,
    onMessage: (() -> Unit)? = null,
    onContact: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    extra: List<PersonActionSpec> = emptyList()
) {
    if (number.isNullOrBlank()) return
    val context = LocalContext.current
    val saved = contactId != null
    val tiles = remember(number, contactId, contact, onCall, onMessage, onContact, onCopy) {
        buildList {
            if (onCall != null || isIntentRegistered(context, dialIntent(number))) {
                add(PersonActionSpec(AppIcons.Call, "Call", "Call") { onCall?.invoke() ?: NumberActions.dial(context, number) })
            }
            if (onMessage != null || isIntentRegistered(context, messageIntent(number))) {
                add(PersonActionSpec(AppIcons.Message, "Message", "Message") { onMessage?.invoke() ?: NumberActions.message(context, number) })
            }
            if (!contact) {
                // The page is the contact itself: nothing to open or add.
            } else if (saved) {
                val id = contactId ?: 0L
                if (onContact != null || (id > 0 && isIntentRegistered(context, openIntent(id)))) {
                    add(PersonActionSpec(AppIcons.Person, "Contact", "Contact") { onContact?.invoke() ?: NumberActions.openContact(context, id) })
                }
            } else if (onContact != null || isIntentRegistered(context, addIntent(number))) {
                add(PersonActionSpec(AppIcons.PersonAdd, "Add contact", "Add contact") { onContact?.invoke() ?: NumberActions.addContact(context, number) })
            }
            add(PersonActionSpec(AppIcons.Copy, "Copy", "Copy number") { onCopy?.invoke() ?: NumberActions.copy(context, number) })
        }
    }
    val all = tiles + extra
    // Adaptive, from Compose's flow layouts recipe: FlowRow with maxItemsInEachRow, every tile
    // Modifier.weight(1f).fillMaxRowHeight()
    // (https://developer.android.com/develop/ui/compose/layouts/flow). The tile's least width is
    // measured, not guessed: the widest label on one line in the current style, font scale and language
    // (TextMeasurer, https://developer.android.com/develop/ui/compose/text/measure-text-layout), plus
    // 16 dp each side; never under 88 dp or the icon with its padding.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.labelLarge
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // The widest label on one line, and the widest single word (the least a tile can be when its
        // label wraps between words), both in px, measured with TextMeasurer
        // (https://developer.android.com/develop/ui/compose/text/measure-text-layout).
        val widths = remember(all.map { it.label }, style, density) {
            val oneLine = all.maxOf { measurer.measure(it.label, style, softWrap = false, maxLines = 1).size.width }
            val word = all.maxOf { spec ->
                spec.label.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
            }
            oneLine to word
        }
        val floor = maxOf(MinTile, TileIcon + TileSide * 2)
        val oneLineWidth = maxOf(floor, with(density) { widths.first.toDp() } + TileSide * 2)
        val wordWidth = maxOf(floor, with(density) { widths.second.toDp() } + TileSide * 2)
        val available = maxWidth
        // How many tiles of a width fit in a FlowRow line, then balanced: rows = ceil(n / fit),
        // perRow = ceil(n / rows), so 5 tiles that do not fit in one row make 3 + 2, never 4 + 1
        // (maxItemsInEachRow of https://developer.android.com/develop/ui/compose/layouts/flow).
        fun perRowFor(width: Dp): Int {
            val fit = ((available + TileGap) / (width + TileGap)).toInt().coerceAtLeast(1)
            val lines = (all.size + fit - 1) / fit
            return (all.size + lines - 1) / lines
        }
        // A last row of one tile (2 + 2 + 1) is what a flow layout leaves by default; the same recipe's
        // rows wrap between words, so labels may then take two lines (https://developer.android.com/develop/ui/compose/layouts/flow).
        fun lone(perRow: Int) = all.size > perRow && all.size % perRow == 1
        // One line labels first; if that leaves a lone tile, the width of the widest word; a lone tile
        // stays only when neither leaves none.
        val perOneLine = perRowFor(oneLineWidth)
        val wrapped = lone(perOneLine) && !lone(perRowFor(wordWidth))
        val perRow = if (wrapped) perRowFor(wordWidth) else perOneLine
        val minWidth = if (wrapped) wordWidth else oneLineWidth
        // One size for every label of the flow, computed once from the tile width the balanced rows give
        // (rememberGroupLabelSize, ui/component/GroupLabelSize.kt: the ladder of TextAutoSize.StepBased asked of the whole group),
        // so Call and Add contact never read in two sizes.
        val inRow = minOf(perRow, all.size)
        val tileWidth = maxOf(minOf(minWidth, available), (available - TileGap * (inRow - 1)) / inRow)
        val labelSize = rememberGroupLabelSize(all.map { it.label }, style, tileWidth - TileSide * 2)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            verticalArrangement = Arrangement.spacedBy(TileGap),
            maxItemsInEachRow = perRow
        ) {
            all.forEach {
                PersonActionTile(
                    it.icon, it.label,
                    Modifier.weight(1f).fillMaxRowHeight().widthIn(min = minOf(minWidth, available)),
                    tint = it.tint, description = it.description, labelSize = labelSize, onClick = it.onClick
                )
            }
        }
    }
}

private val MinTile = 88.dp
private val TileSide = 16.dp
private val TileIcon = 28.dp
private val TileGap = 10.dp

/**
 * One action of [PersonActions]: the shared ones, and the app's own through `extra`
 * (Dialer's Block, SMS's encrypted and video calls), all in the same flow.
 */
class PersonActionSpec(
    val icon: ImageVector,
    val label: String,
    val description: String = label,
    val tint: Color? = null,
    val onClick: () -> Unit
)

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
    labelSize: TextUnit = TextUnit.Unspecified,
    onClick: () -> Unit
) {
    val haptics = rememberHaptics()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "sink")
    // A tile on the page: Material 3's extra large corner, 28 dp (ShapeDefaults,
    // https://developer.android.com/reference/kotlin/androidx/compose/material3/ShapeDefaults).
    ZoneSurface(
        shape = androidx.compose.material3.ShapeDefaults.ExtraLarge,
        modifier = modifier
            .heightIn(min = 88.dp)
            .graphicsLayer {
                scaleX = sink
                scaleY = sink
            }
            .clip(androidx.compose.material3.ShapeDefaults.ExtraLarge)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = description) {
                haptics.tick()
                onClick()
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            // Room for a two line label (a long word, a 200 % font) so it never touches the edge.
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            // Lines break between words only. In a group the size is the one the group computed
            // ([labelSize], rememberGroupLabelSize): every tile reads alike, no autosize of its own. Alone, a single
            // word still too wide shrinks as the last resort: Compose's TextAutoSize.StepBased
            // (https://developer.android.com/develop/ui/compose/text/autosize).
            val style = MaterialTheme.typography.labelLarge
            val color = tint ?: MaterialTheme.colorScheme.onSurface
            if (labelSize.isSpecified) {
                BasicText(label, style = style.copy(color = color, textAlign = TextAlign.Center, fontSize = labelSize))
            } else {
                BasicText(
                    label,
                    style = style.copy(color = color, textAlign = TextAlign.Center),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp)
                )
            }
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

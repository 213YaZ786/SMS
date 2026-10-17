package com.yaz.sms.feature.thread

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaz.sms.ui.component.ContactAvatar
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons

/** One person of a group: their number, the name shown for them and their photo. */
data class GroupMember(val phone: String, val name: String, val photo: String? = null)

/**
 * What a group's page needs and can do. [chat] says the group goes over the encrypted chat: the picture,
 * taking someone out and leaving exist only there (an SMS or MMS group has no server to carry them).
 */
class GroupTools(
    val members: List<GroupMember>,
    val chat: Boolean,
    val left: Boolean,
    /** The engine's file of the group's picture, shown only through the isolated decoder. */
    val picture: String?,
    val mentionsOn: Boolean,
    val onMentions: (Boolean) -> Unit,
    /** The clean JPEG of a new picture, or null to take the picture away. */
    val onPicture: (ByteArray?) -> Unit,
    val onRemove: (GroupMember) -> Unit,
    val onLeave: () -> Unit
)

/**
 * What the group page has open: its questions and the picture being chosen. The ways are Delta Chat
 * Android's (GPL-3.0, https://github.com/deltachat/deltachat-android): the group picture chosen from the
 * gallery or taken away (GroupCreateActivity, AvatarSelector ADD_GALLERY / REMOVE_PHOTO), a member taken
 * out after "Remove %1$s from group?" (ProfileFragment), leaving after "Are you sure you want to leave?"
 * (ConversationActivity.handleLeaveGroup).
 */
@Stable
class GroupUi {
    var removing by mutableStateOf<GroupMember?>(null)
    var leaving by mutableStateOf(false)
    var clearing by mutableStateOf(false)
    var chatOnly by mutableStateOf(false)
    var cropping by mutableStateOf<Uri?>(null)
    /** Opens the gallery; set where the launcher lives. */
    var pick: (() -> Unit)? = null

    /** Runs [action] when the group can do it; else says why not. */
    fun needsChat(tools: GroupTools, action: () -> Unit) {
        if (tools.chat && !tools.left) action() else chatOnly = true
    }
}

/** The questions and the cropper of [ui], drawn over the page. */
@Composable
fun GroupDialogs(tools: GroupTools, ui: GroupUi) {
    val haptics = rememberHaptics()
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) ui.cropping = uri }
    ui.pick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    ui.cropping?.let { photo ->
        GroupPictureCrop(photo, onDone = { jpeg ->
            ui.cropping = null
            tools.onPicture(jpeg)
        }, onDismiss = { ui.cropping = null })
    }
    ui.removing?.let { who ->
        ZoneAlertDialog(
            onDismissRequest = { ui.removing = null },
            title = { Text("Remove ${who.name}?") },
            text = { Text("They leave the group on every phone. They keep what they already received.") },
            confirmButton = {
                TextButton(onClick = {
                    ui.removing = null
                    haptics.firm()
                    tools.onRemove(who)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { ui.removing = null }) { Text("Cancel") } }
        )
    }
    if (ui.leaving) ZoneAlertDialog(
        onDismissRequest = { ui.leaving = false },
        title = { Text("Leave this group?") },
        text = { Text("The others are told. You get nothing more from it and cannot write to it. Its messages stay here.") },
        confirmButton = {
            TextButton(onClick = {
                ui.leaving = false
                haptics.firm()
                tools.onLeave()
            }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { ui.leaving = false }) { Text("Cancel") } }
    )
    if (ui.clearing) ZoneAlertDialog(
        onDismissRequest = { ui.clearing = false },
        title = { Text("Remove the group picture?") },
        text = { Text("It goes for every member.") },
        confirmButton = {
            TextButton(onClick = {
                ui.clearing = false
                haptics.firm()
                tools.onPicture(null)
            }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { ui.clearing = false }) { Text("Cancel") } }
    )
    if (ui.chatOnly) ZoneAlertDialog(
        onDismissRequest = { ui.chatOnly = false },
        title = { Text(if (tools.left) "You left this group" else "Encrypted chat only") },
        text = {
            Text(
                if (tools.left) "You can read it, not change it."
                else "A group picture, taking someone out and leaving need every member on the encrypted chat. This group goes by SMS and MMS."
            )
        },
        confirmButton = { TextButton(onClick = { ui.chatOnly = false }) { Text("OK") } }
    )
}

/**
 * The group's picture in its circle: the engine's file through the isolated decoder (a member's picture
 * is anyone's file), the letters of its name until there is one.
 */
@Composable
fun GroupFace(path: String?, name: String, size: Dp) {
    val picture = path?.let { rememberPicture(Uri.fromFile(java.io.File(it)), with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }) }?.value
    if (picture == null) ContactAvatar(name, null, size)
    else Image(
        picture,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape)
    )
}

/**
 * A member of the group, with a swipe left and a long press that ask to take them out (when [onRemove]
 * is given). Swiping to dismiss is Material 3's SwipeToDismissBox
 * (https://developer.android.com/develop/ui/compose/touch-input/user-interactions/swipe-to-dismiss-box);
 * the row springs back, the question decides.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MemberLine(member: GroupMember, number: String, onRemove: (() -> Unit)?) {
    val haptics = rememberHaptics()
    val body = @Composable {
        ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .then(if (onRemove != null) Modifier.combinedClickable(onClick = {}, onLongClick = { haptics.firm(); onRemove() }, onLongClickLabel = "Remove") else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                ContactAvatar(member.name, member.photo, 40.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(member.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (number.isNotBlank() && number != member.name) {
                        Text(number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (onRemove != null) Box(
                    Modifier.size(44.dp).clip(CircleShape).combinedClickable(onClickLabel = "Remove ${member.name}", onClick = { haptics.tick(); onRemove() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.PersonRemove, contentDescription = "Remove ${member.name}", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
    if (onRemove == null) {
        body()
        return
    }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) {
            haptics.firm()
            onRemove()
        }
        false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(AppIcons.PersonRemove, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        content = { body() }
    )
}

/** The arrangement of the members' lines. */
val MemberSpacing = Arrangement.spacedBy(8.dp)

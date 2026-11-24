package com.yaz.sms.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.dial.ContactLook
import com.yaz.sms.ui.component.ContactAvatar
import com.yaz.sms.ui.theme.zone

/**
 * A conversation's face: the person's, or for a group two of its members' faces.
 * [members] is empty for a person; with two or more, [GroupAvatarView] draws them. [windowBackground] is the
 * colour of what the face sits on (the card's), for a group's front face.
 */
@Composable
internal fun FaceAvatar(
    name: String?,
    photo: String?,
    look: ContactLook.Look?,
    members: List<PinnedFace>,
    size: Dp,
    modifier: Modifier = Modifier,
    windowBackground: Color = MaterialTheme.colorScheme.zone
) {
    if (members.size >= 2) GroupAvatarView(members, size, windowBackground, modifier)
    else ContactAvatar(name, photo, size, modifier, look = look)
}

/** One recipient's avatar: a contact photo or monogram (QKSMS's setRecipient; ContactAvatar is the app's own). */
@Composable
private fun setRecipient(recipient: PinnedFace, size: Dp) {
    ContactAvatar(recipient.name, recipient.photo, size, look = recipient.look)
}

/**
 * A group's face, as QKSMS's GroupAvatarView lays it out (group_avatar_view.xml): avatar2Frame, the second recipient,
 * at the top start with no ring; avatar1Frame, the first, at the bottom end, a circle tinted with the card's colour
 * and a 2 dp padding around its face, so a ring parts the two. Both frames are 0.65 of [size] (QKSMS's
 * matchConstraintPercentWidth is 0.75 for avatar1Frame); QKSMS puts avatar1 at the top, here it is the front one, as
 * agreed on the draft. One recipient alone fills the whole face and avatar2 is hidden (updateView).
 * https://raw.githubusercontent.com/moezbhatti/qksms/master/presentation/src/main/java/com/moez/QKSMS/common/widget/GroupAvatarView.kt#GroupAvatarView
 * not ported: onFinishInflate and isInEditMode (a View's inflate hooks), the recipients setter's sort by lookupKey
 * (the list's own order is kept: the first two are the two most recent), setBackgroundTint (a colour is passed in)
 */
@Composable
private fun GroupAvatarView(recipients: List<PinnedFace>, size: Dp, windowBackground: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) { updateView(recipients, size, windowBackground) }
}

/** GroupAvatarView's updateView: the tint of avatar1Frame, its width, and whether avatar2 shows. */
@Composable
private fun BoxScope.updateView(recipients: List<PinnedFace>, size: Dp, windowBackground: Color) {
    val avatar1 = recipients.getOrNull(0) ?: return
    val avatar2 = recipients.getOrNull(1)
    val isVisible = recipients.size > 1 && avatar2 != null
    val setBackgroundTint = when (recipients.size > 1) {
        true -> windowBackground
        false -> Color.Transparent
    }
    val matchConstraintPercentWidth = if (recipients.size > 1) 0.65f else 1.0f
    val avatar1Frame = size * matchConstraintPercentWidth
    val padding = 2.dp
    if (isVisible && avatar2 != null) Box(Modifier.align(Alignment.TopStart).size(avatar1Frame).padding(padding)) {
        setRecipient(avatar2, avatar1Frame - padding * 2)
    }
    if (isVisible) Box(Modifier.align(Alignment.BottomEnd).size(avatar1Frame).background(setBackgroundTint, CircleShape).padding(padding)) {
        setRecipient(avatar1, avatar1Frame - padding * 2)
    } else setRecipient(avatar1, size)
}

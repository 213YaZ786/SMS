package com.yaz.sms.feature.conversations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.dial.ContactLook
import com.yaz.sms.ui.component.ContactAvatar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A group's most faces in one circle (Element X's MAX_AVATAR_COUNT). */
private const val CLUSTER_MAX = 4

/**
 * A conversation's face: the person's, or for a group the cluster of its members' faces.
 * [members] is empty for a person; with two or more, [GroupFace] draws them.
 */
@Composable
internal fun FaceAvatar(
    name: String?,
    photo: String?,
    look: ContactLook.Look?,
    members: List<PinnedFace>,
    size: Dp,
    modifier: Modifier = Modifier
) {
    if (members.size >= 2) GroupFace(members, size, modifier)
    else ContactAvatar(name, photo, size, modifier, look = look)
}

/**
 * Up to four members' faces in one circle: two, three or four heroes on a ring around the centre, each a
 * contact photo or monogram (ContactAvatar). Geometry as Element X's AvatarCluster (AGPL-3.0, SMS is AGPL):
 * ring radius size/4.2, /4.0, /3.1 and hero size size/2.2, /2.4, /2.2 for 2, 3 and 4 heroes, angle offsets
 * PI, 7*PI/6 and 13*PI/4, the third and fourth swapped so the fourth sits at the bottom right.
 * https://github.com/element-hq/element-x-android/blob/develop/libraries/designsystem/src/main/kotlin/io/element/android/libraries/designsystem/components/avatar/internal/AvatarCluster.kt
 * Element X draws one avatar for a single hero; the caller does ([FaceAvatar]).
 */
@Composable
internal fun GroupFace(members: List<PinnedFace>, size: Dp, modifier: Modifier = Modifier) {
    val heroes = members.take(CLUSTER_MAX).toMutableList()
    val count = heroes.size
    if (count == 4) heroes.apply { val third = this[2]; this[2] = this[3]; this[3] = third }
    val angle = 2 * PI / count
    val offsetRadius = when (count) {
        2 -> size.value / 4.2
        3 -> size.value / 4.0
        else -> size.value / 3.1
    }
    val heroSize = when (count) {
        3 -> size / 2.4f
        else -> size / 2.2f
    }
    val angleOffset = when (count) {
        2 -> PI
        3 -> 7 * PI / 6
        else -> 13 * PI / 4
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        heroes.forEachIndexed { index, hero ->
            val x = (offsetRadius * cos(angle * index + angleOffset)).dp
            val y = (offsetRadius * sin(angle * index + angleOffset)).dp
            Box(Modifier.size(heroSize).offset(x = x, y = y)) {
                ContactAvatar(hero.name, hero.photo, heroSize, look = hero.look)
            }
        }
    }
}

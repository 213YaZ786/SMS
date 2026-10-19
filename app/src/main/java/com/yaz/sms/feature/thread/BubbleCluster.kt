package com.yaz.sms.feature.thread

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.dial.T9
import com.yaz.sms.data.sms.Message
import com.yaz.sms.data.sms.Box as MessageBox
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Where a message stands in a run of messages of one sender: alone, first, in the middle or last. Ported from
 * Signal Android's V2ConversationItemShape (https://github.com/signalapp/Signal-Android, AGPL-3.0,
 * app/src/main/java/org/thoughtcrime/securesms/conversation/v2/items/V2ConversationItemShape.kt): its
 * MessageShape with the margins above and below each (collapsedSpacing 1 dp, defaultSpacing 6 dp), applied as
 * the item's top and bottom margin in V2ConversationItemTextOnlyViewHolder.bind (topMargin = shape.topPadding,
 * bottomMargin = shape.bottomPadding).
 */
internal enum class Cluster(val top: Dp, val bottom: Dp) {
    /** This message stands alone. */
    SINGLE(DEFAULT_SPACING, DEFAULT_SPACING),

    /** The first of a cluster. */
    START(DEFAULT_SPACING, COLLAPSED_SPACING),

    /** The last of a cluster. */
    END(COLLAPSED_SPACING, DEFAULT_SPACING),

    /** In the middle of a cluster. */
    MIDDLE(COLLAPSED_SPACING, COLLAPSED_SPACING);

    val isStarting: Boolean get() = this == SINGLE || this == START
    val isEnding: Boolean get() = this == SINGLE || this == END

    /**
     * The same message once it carries reactions: a message with reactions ends its cluster
     * (Signal's isEndOfMessageCluster, `currentMessage.reactions.isNotEmpty()`).
     */
    fun endedBy(reactions: Boolean): Cluster = when {
        !reactions -> this
        this == START -> SINGLE
        this == MIDDLE -> END
        else -> this
    }
}

private val COLLAPSED_SPACING = 1.dp
private val DEFAULT_SPACING = 6.dp

internal object Clusters {
    /** Signal's bigRadius and smallRadius. */
    val BIG_RADIUS = 18.dp
    val SMALL_RADIUS = 4.dp

    /** Signal's clusterTimeout, 3 minutes. */
    private const val TIMEOUT_MS = 3 * 60 * 1000L

    /**
     * The cluster position of [current] between the message before it ([previous], older) and the one after it
     * ([next], newer): V2ConversationItemShape.setMessageShape with isSingularMessage, isStartOfMessageCluster,
     * isEndOfMessageCluster and isWithinClusteringTime. Adapted to this app's rows: "same sender" is the user
     * for one's own messages and the same address for the others (a group's members), a message waiting for its
     * download (drawn as a notice) breaks a cluster as Signal's `isUpdate` does, `rich` (the encrypted chat) is
     * Signal's `isSecure` (compared outside a group), and a day is the phone's local day. Reactions are applied
     * by [Cluster.endedBy], they arrive with the chat later than the rows.
     */
    fun of(current: Message, previous: Message?, next: Message?, group: Boolean, zone: ZoneId = ZoneId.systemDefault()): Cluster {
        val start = startsCluster(current, previous, group, zone)
        val end = endsCluster(current, next, zone)
        return when {
            start && end -> Cluster.SINGLE
            start -> Cluster.START
            end -> Cluster.END
            else -> Cluster.MIDDLE
        }
    }

    private fun startsCluster(current: Message, previous: Message?, group: Boolean, zone: ZoneId): Boolean {
        if (previous == null) return true
        val shared = previous.waiting ||
            !sameDay(current, previous, zone) ||
            !withinTime(current, previous) ||
            !sameSender(current, previous)
        return if (group) shared else shared || current.rich != previous.rich
    }

    private fun endsCluster(current: Message, next: Message?, zone: ZoneId): Boolean {
        if (next == null || next.waiting || !sameDay(current, next, zone) || !withinTime(current, next)) return true
        return !sameSender(current, next)
    }

    private fun withinTime(a: Message, b: Message): Boolean = abs(a.date - b.date) <= TIMEOUT_MS

    private fun sameDay(a: Message, b: Message, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a.date).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b.date).atZone(zone).toLocalDate()

    /** Signal's `fromRecipient` compared: the user, or the same address (a number's digits, else the sender's text). */
    private fun sameSender(a: Message, b: Message): Boolean {
        val mineA = a.box != MessageBox.RECEIVED
        val mineB = b.box != MessageBox.RECEIVED
        if (mineA || mineB) return mineA == mineB
        return senderKey(a.address) == senderKey(b.address)
    }

    private fun senderKey(address: String): String = T9.clean(address).ifEmpty { address.trim().lowercase() }

    /**
     * The corners of a bubble by its place in the cluster, as setMessageShape sets them: [BIG_RADIUS] all round
     * alone; on the sender's own side (the end side for the user's messages, the start side for the others')
     * [SMALL_RADIUS] where the neighbours of the cluster touch. Compose's start and end follow the layout
     * direction, where Signal keeps a left to right and a right to left set of corners.
     */
    fun corners(cluster: Cluster, mine: Boolean): RoundedCornerShape {
        val big = BIG_RADIUS
        val small = SMALL_RADIUS
        return when (cluster) {
            Cluster.SINGLE -> RoundedCornerShape(big)
            Cluster.START -> RoundedCornerShape(
                topStart = big, topEnd = big,
                bottomEnd = if (mine) small else big,
                bottomStart = if (mine) big else small
            )
            Cluster.END -> RoundedCornerShape(
                topStart = if (mine) big else small,
                topEnd = if (mine) small else big,
                bottomEnd = big, bottomStart = big
            )
            Cluster.MIDDLE -> {
                val start = if (mine) big else small
                val end = if (mine) small else big
                RoundedCornerShape(topStart = start, topEnd = end, bottomEnd = end, bottomStart = start)
            }
        }
    }
}

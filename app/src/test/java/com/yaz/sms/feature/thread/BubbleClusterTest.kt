package com.yaz.sms.feature.thread

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.yaz.sms.data.sms.Box
import com.yaz.sms.data.sms.Message
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signal Android's own cases for the cluster position (https://github.com/signalapp/Signal-Android,
 * app/src/androidTest/java/org/thoughtcrime/securesms/conversation/v2/items/V2ConversationItemShapeTest.kt:
 * no neighbours is SINGLE, a previous within the timeout END, a next within the timeout START, both MIDDLE,
 * and each neighbour outside the 3 minute timeout SINGLE), then the other paths of V2ConversationItemShape:
 * another sender, another day, an update (here a message waiting for its download), the secure flag outside a
 * group, reactions ending a cluster, the corners and the margins of each position.
 */
class BubbleClusterTest {

    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 10, 19, 12, 0, 0, 0, utc).toInstant().toEpochMilli()
    private val minute = 60_000L

    private fun message(
        at: Long = now,
        box: Box = Box.RECEIVED,
        address: String = "+33612345678",
        rich: Boolean = false,
        waiting: Boolean = false
    ) = Message(
        id = at, threadId = 1, address = address, body = "x", date = at, box = box, read = true,
        delivered = false, subId = 0, rich = rich, waiting = waiting
    )

    private fun shape(current: Message, previous: Message? = null, next: Message? = null, group: Boolean = false) =
        Clusters.of(current, previous, next, group, utc)

    @Test
    fun noPreviousNoNextIsSingle() {
        assertEquals(Cluster.SINGLE, shape(message()))
    }

    @Test
    fun previousWithinTimeoutAndNoNextIsEnd() {
        assertEquals(Cluster.END, shape(message(now), previous = message(now - 2 * minute)))
    }

    @Test
    fun nextWithinTimeoutAndNoPreviousIsStart() {
        assertEquals(Cluster.START, shape(message(now - 2 * minute), next = message(now)))
    }

    @Test
    fun previousAndNextWithinTimeoutIsMiddle() {
        assertEquals(Cluster.MIDDLE, shape(message(now), previous = message(now - 2 * minute), next = message(now + 2 * minute)))
    }

    @Test
    fun previousOutsideTimeoutIsSingle() {
        assertEquals(Cluster.SINGLE, shape(message(now), previous = message(now - 4 * minute)))
    }

    @Test
    fun nextOutsideTimeoutIsSingle() {
        assertEquals(Cluster.SINGLE, shape(message(now - 4 * minute), next = message(now)))
    }

    @Test
    fun previousAndNextOutsideTimeoutIsSingle() {
        assertEquals(Cluster.SINGLE, shape(message(now), previous = message(now - 4 * minute), next = message(now + 4 * minute)))
    }

    @Test
    fun exactlyThreeMinutesStillClusters() {
        assertEquals(Cluster.END, shape(message(now), previous = message(now - 3 * minute)))
        assertEquals(Cluster.SINGLE, shape(message(now), previous = message(now - 3 * minute - 1)))
    }

    @Test
    fun anotherSenderBreaksTheCluster() {
        val me = message(now, Box.SENT)
        val them = message(now - minute, Box.RECEIVED)
        assertEquals(Cluster.SINGLE, shape(me, previous = them))
        assertEquals(Cluster.SINGLE, shape(them, next = me))
    }

    @Test
    fun sentAndSendingAndFailedAreTheSameSender() {
        val sent = message(now - minute, Box.SENT)
        assertEquals(Cluster.END, shape(message(now, Box.SENDING), previous = sent))
        assertEquals(Cluster.END, shape(message(now, Box.FAILED), previous = sent))
    }

    @Test
    fun inAGroupTheSameAddressIsTheSameSender() {
        val a1 = message(now - minute, address = "+33 6 12 34 56 78")
        val a2 = message(now, address = "+33612345678")
        val b = message(now, address = "+33699999999")
        assertEquals(Cluster.END, shape(a2, previous = a1, group = true))
        assertEquals(Cluster.SINGLE, shape(b, previous = a1, group = true))
        assertEquals(Cluster.SINGLE, shape(a1, next = b, group = true))
    }

    @Test
    fun textSendersAreComparedByTheirText() {
        val one = message(now - minute, address = "AMAZON")
        assertEquals(Cluster.END, shape(message(now, address = " amazon "), previous = one))
        assertEquals(Cluster.SINGLE, shape(message(now, address = "ORANGE"), previous = one))
    }

    @Test
    fun anotherDayBreaksTheCluster() {
        val late = ZonedDateTime.of(2026, 10, 19, 23, 59, 0, 0, utc).toInstant().toEpochMilli()
        val early = late + 2 * minute
        assertEquals(Cluster.SINGLE, shape(message(early), previous = message(late)))
        assertEquals(Cluster.SINGLE, shape(message(late), next = message(early)))
    }

    @Test
    fun aMessageWaitingForItsDownloadBreaksTheCluster() {
        val waiting = message(now - minute, waiting = true)
        assertEquals(Cluster.SINGLE, shape(message(now), previous = waiting))
        assertEquals(Cluster.SINGLE, shape(message(now - 2 * minute), next = waiting))
    }

    @Test
    fun outsideAGroupTheChatAndSmsAreNotOneCluster() {
        val sms = message(now - minute, rich = false)
        val chat = message(now, rich = true)
        assertEquals(Cluster.SINGLE, shape(chat, previous = sms, group = false))
        assertEquals(Cluster.END, shape(chat, previous = sms, group = true))
    }

    @Test
    fun reactionsEndTheCluster() {
        assertEquals(Cluster.SINGLE, Cluster.START.endedBy(true))
        assertEquals(Cluster.END, Cluster.MIDDLE.endedBy(true))
        assertEquals(Cluster.END, Cluster.END.endedBy(true))
        assertEquals(Cluster.SINGLE, Cluster.SINGLE.endedBy(true))
        Cluster.entries.forEach { assertEquals(it, it.endedBy(false)) }
    }

    @Test
    fun startingAndEndingShapes() {
        assertTrue(Cluster.SINGLE.isStarting && Cluster.SINGLE.isEnding)
        assertTrue(Cluster.START.isStarting && !Cluster.START.isEnding)
        assertTrue(!Cluster.END.isStarting && Cluster.END.isEnding)
        assertFalse(Cluster.MIDDLE.isStarting || Cluster.MIDDLE.isEnding)
    }

    @Test
    fun marginsAreSignalsCollapsedAndDefaultSpacing() {
        assertEquals(6.dp, Cluster.SINGLE.top); assertEquals(6.dp, Cluster.SINGLE.bottom)
        assertEquals(6.dp, Cluster.START.top); assertEquals(1.dp, Cluster.START.bottom)
        assertEquals(1.dp, Cluster.END.top); assertEquals(6.dp, Cluster.END.bottom)
        assertEquals(1.dp, Cluster.MIDDLE.top); assertEquals(1.dp, Cluster.MIDDLE.bottom)
    }

    @Test
    fun cornersAlone() {
        assertEquals(RoundedCornerShape(18.dp), Clusters.corners(Cluster.SINGLE, true))
        assertEquals(RoundedCornerShape(18.dp), Clusters.corners(Cluster.SINGLE, false))
    }

    @Test
    fun cornersOfTheUsersOwnCluster() {
        val big = 18.dp
        val small = 4.dp
        assertEquals(RoundedCornerShape(topStart = big, topEnd = big, bottomEnd = small, bottomStart = big), Clusters.corners(Cluster.START, true))
        assertEquals(RoundedCornerShape(topStart = big, topEnd = small, bottomEnd = big, bottomStart = big), Clusters.corners(Cluster.END, true))
        assertEquals(RoundedCornerShape(topStart = big, topEnd = small, bottomEnd = small, bottomStart = big), Clusters.corners(Cluster.MIDDLE, true))
    }

    @Test
    fun cornersOfAnotherSendersCluster() {
        val big = 18.dp
        val small = 4.dp
        assertEquals(RoundedCornerShape(topStart = big, topEnd = big, bottomEnd = big, bottomStart = small), Clusters.corners(Cluster.START, false))
        assertEquals(RoundedCornerShape(topStart = small, topEnd = big, bottomEnd = big, bottomStart = big), Clusters.corners(Cluster.END, false))
        assertEquals(RoundedCornerShape(topStart = small, topEnd = big, bottomEnd = big, bottomStart = small), Clusters.corners(Cluster.MIDDLE, false))
    }
}

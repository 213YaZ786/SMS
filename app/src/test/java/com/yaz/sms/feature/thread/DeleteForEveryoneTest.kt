package com.yaz.sms.feature.thread

import com.yaz.sms.data.sms.Box
import com.yaz.sms.data.sms.Message
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which messages Delete offers "Delete for everyone" on (requests.md point 8): only a chat message of one's own. The
 * engine call (RichChat.deleteForAll, delete_messages_for_all) sits behind that answer, so an SMS, an MMS or a received
 * message never reaches it. Cases for isValidRemoteDeleteSend, the question Signal's DeleteDialog asks of its records
 * (isNoteToSelfDelete, `all` of the messageRecords outgoing):
 * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/util/DeleteDialog.kt#isNoteToSelfDelete
 */
class DeleteForEveryoneTest {

    private fun messageRecord(box: Box, mms: Boolean = false, rich: Boolean = false) = Message(
        id = 1, threadId = 1, address = "+33612345678", body = "Hello", date = 0L, box = box,
        read = true, delivered = false, subId = -1, mms = mms, rich = rich
    )

    @Test
    fun `a chat message of one's own offers for everyone`() {
        assertTrue(isValidRemoteDeleteSend(setOf(messageRecord(Box.SENT, rich = true))))
    }

    @Test
    fun `a received chat message gets Delete for me only`() {
        assertFalse(isValidRemoteDeleteSend(setOf(messageRecord(Box.RECEIVED, rich = true))))
    }

    @Test
    fun `an SMS of one's own gets Delete for me only`() {
        assertFalse(isValidRemoteDeleteSend(setOf(messageRecord(Box.SENT))))
    }

    @Test
    fun `an MMS of one's own gets Delete for me only`() {
        assertFalse(isValidRemoteDeleteSend(setOf(messageRecord(Box.SENT, mms = true))))
    }
}

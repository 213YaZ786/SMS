package com.yaz.sms.core.mms

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/**
 * The Files app's door, tried as the other doors would be: a ContentProvider built by Robolectric
 * (https://robolectric.org/javadoc/latest/org/robolectric/Robolectric.html, setupContentProvider), its
 * call() reached with and without the signature permission (ShadowBinder gives a caller other than the
 * process itself, https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowBinder.html),
 * the lock from ShadowKeyguardManager (https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowKeyguardManager.html).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class SavedDoorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val door = Robolectric.setupContentProvider(SavedDoor::class.java, context.packageName + ".saved")
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private fun asked(vararg ids: Long) = Bundle().apply { putLongArray("ids", ids) }

    private fun asACaller(granted: Boolean) {
        ShadowBinder.setCallingPid(android.os.Process.myPid() + 1)
        ShadowBinder.setCallingUid(android.os.Process.myUid() + 1)
        if (granted) shadowOf(context as Application).grantPermissions(SavedDoor.permissionOf(context))
    }

    @Test
    fun `a caller without the permission is refused`() {
        asACaller(granted = false)
        try {
            door.call("origins", null, asked(1, 2))
            fail("the door answered a caller without the permission")
        } catch (e: SecurityException) {
            // Refused, as PeopleDoor and InviteProvider refuse.
        }
    }

    @Test
    fun `the caller is the process itself, which holds no permission through call, is refused too`() {
        try {
            door.call("origins", null, asked(1))
            fail("the door answered")
        } catch (e: SecurityException) {
        }
    }

    @Test
    fun `a caller with the permission is answered while unlocked and not while locked`() {
        asACaller(granted = true)
        shadowOf(keyguard).setIsDeviceLocked(true)
        assertNull(door.call("origins", null, asked(1)))
        shadowOf(keyguard).setIsDeviceLocked(false)
        // The door finds the phone book in Koin, started here with the one module it needs
        // (https://insert-koin.io/docs/reference/koin-core/start-koin/, startKoin and stopKoin).
        org.koin.core.context.startKoin {
            modules(org.koin.dsl.module {
                single { com.yaz.sms.data.contacts.PhoneBook(context, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())) }
            })
        }
        try {
            val answer = door.call("origins", null, asked(1))
            assertNotNull(answer)
            assertEquals(0, answer!!.getLongArray("ids")!!.size)
            // Another method is no method of this door.
            assertNull(door.call("other", null, asked(1)))
        } finally {
            org.koin.core.context.stopKoin()
        }
    }

    @Test
    fun `the ids cap keeps the first four hundred`() {
        val many = LongArray(1_000_000) { it + 1L }
        val ids = SavedDoor.idsOf(Bundle().apply { putLongArray("ids", many) })
        assertEquals(SavedDoor.MAX_IDS, ids.size)
        assertEquals(1L, ids.first())
        assertEquals(400L, ids.last())
        assertEquals(400, SavedDoor.MAX_IDS)
    }

    @Test
    fun `ids that are no ids are ignored`() {
        assertEquals(listOf(5L, 7L), SavedDoor.idsOf(asked(0, -3, 5, 5, 7)))
        assertTrue(SavedDoor.idsOf(null).isEmpty())
        assertTrue(SavedDoor.idsOf(Bundle()).isEmpty())
        assertTrue(SavedDoor.idsOf(Bundle().apply { putString("ids", "1,2") }).isEmpty())
        assertTrue(SavedDoor.idsOf(Bundle().apply { putIntArray("ids", intArrayOf(1, 2)) }).isEmpty())
        assertTrue(SavedDoor.idsOf(Bundle().apply { putLong("ids", 4L) }).isEmpty())
        assertTrue(SavedDoor.idsOf(asked()).isEmpty())
    }

    @Test
    fun `the answer holds the four parallel arrays and nothing else`() {
        val answer = SavedDoor.answer(
            listOf(SavedEntry(10, 4, "mms/77", "+33612345678"), SavedEntry(11, 5, "mms/78", "Bob")),
            { sender -> "name of $sender" }
        )
        assertEquals(setOf("ids", "names", "threads", "messages"), answer.keySet())
        assertArrayEquals(longArrayOf(10, 11), answer.getLongArray("ids"))
        assertArrayEquals(arrayOf("name of +33612345678", "name of Bob"), answer.getStringArray("names"))
        assertArrayEquals(longArrayOf(4, 5), answer.getLongArray("threads"))
        assertArrayEquals(arrayOf("mms/77", "mms/78"), answer.getStringArray("messages"))
    }

    @Test
    fun `an empty question gets empty arrays`() {
        val answer = SavedDoor.answer(emptyList()) { it }
        assertEquals(0, answer.getLongArray("ids")!!.size)
        assertEquals(0, answer.getStringArray("names")!!.size)
    }

    @Test
    fun `a sender without a contact is named by its number and never by nothing`() {
        // No contact is found, the number is written the local way.
        val name = SavedDoor.nameOf(context, emptyList())
        assertTrue(name("+33612345678").isNotBlank())
        assertTrue(name("12345").isNotBlank())
    }

    // The contacts as PhoneBook gives them and PhoneIndex finds them, the way ThreadScreen builds its title
    // (PhoneIndex(contacts).find(T9.clean(to))); the case is the one found live on the emulator, two contacts
    // fitting one number by its last nine digits (https://developer.android.com/reference/android/provider/ContactsContract.PhoneLookup).
    private fun contact(id: Long, name: String, number: String) =
        com.yaz.sms.core.dial.PhoneEntry(id, name, number, com.yaz.sms.core.dial.T9.clean(number), null, false)

    private val carmen = contact(1, "Carmen García López", "+34 612 345 678")
    private val fatima = contact(2, "Fatima Zahra El Idrissi", "+212 6 12 34 56 78")

    @Test
    fun `a number fitting two contacts by its last digits is named as the conversation title names it`() {
        val sender = "+33612345678"
        for (book in listOf(listOf(carmen, fatima), listOf(fatima, carmen))) {
            val title = com.yaz.sms.data.contacts.PhoneIndex(book).find(com.yaz.sms.core.dial.T9.clean(sender))!!.name
            assertEquals(title, SavedDoor.nameOf(context, book)(sender))
        }
        // The book's order decides, as the title's does: the first of the two.
        assertEquals("Carmen García López", SavedDoor.nameOf(context, listOf(carmen, fatima))(sender))
        assertEquals("Fatima Zahra El Idrissi", SavedDoor.nameOf(context, listOf(fatima, carmen))(sender))
    }

    @Test
    fun `an exact number wins over a fit by the last digits, in any order`() {
        for (book in listOf(listOf(carmen, fatima), listOf(fatima, carmen))) {
            val name = SavedDoor.nameOf(context, book)
            assertEquals("Fatima Zahra El Idrissi", name("+212612345678"))
            assertEquals("Carmen García López", name("+34612345678"))
        }
    }

    @Test
    fun `a number no contact fits is written as a number`() {
        val name = SavedDoor.nameOf(context, listOf(carmen, fatima))("+33699999999")
        assertTrue(name.any(Char::isDigit))
    }
}

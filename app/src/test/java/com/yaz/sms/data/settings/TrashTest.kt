package com.yaz.sms.data.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The cases follow the rules of the sources they come from, one for each: the 30 days of AOSP
 * MediaProvider (FileUtils.DEFAULT_DURATION_TRASHED) and Aves (TrashMixin.binKeepDuration), an item
 * expired once the keep duration has passed and the days left rounded to the nearest day (Aves
 * isExpiredTrash and trashDaysLeft, https://github.com/deckerst/aves/blob/develop/lib/model/entry/extensions/props.dart),
 * what could not be deleted staying (Aves deleteExpiredTrash), in the style of the app's other
 * tests (StarsTest, AtomicWriteTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class TrashTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun fresh(): TrashStore {
        File(context().filesDir, "trash.json").delete()
        return TrashStore(context())
    }

    @Test
    fun `a conversation is kept thirty days`() {
        val t = Trashed(at = now, cutoff = now - day)
        assertEquals(30 * day, Trash.KEEP_MS)
        assertFalse(Trash.isExpired(t, now + 30 * day))
        assertTrue(Trash.isExpired(t, now + 30 * day + 1))
        assertFalse(Trash.isExpired(t, now))
    }

    @Test
    fun `the days left are rounded to the nearest day and never below zero`() {
        val t = Trashed(at = now, cutoff = 0)
        assertEquals(30, Trash.daysLeft(t, now))
        assertEquals(30, Trash.daysLeft(t, now + day / 2 - 1))
        assertEquals(29, Trash.daysLeft(t, now + day / 2 + 1))
        assertEquals(1, Trash.daysLeft(t, now + 29 * day))
        assertEquals(0, Trash.daysLeft(t, now + 30 * day))
        assertEquals(0, Trash.daysLeft(t, now + 90 * day))
    }

    @Test
    fun `the words under a conversation`() {
        assertEquals("30 days left", Trash.daysLabel(30))
        assertEquals("2 days left", Trash.daysLabel(2))
        assertEquals("1 day left", Trash.daysLabel(1))
        assertEquals("Less than a day left", Trash.daysLabel(0))
        assertEquals("Less than a day left", Trash.daysLabel(-3))
    }

    @Test
    fun `conversations are trashed from now and a second time starts the days again`() {
        val first = Trash.add(emptyMap(), mapOf(1L to 500L, 2L to 600L), now)
        assertEquals(mapOf(1L to Trashed(now, 500L), 2L to Trashed(now, 600L)), first)
        val again = Trash.add(first, mapOf(1L to 900L), now + 5 * day)
        assertEquals(Trashed(now + 5 * day, 900L), again[1L])
        assertEquals(Trashed(now, 600L), again[2L])
    }

    @Test
    fun `restoring takes them out and an unknown thread changes nothing`() {
        val items = Trash.add(emptyMap(), mapOf(1L to 5L, 2L to 6L), now)
        assertEquals(setOf(2L), Trash.remove(items, listOf(1L, 99L)).keys)
        assertEquals(emptyMap<Long, Trashed>(), Trash.remove(items, items.keys))
        assertEquals(items, Trash.remove(items, emptyList()))
    }

    @Test
    fun `only the expired are swept`() {
        val items = mapOf(1L to Trashed(now - 31 * day, 5L), 2L to Trashed(now - 29 * day, 6L), 3L to Trashed(now, 7L))
        assertEquals(setOf(1L), Trash.expired(items, now).keys)
        assertEquals(emptyMap<Long, Trashed>(), Trash.expired(emptyMap(), now))
    }

    @Test
    fun `the sweep is due once a day`() {
        assertTrue(Trash.dueForSweep(0, now))
        assertFalse(Trash.dueForSweep(now - day + 1, now))
        assertTrue(Trash.dueForSweep(now - day, now))
        // A clock set back.
        assertTrue(Trash.dueForSweep(now + day, now))
    }

    @Test
    fun `a conversation the store no longer holds leaves the trash`() {
        val items = mapOf(1L to Trashed(now, 5L), 2L to Trashed(now, 6L))
        assertEquals(setOf(2L), Trash.alive(items, setOf(2L, 3L)).keys)
    }

    @Test
    fun `the set stops growing at its cap but a thread already in can be trashed again`() {
        val full = (0L until Trash.MAX).associateWith { Trashed(now, 1L) }
        assertEquals(full, Trash.add(full, mapOf(Trash.MAX.toLong() to 1L), now))
        assertEquals(Trashed(now + 1, 9L), Trash.add(full, mapOf(0L to 9L), now + 1)[0L])
        assertEquals(Trash.MAX, Trash.add(full, mapOf(0L to 9L), now + 1).size)
    }

    @Test
    fun `a file written is read back`() {
        val file = TrashFile(mapOf(4L to Trashed(now - day, now - 2 * day), 9L to Trashed(now, now)), swept = now - day)
        val text = json.encodeToString(TrashFile.serializer(), file)
        assertEquals(file, Trash.parse(text, json, now))
    }

    @Test
    fun `a hostile file keeps what is valid and clamps the rest`() {
        val text = """{"items":{
            "-1":{"at":5,"cutoff":5},
            "2":{"at":0,"cutoff":5},
            "3":{"at":5,"cutoff":-1},
            "4":{"at":${Long.MAX_VALUE},"cutoff":${Long.MAX_VALUE}},
            "5":{"at":${now + 400 * day},"cutoff":7},
            "6":{"at":${now - day},"cutoff":${now - 2 * day}}},
            "swept":${Long.MAX_VALUE},"later":true}"""
        val read = Trash.parse(text, json, now)
        // A date that is no date is dropped; a day trashed in the future is today; so is a sweep that has not happened yet.
        assertEquals(setOf(5L, 6L), read.items.keys)
        assertEquals(Trashed(now, 7L), read.items[5L])
        assertEquals(now, read.swept)
    }

    @Test
    fun `a file cut short, empty, of another shape or with a bad key gives nothing`() {
        assertEquals(TrashFile(), Trash.parse("""{"items":{"1":{"at":""", json, now))
        assertEquals(TrashFile(), Trash.parse("", json, now))
        assertEquals(TrashFile(), Trash.parse("[1,2]", json, now))
        assertEquals(TrashFile(), Trash.parse("""{"items":{"x":{"at":1,"cutoff":1}}}""", json, now))
        assertEquals(TrashFile(), Trash.parse("""{"items":{"1":"2"}}""", json, now))
    }

    @Test
    fun `a huge file is capped`() {
        val items = (1..Trash.MAX + 50).joinToString(",") { "\"$it\":{\"at\":$now,\"cutoff\":1}" }
        assertEquals(Trash.MAX, Trash.parse("""{"items":{$items}}""", json, now).items.size)
    }

    @Test
    fun `the store keeps the trash across a restart`() {
        val store = fresh()
        store.trash(mapOf(7L to 100L), System.currentTimeMillis())
        assertEquals(setOf(7L), TrashStore(context()).items.value.keys)
        store.restore(listOf(7L))
        assertTrue(TrashStore(context()).items.value.isEmpty())
    }

    @Test
    fun `the sweep deletes the expired up to their cutoff and is not run again the same day`() = runBlocking {
        val store = fresh()
        val real = System.currentTimeMillis()
        store.trash(mapOf(1L to 111L), real - 31 * day)
        store.trash(mapOf(2L to 222L), real)
        val asked = ArrayList<Pair<Long, Long>>()
        val gone = store.sweep(real) { thread, cutoff -> asked += thread to cutoff; true }
        assertEquals(listOf(1L), gone)
        assertEquals(listOf(1L to 111L), asked)
        assertEquals(setOf(2L), store.items.value.keys)
        // Same day: nothing is looked at.
        store.trash(mapOf(3L to 333L), real - 40 * day)
        assertEquals(emptyList<Long>(), store.sweep(real + 1000) { _, _ -> error("not asked") })
        assertEquals(setOf(2L, 3L), store.items.value.keys)
        // The next day it is, and survives a restart.
        assertEquals(listOf(3L), TrashStore(context()).sweep(real + day) { _, _ -> true })
    }

    @Test
    fun `a conversation the store refused to delete stays and the sweep is tried again`() = runBlocking {
        val store = fresh()
        val real = System.currentTimeMillis()
        store.trash(mapOf(1L to 10L, 2L to 20L), real - 40 * day)
        val gone = store.sweep(real) { thread, _ -> thread == 2L }
        assertEquals(listOf(2L), gone)
        assertEquals(setOf(1L), store.items.value.keys)
        // Not counted as a sweep: the next opening tries again at once.
        assertEquals(listOf(1L), store.sweep(real + 1000) { _, _ -> true })
        assertTrue(store.items.value.isEmpty())
    }
}

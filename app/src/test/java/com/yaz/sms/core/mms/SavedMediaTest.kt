package com.yaz.sms.core.mms

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The list of saved media, tried as the app's other stores are (AtomicWriteTest, TrashTest): JUnit's
 * TemporaryFolder rule for the file (https://junit.org/junit4/javadoc/latest/org/junit/rules/TemporaryFolder.html),
 * the rules of the list pure, then the file through a restart. The cases follow the sources of the rules: a capped
 * set whose oldest go first and a file filtered when read (Fossify Messages' Config blocked keywords, KnownStore),
 * rows gone from the media store dropped (Aves' deleteExpiredTrash keeps what could not be dealt with, MediaProvider
 * owns its rows).
 */
class SavedMediaTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.root, "saved-media.json")
    private fun entry(id: Long, thread: Long = 3, uid: String = "mms/$id", sender: String = "+33612345678") = SavedEntry(id, thread, uid, sender)
    private val allThere: (List<Long>) -> Set<Long> = { it.toSet() }

    @Test
    fun `an entry is added and given back by its row id`() {
        val saved = SavedMedia(file())
        saved.add(entry(10, thread = 4, uid = "mms/77"))
        assertEquals(listOf(entry(10, thread = 4, uid = "mms/77")), saved.origins(listOf(10), allThere))
        assertEquals(emptyList<SavedEntry>(), saved.origins(listOf(11), allThere))
    }

    @Test
    fun `a row saved again keeps its last entry only`() {
        val saved = SavedMedia(file())
        saved.add(entry(10, uid = "mms/1"))
        saved.add(entry(10, uid = "mms/2"))
        assertEquals(listOf("mms/2"), saved.all().map { it.uid })
    }

    @Test
    fun `answers follow the order asked and name a row once`() {
        val saved = SavedMedia(file())
        saved.add(entry(1))
        saved.add(entry(2))
        saved.add(entry(3))
        assertEquals(listOf(3L, 1L), saved.origins(listOf(3, 9, 1, 3), allThere).map { it.id })
    }

    @Test
    fun `an entry that is no entry is not kept`() {
        val saved = SavedMedia(file())
        saved.add(entry(0))
        saved.add(entry(-5))
        saved.add(entry(1, thread = -1))
        saved.add(entry(2, uid = "mms/"))
        saved.add(entry(3, uid = "contacts/4"))
        saved.add(entry(4, uid = "sms/" + "9".repeat(40)))
        saved.add(entry(5, sender = " "))
        saved.add(entry(6, sender = "9".repeat(500)))
        assertTrue(saved.all().isEmpty())
        assertFalse(file().exists())
    }

    @Test
    fun `at most five thousand are kept and the oldest go`() {
        val saved = SavedMedia(file())
        for (i in 1L..(SavedEntries.MAX + 20L)) saved.add(entry(i))
        val all = saved.all()
        assertEquals(SavedEntries.MAX, all.size)
        assertEquals(21L, all.first().id)
        assertEquals(SavedEntries.MAX + 20L, all.last().id)
        // And the file holds the same after a restart.
        assertEquals(all, SavedMedia(file()).all())
    }

    @Test
    fun `a file longer than the cap is cut to it when read`() {
        val long = (1L..(SavedEntries.MAX + 50L)).map { entry(it) }
        file().writeText(kotlinx.serialization.json.Json.encodeToString(SavedFile.serializer(), SavedFile(long)))
        val all = SavedMedia(file()).all()
        assertEquals(SavedEntries.MAX, all.size)
        assertEquals(51L, all.first().id)
    }

    @Test
    fun `rows gone from the media store are dropped when read, for good`() {
        val saved = SavedMedia(file())
        saved.add(entry(1))
        saved.add(entry(2))
        saved.add(entry(3))
        val asked = ArrayList<List<Long>>()
        val answer = saved.origins(listOf(1, 2, 3)) { ids -> asked += ids; setOf(1L, 3L) }
        assertEquals(listOf(1L, 3L), answer.map { it.id })
        assertEquals(listOf(listOf(1L, 2L, 3L)), asked)
        assertEquals(listOf(1L, 3L), saved.all().map { it.id })
        // After a restart the dropped one is not back.
        assertEquals(listOf(1L, 3L), SavedMedia(file()).all().map { it.id })
    }

    @Test
    fun `the media store is asked only about the rows kept`() {
        val saved = SavedMedia(file())
        saved.add(entry(1))
        var asked = false
        assertTrue(saved.origins(listOf(7, 8)) { asked = true; emptySet() }.isEmpty())
        assertFalse(asked)
        assertEquals(1, saved.all().size)
    }

    @Test
    fun `entries come back after a restart and the write leaves no temporary file`() {
        SavedMedia(file()).add(entry(5, thread = 2, uid = "sms/9", sender = "Marie"))
        assertEquals(listOf(entry(5, thread = 2, uid = "sms/9", sender = "Marie")), SavedMedia(file()).all())
        assertFalse(File(folder.root, "saved-media.json.tmp").exists())
    }

    @Test
    fun `a broken or empty file gives an empty list and a new entry still works`() {
        file().writeText("{ not json")
        val saved = SavedMedia(file())
        assertTrue(saved.all().isEmpty())
        saved.add(entry(1))
        assertEquals(1, SavedMedia(file()).all().size)
        file().writeText("")
        assertTrue(SavedMedia(file()).all().isEmpty())
    }

    @Test
    fun `invalid entries in a file are dropped when read`() {
        file().writeText(
            """{"items":[{"id":1,"thread":2,"uid":"mms/3","sender":"A"},{"id":0,"thread":2,"uid":"mms/3","sender":"A"},""" +
                """{"id":4,"thread":2,"uid":"../x","sender":"A"},{"id":5,"thread":2,"uid":"sms/6","sender":"B","extra":1}]}"""
        )
        assertEquals(listOf(1L, 5L), SavedMedia(file()).all().map { it.id })
    }

    @Test
    fun `a uid is sms or mms and a number`() {
        assertTrue(SavedEntries.isUid("sms/7"))
        assertTrue(SavedEntries.isUid("mms/45"))
        assertFalse(SavedEntries.isUid(null))
        assertFalse(SavedEntries.isUid(""))
        assertFalse(SavedEntries.isUid("mms/-1"))
        assertFalse(SavedEntries.isUid("mms/4 "))
        assertFalse(SavedEntries.isUid("mms/4/5"))
        assertFalse(SavedEntries.isUid("MMS/4"))
    }
}

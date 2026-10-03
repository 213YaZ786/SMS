package com.sms.app.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionInfoTest {

    @Test
    fun readsTheEnginesWords() {
        val info = EncryptionInfo.parse(
            """
            Messages are end-to-end encrypted.
            Fingerprints:

            Me (4rg5pl2mu@nine.testrun.org):
            8DB2 B276 C2F5 8477 603B
            D2BF 0846 1FA6 6EDD FFE9

            skvlfcxeb@d.gaufr.es (skvlfcxeb@d.gaufr.es):
            560D 1DD7 6847 188F 5822
            F289 71D3 7278 6A29 83AF

            Relays:
            skvlfcxeb@d.gaufr.es
            1xkeooiyh@nine.testrun.org
            """.trimIndent()
        )
        assertTrue(info.encrypted)
        assertEquals("8DB2 B276 C2F5 8477 603B D2BF 0846 1FA6 6EDD FFE9", info.mine)
        assertEquals("560D 1DD7 6847 188F 5822 F289 71D3 7278 6A29 83AF", info.theirs)
        assertEquals(listOf("d.gaufr.es", "nine.testrun.org"), info.relays)
    }
}

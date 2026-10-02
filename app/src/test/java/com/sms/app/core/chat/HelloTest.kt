package com.sms.app.core.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelloTest {

    private val link = "https://i.delta.chat/#20FDD4AD104E8AEF6762825D0E2746391F251B06&v=3&i=mQHIdcgwipgCwG3bFetZeRMP&s=ahK71WbHo9pbqMAsSl0Hae5J&a=xpyrviujh%40nine.testrun.org&n=Alice"
    private val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test
    fun anInviteFitsOneDataSmsAndComesBackWhole() {
        val hello = Hello.fromLink(link, nonce)!!
        val bytes = hello.encode()
        assertTrue("${bytes.size} bytes", bytes.size <= 120)
        val back = Hello.decode(bytes)!!
        assertEquals(hello, back)
        assertEquals("xpyrviujh@nine.testrun.org", back.address)
        assertEquals(
            "https://i.delta.chat/#20FDD4AD104E8AEF6762825D0E2746391F251B06&v=3&i=mQHIdcgwipgCwG3bFetZeRMP&s=ahK71WbHo9pbqMAsSl0Hae5J&a=xpyrviujh%40nine.testrun.org",
            back.link()
        )
    }

    @Test
    fun strangeBytesAreRefused() {
        assertNull(Hello.decode(byteArrayOf(1, 2, 3)))
        assertNull(Hello.decode("Hello world, this is not ours at all, really".toByteArray()))
    }

    @Test
    fun theProofCarriesTheNonce() {
        val text = Hello.proofText(nonce)
        assertTrue(Hello.isProof(text))
        assertArrayEquals(nonce, Hello.proofOf(text))
        assertNull(Hello.proofOf("hello"))
    }
}

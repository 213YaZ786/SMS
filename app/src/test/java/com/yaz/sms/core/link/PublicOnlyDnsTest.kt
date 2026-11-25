package com.yaz.sms.core.link

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Stash's PublicOnlyDns rules, one case each (the ranges Metrolist's PublicArtworkDns also refuses),
 * with a fake lookup standing for the network:
 * https://raw.githubusercontent.com/rawnaldclark/Stash/master/core/addons/src/main/kotlin/com/stash/core/addons/PublicOnlyDns.kt#PublicOnlyDns
 */
class PublicOnlyDnsTest {

    private fun address(text: String) = InetAddress.getByName(text)

    private fun dnsOf(vararg answers: String) = PublicOnlyDns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = answers.map(::address)
    })

    @Test
    fun `private and special addresses are refused`() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.1.1", "100.64.0.1", "::1", "fc00::1", "::ffff:10.0.0.1").forEach {
            assertFalse(it, PublicOnlyDns.isPublic(address(it)))
        }
    }

    @Test
    fun `public addresses are kept`() {
        listOf("93.184.216.34", "2606:2800:220:1::1").forEach {
            assertTrue(it, PublicOnlyDns.isPublic(address(it)))
        }
    }

    @Test
    fun `a name with public and private answers keeps the public ones`() {
        val kept = dnsOf("10.0.0.1", "93.184.216.34", "::1").lookup("example.test")
        assertEquals(listOf(address("93.184.216.34")), kept)
    }

    @Test
    fun `a name whose answers are all private throws`() {
        try {
            dnsOf("10.0.0.1", "127.0.0.1", "fc00::1").lookup("rebind.test")
            fail("a private answer was kept")
        } catch (refused: UnknownHostException) {
            assertTrue(refused is NoPublicAddressException)
        }
    }

    @Test
    fun `a host written as an address is judged as it is`() {
        assertFalse(PublicOnlyDns.isPublicHost("127.0.0.1"))
        assertFalse(PublicOnlyDns.isPublicHost("[::1]"))
        assertTrue(PublicOnlyDns.isPublicHost("93.184.216.34"))
        assertTrue(PublicOnlyDns.isPublicHost("example.com"))
    }
}

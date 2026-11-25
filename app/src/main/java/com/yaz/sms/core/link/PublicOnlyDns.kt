package com.yaz.sms.core.link

import okhttp3.Dns
import okhttp3.Interceptor
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

/**
 * A name that resolves to a private, loopback, link-local or CGNAT address is refused: a link in a
 * message could aim the phone at its own network (a router page, a local service). OkHttp connects
 * only to the addresses a lookup returns, so a filtered answer cannot be bypassed later; a name
 * that rebinds between two lookups is covered since the connection uses this very answer.
 *
 * Private answers are dropped and the public ones kept; a name with only private answers fails as
 * unknown, so the preview is a plain network failure.
 *
 * Ported from Stash (GPL-3.0):
 * https://raw.githubusercontent.com/rawnaldclark/Stash/master/core/addons/src/main/kotlin/com/stash/core/addons/PublicOnlyDns.kt#PublicOnlyDns
 * and its address ranges checked against Metrolist (GPL-3.0):
 * https://raw.githubusercontent.com/MetrolistGroup/Metrolist/main/shared/src/jvmSharedMain/kotlin/com/metrolist/shared/network/PublicArtworkDns.kt#isPublicArtworkAddress
 * (same ranges: 0/8, 10/8, 127/8, 100.64/10 CGNAT, 169.254/16, 172.16/12, 192.168/16, 192.0.0/24,
 * 192.0.2/24, 198.18/15, 198.51.100/24, 203.0.113/24, 224/4 and above, fc00::/7, 2001:db8::/32).
 *
 * Not ported: throughProxies and its proxyHosts (the app uses no proxy), the allowLoopback
 * parameter (a debug build's local add-on; none here), the log line of lookupPublic (nothing
 * about a host is logged), Metrolist's artwork host allowance and its refusal of a whole answer
 * with one private address (Stash's keep-the-public ones is taken).
 */
class PublicOnlyDns(private val delegate: Dns = Dns.SYSTEM) : Dns {

    /** What OkHttp asks: the name's public addresses only. */
    override fun lookup(hostname: String): List<InetAddress> = lookupPublic(hostname)

    /** [hostname]'s public addresses: the filter itself. */
    fun lookupPublic(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        val kept = all.filter { isPublic(it) }
        if (kept.isEmpty()) throw NoPublicAddressException()
        return kept
    }

    companion object {
        /**
         * True for an address on the public internet. False for: unspecified, loopback,
         * link-local, RFC 1918 and IPv6 site-local, CGNAT (100.64/10), ULA (fc00::/7), multicast,
         * `0.0.0.0/8`, the IETF block 192.0.0.0/24, benchmarking 198.18/15, the documentation
         * blocks (192.0.2/24, 198.51.100/24, 203.0.113/24, 2001:db8::/32), Teredo (2001::/32),
         * reserved 240/4 and broadcast; and the IPv4 inside an IPv4-mapped, NAT64 (64:ff9b::/96)
         * or 6to4 (2002::/16) IPv6 address is checked as itself.
         */
        fun isPublic(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) {
                return false
            }
            val b = a.address
            return when (a) {
                is Inet4Address -> isPublicV4(b)
                is Inet6Address -> isPublicV6(b)
                else -> false
            }
        }

        /**
         * A host written into a URL as an address (OkHttp never looks it up, so the DNS filter does not
         * see it): public or not as [isPublic] says; a name is true here, judged when it is resolved.
         * Stash's AddonValidator.isPublicHost, named in the doc of its PublicOnlyDns:
         * https://raw.githubusercontent.com/rawnaldclark/Stash/master/core/addons/src/main/kotlin/com/stash/core/addons/PublicOnlyDns.kt#isPublic
         */
        fun isPublicHost(host: String): Boolean {
            val literal = host.contains(':') || host.all { it.isDigit() || it == '.' }
            return !literal || isPublic(InetAddress.getByName(host.removeSurrounding("[", "]")))
        }

        private fun isPublicV4(b: ByteArray): Boolean {
            val o0 = b[0].toInt() and 0xff
            val o1 = b[1].toInt() and 0xff
            val o2 = b[2].toInt() and 0xff
            return when {
                o0 == 0 || o0 == 10 || o0 == 127 -> false
                o0 == 100 && o1 in 64..127 -> false // CGNAT, RFC 6598
                o0 == 169 && o1 == 254 -> false
                o0 == 172 && o1 in 16..31 -> false
                o0 == 192 && o1 == 168 -> false
                o0 == 192 && o1 == 0 && o2 == 0 -> false // IETF protocol assignments
                o0 == 192 && o1 == 0 && o2 == 2 -> false // documentation (TEST-NET-1)
                o0 == 198 && (o1 == 18 || o1 == 19) -> false // benchmarking
                o0 == 198 && o1 == 51 && o2 == 100 -> false // documentation (TEST-NET-2)
                o0 == 203 && o1 == 0 && o2 == 113 -> false // documentation (TEST-NET-3)
                o0 >= 224 -> false // multicast, reserved, broadcast
                else -> true
            }
        }

        private fun isPublicV6(b: ByteArray): Boolean {
            val first = b[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return false // ULA fc00::/7
            if (first == 0xfe && (b[1].toInt() and 0xc0) == 0x80) return false // fe80::/10
            // IPv4-mapped (::ffff:a.b.c.d) and NAT64 (64:ff9b::a.b.c.d): judge the IPv4 inside.
            val mapped = (0..9).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
            val nat64 = (b[0].toInt() and 0xff) == 0x00 && (b[1].toInt() and 0xff) == 0x64 &&
                (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b && (4..11).all { b[it].toInt() == 0 }
            if (mapped || nat64) return isPublicV4(b.copyOfRange(12, 16))
            // 6to4 (2002:AABB:CCDD::/48) carries the IPv4 AA.BB.CC.DD: a private one inside is private.
            if (first == 0x20 && (b[1].toInt() and 0xff) == 0x02) return isPublicV4(b.copyOfRange(2, 6))
            // 2001:db8::/32, documentation.
            if (first == 0x20 && (b[1].toInt() and 0xff) == 0x01 && (b[2].toInt() and 0xff) == 0x0d && (b[3].toInt() and 0xff) == 0xb8) return false
            // 2001::/32, Teredo: a tunnel address, of a host behind a NAT.
            if (first == 0x20 && (b[1].toInt() and 0xff) == 0x01 && b[2].toInt() == 0 && b[3].toInt() == 0) return false
            // ::/96 (IPv4-compatible, deprecated) and anything else in ::/8 is not a real host.
            if (first == 0) return false
            return true
        }

        /**
         * A network interceptor: the connection's peer must be public. The last check, for what
         * the DNS filter never sees (OkHttp does not look up a host written as an address): no
         * request is ever sent to a private peer.
         */
        fun connectionGuard(): Interceptor = Interceptor { chain ->
            val route = chain.connection()?.route()
            val peer = route?.socketAddress?.address
            if (route != null && route.proxy.type() == Proxy.Type.DIRECT && peer != null && !isPublic(peer)) {
                throw PrivateAddressException()
            }
            chain.proceed(chain.request())
        }
    }
}

/**
 * A name that resolved to the user's own network only: this filter's own refusal, as an
 * [UnknownHostException] so a caller sees a plain network failure. Never names the host.
 */
class NoPublicAddressException : UnknownHostException("no public address for the host")

/** A connection to a private address was refused. Never names the address. */
class PrivateAddressException : IOException("a connection to a private address was refused")

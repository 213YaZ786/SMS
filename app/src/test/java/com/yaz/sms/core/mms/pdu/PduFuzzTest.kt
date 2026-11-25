package com.yaz.sms.core.mms.pdu

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A fixed seed and 5,000 mutations of each golden PDU, in the normal test run. Every input either parses or
 * returns null; an exception out of [PduParser.parse] is a failure (the "INTERNAL" of vibb), and none allocates
 * far beyond its own size or takes long.
 * https://raw.githubusercontent.com/palchrb/vibb-launcher/main/launcher/app/src/test/java/com/kidslauncher/mdm/sms/mms/PduFuzzTest.kt#PduFuzzTest
 * (GPL-3.0, vibb-launcher; credited in the README).
 * not ported: MmsCodec.parseNotification / parseRetrieveConf / parseSendConf and MmsParseResult/MmsParseFailure
 * (vibb's own parser API: here PduParser(bytes, true).parse() returns a GenericPdu or null),
 * not ported: the seed made by MmsCodec.composeSendReq (vibb's composer; the golden PDUs above stay byte-built),
 * not ported: the REJECTED check of an empty notification (vibb's typed failure; here an empty input gives null).
 */
// Robolectric: the parser fills android.util.SparseArray, which plain JVM tests only stub.
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [36])
class PduFuzzTest {
    private val seeds: List<ByteArray> = listOf(
        PduVectors.notification(),
        PduVectors.retrieveConf(),
        PduVectors.sendConf(),
        PduVectors.retrieveHeaders(
            PduBytes().withLength(
                PduBytes().byte(PduVectors.CT_MULTIPART_RELATED, PduVectors.PARAM_START).text("<smil>")
                    .byte(PduVectors.PARAM_TYPE).text("application/smil"),
            ),
        ).uintvar(2)
            .part(
                PduBytes().text("application/smil")
                    .byte(PduVectors.PART_CONTENT_ID).byte(0x22).text("<smil>"),
                "<smil><body><par><img src=\"image000.jpg\"/></par></body></smil>".toByteArray(),
            )
            .part(
                PduBytes().byte(PduVectors.CT_MULTIPART_ALTERNATIVE),
                PduBytes().uintvar(1).part(PduVectors.textPart(), "alt".toByteArray()).build(),
            )
            .build(),
        // The charset form of From, To, Cc and Subject, Content-Disposition with a filename and more
        // parameters, a Content-Type with a charset name.
        PduBytes()
            .byte(PduVectors.MESSAGE_TYPE, 0x84)
            .byte(PduVectors.TRANSACTION).text(PduVectors.TRANSACTION_ID)
            .byte(PduVectors.VERSION, 0x92)
            .byte(PduVectors.DATE).longInteger(PduVectors.DATE_SECONDS)
            .byte(PduVectors.FROM).withLength(PduBytes().byte(0x80).raw(charsetForm("${PduVectors.SENDER}/TYPE=PLMN").build()))
            .byte(PduVectors.TO).raw(charsetForm("${PduVectors.RECIPIENT}/TYPE=PLMN").build())
            .byte(PduVectors.CC).raw(charsetForm("+4700000002/TYPE=PLMN").build())
            .byte(PduVectors.CC).text("+4700000003/TYPE=PLMN")
            .byte(PduVectors.SUBJECT).raw(charsetForm("Bilde æøå").build())
            .byte(PduVectors.CONTENT_TYPE, PduVectors.CT_MULTIPART_MIXED)
            .uintvar(2)
            .part(
                PduBytes().byte(PduVectors.CT_IMAGE_JPEG)
                    .byte(PduVectors.PART_DISPOSITION).withLength(PduBytes().byte(0x81, 0x98).text("bilde.jpg").byte(0x99).text("x"))
                    .byte(PduVectors.PART_CONTENT_LOCATION).text("bilde.jpg"),
                PduVectors.jpeg,
            )
            .part(
                PduBytes().withLength(PduBytes().byte(PduVectors.CT_TEXT_PLAIN, PduVectors.PARAM_CHARSET).text("utf-8").byte(0x85).text("t.txt")),
                PduVectors.TEXT.toByteArray(Charsets.UTF_8),
            )
            .build(),
        // A retrieve-conf with an error status and no body.
        PduBytes()
            .byte(PduVectors.MESSAGE_TYPE, 0x84)
            .byte(PduVectors.TRANSACTION).text(PduVectors.TRANSACTION_ID)
            .byte(PduVectors.VERSION, 0x92)
            .byte(PduVectors.DATE).longInteger(PduVectors.DATE_SECONDS)
            .byte(0x99, 0xE1) // X-Mms-Retrieve-Status: permanent, message not found
            .byte(PduVectors.CONTENT_TYPE, PduVectors.CT_MULTIPART_MIXED)
            .build(),
        // A notification with a charset-form subject and an Element-Descriptor.
        PduBytes()
            .byte(0xB2).withLength(PduBytes().text("ref").byte(0x83, 0x83))
            .byte(PduVectors.SUBJECT).raw(charsetForm("Bilde").build())
            .raw(PduVectors.notification())
            .build(),
    )

    private fun charsetForm(text: String) = PduBytes().withLength(PduBytes().byte(PduVectors.UTF_8).text(text))

    private val interesting = intArrayOf(0x00, 0x01, 0x1E, 0x1F, 0x20, 0x22, 0x7F, 0x80, 0x81, 0x8F, 0xC0, 0xFF)

    private fun mutate(random: Random, input: ByteArray): ByteArray {
        var bytes = input
        repeat(1 + random.nextInt(4)) {
            if (bytes.isEmpty()) bytes = byteArrayOf(random.nextInt(256).toByte())
            val at = random.nextInt(bytes.size)
            bytes = when (random.nextInt(8)) {
                0 -> bytes.copyOf().also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
                1 -> bytes.copyOf().also { it[at] = interesting[random.nextInt(interesting.size)].toByte() }
                2 -> bytes.copyOf().also { it[at] = random.nextInt(256).toByte() }
                3 -> bytes.copyOfRange(0, at) + ByteArray(1 + random.nextInt(8)) { random.nextInt(256).toByte() } + bytes.copyOfRange(at, bytes.size)
                4 -> bytes.copyOfRange(0, at) + bytes.copyOfRange(minOf(bytes.size, at + 1 + random.nextInt(16)), bytes.size)
                5 -> bytes.copyOf(at)
                // A run of continuation octets: the long uintvars and lengths.
                6 -> bytes.copyOfRange(0, at) + ByteArray(1 + random.nextInt(6)) { 0xFF.toByte() } + bytes.copyOfRange(at, bytes.size)
                else -> {
                    val other = seeds[random.nextInt(seeds.size)]
                    val from = random.nextInt(other.size)
                    bytes.copyOfRange(0, at) + other.copyOfRange(from, minOf(other.size, from + 1 + random.nextInt(64)))
                }
            }
        }
        return bytes
    }

    // HotSpot's per-thread allocation counter, by reflection: unit tests compile against android.jar, which has no
    // java.lang.management. -1 where the JVM has none (the time and exception checks still run).
    private val allocatedBytesOf: (() -> Long)? = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
        val read = { method.invoke(bean) as Long }
        read.takeIf { it() >= 0 }
    }.getOrNull()

    private fun allocatedBytes(): Long = allocatedBytesOf?.invoke() ?: -1

    private fun hex(input: ByteArray) = input.joinToString("") { "%02x".format(it) }

    @Test(timeout = 120_000)
    fun `5,000 seeded mutations of each seed parse or return null, in bounded memory and time`() {
        // Each seed is valid as its own type.
        val kinds = seeds.map { seed -> PduParser(seed, true).parse()?.javaClass?.simpleName ?: "invalid" }
        assertEquals(
            listOf("NotificationInd", "RetrieveConf", "SendConf", "RetrieveConf", "RetrieveConf", "RetrieveConf"),
            kinds.take(6),
        )
        val random = Random(77)
        val outcomes = HashMap<String, Int>()
        var slowest = 0L
        val started = System.nanoTime()
        repeat(PER_SEED * seeds.size) { i ->
            val input = mutate(random, seeds[i % seeds.size])
            val before = allocatedBytes()
            val t0 = System.nanoTime()
            val result = try {
                PduParser(input, true).parse()
            } catch (e: Throwable) {
                fail("iteration $i: ${e.javaClass.name} for ${hex(input)}")
                null
            }
            val took = System.nanoTime() - t0
            val allocated = if (before >= 0) allocatedBytes() - before else 0
            slowest = maxOf(slowest, took)
            val key = result?.javaClass?.simpleName ?: "null"
            outcomes[key] = (outcomes[key] ?: 0) + 1
            // One parse copies its input a few times at most: never an attacker-sized array.
            assertTrue("iteration $i allocated $allocated bytes for ${input.size}: ${hex(input)}", allocated < 64L * input.size + 4_000_000)
            assertTrue("iteration $i took ${took / 1_000_000} ms: ${hex(input)}", took < 2_000_000_000L)
        }
        assertTrue("the allocation check must run on the test JVM", allocatedBytesOf != null)
        val seconds = (System.nanoTime() - started) / 1e9
        println("PduFuzzTest: ${PER_SEED * seeds.size} inputs in %.2f s, slowest %.1f ms, outcomes %s".format(seconds, slowest / 1e6, outcomes.toSortedMap()))
        // The fuzzer reaches deep: some mutations still parse, and some are refused.
        assertTrue((outcomes["null"] ?: 0) > 0)
        assertTrue(outcomes.keys.size >= 3)
    }

    @Test
    fun `random bytes of every short length parse or return null`() {
        val random = Random(9)
        repeat(5_000) {
            val input = ByteArray(random.nextInt(48)) { random.nextInt(256).toByte() }
            try {
                PduParser(input, true).parse()
            } catch (e: Throwable) {
                fail("${e.javaClass.name} for ${hex(input)}")
            }
        }
        assertEquals(null, PduParser(ByteArray(0), true).parse())
        assertNotNull(PduParser(PduVectors.notification(), true).parse())
    }

    private companion object {
        const val PER_SEED = 5_000
    }
}

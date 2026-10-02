package com.sms.app.core.chat

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What one SMS app sends another, unseen, in a data SMS to the phone
 * number itself: its invite to an encrypted chat and a random [nonce].
 * The nonce comes back over the chat from whoever got the SMS, which
 * proves the chat is the person holding that number, as RCS does.
 * Packed to fit one data SMS: the fingerprint as bytes, the rest as text.
 */
data class Hello(val nonce: ByteArray, val fingerprint: String, val invite: String, val auth: String, val address: String) {

    /** The invite link the engine joins. */
    fun link(): String =
        "https://i.delta.chat/#$fingerprint&v=3&i=$invite&s=$auth&a=" + URLEncoder.encode(address, "UTF-8")

    fun encode(): ByteArray {
        val text = "$invite\n$auth\n$address".toByteArray(Charsets.US_ASCII)
        return MAGIC + byteArrayOf(VERSION) + nonce + fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray() + text
    }

    override fun equals(other: Any?) = other is Hello && nonce.contentEquals(other.nonce) && fingerprint == other.fingerprint &&
        invite == other.invite && auth == other.auth && address == other.address

    override fun hashCode() = fingerprint.hashCode()

    companion object {
        private val MAGIC = "S+".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = 1
        const val NONCE_BYTES = 8

        /** The parts of an invite link from the engine, or null. */
        fun fromLink(link: String, nonce: ByteArray): Hello? = runCatching {
            val fragment = link.substringAfter('#', "")
            val fingerprint = fragment.substringBefore('&').uppercase()
            val params = fragment.substringAfter('&').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            Hello(
                nonce, fingerprint,
                params["i"] ?: return null, params["s"] ?: return null,
                URLDecoder.decode(params["a"] ?: return null, "UTF-8")
            ).takeIf { it.fingerprint.length == 40 && it.fingerprint.all { c -> c in "0123456789ABCDEF" } }
        }.getOrNull()

        fun decode(bytes: ByteArray): Hello? = runCatching {
            if (bytes.size < 3 + NONCE_BYTES + 20 || !bytes.copyOfRange(0, 2).contentEquals(MAGIC) || bytes[2] != VERSION) return null
            val nonce = bytes.copyOfRange(3, 3 + NONCE_BYTES)
            val fp = bytes.copyOfRange(3 + NONCE_BYTES, 3 + NONCE_BYTES + 20).joinToString("") { "%02X".format(it) }
            val parts = String(bytes.copyOfRange(3 + NONCE_BYTES + 20, bytes.size), Charsets.US_ASCII).split('\n')
            if (parts.size != 3 || parts.any { it.isBlank() || it.length > 80 }) return null
            Hello(nonce, fp, parts[0], parts[1], parts[2]).takeIf { '@' in it.address }
        }.getOrNull()

        /** What comes back over the chat, never shown: the nonce, as proof. */
        fun proofText(nonce: ByteArray): String = PROOF + nonce.joinToString("") { "%02x".format(it) }

        fun proofOf(text: String): ByteArray? =
            text.takeIf { it.startsWith(PROOF) }?.removePrefix(PROOF)?.takeIf { it.length == NONCE_BYTES * 2 }
                ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

        fun isProof(text: String) = text.startsWith(PROOF)

        private const val PROOF = "⁣sms+proof:"
    }
}

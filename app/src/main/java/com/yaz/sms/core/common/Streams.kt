package com.yaz.sms.core.common

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Up to [limit] bytes of the stream (readNBytes needs Android 13). */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - total))
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}

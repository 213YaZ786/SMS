package com.sms.app.core.scene

import kotlin.math.max
import kotlin.math.min

/**
 * A photo's light for a conversation's background: its few main colours
 * and where they sit, never the photo itself (nothing of it is kept).
 */
object PhotoLight {

    /** One colour of the photo: 0xRRGGBB, its centre in fractions of the picture, its share of it. */
    data class Light(val rgb: Int, val x: Float, val y: Float, val share: Float)

    /** The [k] main colours of [pixels] (ARGB, [width] × [height]), the largest first. */
    fun of(pixels: IntArray, width: Int, height: Int, k: Int = 4): List<Light> {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return emptyList()
        val n = pixels.size
        // Each pixel as colour and place; the place weighs less, so a colour stays one even when spread.
        val px = FloatArray(n * 5)
        for (i in 0 until n) {
            val c = pixels[i]
            px[i * 5] = ((c shr 16) and 0xFF) / 255f
            px[i * 5 + 1] = ((c shr 8) and 0xFF) / 255f
            px[i * 5 + 2] = (c and 0xFF) / 255f
            px[i * 5 + 3] = (i % width) / width.toFloat() * PLACE
            px[i * 5 + 4] = (i / width) / height.toFloat() * PLACE
        }
        // Started from the pixel furthest from the average, then each time the one furthest
        // from those already taken: distinct colours, and the same photo always gives the same light.
        val mean = FloatArray(5).also { m -> for (i in 0 until n) for (e in 0 until 5) m[e] += px[i * 5 + e] / n }
        val near = FloatArray(n) { i -> var d = 0f; for (e in 0 until 5) { val v = px[i * 5 + e] - mean[e]; d += v * v }; d }
        val centres = Array(k) { FloatArray(5) }
        for (j in 0 until k) {
            var far = 0
            for (i in 1 until n) if (near[i] > near[far]) far = i
            for (e in 0 until 5) centres[j][e] = px[far * 5 + e]
            for (i in 0 until n) {
                var d = 0f
                for (e in 0 until 5) { val v = px[i * 5 + e] - centres[j][e]; d += v * v }
                if (j == 0 || d < near[i]) near[i] = d
            }
        }
        val owner = IntArray(n)
        repeat(10) {
            for (i in 0 until n) {
                var best = 0
                var bestD = Float.MAX_VALUE
                for (j in 0 until k) {
                    var d = 0f
                    for (e in 0 until 5) { val v = px[i * 5 + e] - centres[j][e]; d += v * v }
                    if (d < bestD) { bestD = d; best = j }
                }
                owner[i] = best
            }
            val sums = Array(k) { FloatArray(6) }
            for (i in 0 until n) {
                val s = sums[owner[i]]
                for (e in 0 until 5) s[e] += px[i * 5 + e]
                s[5] += 1f
            }
            for (j in 0 until k) if (sums[j][5] > 0f) for (e in 0 until 5) centres[j][e] = sums[j][e] / sums[j][5]
        }
        val counts = IntArray(k).also { c -> owner.forEach { c[it]++ } }
        return (0 until k).filter { counts[it] > 0 }.map { j ->
            val c = centres[j]
            val rgb = (channel(c[0]) shl 16) or (channel(c[1]) shl 8) or channel(c[2])
            Light(rgb, c[3] / PLACE, c[4] / PLACE, counts[j] / n.toFloat())
        }.sortedByDescending { it.share }
    }

    /** As kept in the settings: "rrggbb,x,y,share;…". */
    fun encode(lights: List<Light>): String = lights.joinToString(";") {
        "%06x,%.3f,%.3f,%.3f".format(java.util.Locale.ROOT, it.rgb and 0xFFFFFF, it.x, it.y, it.share)
    }

    fun decode(text: String): List<Light> = text.split(';').mapNotNull { part ->
        val f = part.split(',')
        if (f.size != 4) return@mapNotNull null
        val rgb = f[0].toIntOrNull(16) ?: return@mapNotNull null
        val x = f[1].toFloatOrNull() ?: return@mapNotNull null
        val y = f[2].toFloatOrNull() ?: return@mapNotNull null
        val share = f[3].toFloatOrNull() ?: return@mapNotNull null
        Light(rgb, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), share.coerceIn(0f, 1f))
    }

    private fun channel(v: Float) = (max(0f, min(1f, v)) * 255f + 0.5f).toInt()

    private const val PLACE = 0.35f
}

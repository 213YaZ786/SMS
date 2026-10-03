package com.yaz.sms.core.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoLightTest {

    @Test
    fun findsTheColoursAndWhereTheyAre() {
        // Left half blue, right half orange.
        val w = 20
        val h = 10
        val pixels = IntArray(w * h) { i -> if (i % w < w / 2) 0xFF2050E0.toInt() else 0xFFF08020.toInt() }
        val lights = PhotoLight.of(pixels, w, h, k = 2)
        assertEquals(2, lights.size)
        val blue = lights.first { it.rgb == 0x2050E0 }
        val orange = lights.first { it.rgb == 0xF08020 }
        assertTrue(blue.x < 0.5f && orange.x > 0.5f)
        assertEquals(0.5f, blue.share, 0.01f)
    }

    @Test
    fun keptAndReadBack() {
        val lights = listOf(PhotoLight.Light(0x123456, 0.25f, 0.75f, 0.6f), PhotoLight.Light(0xABCDEF, 0.9f, 0.1f, 0.4f))
        val back = PhotoLight.decode(PhotoLight.encode(lights))
        assertEquals(lights.map { it.rgb }, back.map { it.rgb })
        assertEquals(0.25f, back[0].x, 0.001f)
        assertTrue(PhotoLight.decode("nonsense;12,1").isEmpty())
    }
}

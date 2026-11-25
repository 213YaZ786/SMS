package com.yaz.sms.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// https://github.com/material-foundation/material-color-utilities/blob/main/java/blend/Blend.java#harmonize
// Blend's rule: the design colour's hue turns toward the source colour's by half the gap, 15 degrees at most.
class HarmonizeTest {
    private val designColors = listOf(Color(0xFF1E9E4A), Color(0xFFE8890C), Color(0xFFFF3B6B), Color(0xFF64D8FF), Color(0xFFFFD36B))
    private val sourceColors = listOf(Color(0xFF006A6A), Color(0xFFB3261E), Color(0xFF6750A4), Color(0xFFE8890C))

    private fun differenceDegrees(a: Double, b: Double): Double = 180.0 - abs(abs(a - b) - 180.0)

    @Test
    fun harmonizeKeepsTheHueWithinFifteenDegreesTowardTheSource() {
        for (designColor in designColors) for (sourceColor in sourceColors) {
            val fromHct = Hct.fromInt(designColor.toArgb())
            val toHct = Hct.fromInt(sourceColor.toArgb())
            val outputHct = Hct.fromInt(harmonize(designColor, sourceColor).toArgb())
            val moved = differenceDegrees(fromHct.hue, outputHct.hue)
            assertTrue("moved $moved", moved <= 15.5)
            assertTrue(
                differenceDegrees(outputHct.hue, toHct.hue) <= differenceDegrees(fromHct.hue, toHct.hue) + 1.0
            )
        }
    }

    @Test
    fun harmonizeEqualsTheDesignColorWhenTheSourceHasTheSameHue() {
        val designColor = Color(0xFF1E9E4A)
        val fromHct = Hct.fromInt(designColor.toArgb())
        val sourceColor = Color(Hct.from(fromHct.hue, 40.0, 50.0).toInt())
        val outputHct = Hct.fromInt(harmonize(designColor, sourceColor).toArgb())
        assertEquals(fromHct.hue, outputHct.hue, 1.0)
    }
}

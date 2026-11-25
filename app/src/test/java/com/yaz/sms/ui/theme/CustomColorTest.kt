package com.yaz.sms.ui.theme

import androidx.core.graphics.ColorUtils
import com.materialkolor.hct.Hct
import org.junit.Assert.assertTrue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** WCAG contrast (androidx.core.graphics.ColorUtils.calculateContrast) of customColor's pairs, primaries at every 15 degrees of hue. */
// Robolectric: ColorUtils.calculateContrast reads Color.alpha, which the plain unit-test android.jar answers with 0
// ("background can not be translucent" for an opaque #ff336b00).
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [36])
class CustomColorTest {

    private val green = CustomColor(0xFF1E9E4A.toInt(), "Call", true)

    @Test
    fun pairsReachFourAndAHalf() {
        for (tone in listOf(40.0, 80.0)) {
            for (hue in 0 until 360 step 15) {
                val source = Hct.from(hue.toDouble(), 48.0, tone).toInt()
                val group = customColor(source, green)
                for ((name, g) in listOf("light" to group.light, "dark" to group.dark)) {
                    val a = ColorUtils.calculateContrast(g.onColor, g.color)
                    val b = ColorUtils.calculateContrast(g.onColorContainer, g.colorContainer)
                    assertTrue("$name hue $hue tone $tone: onColor $a", a >= 4.5)
                    assertTrue("$name hue $hue tone $tone: onColorContainer $b", b >= 4.5)
                }
            }
        }
    }
}

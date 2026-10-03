package com.sms.app.core.sms

import com.sms.app.core.sms.Effects.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectsTest {

    @Test
    fun everyEffectTravelsAndComesBack() {
        Effect.entries.forEach { e ->
            assertEquals("Salut" to e, Effects.read(Effects.mark("Salut", e)))
        }
    }

    @Test
    fun aTextWithoutMarkHasNoEffect() {
        assertEquals("Salut" to null, Effects.read("Salut"))
        assertEquals("a⁤b" to null, Effects.read("a⁤b"))
    }

    @Test
    fun wordsBringTheirEffectInManyLanguages() {
        assertEquals(Effect.BALLOONS, Effects.fromWords("Joyeux anniversaire Salma !"))
        assertEquals(Effect.BALLOONS, Effects.fromWords("Feliz cumpleaños 🎂"))
        assertEquals(Effect.FIREWORKS, Effects.fromWords("Bonne année à tous"))
        assertEquals(Effect.FIREWORKS, Effects.fromWords("Frohes neues Jahr!"))
        assertEquals(Effect.CONFETTI, Effects.fromWords("Félicitations pour le bac"))
        assertEquals(Effect.CONFETTI, Effects.fromWords("Congrats!!"))
        assertEquals(Effect.LOVE, Effects.fromWords("je t'aime"))
        assertNull(Effects.fromWords("On se voit demain"))
    }
}

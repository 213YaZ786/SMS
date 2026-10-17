package com.yaz.sms.core.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model used the way SpamBlocker uses it (MIT, https://github.com/aj3423/SpamBlocker): trained on
 * a labelled table (ui/setting/quick/LocalAI.kt TrainingDialog.trainCNB: `model.train(samples)`), a
 * sample labelled one by one (updateCategory: `addSample`), unlabelled (`removeSample`) or flipped
 * (`changeCategory`), and asked for `spamProbability`, spam above Bayes.Default_Threshold = 0.5
 * (service/checker/Checker.kt NaiveBayes). SpamBlocker has no test of the model itself; these cases
 * hold what those calls promise.
 */
class ComplementNaiveBayesTest {

    private val spam = listOf(
        "URGENT: you won a prize of \$1000, claim now at https://win-prize.top/x",
        "Congratulations! Free gift card, claim your prize now www.gift-now.xyz",
        "Vous avez gagne 500 euros, cliquez ici pour reclamer votre prix bit.ly/gain",
        "Offre exclusive: pret immediat sans justificatif, repondez OUI",
        "Your account is suspended, verify now at secure-bank.top/login",
        "Gagnez de l'argent facilement, cliquez maintenant sur ce lien gratuit"
    )
    private val ham = listOf(
        "Salut, on se retrouve a 18h devant le cinema ?",
        "Can you pick up the kids at school today? I am late at work",
        "Merci pour le diner hier soir, c'etait super",
        "Meeting moved to Thursday at 10, see you there",
        "Maman: n'oublie pas d'appeler la grand-mere ce soir",
        "Thanks for the lift, see you at the weekend"
    )

    private fun trained(): ComplementNaiveBayes = ComplementNaiveBayes().also { m ->
        m.train(spam.map { it to true } + ham.map { it to false })
    }

    @Test
    fun anUntrainedModelDoesNotSay() {
        assertEquals(0.0, ComplementNaiveBayes().spamProbability("claim your prize now"), 0.0)
    }

    @Test
    fun oneClassAloneDoesNotSay() {
        val m = ComplementNaiveBayes()
        m.train(spam.map { it to true })
        assertEquals(0.0, m.spamProbability("claim your prize now"), 0.0)
    }

    @Test
    fun aTextWithNoTokenIsNotAsked() {
        assertEquals(0.0, trained().spamProbability("a b c"), 0.0)
        assertEquals(0.0, trained().spamProbability(""), 0.0)
    }

    @Test
    fun spamIsAboveTheThresholdAndHamBelow() {
        val m = trained()
        val threshold = SpamFilter.THRESHOLD
        assertTrue(m.spamProbability("Congratulations, you won a free prize, claim now at prize-now.top") > threshold)
        assertTrue(m.spamProbability("Cliquez ici pour reclamer votre cadeau gratuit maintenant") > threshold)
        assertTrue(m.spamProbability("Salut, tu peux appeler maman ce soir ? merci") < threshold)
        assertTrue(m.spamProbability("See you at the cinema Thursday, thanks for the lift") < threshold)
    }

    @Test
    fun theProbabilityStaysInsideItsBounds() {
        val m = trained()
        for (t in spam + ham + listOf("zzz qqq", "x".repeat(100_000))) {
            val p = m.spamProbability(t)
            assertTrue(t, p == 0.0 || p in 0.01..0.99)
        }
    }

    @Test
    fun samplesAddedOneByOneMakeTheModelOfTheBatch() {
        val batch = trained()
        val step = ComplementNaiveBayes()
        (spam.map { it to true } + ham.map { it to false }).forEach { (t, s) -> step.addSample(t, s) }
        assertEquals(batch.totalSamples, step.totalSamples)
        for (t in spam + ham + listOf("claim your free prize", "see you tonight")) {
            assertEquals(t, batch.spamProbability(t), step.spamProbability(t), 1e-12)
        }
    }

    @Test
    fun removingASampleUndoesAddingIt() {
        val m = trained()
        val before = listOf("claim your free prize now", "see you tonight, thanks").map { m.spamProbability(it) }
        m.addSample("Win a free prize today, click prize.top/now", true)
        assertEquals(spam.size + ham.size + 1, m.totalSamples)
        m.removeSample("Win a free prize today, click prize.top/now", true)
        assertEquals(spam.size + ham.size, m.totalSamples)
        val after = listOf("claim your free prize now", "see you tonight, thanks").map { m.spamProbability(it) }
        before.zip(after).forEach { (a, b) -> assertEquals(a, b, 1e-12) }
    }

    @Test
    fun removingEverySampleEmptiesTheModel() {
        val m = ComplementNaiveBayes()
        m.addSample("free prize now", true)
        m.addSample("see you tonight", false)
        m.removeSample("free prize now", true)
        m.removeSample("see you tonight", false)
        assertEquals(0, m.totalSamples)
        assertEquals(0.0, m.spamProbability("free prize now"), 0.0)
        // Removing from an empty model leaves it empty, never below zero.
        m.removeSample("see you tonight", false)
        assertEquals(0, m.totalSamples)
    }

    @Test
    fun changingASampleOfClassIsTrainingItInTheOtherOne() {
        val flipped = ComplementNaiveBayes()
        val all = spam.map { it to true } + ham.map { it to false }
        flipped.train(all)
        flipped.changeCategory(ham[0], oldIsSpam = false, newIsSpam = true)
        val direct = ComplementNaiveBayes()
        direct.train(all.map { (t, s) -> t to (s || t == ham[0]) })
        assertEquals(direct.totalSamples, flipped.totalSamples)
        for (t in spam + ham) assertEquals(t, direct.spamProbability(t), flipped.spamProbability(t), 1e-12)
        // The same class changes nothing.
        val p = flipped.spamProbability(ham[1])
        flipped.changeCategory(ham[1], oldIsSpam = false, newIsSpam = false)
        assertEquals(p, flipped.spamProbability(ham[1]), 0.0)
    }

    @Test
    fun theVocabularyIsCapped() {
        val m = ComplementNaiveBayes(maxFeatures = 5)
        m.train(spam.map { it to true } + ham.map { it to false })
        // Still answers with a handful of features.
        val p = m.spamProbability("claim prize free now")
        assertTrue(p in 0.01..0.99)
    }

    // The filter asks the model only with enough samples (SpamBlocker's own gate of ten in recomputeWeights).
    @Test
    fun theFilterWaitsForEnoughSamples() {
        val few = ComplementNaiveBayes()
        few.train(listOf("free prize now claim" to true, "see you tonight" to false))
        assertFalse(SpamFilter(model = few).isSpam("free prize now claim"))
        assertTrue(SpamFilter(model = trained()).isSpam("Congratulations, you won a free prize, claim now at prize-now.top"))
        assertFalse(SpamFilter().isSpam("Congratulations, you won a free prize"))
    }
}

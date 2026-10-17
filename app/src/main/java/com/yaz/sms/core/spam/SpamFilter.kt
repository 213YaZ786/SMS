package com.yaz.sms.core.spam

/**
 * What the spam checkers of Screening ask: the senders the user reported (true) or cleared
 * (false), and the classifier trained on the messages the user labelled. The questions are
 * SpamBlocker's Checker.NaiveBayes (MIT, https://github.com/aj3423/SpamBlocker,
 * app/src/main/java/spam/blocker/service/checker/Checker.kt: the model is asked only when it
 * exists, `spamProbability(content) > threshold` is spam, the threshold being
 * Bayes.Default_Threshold = 0.5 in ui/setting/quick/LocalAI.kt) and its Contact and SpamDB
 * checkers for the lists of numbers (a number the user vouched for is let through, a number in the
 * spam list is blocked before any content is read).
 */
class SpamFilter(
    /** Screening.senderKey to true (reported) or false (cleared by Not spam). */
    val senders: Map<String, Boolean> = emptyMap(),
    private val model: ComplementNaiveBayes? = null
) {
    /** The user's word on a sender: true reported, false cleared, null none. */
    fun reported(key: String): Boolean? = senders[key]

    /**
     * SpamBlocker's NaiveBayes check: the model has enough samples (its own gate of ten in
     * recomputeWeights) and both classes, and its probability of spam is above the threshold.
     */
    fun isSpam(text: String): Boolean {
        val m = model ?: return false
        if (m.totalSamples < ComplementNaiveBayes.MIN_SAMPLES) return false
        return m.spamProbability(text) > THRESHOLD
    }

    companion object {
        /** SpamBlocker's Bayes.Default_Threshold (ui/setting/quick/LocalAI.kt). */
        const val THRESHOLD = 0.5
    }
}

package com.yaz.sms.core.sms

import com.yaz.sms.core.spam.ComplementNaiveBayes
import com.yaz.sms.core.spam.SpamFilter
import com.yaz.sms.data.sms.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of the tests, tested the way SpamBlocker tests its order
 * (https://github.com/aj3423/SpamBlocker, app/src/androidTest/java/spam/blocker/service/RuleTest.kt:
 * contact_lenient, a contact passes before any rule; dialed, the history of messages sent to a
 * number lets it through), QUIK's keyword cases for the words (https://github.com/octoshrimpy/quik,
 * MessageContentFilterRepositoryImpl: whole words, case ignored), and Spam in the lists as Google
 * Messages keeps it (https://support.google.com/messages/answer/9061432).
 */
class SpamJudgeTest {

    private val stranger = "+33799887766"

    private val spamText = "Congratulations, you won a free prize, claim now at prize-now.top"

    private fun model(): ComplementNaiveBayes = ComplementNaiveBayes().also { m ->
        m.train(
            listOf(
                "URGENT: you won a prize of \$1000, claim now at https://win-prize.top/x" to true,
                "Congratulations! Free gift card, claim your prize now www.gift-now.xyz" to true,
                "Your account is suspended, verify now at secure-bank.top/login" to true,
                "Win a free prize today, click prize.top/now to claim" to true,
                "Gagnez de l'argent facilement, cliquez maintenant sur ce lien gratuit" to true,
                "Salut, on se retrouve a 18h devant le cinema ?" to false,
                "Can you pick up the kids at school today? I am late at work" to false,
                "Merci pour le diner hier soir, c'etait super" to false,
                "Meeting moved to Thursday at 10, see you there" to false,
                "Thanks for the lift, see you at the weekend" to false,
                "Maman: n'oublie pas d'appeler la grand-mere ce soir" to false
            )
        )
    }

    private fun filter(senders: Map<String, Boolean> = emptyMap()) = SpamFilter(senders, model())

    private fun conversation(address: String = stranger, snippet: String = spamText, fromMe: Boolean = false, group: Boolean = false) =
        Conversation(
            threadId = 1, address = address, snippet = snippet, date = 1_000_000L, unread = 1, fromMe = fromMe, failed = false,
            addresses = if (group) listOf(address, "+33711111111") else listOf(address)
        )

    // ---- The classifier

    @Test
    fun theClassifierSendsSpamToSpam() {
        assertTrue(SpamJudge.isSpam(stranger, spamText, filter()))
    }

    @Test
    fun aMessageTheClassifierLikesIsNotSpam() {
        assertFalse(SpamJudge.isSpam(stranger, "Salut, tu peux appeler maman ce soir ? merci", filter()))
    }

    @Test
    fun withoutAFilterNothingIsSpam() {
        assertFalse(SpamJudge.isSpam(stranger, spamText, null))
    }

    @Test
    fun aModelWithTooFewSamplesDoesNotJudge() {
        val few = ComplementNaiveBayes().also { it.train(listOf("free prize claim now" to true, "see you tonight" to false)) }
        assertFalse(SpamJudge.isSpam(stranger, "free prize claim now", SpamFilter(model = few)))
    }

    // ---- What is plainly not spam, before the classifier is asked

    @Test
    fun aCodeIsNeverJudgedSpam() {
        assertFalse(SpamJudge.isSpam("GOOGLE", "Your code is 482913. Claim your free prize now", filter()))
    }

    @Test
    fun aParcelIsNeverJudgedSpam() {
        assertFalse(SpamJudge.isSpam(stranger, "Votre colis 1Z999AA10123456784 est livre, claim your free prize now", filter()))
    }

    @Test
    fun anAccountAlertFromAServiceIsNeverJudgedSpam() {
        assertFalse(SpamJudge.isSpam("BANQUE", "Your balance is 25 EUR, claim your free prize now", filter()))
        // The same words from a number that is no service are a person's: the classifier judges them.
        assertTrue(SpamJudge.isSpam(stranger, "Your balance is 25 EUR, claim your free prize now", filter()))
    }

    // ---- The user's word on a sender

    @Test
    fun aReportedSenderIsSpamWhateverItSays() {
        val spam = filter(mapOf(SpamJudge.senderKey(stranger) to true))
        assertTrue(SpamJudge.isSpam(stranger, "Salut, c'est Paul", spam))
        // Even a code: the user's word is the first test.
        assertTrue(SpamJudge.isSpam(stranger, "Your code is 482913", spam))
        // Another sender is not touched.
        assertFalse(SpamJudge.isSpam("+33788776655", "Salut, c'est Paul", spam))
    }

    @Test
    fun aClearedSenderIsNotJudgedByTheClassifier() {
        val spam = filter(mapOf(SpamJudge.senderKey(stranger) to false))
        assertFalse(SpamJudge.isSpam(stranger, spamText, spam))
    }

    // ---- The conversation of the list

    @Test
    fun theListSortsAConversationAsSpam() {
        assertTrue(SpamJudge.isSpam(conversation(), known = false, spam = filter()))
    }

    @Test
    fun aKnownSenderIsNeverJudgedByTheClassifier() {
        assertFalse(SpamJudge.isSpam(conversation(), known = true, spam = filter()))
    }

    @Test
    fun aConversationEndingOnTheUsersOwnMessageIsNotJudged() {
        assertFalse(SpamJudge.isSpam(conversation(fromMe = true), known = false, spam = filter()))
    }

    @Test
    fun aReportedKnownSenderIsSpamAllTheSame() {
        val spam = filter(mapOf(SpamJudge.senderKey(stranger) to true))
        assertTrue(SpamJudge.isSpam(conversation(snippet = "hello"), known = true, spam = spam))
        // Even when the newest message is the user's own.
        assertTrue(SpamJudge.isSpam(conversation(snippet = "stop", fromMe = true), known = false, spam = spam))
    }

    @Test
    fun groupsAndChatAddressesAreNeverSpam() {
        val spam = filter(mapOf(SpamJudge.senderKey(stranger) to true))
        assertFalse(SpamJudge.isSpam(conversation(group = true), known = false, spam = spam))
        assertFalse(SpamJudge.isSpam(conversation(address = "alice@chat.example"), known = false, spam = filter()))
        assertFalse(SpamJudge.isSpam(conversation(address = ""), known = false, spam = spam))
    }

    @Test
    fun aSpamConversationLeavesTheMainListAndNothingElseDoes() {
        val all = listOf(conversation().copy(threadId = 1), conversation(snippet = "Salut").copy(threadId = 2, address = "+33788776655"))
        val spam = all.filter { SpamJudge.isSpam(it, known = false, spam = filter()) }.map { it.threadId }.toSet()
        assertEquals(setOf(1L), spam)
        fun ids(f: Filter) = Lists.shown(all, f, emptyList(), emptyMap(), spam = spam).map { it.threadId }
        assertEquals(listOf(2L), ids(Filter.ALL))
        assertEquals(listOf(1L), ids(Filter.SPAM))
    }

    // ---- Who is known (SpamBlocker's Contact and Dialed checkers)

    @Test
    fun aContactIsKnown() {
        assertTrue(SpamJudge.isKnown(contact = true, sent = 0))
    }

    @Test
    fun threeRepliesMakeASenderKnown() {
        assertFalse(SpamJudge.isKnown(false, 0))
        assertFalse(SpamJudge.isKnown(false, 2))
        assertTrue(SpamJudge.isKnown(false, 3))
        assertTrue(SpamJudge.isKnown(false, 40))
    }

    // ---- Words, as QUIK's keyword filter

    @Test
    fun wordsAreWholeWordsWhateverTheCase() {
        val words = Keywords(listOf("card", "payé", "débit"))
        assertTrue(words.inside("Your CARD was used"))
        assertFalse(words.inside("Do not discard it"))
        assertTrue(words.inside("Vous avez payé"))
        assertTrue(words.inside("Un DÉBIT de 25 EUR"))
        assertFalse(words.inside("cardigan"))
    }

    @Test
    fun senderKeys() {
        assertEquals(SpamJudge.senderKey("+33 6 12 34 56 78"), SpamJudge.senderKey("0612345678"))
        assertEquals("banque", SpamJudge.senderKey(" BANQUE "))
        assertTrue(SpamJudge.senderKey("36105").isNotEmpty())
    }
}

package com.yaz.sms.core.sms

import com.yaz.sms.core.spam.ComplementNaiveBayes
import com.yaz.sms.core.spam.SpamFilter
import com.yaz.sms.data.sms.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The order of the checkers with the spam ones in the chain, tested the way SpamBlocker tests its
 * order (https://github.com/aj3423/SpamBlocker, app/src/androidTest/java/spam/blocker/service/RuleTest.kt:
 * number_rules_priority, the higher priority answers first; contact_lenient, a contact passes before
 * any rule), and Spam in the lists as Google Messages keeps it
 * (https://support.google.com/messages/answer/9061432).
 */
class SpamScreeningTest {

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

    private fun check(text: String, address: String = stranger, spam: SpamFilter? = filter()) =
        Screening.check(Case(address, text, "fr"), spam)

    private fun conversation(address: String = stranger, snippet: String = spamText, fromMe: Boolean = false, group: Boolean = false) =
        Conversation(
            threadId = 1, address = address, snippet = snippet, date = 1_000_000L, unread = 1, fromMe = fromMe, failed = false,
            addresses = if (group) listOf(address, "+33711111111") else listOf(address)
        )

    // ---- The learned checker

    @Test
    fun theClassifierSendsSpamToSpam() {
        val v = check(spamText)
        assertEquals(Category.SPAM, v.category)
        assertEquals(Screening.BY_LEARNED, v.by)
    }

    @Test
    fun aMessageTheClassifierLikesStaysUnknown() {
        assertEquals(Category.UNKNOWN, check("Salut, tu peux appeler maman ce soir ? merci").category)
    }

    @Test
    fun withoutAFilterTheChainIsAsBefore() {
        assertEquals(Category.UNKNOWN, check(spamText, spam = null).category)
        assertEquals(Category.UNKNOWN, Screening.check(Case(stranger, spamText, "fr")).category)
    }

    @Test
    fun aModelWithTooFewSamplesDoesNotJudge() {
        val few = ComplementNaiveBayes().also { it.train(listOf("free prize claim now" to true, "see you tonight" to false)) }
        assertEquals(Category.UNKNOWN, check("free prize claim now", spam = SpamFilter(model = few)).category)
    }

    // ---- Where it stands in the chain: after the codes, parcels and accounts, before the promotions.

    @Test
    fun aCodeIsNeverSpam() {
        val v = check("Your code is 482913. Claim your free prize now", "GOOGLE")
        assertEquals(Screening.BY_CODE, v.by)
    }

    @Test
    fun aParcelIsNeverSpam() {
        assertEquals(Screening.BY_PARCEL, check("Votre colis 1Z999AA10123456784 est livre, claim your free prize now").by)
    }

    @Test
    fun anAccountAlertIsNeverSpam() {
        assertEquals(Screening.BY_ACCOUNT, check("Your balance is 25 EUR, claim your free prize now", "BANQUE").by)
    }

    @Test
    fun spamComesBeforeThePromotionItCarries() {
        val v = check("$spamText. STOP au 36180")
        assertEquals(Category.SPAM, v.category)
        // The same text without the classifier is a promotion.
        assertEquals(Category.PROMOTIONS, check("$spamText. STOP au 36180", spam = null).category)
    }

    @Test
    fun spamComesBeforeASalesNumber() {
        assertEquals(Category.SPAM, check(spamText, "+33 9 48 12 34 56").category)
    }

    // ---- The user's word on a sender

    @Test
    fun aReportedSenderIsSpamWhateverItSays() {
        val key = Screening.senderKey(stranger)
        val spam = filter(mapOf(key to true))
        assertEquals(Screening.BY_REPORTED, check("Salut, c'est Paul", spam = spam).by)
        assertEquals(Category.SPAM, check("Salut, c'est Paul", spam = spam).category)
        // Even a code: the user's word is the first checker.
        assertEquals(Category.SPAM, check("Your code is 482913", spam = spam).category)
        // Another sender is not touched.
        assertEquals(Category.UNKNOWN, check("Salut, c'est Paul", "+33788776655", spam = spam).category)
    }

    @Test
    fun aClearedSenderIsNotJudgedByTheClassifier() {
        val spam = filter(mapOf(Screening.senderKey(stranger) to false))
        assertEquals(Category.UNKNOWN, check(spamText, spam = spam).category)
        // The other checkers still work for it.
        assertEquals(Category.PROMOTIONS, check("$spamText. STOP au 36180", spam = spam).category)
    }

    // ---- The conversation of the list

    @Test
    fun theListSortsAConversationAsSpam() {
        assertEquals(Category.SPAM, Screening.verdict(conversation(), known = false, country = "fr", spam = filter())?.category)
    }

    @Test
    fun aKnownSenderIsNeverJudgedByTheClassifier() {
        assertNull(Screening.verdict(conversation(), known = true, country = "fr", spam = filter()))
    }

    @Test
    fun aReportedKnownSenderIsSpamAllTheSame() {
        val spam = filter(mapOf(Screening.senderKey(stranger) to true))
        assertEquals(Category.SPAM, Screening.verdict(conversation(snippet = "hello"), known = true, country = "fr", spam = spam)?.category)
        // Even when the newest message is the user's own.
        assertEquals(Category.SPAM, Screening.verdict(conversation(snippet = "stop", fromMe = true), known = false, country = "fr", spam = spam)?.category)
    }

    @Test
    fun groupsAndChatAddressesAreNeverSpam() {
        val spam = filter(mapOf(Screening.senderKey(stranger) to true))
        assertNull(Screening.verdict(conversation(group = true), known = false, country = "fr", spam = spam))
        assertNull(Screening.verdict(conversation(address = "alice@chat.example"), known = false, country = "fr", spam = filter()))
        assertNull(Screening.verdict(conversation(address = ""), known = false, country = "fr", spam = spam))
    }

    @Test
    fun aSpamConversationLeavesTheMainListAndTheUnknownPlace() {
        val all = listOf(conversation().copy(threadId = 1), conversation(snippet = "Salut").copy(threadId = 2, address = "+33788776655"))
        val screened = mapOf(
            1L to Verdict(Category.SPAM, Screening.BY_LEARNED),
            2L to Verdict(Category.UNKNOWN, Screening.BY_DEFAULT)
        )
        fun ids(f: Filter) = Lists.shown(all, f, emptyList(), emptyMap(), screened = screened, now = 1_000_000L).map { it.threadId }
        assertEquals(emptyList<Long>(), ids(Filter.ALL))
        assertEquals(emptyList<Long>(), ids(Filter.UNREAD))
        assertEquals(listOf(2L), ids(Filter.UNKNOWN))
        assertEquals(listOf(1L), ids(Filter.SPAM))
        assertEquals(emptyList<Long>(), ids(Filter.PROMOTIONS))
        // Screening off: no Spam place either.
        assertEquals(emptyList<Long>(), Lists.shown(all, Filter.SPAM, emptyList(), emptyMap(), screened = null).map { it.threadId })
    }
}

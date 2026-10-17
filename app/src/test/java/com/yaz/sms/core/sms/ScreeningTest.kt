package com.yaz.sms.core.sms

import com.yaz.sms.data.settings.Knowns
import com.yaz.sms.data.sms.Conversation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per checker and per order of checkers, as SpamBlocker's RuleTest does
 * (https://github.com/aj3423/SpamBlocker, app/src/androidTest/java/spam/blocker/service/RuleTest.kt:
 * contact_lenient, a contact passes before any rule; dialed, the history of messages sent
 * to a number lets it through; regex_flags_case_sensitive; number_rules_priority, the
 * higher priority answers first), with QUIK's keyword cases for the words
 * (https://github.com/octoshrimpy/quik, MessageContentFilterRepositoryImpl: whole words,
 * case ignored) and Apple's rules of iOS 26 Messages for the known sender
 * (https://support.apple.com/en-us/125068: three replies, marked known).
 */
class ScreeningTest {

    private val stranger = "+33799887766"

    private fun check(text: String, address: String = stranger, country: String? = "fr") = Screening.check(Case(address, text, country))

    private fun conversation(address: String = stranger, snippet: String = "hello", fromMe: Boolean = false, group: Boolean = false, sent: Int = 0) =
        Conversation(
            threadId = 1, address = address, snippet = snippet, date = 1_000_000L, unread = 1, fromMe = fromMe, failed = false,
            addresses = if (group) listOf(address, "+33711111111") else listOf(address), sent = sent
        )

    // ---- Transactions

    @Test
    fun aCodeIsATransactionAndTimeSensitive() {
        val v = check("Votre code de vérification est 482913")
        assertEquals(Category.TRANSACTIONS, v.category)
        assertEquals(Screening.BY_CODE, v.by)
        assertTrue(v.timeSensitive)
        assertEquals(Category.TRANSACTIONS, check("Your Uber code is 4821", "UBER", "us").category)
        assertEquals(Category.TRANSACTIONS, check("Tu código de seguridad es 482913").category)
        assertEquals(Category.TRANSACTIONS, check("Ihr Bestätigungscode lautet 482913").category)
        assertEquals(Category.TRANSACTIONS, check("Il tuo codice di conferma è 482913").category)
        assertEquals(Category.TRANSACTIONS, check("Seu código de verificação: 482913").category)
        assertEquals(Category.TRANSACTIONS, check("Uw verificatiecode is 482913").category)
    }

    @Test
    fun datesAndPhoneNumbersAreNotCodes() {
        assertEquals(Category.UNKNOWN, check("Rendez-vous le 12-10-2026 à 18h").category)
        assertEquals(Category.UNKNOWN, check("Rappelle-moi au 06 12 34 56 78").category)
        assertEquals(Category.UNKNOWN, check("Your code: pay 1500€ before Friday").category)
        assertEquals(Category.UNKNOWN, check("Verification: call (888) 888-8888").category)
        assertFalse(check("Code promo SOLDES20, jusqu'à dimanche").timeSensitive)
    }

    @Test
    fun aParcelIsATransaction() {
        val v = check("Votre colis 1Z999AA10123456784 sera livré demain", "COLISSIMO")
        assertEquals(Category.TRANSACTIONS, v.category)
        assertEquals(Screening.BY_PARCEL, v.by)
        assertFalse(v.timeSensitive)
        assertEquals(Category.TRANSACTIONS, check("Your parcel 1Z999AA10123456784 is out for delivery").category)
    }

    @Test
    fun aServiceThatTalksOfAnAccountIsATransaction() {
        assertEquals(Screening.BY_ACCOUNT, check("Paiement de 25,00 EUR par carte chez FNAC", "BANQUE").by)
        assertEquals(Screening.BY_ACCOUNT, check("Su tarjeta ha realizado un pago de 25 EUR", "BBVA").by)
        assertEquals(Screening.BY_ACCOUNT, check("Ihre Kontostand beträgt 25 EUR", "SPARKASSE").by)
        assertEquals(Screening.BY_ACCOUNT, check("Betaling van 25 EUR ontvangen op uw rekening", "ING").by)
        assertEquals(Screening.BY_ACCOUNT, check("Your balance is 25 EUR", "36105").by)
        // The same words from a number that is no service are a person's.
        assertEquals(Category.UNKNOWN, check("Paiement de 25,00 EUR par carte chez FNAC").category)
        // A service without those words stays unknown.
        assertEquals(Category.UNKNOWN, check("Bonne année à tous", "BANQUE").category)
    }

    // ---- Promotions

    @Test
    fun anOptOutMarkerIsAPromotion() {
        for (text in listOf(
            "Soldes -50% sur tout le site ! STOP au 36180",
            "Offre flash. STOP SMS au 36180",
            "Big sale today. Reply STOP to opt out",
            "Free delivery! Text STOP to end",
            "Para darte de baja responde BAJA",
            "Zum Abmelden antworte STOP",
            "Per non ricevere più messaggi rispondi STOP",
            "Para não receber mais mensagens responda SAIR",
            "Om u af te melden stuur STOP",
            "Cliquez pour vous désinscrire: https://example.com/u",
            "Click here to unsubscribe"
        )) {
            val v = check(text)
            assertEquals(text, Category.PROMOTIONS, v.category)
            assertEquals(Screening.BY_OPT_OUT, v.by)
            assertFalse(v.timeSensitive)
        }
    }

    @Test
    fun ordinaryTextWithTheWordStopIsNotAPromotion() {
        assertEquals(Category.UNKNOWN, check("See you at the bus stop at 18:30").category)
        assertEquals(Category.UNKNOWN, check("Don't stop believing").category)
        assertEquals(Category.UNKNOWN, check("Arrête-toi au stop puis tourne à droite").category)
    }

    @Test
    fun aSalesNumberIsAPromotion() {
        val v = check("Bonjour, une offre pour vous", "0162123456")
        assertEquals(Category.PROMOTIONS, v.category)
        assertEquals(Screening.BY_SALES, v.by)
        assertEquals(Category.PROMOTIONS, check("Bonjour", "+33 9 48 12 34 56", "us").category)
        // A mobile number is not in the ranges.
        assertEquals(Category.UNKNOWN, check("Bonjour, une offre pour vous", "0612345678").category)
        // No country, no range.
        assertEquals(Category.UNKNOWN, check("Bonjour", "0162123456", null).category)
    }

    // ---- Order: the first checker that answers wins

    @Test
    fun theFirstCheckerThatAnswersWins() {
        // A code beats an opt-out marker, a parcel beats a sales number, an account beats an opt-out.
        assertEquals(Screening.BY_CODE, check("Votre code est 4821. STOP au 36180").by)
        assertEquals(Screening.BY_PARCEL, check("Votre colis 1Z999AA10123456784 est livré", "0162123456").by)
        assertEquals(Screening.BY_ACCOUNT, check("Votre solde est de 25 EUR. STOP au 36180", "BANQUE").by)
        assertEquals(Screening.BY_OPT_OUT, check("Offre spéciale. STOP au 36180", "0162123456").by)
        assertEquals(Screening.BY_DEFAULT, check("Salut, tu es là ?").by)
    }

    // ---- Rules, as SpamBlocker's RegexRule

    @Test
    fun aRuleIsCaseSensitiveWhenAsked() {
        val loose = Rule("loose", ".*STOP.*")
        val strict = Rule("strict", ".*STOP.*", caseSensitive = true)
        assertTrue(loose.matches("please stop"))
        assertFalse(strict.matches("please stop"))
        assertTrue(strict.matches("reply STOP"))
    }

    @Test
    fun aRuleThatDoesNotCompileNeverMatches() {
        assertFalse(Rule("broken", "([").matches("["))
    }

    @Test
    fun aRuleSeesTheWholeTextOnSeveralLines() {
        assertTrue(Rule("lines", ".*STOP.*", caseSensitive = true).matches("first line\nsecond line STOP\nthird"))
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

    // ---- Who is known

    @Test
    fun aContactIsKnown() {
        assertTrue(Screening.isKnown(contact = true, marked = emptySet(), address = stranger, sent = 0))
    }

    @Test
    fun aSenderMarkedKnownIsKnownByItsNumberInAnyForm() {
        val marked = setOf(Screening.senderKey("+33 7 99 88 77 66"))
        assertTrue(Screening.isKnown(false, marked, "0799887766", 0))
        assertTrue(Screening.isKnown(false, marked, "+33799887766", 0))
        assertFalse(Screening.isKnown(false, marked, "0799887767", 0))
        assertTrue(Screening.isKnown(false, setOf(Screening.senderKey("Banque")), "BANQUE", 0))
    }

    @Test
    fun threeRepliesMakeASenderKnown() {
        assertFalse(Screening.isKnown(false, emptySet(), stranger, 0))
        assertFalse(Screening.isKnown(false, emptySet(), stranger, 2))
        assertTrue(Screening.isKnown(false, emptySet(), stranger, 3))
        assertTrue(Screening.isKnown(false, emptySet(), stranger, 40))
    }

    @Test
    fun aConversationIsScreenedOnlyWhenItsSenderIsUnknown() {
        assertEquals(Category.PROMOTIONS, Screening.verdict(conversation(snippet = "Offre. STOP au 36180"), known = false, country = "fr")?.category)
        assertNull(Screening.verdict(conversation(snippet = "Offre. STOP au 36180"), known = true, country = "fr"))
        // A group, a person met over the chat, the user's own last message, no address: not screened.
        assertNull(Screening.verdict(conversation(group = true), known = false, country = "fr"))
        assertNull(Screening.verdict(conversation(address = "alice@chat.example"), known = false, country = "fr"))
        assertNull(Screening.verdict(conversation(fromMe = true), known = false, country = "fr"))
        assertNull(Screening.verdict(conversation(address = ""), known = false, country = "fr"))
    }

    @Test
    fun aCodeStaysListedForEightHours() {
        val hour = 60L * 60 * 1000
        val code = Verdict(Category.TRANSACTIONS, Screening.BY_CODE)
        val c = conversation()
        assertTrue(Screening.staysListed(code, c, c.date + 7 * hour))
        assertFalse(Screening.staysListed(code, c, c.date + 9 * hour))
        assertFalse(Screening.staysListed(Verdict(Category.PROMOTIONS, Screening.BY_OPT_OUT), c, c.date + hour))
        assertFalse(Screening.staysListed(Verdict(Category.TRANSACTIONS, Screening.BY_PARCEL), c, c.date + hour))
    }

    // ---- The set of senders marked known (Fossify Messages' set of strings)

    @Test
    fun theKnownSetAddsAndRemoves() {
        val one = Knowns.add(emptySet(), "799887766")
        assertEquals(setOf("799887766"), one)
        assertEquals(one, Knowns.add(one, "799887766"))
        assertEquals(emptySet<String>(), Knowns.remove(one, "799887766"))
        assertEquals(one, Knowns.add(one, "  "))
        assertEquals(one, Knowns.add(one, "x".repeat(65)))
    }

    @Test
    fun theKnownSetIsCappedAndReadBackSafely() {
        val full = (1..Knowns.MAX).map { "k$it" }.toSet()
        assertEquals(full, Knowns.add(full, "one more"))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(setOf("a", "b"), Knowns.parse("""{"items":["a","b"," ",""]}""", json))
        assertEquals(emptySet<String>(), Knowns.parse("not json", json))
        assertEquals(emptySet<String>(), Knowns.parse("", json))
    }

    @Test
    fun senderKeys() {
        assertEquals(Screening.senderKey("+33 6 12 34 56 78"), Screening.senderKey("0612345678"))
        assertEquals("banque", Screening.senderKey(" BANQUE "))
        assertTrue(Screening.senderKey("36105").isNotEmpty())
    }
}

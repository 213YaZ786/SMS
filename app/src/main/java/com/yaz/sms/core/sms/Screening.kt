package com.yaz.sms.core.sms

import android.content.Context
import android.provider.Telephony
import com.yaz.sms.core.dial.ContactLookup
import com.yaz.sms.core.dial.SalesCalls
import com.yaz.sms.data.settings.KnownStore
import com.yaz.sms.core.spam.SpamFilter
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.data.settings.SpamStore
import com.yaz.sms.data.sms.Conversation

/**
 * What an unknown sender's conversation is, as Messages in iOS 26 sorts it, and Spam as Google
 * Messages keeps it (https://support.google.com/messages/answer/9061432: Spam & blocked).
 */
enum class Category { TRANSACTIONS, PROMOTIONS, UNKNOWN, SPAM }

/** The answer of the checkers: the category, and which checker gave it. */
data class Verdict(val category: Category, val by: String) {
    /** A code is time sensitive, as iOS 26 Messages treats verification codes. */
    val timeSensitive: Boolean get() = by == Screening.BY_CODE
}

/**
 * What the checkers look at: SpamBlocker's CheckContext (rawNumber, smsContent), here the sender's
 * address and the text, and the country the number is read in.
 */
class Case(val address: String, val text: String, val country: String?)

/**
 * SpamBlocker's IChecker (MIT, https://github.com/aj3423/SpamBlocker, app/src/main/java/spam/blocker/service/checker/Checker.kt):
 * a priority and a check that answers or passes.
 */
interface Checker {
    val priority: Int
    fun check(case: Case): Verdict?
}

/**
 * A rule on the text of a message: SpamBlocker's RegexRule (db/RegexTable.kt: pattern, description,
 * priority; matches = String.regexMatches in util/Util.kt, the whole text against the pattern with
 * the options of Util.flagsToRegexOptions: ignore case unless case sensitive, multi line, a dot
 * that matches line ends; a pattern that does not compile never matches).
 */
class Rule(val description: String, pattern: String, val priority: Int = 0, caseSensitive: Boolean = false) {
    private val regex: Regex? = runCatching {
        val options = buildSet {
            if (!caseSensitive) add(RegexOption.IGNORE_CASE)
            add(RegexOption.MULTILINE)
            add(RegexOption.DOT_MATCHES_ALL)
        }
        Regex(pattern, options)
    }.getOrNull()

    fun matches(text: String): Boolean = regex?.matches(text) == true
}

/**
 * A list of words looked for in a text, whole words only and without regard to case: QUIK's
 * MessageContentFilterRepositoryImpl.isBlocked (GPL-3.0, https://github.com/octoshrimpy/quik,
 * data/src/main/java/com/moez/QKSMS/repository/MessageContentFilterRepositoryImpl.kt), which
 * wraps the keyword escaped between word boundaries and compares in lower case. Fossify
 * Messages' isMessageFilteredOut (helpers/ReceiverUtils.kt) is the same test without the
 * boundaries (`body.contains(keyword, ignoreCase = true)`). Here the words are joined in one
 * expression built once, and the boundaries are lookarounds (a `\b` is ASCII only on a recent JVM).
 */
class Keywords(words: List<String>) {
    private val regex = Regex(
        "(?<![\\p{L}\\p{N}\\p{M}_])(?:" + words.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}\\p{M}_])",
        RegexOption.IGNORE_CASE
    )

    fun inside(text: String): Boolean = regex.containsMatchIn(text)
}

/**
 * Unknown senders sorted into Transactions and Promotions, the way Messages in iOS 26 does
 * (https://support.apple.com/en-us/125068: the Unknown Senders filter, the two categories,
 * time sensitive messages such as codes still notify and stay listed for eight hours, a sender
 * stops being unknown once marked known or replied to three times). The checkers are
 * SpamBlocker's: every one has a priority, they are tried from the highest, the first to
 * answer wins, and a last one that always answers ends the list (Checker.checkSms:
 * `sortedByDescending { priority }.firstNotNullOf { check }` with PassedByDefault at Int.MIN_VALUE).
 */
object Screening {

    const val BY_CODE = "code"
    const val BY_PARCEL = "parcel"
    const val BY_ACCOUNT = "account"
    const val BY_OPT_OUT = "opt-out"
    const val BY_SALES = "sales number"
    const val BY_DEFAULT = "default"
    const val BY_REPORTED = "reported"
    const val BY_LEARNED = "learned"

    /** iOS 26: "Message filtering doesn't apply to any sender you've replied to three times or more." */
    const val REPLIES_TO_KNOW = 3

    /** iOS 26: a message allowed to notify shows in the conversation list for eight hours. */
    const val LISTED_MS = 8L * 60 * 60 * 1000

    // Transactions: a code, a parcel, or a service that talks of an account or a payment.
    private object CodeChecker : Checker {
        override val priority = 60
        override fun check(case: Case): Verdict? = if (Codes.isCode(case.text)) Verdict(Category.TRANSACTIONS, BY_CODE) else null
    }

    private object ParcelChecker : Checker {
        override val priority = 50
        override fun check(case: Case): Verdict? = if (Finds.parcel(case.text) != null) Verdict(Category.TRANSACTIONS, BY_PARCEL) else null
    }

    // The words of an account, a payment or an order, in English, French, Spanish, German,
    // Italian, Portuguese and Dutch. Matched as QUIK's keyword filter matches (Keywords).
    private val AccountWords = Keywords(
        listOf(
            "account", "balance", "payment", "paid", "transaction", "card", "debit", "credit", "transfer", "deposit",
            "withdrawal", "invoice", "bill", "statement", "receipt", "order", "purchase", "bank", "fraud", "statement",
            "compte", "solde", "paiement", "payé", "carte", "débit", "crédit", "virement", "prélèvement", "facture",
            "reçu", "commande", "achat", "banque", "relevé", "opération",
            "cuenta", "saldo", "pago", "pagado", "transacción", "tarjeta", "débito", "crédito", "transferencia",
            "factura", "recibo", "pedido", "compra", "banco",
            "konto", "kontostand", "zahlung", "bezahlt", "transaktion", "karte", "lastschrift", "überweisung",
            "rechnung", "beleg", "bestellung", "kauf",
            "conto", "pagamento", "pagato", "transazione", "carta", "addebito", "accredito", "bonifico", "fattura",
            "ricevuta", "ordine", "acquisto", "banca",
            "conta", "pago", "transação", "cartão", "transferência", "fatura", "recibo", "compra",
            "rekening", "betaling", "betaald", "transactie", "kaart", "afschrijving", "overboeking", "factuur",
            "bon", "bestelling", "aankoop"
        )
    )

    private object AccountChecker : Checker {
        override val priority = 40
        override fun check(case: Case): Verdict? =
            if (Lists.isServiceAddress(case.address) && AccountWords.inside(case.text)) Verdict(Category.TRANSACTIONS, BY_ACCOUNT) else null
    }

    // Promotions: an opt-out marker, or a number kept for sales. Rules in the way of SpamBlocker's
    // content rules (Checker.Content): the first rule that matches the text answers.
    private fun containing(description: String, pattern: String, caseSensitive: Boolean = false) =
        Rule(description, ".*(?:$pattern).*", caseSensitive = caseSensitive)

    private const val NOT_LETTER_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val NOT_LETTER_AFTER = "(?![\\p{L}\\p{N}])"

    private val OptOutRules = listOf(
        // "STOP au 36180", "STOP SMS au 36180", "STOP 36180": the word in capitals, then a short number.
        containing("STOP and a short number", "${NOT_LETTER_BEFORE}STOP$NOT_LETTER_AFTER.{0,40}?(?<!\\d)\\d{4,6}(?!\\d)", caseSensitive = true),
        containing(
            "stop and a short number",
            "${NOT_LETTER_BEFORE}stop$NOT_LETTER_AFTER\\s*(?:sms\\s*)?(?:au|to|al|an|a|para|per|naar)?\\s*:?\\s*(?<!\\d)\\d{4,6}(?!\\d)"
        ),
        containing(
            "reply STOP",
            "(?:reply|text|send|txt|r[ée]pondez|r[ée]ponds|envoyez|responde|env[ií]a|escribe|antworte?|senden\\s+sie|rispondi|invia|" +
                "responda|envie|antwoord|stuur)\\W+(?:(?:with|par|con|mit|com|met|por)\\W+)?(?:stop|stopp|end|cancel|baja|sair|pare)$NOT_LETTER_AFTER"
        ),
        containing("English", "unsubscribe|opt[\\s-]?out"),
        containing("Français", "d[ée]sinscri|d[ée]sabonn|ne plus recevoir"),
        containing("Español", "darte de baja|darse de baja|darme de baja|cancelar\\s+(?:tu\\s+)?suscripci[óo]n|para no recibir"),
        containing("Deutsch", "abmelden|abbestellen|abbestellung|nicht mehr (?:zu )?erhalten|keine werbung mehr"),
        containing("Italiano", "disiscri|annulla\\w*\\s+(?:l['’]?\\s*)?iscrizione|per non (?:pi[ùu] )?ricevere|cancellarti"),
        containing("Português", "cancelar\\s+(?:a\\s+)?(?:sua\\s+)?inscri[çc][ãa]o|descadastr|para (?:n[ãa]o|deixar de) receber"),
        containing("Nederlands", "uitschrijven|afmelden|geen (?:berichten|sms) meer")
    ).sortedByDescending { it.priority }

    private object OptOutChecker : Checker {
        override val priority = 30
        override fun check(case: Case): Verdict? = if (OptOutRules.any { it.matches(case.text) }) Verdict(Category.PROMOTIONS, BY_OPT_OUT) else null
    }

    private object SalesNumberChecker : Checker {
        override val priority = 20
        override fun check(case: Case): Verdict? =
            if (SalesCalls.isSalesCall(case.address, case.country)) Verdict(Category.PROMOTIONS, BY_SALES) else null
    }

    /** SpamBlocker's PassedByDefault: the last checker, it always answers. */
    private object ByDefault : Checker {
        override val priority = Int.MIN_VALUE
        override fun check(case: Case): Verdict = Verdict(Category.UNKNOWN, BY_DEFAULT)
    }

    /**
     * A number the user reported answers before any content is read, as SpamBlocker's SpamDB
     * checker answers for a number of its list (Checker.SpamDB, service/checker/Checker.kt, the
     * highest priority of the content-free checkers).
     */
    private class ReportedChecker(private val spam: SpamFilter) : Checker {
        override val priority = 70
        override fun check(case: Case): Verdict? =
            if (spam.reported(senderKey(case.address)) == true) Verdict(Category.SPAM, BY_REPORTED) else null
    }

    /**
     * The classifier trained on the messages the user labelled: SpamBlocker's Checker.NaiveBayes
     * with checkSpam = true (service/checker/Checker.kt), after the checkers that know what a
     * message is (a code, a parcel, an account alert) and before the promotions, since a spam
     * message often carries an opt-out line. A sender the user cleared with Not spam is let by,
     * as SpamBlocker lets a number of its Contact checker by.
     */
    private class LearnedChecker(private val spam: SpamFilter) : Checker {
        override val priority = 35
        override fun check(case: Case): Verdict? =
            if (spam.reported(senderKey(case.address)) != false && spam.isSpam(case.text)) Verdict(Category.SPAM, BY_LEARNED) else null
    }

    private val Checkers: List<Checker> =
        listOf(CodeChecker, ParcelChecker, AccountChecker, OptOutChecker, SalesNumberChecker, ByDefault).sortedByDescending { it.priority }

    /**
     * SpamBlocker's Checker.checkSms: the first checker, from the highest priority, that answers.
     * With a [spam] filter the reported-number and learned checkers join the chain.
     */
    fun check(case: Case, spam: SpamFilter? = null): Verdict {
        val chain = if (spam == null) Checkers else (Checkers + ReportedChecker(spam) + LearnedChecker(spam)).sortedByDescending { it.priority }
        return chain.firstNotNullOf { it.check(case) }
    }

    /**
     * Who a sender is, for the lists of the known: a number by its last nine digits (as
     * Signatures.key does for a person's vibration), a sender written in letters by its name.
     */
    fun senderKey(address: String): String {
        val trimmed = address.trim()
        return if (trimmed.any(Char::isLetter)) trimmed.lowercase() else Signatures.key(trimmed)
    }

    /**
     * A known sender is let through before any content is looked at, as SpamBlocker's Contact
     * checker lets a contact through (Checker.Contact, highest priority) and its Dialed checker
     * lets a number through once the user has written to it (Checker.Dialed, with
     * Util.countHistorySMSByNumber counting the outgoing messages of a number); here: a contact,
     * a sender marked known, or [REPLIES_TO_KNOW] messages sent to it.
     */
    fun isKnown(contact: Boolean, marked: Set<String>, address: String, sent: Int): Boolean =
        contact || senderKey(address) in marked || sent >= REPLIES_TO_KNOW

    /**
     * The verdict on a conversation of the list, or null when it is not screened: a group, a
     * person met over the encrypted chat (an address with no number), a known sender, or a
     * conversation whose newest message is the user's own (writing to someone is not receiving
     * from an unknown sender; iOS lists a conversation the user started). A sender the user
     * reported is spam whoever they are, since it is the user's own word (Google Messages' Report
     * spam moves the conversation whatever it was).
     */
    fun verdict(c: Conversation, known: Boolean, country: String?, spam: SpamFilter? = null): Verdict? {
        if (c.group || c.address.isBlank() || '@' in c.address) return null
        val reported = spam?.reported(senderKey(c.address)) == true
        if (!reported && (known || c.fromMe)) return null
        return check(Case(c.address, c.snippet, country), spam)
    }

    /** iOS 26: a time sensitive message stays in the conversation list for eight hours. */
    fun staysListed(v: Verdict, c: Conversation, now: Long): Boolean = v.timeSensitive && now - c.date < LISTED_MS

    /** The messages the user sent to each conversation, in one read of the store. */
    fun sentCounts(context: Context): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        runCatching {
            context.contentResolver.query(Telephony.Sms.Sent.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getLong(0).let { out[it] = (out[it] ?: 0) + 1 }
            }
        }
        runCatching {
            context.contentResolver.query(Telephony.Mms.Sent.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getLong(0).let { out[it] = (out[it] ?: 0) + 1 }
            }
        }
        return out
    }

    /** The messages the user sent to one conversation (SpamBlocker's countHistorySMSByNumber, by thread). */
    fun sentIn(context: Context, threadId: Long): Int {
        var n = 0
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Sent.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), null
            )?.use { n += it.count }
        }
        runCatching {
            context.contentResolver.query(
                Telephony.Mms.Sent.CONTENT_URI, arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()), null
            )?.use { n += it.count }
        }
        return n
    }

    /**
     * The verdict on a message that has just arrived, for the moment before it notifies (the
     * lists use [verdict]): null when the setting is off or the sender is known; a sender the
     * user reported is spam even then. Off the main thread: the classifier may be trained here.
     */
    fun verdictFor(context: Context, threadId: Long, address: String, text: String, subscription: Int): Verdict? {
        if (address.isBlank() || '@' in address) return null
        val koin = runCatching { org.koin.core.context.GlobalContext.get() }.getOrNull() ?: return null
        if (!koin.get<SettingsStore>().current.screenUnknown) return null
        val spam = koin.get<SpamStore>().filter()
        if (spam.reported(senderKey(address)) != true) {
            val marked = koin.get<KnownStore>().known.value
            if (isKnown(ContactLookup.isContact(context, address), marked, address, sentIn(context, threadId))) return null
        }
        return check(Case(address, text, SalesCalls.country(context, subscription = subscription)), spam)
    }
}

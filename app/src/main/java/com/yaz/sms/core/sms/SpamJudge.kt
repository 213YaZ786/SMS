package com.yaz.sms.core.sms

import android.content.Context
import android.provider.Telephony
import com.yaz.sms.core.dial.ContactLookup
import com.yaz.sms.core.spam.SpamFilter
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.data.settings.SpamStore
import com.yaz.sms.data.sms.Conversation

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
 * Which conversations are Spam, as Google Messages keeps its Spam & blocked
 * (https://support.google.com/messages/answer/9061432): a sender the user reported, or a message
 * the classifier trained on the user's own labels judges spam. Only that: nothing else leaves
 * the main list. The order of the tests is SpamBlocker's (MIT, https://github.com/aj3423/SpamBlocker,
 * app/src/main/java/spam/blocker/service/checker/Checker.kt, checkSms: the highest priority that
 * answers wins): the user's word on the number, then what is plainly not spam (a code, a parcel,
 * an account alert), then the classifier (Checker.NaiveBayes).
 */
object SpamJudge {

    /** Three messages sent to a sender make it known: the classifier leaves it alone (SpamBlocker's Dialed checker, Util.countHistorySMSByNumber). */
    const val REPLIES_TO_KNOW = 3

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

    /**
     * Who a sender is, for the lists of the reported: a number by its last nine digits (as
     * Signatures.key does for a person's vibration), a sender written in letters by its name.
     */
    fun senderKey(address: String): String {
        val trimmed = address.trim()
        return if (trimmed.any(Char::isLetter)) trimmed.lowercase() else Signatures.key(trimmed)
    }

    /** What a classifier is never asked about: a code, a parcel, or an account alert from a service. */
    fun plainlyNotSpam(address: String, text: String): Boolean =
        Codes.isCode(text) || Finds.parcel(text) != null || (Lists.isServiceAddress(address) && AccountWords.inside(text))

    /** Whether a message from [address] is spam, by the user's word on the sender and then the classifier. */
    fun isSpam(address: String, text: String, spam: SpamFilter?): Boolean {
        spam ?: return false
        val word = spam.reported(senderKey(address))
        if (word == true) return true
        // A sender the user cleared with Not spam is let by, as SpamBlocker lets a number of its Contact checker by.
        return word == null && !plainlyNotSpam(address, text) && spam.isSpam(text)
    }

    /**
     * A sender the user vouches for is let through before any content is looked at, as SpamBlocker's
     * Contact checker (highest priority) and Dialed checker do: a contact, or [REPLIES_TO_KNOW]
     * messages sent to it.
     */
    fun isKnown(contact: Boolean, sent: Int): Boolean = contact || sent >= REPLIES_TO_KNOW

    /**
     * Whether a conversation of the list is Spam: not a group, not a person met over the encrypted
     * chat (an address with no number), and either reported (spam whoever they are, since it is the
     * user's own word: Google Messages' Report spam moves the conversation whatever it was), or
     * neither [known] nor ending on the user's own message and judged by the classifier.
     */
    fun isSpam(c: Conversation, known: Boolean, spam: SpamFilter?): Boolean {
        if (c.group || c.address.isBlank() || '@' in c.address) return false
        if (spam?.reported(senderKey(c.address)) == true) return true
        if (known || c.fromMe) return false
        return isSpam(c.address, c.snippet, spam)
    }

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
     * Whether a message that has just arrived is spam, for the moment before it notifies (the
     * lists use [isSpam] on a conversation): false when spam protection is off or the sender is
     * known; a sender the user reported is spam even then. Off the main thread: the classifier
     * may be trained here.
     */
    fun arrivedIsSpam(context: Context, threadId: Long, address: String, text: String): Boolean {
        if (address.isBlank() || '@' in address) return false
        val koin = runCatching { org.koin.core.context.GlobalContext.get() }.getOrNull() ?: return false
        if (!koin.get<SettingsStore>().current.spamProtection) return false
        val spam = koin.get<SpamStore>().filter()
        if (spam.reported(senderKey(address)) != true && isKnown(ContactLookup.isContact(context, address), sentIn(context, threadId))) return false
        return isSpam(address, text, spam)
    }
}

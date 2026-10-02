package com.sms.app.core.link

import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * What a link in a message may be hiding, worked out on the phone from the
 * link alone: nothing is sent anywhere. Each rule is one a scam text
 * relies on: a bare internet address, a name written with letters that
 * imitate others, a company's name on a site that is not the company's, a
 * shortened link from a stranger, or a site on the public list of
 * dangerous ones ([BadHosts]).
 */
object LinkCheck {

    enum class Risk { LISTED, BARE_ADDRESS, DISGUISED, LOOKALIKE, SHORTENED }

    data class Verdict(val risk: Risk, val brand: String? = null) {
        /** Why, in a few words. */
        val words: String
            get() = when (risk) {
                Risk.LISTED -> "A known dangerous site"
                Risk.BARE_ADDRESS -> "An address with no site name"
                Risk.DISGUISED -> "A disguised address"
                Risk.LOOKALIKE -> "Not $brand's site"
                Risk.SHORTENED -> "A shortened link from a stranger"
            }
    }

    /**
     * The worst thing [url] may be, or null when nothing is wrong with it.
     * [stranger]: the sender is not in the contacts. [listed]: the public
     * list of dangerous hosts says so of this host.
     */
    fun check(url: String, stranger: Boolean, listed: (String) -> Boolean = { false }): Verdict? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        // A name before an @ is not where the link goes: paypal.com@evil.example goes to evil.example.
        if (uri.rawUserInfo != null) return Verdict(Risk.DISGUISED)
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (listed(host) || parents(host).any(listed)) return Verdict(Risk.LISTED)
        if (isAddress(host)) return Verdict(Risk.BARE_ADDRESS)
        if (disguised(host)) return Verdict(Risk.DISGUISED)
        // A company's name on another site: said of strangers only, a friend's link to a recipe with "apple" in it is no scam.
        if (stranger) lookalike(host)?.let { return Verdict(Risk.LOOKALIKE, it) }
        if (stranger && registrable(host) in SHORTENERS) return Verdict(Risk.SHORTENED)
        return null
    }

    /** The host and the domains above it: a.b.example.com, b.example.com, example.com. */
    private fun parents(host: String): List<String> {
        val labels = host.split('.')
        return (1 until labels.size - 1).map { labels.drop(it).joinToString(".") }
    }

    private fun isAddress(host: String): Boolean =
        host.startsWith("[") || host.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.toIntOrNull() in 0..255 } } ||
            host.all { it.isDigit() || it == '.' }

    /**
     * A name in another alphabet dressed as Latin letters (раураl with
     * Cyrillic letters), or written in punycode to hide that it is.
     */
    private fun disguised(host: String): Boolean {
        val unicode = runCatching { IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host)
        if (unicode.all { it.code < 128 }) return false
        val scripts = unicode.filter { it.isLetter() }.map { Character.UnicodeScript.of(it.code) }.toSet()
        // Latin mixed with Cyrillic or Greek, or a whole name in those that reads like Latin.
        return Character.UnicodeScript.LATIN in scripts && scripts.size > 1 ||
            scripts.all { it == Character.UnicodeScript.CYRILLIC || it == Character.UnicodeScript.GREEK } &&
            unicode.filter { it.isLetter() }.all { it in LOOK_LATIN }
    }

    /** The company named in [host] when the site is not one of that company's, else null. */
    private fun lookalike(host: String): String? {
        val domain = registrable(host)
        val tokens = host.split('.', '-').filter { it.isNotEmpty() }
        val flat = host.replace(".", "").replace("-", "")
        for (brand in BRANDS) {
            val key = brand.keys.firstOrNull { key -> key in tokens || key.length >= 7 && flat.contains(key) } ?: continue
            if (brand.domains.any { domain == it || host.endsWith(".$it") || host == it }) return null
            // A name that is also an everyday word (apple, orange, chase) needs a scam's own word beside it.
            if (key in EVERYDAY && tokens.none { t -> SCAM_WORDS.any { w -> if (w.length < 4) t == w else t.contains(w) } }) continue
            return brand.name
        }
        return null
    }

    /** Brand keys that are also ordinary words or letters. */
    private val EVERYDAY = setOf("apple", "orange", "chase", "ing", "sat", "ato", "caf", "ants", "edf", "ups", "att", "cra", "gls", "dpd", "free", "citi", "wise", "steam", "engie", "lcl", "sfr", "bnp")

    /** Words scam addresses lean on, in the major languages. */
    private val SCAM_WORDS = listOf(
        "login", "signin", "secure", "security", "verify", "verif", "account", "update", "billing", "support", "pay", "payment", "refund",
        "track", "deliver", "parcel", "package", "unlock", "locked", "confirm", "auth", "id", "wallet", "bonus", "prize",
        "colis", "suivi", "livraison", "paiement", "rembours", "compte", "amende",
        "sicherheit", "konto", "zahlung", "paket", "zustell",
        "envio", "pago", "cuenta", "entrega", "paquete",
        "pagament", "conta", "entrega", "encomenda",
        "rimborso", "verifica", "pacco", "consegna",
        "betaling", "pakket", "bezorg", "rekening"
    )

    /**
     * The name a site registers, roughly: the last two labels, three under
     * the shared second levels (gouv.fr, co.uk). Enough to tell a company's
     * own site from another.
     */
    fun registrable(host: String): String {
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in SHARED_LEVELS) labels.takeLast(3).joinToString(".") else lastTwo
    }

    private class Brand(val name: String, val keys: List<String>, val domains: List<String>)

    private fun brand(name: String, keys: String, domains: String) =
        Brand(name, keys.split(' '), domains.split(' '))

    /**
     * Companies and public services that scam texts most often pretend to
     * be, around the world: parcels, banks, tax and fines, health, phone
     * operators, shops, social networks and money apps. A key is a word of
     * the host (or, long enough, a piece of it); the domains are theirs.
     */
    private val BRANDS = listOf(
        // Worldwide.
        brand("PayPal", "paypal", "paypal.com paypal.fr paypal.de paypal.me paypal-communication.com paypal-community.com paypalobjects.com"),
        brand("Amazon", "amazon", "amazon.com amazon.fr amazon.de amazon.co.uk amazon.es amazon.it amazon.nl amazon.ca amazon.com.au amazon.in amazon.com.br amazon.co.jp amazon.com.mx amazon.pl amazon.se amazon.com.be amazon.ae amazon.sa amazon.sg amazon.com.tr amazon.eg amzn.to amzn.eu"),
        brand("Apple", "apple icloud appleid", "apple.com icloud.com apple.news apple.co"),
        brand("Microsoft", "microsoft outlook office365 hotmail", "microsoft.com live.com office.com outlook.com hotmail.com"),
        brand("Google", "google gmail", "google.com gmail.com youtube.com goo.gl"),
        brand("Netflix", "netflix", "netflix.com"),
        brand("Spotify", "spotify", "spotify.com"),
        brand("Disney+", "disneyplus", "disneyplus.com"),
        brand("WhatsApp", "whatsapp", "whatsapp.com whatsapp.net wa.me"),
        brand("Instagram", "instagram", "instagram.com"),
        brand("Facebook", "facebook", "facebook.com fb.com fb.me"),
        brand("TikTok", "tiktok", "tiktok.com"),
        brand("Telegram", "telegram", "telegram.org t.me"),
        brand("DHL", "dhl", "dhl.com dhl.fr dhl.de dhl.co.uk dhl.es dhl.it dhl.nl"),
        brand("UPS", "ups", "ups.com"),
        brand("FedEx", "fedex", "fedex.com"),
        brand("DPD", "dpd", "dpd.com dpd.fr dpd.de dpd.co.uk"),
        brand("GLS", "gls", "gls-group.com gls-group.eu gls-france.com gls-pakete.de"),
        brand("eBay", "ebay", "ebay.com ebay.fr ebay.de ebay.co.uk ebay.es ebay.it"),
        brand("AliExpress", "aliexpress", "aliexpress.com"),
        brand("Temu", "temu", "temu.com"),
        brand("Shein", "shein", "shein.com"),
        brand("Binance", "binance", "binance.com"),
        brand("Coinbase", "coinbase", "coinbase.com"),
        brand("Revolut", "revolut", "revolut.com"),
        brand("Wise", "transferwise", "wise.com"),
        brand("N26", "n26", "n26.com"),
        brand("Klarna", "klarna", "klarna.com"),
        brand("Steam", "steamcommunity steampowered", "steamcommunity.com steampowered.com"),
        brand("DocuSign", "docusign", "docusign.com docusign.net"),
        brand("Dropbox", "dropbox", "dropbox.com"),
        brand("Booking.com", "bookingcom", "booking.com"),
        // France.
        brand("Ameli", "ameli", "ameli.fr"),
        brand("the tax office", "impots impot dgfip", "impots.gouv.fr economie.gouv.fr"),
        brand("ANTAI", "antai amendes", "antai.gouv.fr amendes.gouv.fr"),
        brand("ANTS", "ants", "ants.gouv.fr"),
        brand("Mon Compte Formation", "moncompteformation cpf", "moncompteformation.gouv.fr"),
        brand("CAF", "caf", "caf.fr"),
        brand("URSSAF", "urssaf", "urssaf.fr"),
        brand("Crit'Air", "critair certificat-air", "certificat-air.gouv.fr"),
        brand("La Poste", "laposte labanquepostale", "laposte.fr laposte.net labanquepostale.fr"),
        brand("Colissimo", "colissimo", "colissimo.fr laposte.fr"),
        brand("Chronopost", "chronopost", "chronopost.fr chronopost.com"),
        brand("Mondial Relay", "mondialrelay", "mondialrelay.fr mondialrelay.com"),
        brand("Vinted", "vinted", "vinted.fr vinted.com vinted.de vinted.es vinted.it vinted.nl vinted.be vinted.co.uk vinted.pl vinted.pt"),
        brand("Leboncoin", "leboncoin", "leboncoin.fr"),
        brand("Crédit Agricole", "creditagricole credit-agricole", "credit-agricole.fr credit-agricole.com"),
        brand("Société Générale", "societegenerale", "societegenerale.fr societegenerale.com"),
        brand("BNP Paribas", "bnpparibas bnp", "bnpparibas.fr bnpparibas.com bnpparibas mabanque.bnpparibas"),
        brand("Caisse d'Epargne", "caisseepargne caisse-epargne", "caisse-epargne.fr"),
        brand("Boursorama", "boursorama boursobank", "boursorama.com boursobank.com"),
        brand("LCL", "lcl", "lcl.fr"),
        brand("Crédit Mutuel", "creditmutuel", "creditmutuel.fr"),
        brand("Orange", "orange", "orange.fr orange.com sosh.fr"),
        brand("SFR", "sfr", "sfr.fr"),
        brand("Bouygues Telecom", "bouyguestelecom bouygues", "bouyguestelecom.fr"),
        brand("Free", "freemobile free-mobile", "free.fr free-mobile.fr"),
        brand("EDF", "edf", "edf.fr"),
        brand("Engie", "engie", "engie.fr engie.com"),
        // United States.
        brand("USPS", "usps", "usps.com"),
        brand("the IRS", "irs", "irs.gov"),
        brand("E-ZPass", "ezpass e-zpass", "e-zpassny.com ezpassnj.com ezpassva.com ezpassmd.com e-zpassiag.com"),
        brand("Chase", "chase", "chase.com"),
        brand("Bank of America", "bankofamerica", "bankofamerica.com bofa.com"),
        brand("Wells Fargo", "wellsfargo", "wellsfargo.com"),
        brand("Citi", "citi citibank", "citi.com citibank.com"),
        brand("Venmo", "venmo", "venmo.com"),
        brand("Cash App", "cashapp", "cash.app cash.me"),
        brand("Zelle", "zelle", "zellepay.com"),
        brand("Verizon", "verizon", "verizon.com"),
        brand("AT&T", "att", "att.com"),
        brand("T-Mobile", "tmobile t-mobile", "t-mobile.com"),
        // United Kingdom.
        brand("HMRC", "hmrc", "gov.uk"),
        brand("the DVLA", "dvla", "gov.uk"),
        brand("the NHS", "nhs", "nhs.uk"),
        brand("Royal Mail", "royalmail", "royalmail.com"),
        brand("Evri", "evri myhermes", "evri.com"),
        brand("Barclays", "barclays", "barclays.co.uk barclays.com"),
        brand("HSBC", "hsbc", "hsbc.co.uk hsbc.com hsbc.fr"),
        brand("Lloyds", "lloyds lloydsbank", "lloydsbank.com lloyds.com"),
        brand("NatWest", "natwest", "natwest.com"),
        brand("Santander", "santander", "santander.co.uk santander.com santander.es santander.de"),
        brand("Monzo", "monzo", "monzo.com"),
        // Germany, Austria, Switzerland.
        brand("Deutsche Post", "deutschepost", "deutschepost.de dhl.de"),
        brand("Hermes", "hermes", "myhermes.de hermesworld.com hermes.com"),
        brand("Sparkasse", "sparkasse", "sparkasse.de sparkasse.at"),
        brand("Volksbank", "volksbank", "volksbank.de vr.de"),
        brand("Deutsche Bank", "deutschebank", "deutsche-bank.de db.com"),
        brand("Commerzbank", "commerzbank", "commerzbank.de"),
        brand("ING", "ing", "ing.com ing.de ing.nl ing.be ing.es ing.it ing.fr"),
        brand("Swiss Post", "swisspost postfinance", "post.ch postfinance.ch"),
        // Spain.
        brand("Correos", "correos", "correos.es correos.com"),
        brand("the tax agency", "agenciatributaria", "agenciatributaria.gob.es agenciatributaria.es"),
        brand("the DGT", "dgt", "dgt.es"),
        brand("BBVA", "bbva", "bbva.es bbva.com"),
        brand("CaixaBank", "caixabank", "caixabank.es caixabank.com"),
        // Italy.
        brand("Poste Italiane", "posteitaliane", "poste.it posteitaliane.it"),
        brand("the Agenzia delle Entrate", "agenziaentrate", "agenziaentrate.gov.it"),
        brand("Intesa Sanpaolo", "intesasanpaolo", "intesasanpaolo.com"),
        brand("UniCredit", "unicredit", "unicredit.it unicredit.eu"),
        // Netherlands and Belgium.
        brand("PostNL", "postnl", "postnl.nl postnl.com"),
        brand("the Belastingdienst", "belastingdienst", "belastingdienst.nl"),
        brand("Rabobank", "rabobank", "rabobank.nl rabobank.com"),
        brand("ABN AMRO", "abnamro", "abnamro.nl abnamro.com"),
        brand("bpost", "bpost", "bpost.be bpost.cloud"),
        brand("itsme", "itsme", "itsme-id.com itsme.be"),
        // Canada, Australia.
        brand("Canada Post", "canadapost postescanada", "canadapost-postescanada.ca canadapost.ca"),
        brand("the CRA", "cra", "canada.ca"),
        brand("Australia Post", "auspost", "auspost.com.au"),
        brand("myGov", "mygov", "my.gov.au"),
        brand("the ATO", "ato", "ato.gov.au"),
        brand("Linkt", "linkt", "linkt.com.au"),
        // India.
        brand("India Post", "indiapost", "indiapost.gov.in"),
        brand("SBI", "sbi onlinesbi", "sbi.co.in onlinesbi.sbi"),
        brand("HDFC Bank", "hdfc hdfcbank", "hdfcbank.com"),
        brand("ICICI Bank", "icici icicibank", "icicibank.com"),
        // Brazil, Mexico.
        brand("Correios", "correios", "correios.com.br"),
        brand("gov.br", "govbr", "gov.br"),
        brand("Nubank", "nubank", "nubank.com.br nu.com.mx"),
        brand("Itaú", "itau", "itau.com.br"),
        brand("Bradesco", "bradesco", "bradesco.com.br"),
        brand("the SAT", "sat", "sat.gob.mx"),
    )

    /** Link shorteners: from a stranger they hide where the link goes. */
    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.ly", "is.gd", "v.gd", "cutt.ly", "rebrand.ly", "shorturl.at", "rb.gy", "ow.ly",
        "s.id", "tiny.cc", "t.co", "bit.do", "goo.su", "urlz.fr", "qrco.de", "shorturl.gg", "tny.im", "short.gy", "clck.ru", "u.to"
    )

    /** Second levels under which each name registers its own third level. */
    private val SHARED_LEVELS = setOf(
        "gouv.fr", "co.uk", "org.uk", "gov.uk", "ac.uk", "com.au", "net.au", "co.nz", "co.jp", "com.br", "com.mx",
        "com.tr", "co.za", "com.cn", "co.in", "co.kr", "com.ar", "com.es", "asso.fr", "gob.es", "gov.it", "gov.au",
        "com.au", "gov.br", "gob.mx", "com.mx", "gov.in", "nhs.uk", "or.jp", "ne.jp"
    )

    /** Cyrillic and Greek letters drawn like Latin ones. */
    private val LOOK_LATIN = setOf(
        'а', 'е', 'о', 'р', 'с', 'у', 'х', 'і', 'ј', 'ѕ', 'һ', 'ԁ', 'ԛ', 'ԝ', 'ӏ',
        'α', 'ο', 'ρ', 'ν', 'τ', 'κ', 'ι', 'χ'
    )
}

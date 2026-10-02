package com.sms.app.core.link

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Links people really get by text, around the world, from someone not in
 * the contacts (the strictest case): none may be flagged. And scam shapes
 * seen in the wild: each must be.
 */
class LinkCheckWideTest {

    private val sound = listOf(
        // France
        "https://www.ameli.fr/assure/remboursements", "https://assure.ameli.fr/PortailAS/appmanager", "https://www.impots.gouv.fr/accueil",
        "https://www.antai.gouv.fr/", "https://www.caf.fr/allocataires", "https://www.service-public.fr/particuliers",
        "https://www.laposte.fr/outils/suivre-vos-envois?code=6A12345678901", "https://www.colissimo.fr/", "https://www.chronopost.fr/tracking-no-cms/suivi-page?listeNumerosLT=XY",
        "https://www.mondialrelay.fr/suivi-de-colis/", "https://www.vinted.fr/items/123-robe", "https://www.leboncoin.fr/ad/voitures/123",
        "https://www.credit-agricole.fr/ca-paris/particulier.html", "https://particuliers.societegenerale.fr/", "https://mabanque.bnpparibas/",
        "https://www.labanquepostale.fr/", "https://www.lcl.fr/", "https://www.creditmutuel.fr/fr/particuliers.html", "https://www.boursobank.com/",
        "https://www.orange.fr/portail", "https://boutique.orange.fr/", "https://www.sfr.fr/", "https://mobile.free.fr/account",
        "https://www.bouyguestelecom.fr/", "https://particulier.edf.fr/fr", "https://www.doctolib.fr/rendez-vous", "https://www.sncf-connect.com/",
        // Worldwide shops, maps, chat, video
        "https://www.amazon.fr/gp/your-account/order-details?orderID=1", "https://amzn.eu/d/abc123", "https://a.co/d/xyz", "https://www.amazon.co.jp/",
        "https://maps.app.goo.gl/AbCdEf", "https://goo.gl/maps/xyz", "https://docs.google.com/document/d/1", "https://forms.gle/abc", "https://meet.google.com/abc-defg-hij",
        "https://youtu.be/dQw4w9WgXcQ", "https://www.youtube.com/watch?v=x", "https://wa.me/33612345678", "https://chat.whatsapp.com/AbC",
        "https://www.instagram.com/p/abc/", "https://vm.tiktok.com/ZMabc/", "https://fb.watch/abc/", "https://t.me/channel",
        "https://apple.news/Abc", "https://apps.apple.com/app/id1", "https://music.apple.com/fr/album/1", "https://support.apple.com/fr-fr/HT1",
        "https://www.paypal.com/myaccount/summary", "https://www.paypal-communication.com/", "https://www.netflix.com/browse",
        "https://outlook.office.com/mail/", "https://outlook.live.com/mail/", "https://aka.ms/abc", "https://zoom.us/j/123456",
        "https://www.booking.com/hotel/fr/x.html", "https://www.airbnb.fr/rooms/1", "https://open.spotify.com/track/1",
        "https://bucket.s3.amazonaws.com/file.pdf", "https://www.dhl.com/fr-fr/home/suivi.html", "https://www.ups.com/track?tracknum=1Z",
        "https://www.fedex.com/fedextrack/?trknbr=1", "https://www.dpd.fr/trace/123", "https://www.ebay.fr/itm/1", "https://www.aliexpress.com/item/1.html",
        "https://www.temu.com/", "https://www.revolut.com/", "https://wise.com/", "https://steamcommunity.com/id/x",
        // United States, United Kingdom
        "https://tools.usps.com/go/TrackConfirmAction?tLabels=9400", "https://www.irs.gov/refunds", "https://www.chase.com/", "https://www.bankofamerica.com/",
        "https://www.wellsfargo.com/", "https://venmo.com/u/x", "https://cash.app/\$x", "https://www.verizon.com/", "https://www.t-mobile.com/",
        "https://www.gov.uk/check-mot-history", "https://www.tax.service.gov.uk/", "https://www.nhs.uk/nhs-app/", "https://www.royalmail.com/track-your-item",
        "https://www.evri.com/track-a-parcel", "https://www.barclays.co.uk/", "https://www.hsbc.co.uk/", "https://www.natwest.com/", "https://monzo.com/",
        // Europe, Canada, Australia, India, Brazil
        "https://www.sparkasse.de/", "https://www.dhl.de/de/privatkunden.html", "https://www.ing.de/", "https://www.commerzbank.de/",
        "https://www.correos.es/es/es/herramientas/localizador/envios", "https://sede.agenciatributaria.gob.es/", "https://www.bbva.es/", "https://www.caixabank.es/",
        "https://www.poste.it/cerca/index.html", "https://www.agenziaentrate.gov.it/", "https://www.intesasanpaolo.com/", "https://www.postnl.nl/tracktrace/",
        "https://www.belastingdienst.nl/", "https://www.rabobank.nl/", "https://track.bpost.cloud/btr/web/#/search?itemCode=1", "https://www.bpost.be/",
        "https://www.canadapost-postescanada.ca/track-reperage/en", "https://www.canada.ca/en/revenue-agency.html", "https://auspost.com.au/mypost/track/",
        "https://my.gov.au/", "https://www.ato.gov.au/", "https://www.onlinesbi.sbi/", "https://netbanking.hdfcbank.com/netbanking/", "https://www.icicibank.com/",
        "https://rastreamento.correios.com.br/app/index.php", "https://www.gov.br/pt-br", "https://nubank.com.br/", "https://www.itau.com.br/",
        // Sites in their own letters, and ordinary words
        "https://www.müller.de/", "https://почта.рф/", "https://www.wikipedia.org/wiki/Main_Page", "https://github.com/213YaZ786/SMS",
        "https://www.lemonde.fr/article", "https://www.bbc.co.uk/news", "https://www.startups-groups.com", "https://apple-pie-recipes.example"
    )

    private val scams = listOf(
        "https://colissimo.suivi-colis.top/fr", "https://ameli-remboursement.info/dossier", "https://antai-gouv.fr/paiement",
        "https://impots-remboursement.com/", "https://laposte-avis.com/colis", "https://chronopost-livraison.net/",
        "https://usps.com-track.info/us", "https://usps-redelivery.top/", "https://irs-refund-claim.com/", "https://ezpass-toll-payment.xyz/",
        "https://hmrc-refund-claim.co/uk", "https://royalmail-redelivery.com/", "https://evri-parcel-update.net/", "https://nhs-covid-pass.org/",
        "https://dhl-paket-zustellung.de.com/", "https://sparkasse-sicherheit.online/", "https://correos-envio.top/", "https://posteitaliane-verifica.com/",
        "https://postnl-bezorging.info/", "https://auspost.parcel-redelivery.net/", "https://canadapost-delivery.ca.com/",
        "https://paypal.com.secure-login.xyz/", "https://www.paypal.com@evil.example/login", "https://xn--pyl-6cdb9g.com/",
        "https://amazon-prime-renew.com/", "https://netflix-billing-update.com/", "https://apple-id-locked.com/", "https://whatsapp-verify.net/",
        "https://bit.ly/3xYzAb", "https://tinyurl.com/abc", "http://185.12.44.2/pay", "http://[2001:db8::1]/x"
    )

    @Test
    fun linksPeopleReallyGetAreNotFlagged() {
        val flagged = sound.mapNotNull { url -> LinkCheck.check(url, stranger = true)?.let { "$url -> ${it.risk} ${it.brand ?: ""}" } }
        assertTrue("Flagged by mistake:\n" + flagged.joinToString("\n"), flagged.isEmpty())
    }

    @Test
    fun scamShapesAreFlagged() {
        val missed = scams.filter { LinkCheck.check(it, stranger = true) == null }
        assertTrue("Missed:\n" + missed.joinToString("\n"), missed.isEmpty())
    }
}

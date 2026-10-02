package com.sms.app.core.link

import com.sms.app.core.link.LinkCheck.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkCheckTest {

    private fun risk(url: String, stranger: Boolean = true, listed: Set<String> = emptySet()) =
        LinkCheck.check(url, stranger) { it in listed }?.risk

    @Test
    fun aCompanysNameOnAnotherSiteIsALookalike() {
        assertEquals(Risk.LOOKALIKE, risk("https://ameli-remboursement.info/dossier"))
        assertEquals(Risk.LOOKALIKE, risk("https://colissimo.suivi-colis.top/fr"))
        assertEquals(Risk.LOOKALIKE, risk("https://paypal.com.secure-login.xyz"))
        assertEquals(Risk.LOOKALIKE, risk("https://antai-gouv.fr/paiement"))
        assertEquals("Ameli", LinkCheck.check("https://ameli-remboursement.info", true)?.brand)
    }

    @Test
    fun scamsAroundTheWorldAreSaid() {
        assertEquals("USPS", LinkCheck.check("https://usps.com-track.info/us", true)?.brand)
        assertEquals("HMRC", LinkCheck.check("https://hmrc-refund-claim.co/uk", true)?.brand)
        assertEquals("Correos", LinkCheck.check("https://correos-envio.top", true)?.brand)
        assertEquals("Poste Italiane", LinkCheck.check("https://posteitaliane-verifica.com", true)?.brand)
        assertEquals("Australia Post", LinkCheck.check("https://auspost.parcel-redelivery.net", true)?.brand)
        assertEquals("Booking.com", LinkCheck.check("https://booking-com.reservation-confirm.xyz", true)?.brand)
    }

    @Test
    fun theirOwnSitesAroundTheWorldAreFine() {
        assertNull(risk("https://tools.usps.com/go/TrackConfirmAction"))
        assertNull(risk("https://www.gov.uk/government/organisations/hm-revenue-customs"))
        assertNull(risk("https://www.correos.es/es/es/herramientas/localizador"))
        assertNull(risk("https://www.poste.it/"))
        assertNull(risk("https://auspost.com.au/mypost/track"))
        assertNull(risk("https://www.booking.com/"))
        assertNull(risk("https://www.restaurantbooking.co.uk/"))
    }

    @Test
    fun aCompanysOwnSiteIsFine() {
        assertNull(risk("https://www.ameli.fr/assure"))
        assertNull(risk("https://www.impots.gouv.fr/accueil"))
        assertNull(risk("https://www.laposte.fr/outils/suivre-vos-envois"))
        assertNull(risk("https://www.paypal.com/fr/home"))
        assertNull(risk("https://mabanque.bnpparibas/"))
        assertNull(risk("https://www.amazon.co.uk/"))
    }

    @Test
    fun ordinaryWordsAreNotCompanies() {
        assertNull(risk("https://www.startups-groups.com"))
        assertNull(risk("https://plants.example.org"))
    }

    @Test
    fun aFriendsLinkWithACompanysWordIsNotSaid() {
        assertNull(risk("https://apple-pie-recipes.com", stranger = false))
    }

    @Test
    fun bareAddressesAreSaid() {
        assertEquals(Risk.BARE_ADDRESS, risk("http://185.12.44.2/colis", stranger = false))
        assertEquals(Risk.BARE_ADDRESS, risk("http://[2001:db8::1]/x", stranger = false))
    }

    @Test
    fun disguisedAddressesAreSaid() {
        // "paypal" written with a Cyrillic а and р.
        assertEquals(Risk.DISGUISED, risk("https://xn--pyl-6cdb9g.com", stranger = false))
        assertEquals(Risk.DISGUISED, risk("https://www.paypal.com@evil.example/login", stranger = false))
    }

    @Test
    fun shortenedLinksFromStrangersOnly() {
        assertEquals(Risk.SHORTENED, risk("https://bit.ly/3abcd"))
        assertNull(risk("https://bit.ly/3abcd", stranger = false))
    }

    @Test
    fun theListedOnesAreSaidFirst() {
        assertEquals(Risk.LISTED, risk("https://cdn.bad.example/file.apk", stranger = false, listed = setOf("bad.example")))
        assertEquals(Risk.LISTED, risk("https://bad.example", stranger = false, listed = setOf("bad.example")))
    }

    @Test
    fun ordinaryLinksAreFine() {
        assertNull(risk("https://www.wikipedia.org/wiki/Paris"))
        assertNull(risk("https://github.com/213YaZ786/SMS"))
        assertNull(risk("not a link"))
    }
}

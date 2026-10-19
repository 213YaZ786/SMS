package com.yaz.sms.core.dial

import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The number ranges a country's regulator keeps for sales calls, so a
 * cold call is known by its number alone, on the phone, with no list to
 * download. A country is added only when its regulator's own text reserves
 * a range to telemarketing, cited beside it. Most countries have none
 * (checked 2026-10-18: Germany, UK, USA, Canada, Netherlands, Belgium,
 * Portugal, Argentina, Mexico, Colombia rely on consent registers and
 * caller ID rules; Italy's AGCOM 0844 (Delibera 156/18/CIR) is defined but
 * unused, its Delibera 21/26/CIR says so; Brazil's Anatel 0303 stopped
 * being mandatory in August 2025).
 */
object SalesCalls {

    /**
     * France: ARCEP decision no. 2022-1583 of 1 September 2022 (since 1 January 2023),
     * the "numeros polyvalents verifies" automated outbound calls must use:
     * mainland and overseas ranges, the nine digits after the trunk 0.
     */
    private val France = listOf(
        "162", "163", "270", "271", "377", "378", "424", "425", "568", "569", "948", "949",
        "9475", "9476", "9477", "9478", "9479"
    )

    /** One country: its ISO code, its calling code, and what a national number (no trunk 0) must be. */
    private class Rule(val iso: String, val code: String, val inRange: (String) -> Boolean)

    private val rules = listOf(
        Rule("fr", "33") { it.length == 9 && France.any(it::startsWith) },
        // Spain: Resolucion of 14 April 2026 (BOE-A-2026-8409, under Ley 10/2025 art. 16.3): code 400,
        // nine digits, the only range allowed for commercial calls from 17 October 2026.
        Rule("es", "34") { it.length == 9 && it.startsWith("400") },
        // India: TRAI, TCCCPR 2018 and its 2025 amendment, press release no. 91/2026: the 140 series
        // is for promotional calls. The 1600 series is banks' and governments' service calls and
        // must never be blocked or tagged, so it is not here.
        Rule("in", "91") { it.length == 10 && it.startsWith("140") },
        // Chile: Subtel Resolucion Exenta 286/2024 (in force 13 August 2025, updated 5 June 2026):
        // 809 marks unsolicited mass advertising calls (600 is for calls the person asked for,
        // not sales). The length is not stated: Chile's 600 and 800 numbers have 9 or 10 digits.
        Rule("cl", "56") { it.length in 9..10 && it.startsWith("809") },
        // Peru: Decreto Supremo 018-2026-MTC (El Peruano, 15 September 2026), article 5:
        // the 500 series, format 500 XXX XXX, is exclusive to commercial and advertising calls.
        Rule("pe", "51") { it.length == 9 && it.startsWith("500") }
    )

    /** Countries with ranges, by ISO code. */
    val countries: Set<String> = rules.map { it.iso }.toSet()

    /**
     * The country a national number is read in: the network of the line
     * (the call's [account] or the message's [subscription]), then its SIM,
     * then the phone's language. The network alone is blank on Wi-Fi calling
     * or before it is found.
     */
    fun country(
        context: Context,
        account: PhoneAccountHandle? = null,
        subscription: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    ): String? {
        val phone = context.getSystemService(TelephonyManager::class.java)
        val line = runCatching {
            when {
                account != null -> phone?.createForPhoneAccountHandle(account)
                SubscriptionManager.isValidSubscriptionId(subscription) -> phone?.createForSubscriptionId(subscription)
                else -> null
            }
        }.getOrNull() ?: phone
        return firstCountry(line?.networkCountryIso, line?.simCountryIso, Locale.getDefault().country)
    }

    fun firstCountry(vararg candidates: String?): String? = candidates.firstOrNull { !it.isNullOrBlank() }?.lowercase()

    /**
     * Whether [number] is in a range kept for sales calls. Matched twice,
     * as Android's own blocked list matches every caller (AOSP
     * BlockedNumberProvider's Utils.getE164Number, Dialer's Blocking.java):
     * once put in E.164 by the system, once as it came. So +33 (0)5 68…,
     * +330…, 0033…, 05 68… and an ;ext= are all the same number, read as
     * [country] dials it when no + leads.
     */
    fun isSalesCall(number: String, country: String?): Boolean {
        val e164 = runCatching {
            PhoneNumberUtils.formatNumberToE164(number, country?.uppercase(Locale.ROOT))
        }.getOrNull()
        return (e164 != null && inSalesRange(e164, country)) || inSalesRange(number, country)
    }

    private fun inSalesRange(number: String, country: String?): Boolean {
        val digits = number.substringBefore(';').trim()
            .filterIndexed { i, c -> c.isDigit() || (c == '+' && i == 0) }
            .replaceFirst(Regex("^00"), "+")
        val (rule, rest) = when {
            digits.startsWith("+") -> {
                val found = rules.firstOrNull { digits.startsWith("+" + it.code) } ?: return false
                found to digits.removePrefix("+" + found.code)
            }
            else -> (rules.firstOrNull { it.iso == country?.lowercase() } ?: return false) to digits
        }
        // The trunk 0 of a national form, or one some networks keep after the country code.
        return rule.inRange(rest.removePrefix("0"))
    }
}

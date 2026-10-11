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
 * download. Countries are added as their rules are published.
 */
object SalesCalls {

    /** France (ARCEP, since 2023): mainland and overseas ranges, the nine digits after the trunk 0. */
    private val France = listOf(
        "162", "163", "270", "271", "377", "378", "424", "425", "568", "569", "948", "949",
        "9475", "9476", "9477", "9478", "9479"
    )

    /** Countries with ranges, by ISO code. */
    val countries = setOf("fr", "es", "in")

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
        val (cc, rest) = when {
            digits.startsWith("+33") -> "fr" to digits.removePrefix("+33")
            digits.startsWith("+34") -> "es" to digits.removePrefix("+34")
            digits.startsWith("+91") -> "in" to digits.removePrefix("+91")
            digits.startsWith("+") -> return false
            else -> country?.lowercase() to digits
        }
        // The trunk 0 of a national form, or one some networks keep after the country code.
        val national = rest.removePrefix("0")
        return when (cc) {
            "fr" -> national.length == 9 && France.any(national::startsWith)
            // Spain (Law 10/2025): nine digit numbers starting with 400.
            "es" -> national.length == 9 && national.startsWith("400")
            // India (TRAI): the 140 series, kept for promotional calls.
            "in" -> national.length == 10 && national.startsWith("140")
            else -> false
        }
    }
}

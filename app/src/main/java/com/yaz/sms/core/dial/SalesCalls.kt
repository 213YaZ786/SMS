package com.yaz.sms.core.dial

import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The number ranges a country's regulator keeps for sales calls, so a
 * cold call is known by its number alone, on the phone, with no list to
 * download. Countries are added as their rules are published.
 */
object SalesCalls {

    /** France (ARCEP, since 2023): mainland and overseas ranges. */
    private val France = listOf(
        "0162", "0163", "0270", "0271", "0377", "0378", "0424", "0425", "0568", "0569", "0948", "0949",
        "09475", "09476", "09477", "09478", "09479"
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
     * Whether [number] is in a range kept for sales calls, read as [country]
     * dials it (the network's country) when it has no +.
     */
    fun isSalesCall(number: String, country: String?): Boolean {
        val digits = number.filterIndexed { i, c -> c.isDigit() || (c == '+' && i == 0) }.replaceFirst(Regex("^00"), "+")
        val (cc, national) = when {
            digits.startsWith("+33") -> "fr" to "0" + digits.removePrefix("+33")
            digits.startsWith("+34") -> "es" to digits.removePrefix("+34")
            digits.startsWith("+91") -> "in" to digits.removePrefix("+91")
            digits.startsWith("+") -> return false
            else -> country?.lowercase() to digits
        }
        return when (cc) {
            "fr" -> national.length == 10 && France.any { national.startsWith(it) }
            // Spain (Law 10/2025): nine digit numbers starting with 400.
            "es" -> national.length == 9 && national.startsWith("400")
            // India (TRAI): the 140 series, kept for promotional calls.
            "in" -> national.removePrefix("0").let { it.length == 10 && it.startsWith("140") }
            else -> false
        }
    }
}

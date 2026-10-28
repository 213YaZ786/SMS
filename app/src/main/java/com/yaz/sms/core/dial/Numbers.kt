package com.yaz.sms.core.dial

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** Numbers written the local way: 06 12 34 56 78 in France, (650) 555-1234 in the US; a foreign one in full: +33 2 81 03 77 77. */
object Numbers {

    /** The country of the network, else of the phone's language. */
    fun countryIso(context: Context): String =
        context.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.takeIf { it.isNotBlank() }?.uppercase()
            ?: Locale.getDefault().country

    /**
     * The countries a national number may be dialled in, the phone's own first: the call's network, its SIM, the
     * language. Same order as SalesCalls.country (Modules' phone kit), which keeps only the first; here every one is
     * kept, because the network of an emulator or a roaming phone is not where a saved number comes from.
     */
    fun countries(context: Context): List<String> {
        val phone = context.getSystemService(TelephonyManager::class.java)
        return listOf(phone?.networkCountryIso, phone?.simCountryIso, Locale.getDefault().country)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT) }
            .distinct()
    }

    fun format(context: Context, number: String): String = format(number, countries(context))

    /**
     * The number as the phone's own country writes it, else in full international form. Ported from AOSP Dialer's
     * PhoneNumberHelper.formatNumber: the number is parsed with parseAndKeepRawInput in the country of the call's
     * network, else of its SIM, else of the locale; "*" and "#" numbers are never formatted; a SIP address is left as
     * it is. The first country where the number is valid reads it. It shows in NATIONAL format when that country is
     * the first candidate (the phone's own), else INTERNATIONAL (a number of another country, or one typed with "+").
     * Anything invalid in every country is returned as typed.
     * https://android.googlesource.com/platform/packages/apps/Dialer/+/refs/heads/master/java/com/android/dialer/phonenumberutil/PhoneNumberHelper.java
     * Parsing and formats: https://github.com/google/libphonenumber (PhoneNumberUtil.java, Apache-2.0).
     */
    fun format(number: String, countries: List<String>): String {
        val text = number.trim()
        if (text.isEmpty() || text.startsWith('*') || text.startsWith('#')) return number
        if (text.any { it == '@' || it.isLetter() }) return number
        val util = PhoneNumberUtil.getInstance()
        val regions = countries.map { it.uppercase(Locale.ROOT) }.filter { it in util.supportedRegions }
        // A number typed with "+" is read the same in every country: one try suffices.
        val tries = if (text.startsWith('+')) listOf(regions.firstOrNull() ?: "ZZ") else regions
        for (country in tries) {
            val parsed = runCatching { util.parseAndKeepRawInput(text, country) }.getOrNull() ?: continue
            if (!util.isValidNumber(parsed)) continue
            val own = util.getRegionCodeForNumber(parsed) == regions.firstOrNull()
            return util.format(
                parsed,
                if (own) PhoneNumberUtil.PhoneNumberFormat.NATIONAL else PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
            )
        }
        return number
    }
}

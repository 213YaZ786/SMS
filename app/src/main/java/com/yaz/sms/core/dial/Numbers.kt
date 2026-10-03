package com.yaz.sms.core.dial

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/** Numbers written the local way: 06 12 34 56 78 in France, (650) 555-1234 in the US. */
object Numbers {

    /** The country of the network, else of the phone's language. */
    fun countryIso(context: Context): String =
        context.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.takeIf { it.isNotBlank() }?.uppercase()
            ?: Locale.getDefault().country

    fun format(context: Context, number: String): String =
        if (number.isBlank()) number else PhoneNumberUtils.formatNumber(number, countryIso(context)) ?: number
}

package com.yaz.sms.core.mms

/**
 * The subject of a picture message as it is shown. Carriers and some phones fill the field with
 * "no subject" when the sender wrote none; AOSP Messaging's MmsUtils.cleanseMmsSubject takes that out
 * (https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/sms/MmsUtils.java)
 * against its empty_subject_strings (res/values/arrays.xml, "no subject" and "nosubject").
 */
object MmsSubject {

    /** AOSP Messaging's res/values/arrays.xml, empty_subject_strings. */
    private val EMPTY = listOf("no subject", "nosubject")

    /** The longest subject shown, a few lines: a hostile sender cannot fill the screen with it. */
    private const val MAX = 200

    /**
     * [subject] as shown, or null when there is nothing to show: empty, or one of the fillers, compared
     * ignoring case as AOSP does (equalsIgnoreCase). Blanks around it are dropped, control characters
     * (which the sender can put in a header) become spaces and the length is capped; none of that is in AOSP.
     */
    fun cleanse(subject: String?): String? {
        val clean = subject?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")?.trim().orEmpty()
        if (clean.isEmpty()) return null
        if (EMPTY.any { clean.equals(it, ignoreCase = true) }) return null
        return if (clean.length > MAX) clean.take(MAX - 1).trimEnd() + "…" else clean
    }
}

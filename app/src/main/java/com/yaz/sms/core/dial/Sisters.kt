package com.yaz.sms.core.dial

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Dialer, SMS and Contacts call each other's providers and activities by
 * authority or action name, which any app may claim when ours is not
 * installed. Nothing is asked or opened before the package behind the name
 * is one of ours and signed with the same key as this app. The debug
 * packages and authorities (".debug") count only in a debug build.
 */
object Sisters {

    private val apps = listOf("dialer", "sms", "contacts")

    /** The packages of our apps: the release ones, and the debug ones in a debug build only. */
    fun packages(app: String): List<String> =
        if (com.yaz.sms.BuildConfig.DEBUG) listOf("com.yaz.$app", "com.yaz.$app.debug") else listOf("com.yaz.$app")

    /**
     * Whether [pkg] is one of ours and has this app's signature. Adapted from
     * the comparison of signatures of PackageManager.checkSignatures
     * (https://developer.android.com/reference/android/content/pm/PackageManager#checkSignatures(java.lang.String,%20java.lang.String)).
     */
    fun isOurs(context: Context, pkg: String?): Boolean {
        if (pkg == null || apps.none { pkg in packages(it) }) return false
        if (pkg == context.packageName) return true
        return runCatching { context.packageManager.checkSignatures(context.packageName, pkg) == PackageManager.SIGNATURE_MATCH }
            .getOrDefault(false)
    }

    /**
     * Whether the provider answering [authority] belongs to one of our apps.
     * Adapted from belongsToCurrentApplication of Android's content resolver
     * risks (https://developer.android.com/privacy-and-security/risks/content-resolver):
     * it compares the package to the app's own, here to ours and signed alike.
     * Null from resolveContentProvider means no provider: not ours.
     */
    @Suppress("DEPRECATION")
    fun providerIsOurs(context: Context, authority: String): Boolean {
        val info = runCatching { context.packageManager.resolveContentProvider(authority, 0) }.getOrNull() ?: return false
        return isOurs(context, info.packageName)
    }

    /** The authorities to try for [base] (com.yaz.contacts.cards): it, then in a debug build com.yaz.contacts.debug.cards. */
    fun authorities(base: String): List<String> {
        if (!com.yaz.sms.BuildConfig.DEBUG) return listOf(base)
        val parts = base.split('.')
        return listOf(base, (parts.take(3) + "debug" + parts.drop(3)).joinToString("."))
    }

    /** Of [authorities] for [base], those whose provider is ours: the only ones to ask. */
    fun usable(context: Context, base: String): List<String> = authorities(base).filter { providerIsOurs(context, it) }

    /**
     * [intent] aimed at the one of our apps that answers it, or null when none
     * does: no intent goes out for any app to take. Adapted from the fix for
     * implicit intent hijacking
     * (https://developer.android.com/privacy-and-security/risks/implicit-intent-hijacking):
     * the intent is made explicit with setPackage once its target is known.
     */
    @Suppress("DEPRECATION")
    fun explicit(context: Context, intent: Intent): Intent? {
        val found = runCatching { context.packageManager.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()
            .firstOrNull { isOurs(context, it.activityInfo?.packageName) } ?: return null
        return Intent(intent).setPackage(found.activityInfo.packageName)
    }
}

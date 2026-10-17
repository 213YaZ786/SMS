package com.yaz.sms.core.dial

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.sms.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sisters: a provider or an activity of a sister app is used only when its
 * package is ours and signed alike. Packages, providers and signatures are
 * set up as Robolectric documents for ShadowPackageManager
 * (https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowPackageManager.html:
 * installPackage with PackageInfo.signatures, addOrUpdateProvider,
 * addResolveInfoForIntent); its checkSignatures compares those signatures.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class SistersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packages = shadowOf(context.packageManager)
    private val ours = Signature("0102")
    private val theirs = Signature("0304")

    // A package with a signature, and the provider it declares for an authority when one is given.
    private fun install(name: String, signature: Signature, authority: String? = null) {
        packages.installPackage(PackageInfo().apply {
            packageName = name
            signatures = arrayOf(signature)
        })
        if (authority != null) packages.addOrUpdateProvider(ProviderInfo().apply {
            packageName = name
            this.authority = authority
            this.name = "$name.Door"
        })
    }

    @Before
    fun thisAppIsSigned() = install(context.packageName, ours)

    @Test
    fun aProviderOfOursSignedAlikeIsAccepted() {
        install("com.yaz.contacts", ours, "com.yaz.contacts.private")
        assertTrue(Sisters.providerIsOurs(context, "com.yaz.contacts.private"))
        assertEquals(listOf("com.yaz.contacts.private"), Sisters.usable(context, "com.yaz.contacts.private"))
    }

    @Test
    fun theSamePackageSignedByAnotherKeyIsRefused() {
        install("com.yaz.contacts", theirs, "com.yaz.contacts.private")
        assertFalse(Sisters.providerIsOurs(context, "com.yaz.contacts.private"))
        assertTrue(Sisters.usable(context, "com.yaz.contacts.private").isEmpty())
    }

    @Test
    fun anotherPackageHoldingTheAuthorityIsRefused() {
        // Our key or not, a package that is none of ours never answers.
        install("com.evil.app", ours, "com.yaz.contacts.private")
        assertFalse(Sisters.providerIsOurs(context, "com.yaz.contacts.private"))
    }

    @Test
    fun anAuthorityNobodyHoldsIsRefused() {
        assertFalse(Sisters.providerIsOurs(context, "com.yaz.contacts.cards"))
        assertTrue(Sisters.usable(context, "com.yaz.contacts.cards").isEmpty())
    }

    @Test
    fun aPackageNamedLikeOursIsNotOurs() {
        install("com.yaz.contacts.evil", ours)
        install("com.yaz.contactsx", ours)
        assertFalse(Sisters.isOurs(context, "com.yaz.contacts.evil"))
        assertFalse(Sisters.isOurs(context, "com.yaz.contactsx"))
        assertFalse(Sisters.isOurs(context, null))
    }

    @Test
    fun debugNamesCountOnlyInADebugBuild() {
        install("com.yaz.contacts.debug", ours, "com.yaz.contacts.debug.cards")
        assertEquals(BuildConfig.DEBUG, Sisters.isOurs(context, "com.yaz.contacts.debug"))
        assertEquals(BuildConfig.DEBUG, Sisters.providerIsOurs(context, "com.yaz.contacts.debug.cards"))
        assertEquals(
            if (BuildConfig.DEBUG) listOf("com.yaz.contacts.cards", "com.yaz.contacts.debug.cards") else listOf("com.yaz.contacts.cards"),
            Sisters.authorities("com.yaz.contacts.cards")
        )
        assertEquals(
            if (BuildConfig.DEBUG) listOf("com.yaz.contacts.debug.cards") else emptyList<String>(),
            Sisters.usable(context, "com.yaz.contacts.cards")
        )
    }

    private fun answering(pkg: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.Main"
        }
    }

    private val show = Intent("com.dialer.app.action.SHOW_NUMBER", Uri.fromParts("tel", "0612345678", null))

    @Test
    fun anIntentIsAimedAtOurAppOnly() {
        install("com.yaz.dialer", ours)
        install("com.evil.dialer", ours)
        packages.addResolveInfoForIntent(show, listOf(answering("com.evil.dialer"), answering("com.yaz.dialer")))
        val aimed = Sisters.explicit(context, show)
        assertNotNull(aimed)
        assertEquals("com.yaz.dialer", aimed!!.`package`)
        assertEquals(show.action, aimed.action)
        assertEquals(show.data, aimed.data)
    }

    @Test
    fun anIntentOnlyAnotherAppAnswersGoesNowhere() {
        install("com.evil.dialer", ours)
        install("com.yaz.dialer", theirs)
        packages.addResolveInfoForIntent(show, listOf(answering("com.evil.dialer"), answering("com.yaz.dialer")))
        assertNull(Sisters.explicit(context, show))
    }

    @Test
    fun nobodyAnsweringGoesNowhere() {
        assertNull(Sisters.explicit(context, show))
    }
}

package com.yaz.sms.core.sms

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * SHOW_MESSAGE: the extras of the intent read as Intent's typed getters give them, a wrong type being
 * the default (https://developer.android.com/reference/android/content/Intent#getLongExtra(java.lang.String,%20long)),
 * and the caller judged by signature, with packages installed as SistersTest installs them for
 * ShadowPackageManager (https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowPackageManager.html).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ShowMessageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun show() = Intent(ShowMessage.ACTION)

    @Test
    fun `a thread and a message uid make a request`() {
        val asked = ShowMessage.of(show().putExtra("thread", 12L).putExtra("message", "mms/345"))
        assertEquals(ShowMessage(12L, "mms/345"), asked)
        assertEquals(ShowMessage(0L, "sms/1"), ShowMessage.of(show().putExtra("thread", 0L).putExtra("message", "sms/1")))
    }

    @Test
    fun `a missing extra gives nothing`() {
        assertNull(ShowMessage.of(show()))
        assertNull(ShowMessage.of(show().putExtra("thread", 12L)))
        assertNull(ShowMessage.of(show().putExtra("message", "sms/1")))
        assertNull(ShowMessage.of(null))
    }

    @Test
    fun `an extra of the wrong type gives nothing`() {
        assertNull(ShowMessage.of(show().putExtra("thread", "12").putExtra("message", "sms/1")))
        assertNull(ShowMessage.of(show().putExtra("thread", 12L).putExtra("message", 45L)))
        assertNull(ShowMessage.of(show().putExtra("thread", longArrayOf(1, 2)).putExtra("message", arrayOf("sms/1"))))
        assertNull(ShowMessage.of(show().putExtra("thread", 12L).putExtra("message", booleanArrayOf(true))))
    }

    @Test
    fun `a thread below zero or a message that is no uid gives nothing`() {
        assertNull(ShowMessage.of(show().putExtra("thread", -1L).putExtra("message", "sms/1")))
        assertNull(ShowMessage.of(show().putExtra("thread", Long.MIN_VALUE).putExtra("message", "sms/1")))
        for (bad in listOf("", " ", "sms", "sms/", "sms/x", "sms/1; DROP", "content://sms/1", "../mms/1", "sms/-1", "sms/1\n")) {
            assertNull(bad, ShowMessage.of(show().putExtra("thread", 1L).putExtra("message", bad)))
        }
    }

    @Test
    fun `huge values give nothing and cost nothing`() {
        val huge = "9".repeat(1_000_000)
        assertNull(ShowMessage.of(show().putExtra("thread", 1L).putExtra("message", "mms/$huge")))
        assertNull(ShowMessage.of(show().putExtra("thread", 1L).putExtra("message", huge)))
        // A uid of nineteen digits could overflow a Long: refused. The biggest thread number and an eighteen digit uid
        // still pass as numbers; the activity then finds no such message.
        assertNull(ShowMessage.of(show().putExtra("thread", 1L).putExtra("message", "mms/${Long.MAX_VALUE}")))
        assertNotNull(ShowMessage.of(show().putExtra("thread", Long.MAX_VALUE).putExtra("message", "mms/" + "9".repeat(18))))
    }

    @Test
    fun `another action is not a request`() {
        assertNull(ShowMessage.of(Intent("com.yaz.sms.THREAD").putExtra("thread", 1L).putExtra("message", "sms/1")))
        assertNull(ShowMessage.of(Intent().putExtra("thread", 1L).putExtra("message", "sms/1")))
    }

    private fun install(name: String, signature: Signature) =
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            signatures = arrayOf(signature)
        })

    @Test
    fun `an unknown caller simply opens, and ourselves`() {
        assertTrue(ShowMessage.allowed(context, null))
        assertTrue(ShowMessage.allowed(context, context.packageName))
    }

    // BuildConfig.DEBUG as Sisters' own test reads it (SistersTest.debugNamesCountOnlyInADebugBuild); the shell is the caller of `adb shell am start`
    // (https://developer.android.com/tools/adb#am).
    @Test
    fun `the adb shell is a caller in a debug build only`() {
        assertEquals(com.yaz.sms.BuildConfig.DEBUG, ShowMessage.allowed(context, "com.android.shell"))
    }

    @Test
    fun `a package of the suite signed alike is allowed, any other is not`() {
        install(context.packageName, Signature("0102"))
        install("com.yaz.files", Signature("0102"))
        install("com.yaz.evil", Signature("0304"))
        install("com.evil.files", Signature("0102"))
        assertTrue(ShowMessage.allowed(context, "com.yaz.files"))
        assertFalse(ShowMessage.allowed(context, "com.yaz.evil"))
        assertFalse(ShowMessage.allowed(context, "com.evil.files"))
        assertFalse(ShowMessage.allowed(context, "com.yaz.notinstalled"))
        assertFalse(ShowMessage.allowed(context, ""))
    }
}

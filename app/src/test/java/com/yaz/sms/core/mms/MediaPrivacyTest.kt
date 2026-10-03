package com.yaz.sms.core.mms

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPrivacyTest {

    @Test
    fun extensionIsNeverAPath() {
        assertEquals("jpeg", MediaPrivacy.extension("image/jpeg"))
        assertEquals("xvcard", MediaPrivacy.extension("text/x-vcard; charset=utf-8"))
        assertEquals("x", MediaPrivacy.extension("image/../../x"))
        assertEquals("bin", MediaPrivacy.extension("image/"))
    }

    @Test
    fun whatCouldRunCodeIsOpenedAsText() {
        assertEquals("text/plain", MediaPrivacy.safeType("text/html; charset=utf-8"))
        assertEquals("text/plain", MediaPrivacy.safeType("image/svg+xml"))
        assertEquals("text/plain", MediaPrivacy.safeType("application/javascript"))
        assertEquals("txt", MediaPrivacy.extension("text/html"))
        assertEquals("image/jpeg", MediaPrivacy.safeType("image/jpeg"))
        assertEquals("application/pdf", MediaPrivacy.safeType("application/pdf"))
    }

    @Test
    fun anAppIsFoundWhateverTypeItClaims() {
        val apk = File.createTempFile("pic", ".jpg").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        assertTrue(MediaPrivacy.isApp("image/jpeg", apk))
        val zip = File.createTempFile("notes", ".zip").apply {
            ZipOutputStream(outputStream()).use { z ->
                z.putNextEntry(ZipEntry("notes.txt"))
                z.write("hello".toByteArray())
                z.closeEntry()
            }
        }
        assertFalse(MediaPrivacy.isApp("application/zip", zip))
        val photo = File.createTempFile("photo", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32)) }
        assertFalse(MediaPrivacy.isApp("image/jpeg", photo))
        assertTrue(MediaPrivacy.isApp("application/vnd.android.package-archive", photo))
    }
}

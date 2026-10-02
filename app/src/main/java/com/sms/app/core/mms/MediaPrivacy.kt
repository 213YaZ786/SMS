package com.sms.app.core.mms

import android.content.Context
import android.media.ExifInterface
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipInputStream

/**
 * What leaves with a photo, and what may be opened from a message.
 */
object MediaPrivacy {

    /** What a photo says beyond the picture: where, with what, when. */
    private val Revealing = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_UNIQUE_ID,
        "CameraOwnerName", "BodySerialNumber", "LensSerialNumber",
        "LensMake", "LensModel", ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
    )

    private val Cleanable = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")

    /**
     * A photo as it goes out: a copy without its place, its camera, its
     * dates or its serial numbers; the turn of the picture stays. Anything
     * else, or a photo that cannot be read, goes as it is.
     */
    fun clean(context: Context, a: Attachment): Attachment {
        if (a.contentType.lowercase() !in Cleanable) return a
        return runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val ext = if (a.contentType.endsWith("png")) "png" else if (a.contentType.endsWith("webp")) "webp" else "jpg"
            val out = File(dir, "photo-${System.nanoTime()}.$ext")
            context.contentResolver.openInputStream(a.uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return a
            ExifInterface(out.path).apply {
                Revealing.forEach { setAttribute(it, null) }
                saveAttributes()
            }
            Attachment(FileProvider.getUriForFile(context, context.packageName + ".mms", out), a.contentType)
        }.getOrDefault(a)
    }

    /** A file's ending for its type, letters and digits only, never a path. */
    fun extension(contentType: String): String =
        contentType.substringAfter('/').substringBefore(';').lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }

    /**
     * An app to install, whatever type the sender gave it: by its type, or
     * by its contents (an archive holding an Android manifest).
     */
    fun isApp(contentType: String, file: File): Boolean {
        if ("android.package-archive" in contentType.lowercase()) return true
        return runCatching {
            ZipInputStream(file.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.take(4000).any { it.name == "AndroidManifest.xml" || it.name == "classes.dex" }
            }
        }.getOrDefault(false)
    }
}

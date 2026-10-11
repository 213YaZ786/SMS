package com.yaz.sms.core.mms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Saves a message's media to the phone, as AOSP Messaging saves them
 * (ConversationFragment.SaveAttachmentTask): a picture into the photos,
 * a film into the films, each under a folder of the app's name, anything
 * else into Downloads. Written the official MediaStore way: the row
 * inserted IS_PENDING, the bytes copied, the row published.
 */
object SaveParts {

    /** Saves [parts]; how many made it. */
    fun save(context: Context, parts: List<MmsPart>): Int = parts.count { save(context, it) }

    private fun save(context: Context, part: MmsPart): Boolean = runCatching {
        val type = MediaPrivacy.safeType(part.contentType)
        val (collection, folder) = when {
            ContentType.isImageType(type) -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/SMS"
            ContentType.isVideoType(type) -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/SMS"
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to null
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, part.name ?: "SMS-${System.currentTimeMillis()}.${MediaPrivacy.extension(type)}")
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            folder?.let { put(MediaStore.MediaColumns.RELATIVE_PATH, it) }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val row: Uri = resolver.insert(collection, values) ?: return false
        val copied = runCatching {
            resolver.openInputStream(part.uri)?.use { input ->
                resolver.openOutputStream(row)?.use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!copied) {
            resolver.delete(row, null, null)
            return false
        }
        resolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        true
    }.getOrDefault(false)
}

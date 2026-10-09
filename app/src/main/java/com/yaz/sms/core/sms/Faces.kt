package com.yaz.sms.core.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
import com.yaz.sms.R
import com.yaz.sms.core.dial.ContactLook
import kotlin.math.max

/**
 * A person's face for their notification and their bubble: their photo in
 * Android's contacts, else their monogram on their colour (as the Contacts
 * app draws it on the home screen), else the outline of a person.
 */
object Faces {

    /** An adaptive icon: Android crops it round for the bubble and the conversation. */
    private const val SIDE = 216

    fun of(context: Context, number: String, name: String): IconCompat {
        val look = ContactLook.ofNumber(context, number)
        return IconCompat.createWithAdaptiveBitmap(draw(context, photo(context, number), name, look))
    }

    /**
     * The contact's photo as Android's contacts provider keeps it (it decoded
     * and re-encoded it itself), sampled down to the size drawn.
     */
    private fun photo(context: Context, number: String): Bitmap? {
        if (number.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val contact = contactUri(context, number) ?: return null
        val bytes = runCatching {
            ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, contact, true)?.use { it.readNBytes(MAX_BYTES + 1) }
        }.getOrNull()?.takeIf { it.size in 1..MAX_BYTES } ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIDE && bounds.outHeight / (sample * 2) >= SIDE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    /** The contact of [number], matched as the app's lists match it: exactly, else by the last nine digits. */
    private fun contactUri(context: Context, number: String): Uri? = runCatching {
        val columns = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY)
        context.contentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), columns, null, null, null)?.use { c ->
            if (c.moveToFirst()) ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)) else null
        } ?: number.filter(Char::isDigit).takeIf { it.length >= 9 }?.let { digits ->
            context.contentResolver.query(
                Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY),
                "${Phone.NORMALIZED_NUMBER} LIKE ?", arrayOf("%" + digits.takeLast(9)), null
            )?.use { c -> if (c.moveToFirst()) ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)) else null }
        }
    }.getOrNull()

    private fun draw(context: Context, photo: Bitmap?, name: String, look: ContactLook.Look?): Bitmap {
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (photo != null) {
            val scale = max(SIDE.toFloat() / photo.width, SIDE.toFloat() / photo.height)
            val w = photo.width * scale
            val h = photo.height * scale
            canvas.drawBitmap(photo, Rect(0, 0, photo.width, photo.height), RectF((SIDE - w) / 2, (SIDE - h) / 2, (SIDE + w) / 2, (SIDE + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            photo.recycle()
            return out
        }
        val colour = look?.color ?: ContextCompat.getColor(context, android.R.color.system_accent1_600)
        canvas.drawColor(colour)
        // On their colour the letters take white or black, whichever reads.
        val ink = if (ColorUtils.calculateLuminance(colour) > 0.5) 0xCC000000.toInt() else android.graphics.Color.WHITE
        val text = look?.emoji ?: look?.letters ?: name.split(' ').filter { w -> w.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() }
        if (text.isBlank()) {
            // A number without a name: the outline of a person, in the safe zone of the round crop.
            ContextCompat.getDrawable(context, R.drawable.ic_face_person)?.mutate()?.let { glyph ->
                val inset = (SIDE * 0.3f).toInt()
                glyph.setTint(ink)
                glyph.setBounds(inset, inset, SIDE - inset, SIDE - inset)
                glyph.draw(canvas)
            }
            return out
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            textSize = if (text.length > 1) SIDE * 0.26f else SIDE * 0.32f
        }
        canvas.drawText(text, SIDE / 2f, SIDE / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return out
    }

    /** A contact photo is a few hundred kilobytes at most; anything larger is not read. */
    private const val MAX_BYTES = 4 * 1024 * 1024
}

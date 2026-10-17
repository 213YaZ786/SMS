package com.yaz.sms.core.chat

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A group's picture: the square taken from a photo, and the JPEG that goes to the chat. The photo is
 * decoded only by the isolated decoder (SafeImages) before it reaches here, and the core receives
 * nothing but the clean square made here.
 *
 * Sources: Delta Chat Android (GPL-3.0, https://github.com/deltachat/deltachat-android,
 * profiles/AvatarHelper.java: AVATAR_SIZE 512, setGroupAvatar compresses the bitmap to a JPEG file in
 * the cache, hands its path to setChatProfileImage, then deletes it; cropAvatar sends the photo to a
 * square crop first, GroupCreateActivity.setAvatar) and Android's own recipe for moving a picture under
 * a window (https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch).
 */
object GroupPicture {

    /** Delta Chat AvatarHelper.AVATAR_SIZE: the most a group picture is wide. */
    const val SIDE = 512

    /** The most the photo may be enlarged under the window. */
    const val MAX_ZOOM = 5f

    /** The square taken from the photo, in its own pixels. */
    data class Region(val left: Int, val top: Int, val size: Int)

    /**
     * Where the photo sits under a square window of [window] pixels, kept so the window is always full:
     * the photo fills it at zoom 1 (the shorter side matching), is enlarged by [zoom], and moved by
     * ([tx], [ty]) from the centre. The move is held to what keeps the photo under the whole window
     * (Android's multi-touch recipe moves it freely; a crop must not show an empty corner).
     */
    fun clampPan(width: Int, height: Int, window: Float, zoom: Float, tx: Float, ty: Float): Pair<Float, Float> {
        if (width <= 0 || height <= 0 || window <= 0f) return 0f to 0f
        val scale = window / min(width, height) * zoom.coerceIn(1f, MAX_ZOOM)
        val room = { side: Int -> max(0f, (side * scale - window) / 2f) }
        // "+ 0f" turns a -0.0 into 0.0.
        return tx.coerceIn(-room(width), room(width)) + 0f to ty.coerceIn(-room(height), room(height)) + 0f
    }

    /** The square of the photo that shows in the window, in the photo's pixels. */
    fun region(width: Int, height: Int, window: Float, zoom: Float, tx: Float, ty: Float): Region {
        if (width <= 0 || height <= 0) return Region(0, 0, 0)
        val z = zoom.coerceIn(1f, MAX_ZOOM)
        val scale = window / min(width, height) * z
        val (px, py) = clampPan(width, height, window, z, tx, ty)
        // Rounded, not cut: 400 / 0.4 must give 1000, not 999.
        val size = min(min(width, height).toFloat(), window / scale).roundToInt().coerceAtLeast(1)
        val left = (width / 2f - (window / 2f + px) / scale).roundToInt().coerceIn(0, width - size)
        val top = (height / 2f - (window / 2f + py) / scale).roundToInt().coerceIn(0, height - size)
        return Region(left, top, size)
    }

    /** The region as a JPEG at most [SIDE] wide: only the pixels, no place, camera or date. */
    fun jpeg(photo: Bitmap, region: Region): ByteArray? {
        if (region.size <= 0) return null
        val square = Bitmap.createBitmap(photo, region.left, region.top, region.size, region.size)
        val out = if (square.width > SIDE) Bitmap.createScaledBitmap(square, SIDE, SIDE, true) else square
        val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        if (out !== square) out.recycle()
        if (square !== photo) square.recycle()
        return bytes
    }
}

package com.yaz.sms.core.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.sms.core.chat.GroupPicture.Region
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The square of a photo under a window (Android's multi-touch recipe, with the window held full:
 * https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch) and the JPEG
 * Delta Chat Android's AvatarHelper.setGroupAvatar makes of it (https://github.com/deltachat/deltachat-android).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GroupPictureTest {

    @Test
    fun squarePhotoFillsTheWindow() {
        assertEquals(Region(0, 0, 1000), GroupPicture.region(1000, 1000, 300f, 1f, 0f, 0f))
    }

    @Test
    fun landscapeIsCentredThenMoved() {
        // 2000x1000 under 400 px: the short side fits, the long one overflows by 200 px each way.
        assertEquals(Region(500, 0, 1000), GroupPicture.region(2000, 1000, 400f, 1f, 0f, 0f))
        // Dragged 100 px to the right the left of the photo comes into view.
        assertEquals(Region(250, 0, 1000), GroupPicture.region(2000, 1000, 400f, 1f, 100f, 0f))
        // Dragged past the edge it stops at the edge: never an empty corner.
        assertEquals(Region(0, 0, 1000), GroupPicture.region(2000, 1000, 400f, 1f, 5000f, 0f))
        assertEquals(Region(1000, 0, 1000), GroupPicture.region(2000, 1000, 400f, 1f, -5000f, 0f))
        // The short side has no room at zoom 1: a vertical drag changes nothing.
        assertEquals(Region(500, 0, 1000), GroupPicture.region(2000, 1000, 400f, 1f, 0f, 300f))
    }

    @Test
    fun portraitMovesUpAndDown() {
        assertEquals(Region(0, 500, 1000), GroupPicture.region(1000, 2000, 400f, 1f, 0f, 0f))
        assertEquals(Region(0, 0, 1000), GroupPicture.region(1000, 2000, 400f, 1f, 0f, 9999f))
    }

    @Test
    fun zoomShowsLess() {
        assertEquals(Region(250, 250, 500), GroupPicture.region(1000, 1000, 500f, 2f, 0f, 0f))
        // At the largest zoom and beyond: held to the maximum.
        val max = GroupPicture.region(1000, 1000, 500f, GroupPicture.MAX_ZOOM, 0f, 0f)
        assertEquals(max, GroupPicture.region(1000, 1000, 500f, 99f, 0f, 0f))
        assertEquals(200, max.size)
        // Below 1 held to 1.
        assertEquals(GroupPicture.region(1000, 1000, 500f, 1f, 0f, 0f), GroupPicture.region(1000, 1000, 500f, 0.1f, 0f, 0f))
    }

    @Test
    fun movingIsHeldToTheRoom() {
        assertEquals(0f to 0f, GroupPicture.clampPan(1000, 1000, 300f, 1f, 80f, -80f))
        // Enlarged twice the photo is 600 px under 300: 150 px of room each way.
        val (x, y) = GroupPicture.clampPan(1000, 1000, 300f, 2f, 400f, -400f)
        assertEquals(150f, x, 0.01f)
        assertEquals(-150f, y, 0.01f)
        assertEquals(40f to -30f, GroupPicture.clampPan(1000, 1000, 300f, 2f, 40f, -30f))
    }

    @Test
    fun unusableInputsGiveNothing() {
        assertEquals(0, GroupPicture.region(0, 0, 300f, 1f, 0f, 0f).size)
        assertEquals(0f to 0f, GroupPicture.clampPan(0, 100, 300f, 1f, 5f, 5f))
        assertEquals(0f to 0f, GroupPicture.clampPan(100, 100, 0f, 1f, 5f, 5f))
        // A photo smaller than the window is still taken whole, never outside itself.
        val tiny = GroupPicture.region(40, 30, 300f, 1f, 0f, 0f)
        assertEquals(30, tiny.size)
        assertTrue(tiny.left in 0..10 && tiny.top == 0)
    }

    @Test
    fun jpegIsASquareNoLargerThanTheMaximum() {
        val photo = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        val bytes = GroupPicture.jpeg(photo, GroupPicture.region(1600, 1000, 400f, 1f, 0f, 0f))
        assertNotNull(bytes)
        // A JPEG starts with FF D8.
        assertEquals(0xFF.toByte(), bytes!![0])
        assertEquals(0xD8.toByte(), bytes[1])
        val back = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(GroupPicture.SIDE, back.width)
        assertEquals(GroupPicture.SIDE, back.height)
        // A small region is not enlarged.
        val small = GroupPicture.jpeg(photo, Region(0, 0, 200))
        val smallBack = android.graphics.BitmapFactory.decodeByteArray(small!!, 0, small.size)
        assertEquals(200, smallBack.width)
        assertNull(GroupPicture.jpeg(photo, Region(0, 0, 0)))
    }

    @Test
    fun anOldGroupFileStillReads() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<ChatGroup>("""{"chatId":12,"phones":["+33600000001","+33600000002"]}""")
        assertEquals(ChatGroup(12, listOf("+33600000001", "+33600000002")), old)
        assertTrue(old.gone.isEmpty() && !old.left)
        val now = Json.decodeFromString<ChatGroup>(Json.encodeToString(ChatGroup.serializer(), old.copy(gone = listOf("+33600000002"), left = true)))
        assertEquals(listOf("+33600000002"), now.gone)
        assertTrue(now.left)
    }
}

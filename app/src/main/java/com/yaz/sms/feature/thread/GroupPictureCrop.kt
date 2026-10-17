package com.yaz.sms.feature.thread

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaz.sms.core.chat.GroupPicture
import com.yaz.sms.core.security.SafeImages
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons

/**
 * Choosing the square of a photo for a group's picture: the photo sits under a window, pinch to enlarge,
 * drag to move, and the circle the picture will be shown in is lit. The photo is read only by the
 * isolated decoder (SafeImages); what [onDone] gets is the clean JPEG made from the square.
 *
 * Sources: Delta Chat Android (GPL-3.0, https://github.com/deltachat/deltachat-android) picks a photo for
 * a group, then crops it to a square before setting it (profiles/AvatarHelper.java cropAvatar,
 * GroupCreateActivity.setAvatar); the pinch and drag are Android's own recipe,
 * https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch
 * (detectTransformGestures), the window held full by GroupPicture.clampPan.
 */
@Composable
fun GroupPictureCrop(photo: Uri, onDone: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // 1024 pixels is more than the 512 that is kept; a photo that is no picture gives null.
    val loaded by produceState<Pair<Bitmap, Boolean>?>(null, photo) {
        val bitmap = SafeImages.decode(context, photo, 1024)
        value = bitmap?.let { it to true } ?: (Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) to false)
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight - 120.dp).coerceAtLeast(120.dp)
                val sidePx = with(LocalDensity.current) { side.toPx() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val ready = loaded
                    if (ready == null) {
                        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                        }
                    } else if (!ready.second) {
                        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                            Text("Can't use this picture", color = Color.White, textAlign = TextAlign.Center)
                        }
                    } else {
                        val bitmap = ready.first
                        Box(
                            Modifier.size(side).clipToBounds()
                                .pointerInput(bitmap) {
                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                        zoom = (zoom * gestureZoom).coerceIn(1f, GroupPicture.MAX_ZOOM)
                                        val (x, y) = GroupPicture.clampPan(bitmap.width, bitmap.height, sidePx, zoom, tx + pan.x, ty + pan.y)
                                        tx = x
                                        ty = y
                                    }
                                }
                                .drawWithContent {
                                    drawContent()
                                    // The circle it will be shown in stays clear, the corners are veiled.
                                    val veil = Path().apply {
                                        fillType = PathFillType.EvenOdd
                                        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(size.width, size.height)))
                                        addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(size.width, size.height)))
                                    }
                                    drawPath(veil, Color.Black.copy(alpha = 0.55f))
                                }
                        ) {
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = "Group picture, pinch to enlarge, drag to move",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = tx
                                    translationY = ty
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        FloatingAction(AppIcons.Close, "Cancel", onDismiss)
                        if (loaded?.second == true) FloatingAction(AppIcons.Done, "Use as the group picture", {
                            val bitmap = loaded?.first ?: return@FloatingAction
                            val region = GroupPicture.region(bitmap.width, bitmap.height, sidePx, zoom, tx, ty)
                            val bytes = GroupPicture.jpeg(bitmap, region)
                            if (bytes != null) {
                                haptics.done()
                                onDone(bytes)
                            } else haptics.reject()
                        })
                    }
                }
            }
        }
    }
}

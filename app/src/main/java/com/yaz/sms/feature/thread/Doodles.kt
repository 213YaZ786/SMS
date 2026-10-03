package com.yaz.sms.feature.thread

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.yaz.sms.ui.icon.AppIcons
import kotlin.random.Random

/** The background "Doodles": small drawings scattered over the conversation, as chat apps have. */
internal const val DOODLE = "doodle"

/** The same, condensed: many more, of varied sizes, in a tone of the accent. */
internal const val DOODLE_DENSE = "doodle-dense"

/**
 * Material Symbols (Google, Apache 2.0) sown over the whole conversation,
 * turned a little each, in a faint tone of the light under them: hearts,
 * notes, cups, clouds, planes, cakes and the app's own signs.
 */
@Composable
internal fun DoodleGround(ink: Color, modifier: Modifier = Modifier.fillMaxSize(), cell: Float = 64f, dense: Boolean = false) {
    val vectors = remember {
        DOODLES + listOf(
            AppIcons.Star, AppIcons.PhotoCamera, AppIcons.Place, AppIcons.Mic, AppIcons.Call, AppIcons.Send, AppIcons.Schedule,
            AppIcons.AutoAwesome, AppIcons.Headset, AppIcons.Image, AppIcons.Poll, AppIcons.Videocam, AppIcons.Smartphone,
            AppIcons.Message, AppIcons.Group, AppIcons.Person, AppIcons.Link, AppIcons.PushPin, AppIcons.Timer, AppIcons.Palette
        )
    }
    val painters = vectors.map { rememberVectorPainter(it) }
    val filter = remember(ink) { ColorFilter.tint(ink) }
    Canvas(modifier) {
        // Dense: a closer grid, and sizes from a dot-like 10 to a large 34.
        val step = (if (dense) cell * 0.62f else cell).dp.toPx()
        val random = Random(7)
        var row = 0
        var y = -step / 2
        while (y < size.height + step) {
            var x = if (row % 2 == 0) -step / 2 else 0f
            while (x < size.width + step) {
                val p = painters[random.nextInt(painters.size)]
                val s = if (dense) {
                    // Mostly small, a few large ones, as hand-drawn papers have.
                    val r = random.nextFloat()
                    (if (r < 0.55f) 10f + r * 14f else if (r < 0.9f) 16f + r * 10f else 28f + r * 6f).dp.toPx()
                } else (20 + random.nextInt(10)).dp.toPx()
                val jx = (random.nextFloat() - 0.5f) * step * (if (dense) 0.4f else 0.35f)
                val jy = (random.nextFloat() - 0.5f) * step * (if (dense) 0.4f else 0.35f)
                val turn = (random.nextFloat() - 0.5f) * 50f
                translate(x + jx, y + jy) {
                    rotate(turn, pivot = androidx.compose.ui.geometry.Offset(s / 2, s / 2)) {
                        with(p) { draw(Size(s, s), colorFilter = filter) }
                    }
                }
                x += step
            }
            y += step * 0.85f
            row++
        }
    }
}

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(PathParser().parsePathString(path).toNodes(), fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
    .build()

/** Material Symbols (Apache 2.0): favorite, music_note, local_cafe, cloud, flight, chat_bubble, cake. */
private val DOODLES = listOf(
    icon("favorite", "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"),
    icon("music_note", "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"),
    icon("local_cafe", "M20 3H4v10c0 2.21 1.79 4 4 4h6c2.21 0 4-1.79 4-4v-3h2c1.11 0 2-.9 2-2V5c0-1.11-.89-2-2-2zm0 5h-2V5h2v3zM2 21h18v-2H2v2z"),
    icon("cloud", "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z"),
    icon("flight", "M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z"),
    icon("chat_bubble", "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"),
    icon("cake", "M12 6c1.11 0 2-.9 2-2 0-.38-.1-.73-.29-1.03L12 0l-1.71 2.97c-.19.3-.29.65-.29 1.03 0 1.1.9 2 2 2zm4.6 9.99l-1.07-1.07-1.08 1.07c-1.3 1.3-3.58 1.31-4.89 0l-1.07-1.07-1.09 1.07C6.75 16.64 5.88 17 4.96 17c-.73 0-1.4-.23-1.96-.61V21c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-4.61c-.56.38-1.23.61-1.96.61-.92 0-1.79-.36-2.44-1.01zM18 9h-5V7h-2v2H6c-1.66 0-3 1.34-3 3v1.54c0 1.08.88 1.96 1.96 1.96.52 0 1.02-.2 1.38-.57l2.14-2.13 2.13 2.13c.74.74 2.03.74 2.77 0l2.14-2.13 2.13 2.13c.37.37.86.57 1.38.57 1.08 0 1.96-.88 1.96-1.96V12C21 10.34 19.66 9 18 9z")
)

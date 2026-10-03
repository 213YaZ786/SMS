package com.sms.app.feature.thread

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sms.app.core.scene.PhotoLight
import com.sms.app.ui.component.ZoneSurface
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.glass.GlassLook
import com.sms.app.ui.glass.Halo
import com.sms.app.ui.glass.drawGround
import com.sms.app.ui.icon.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.max

/**
 * A conversation's background: the light the glass lies on, in other
 * colours. Every pane, bubble and button keeps reading it as it does the
 * app's own light, so nothing costs more and everything stays legible.
 */
internal enum class Scene(val code: String, val label: String) {
    YOURS("yours", "Your colours"),
    SKY("sky", "Sky now"),
    DAWN("dawn", "Dawn"),
    LAGOON("lagoon", "Lagoon"),
    FOREST("forest", "Forest"),
    DUSK("dusk", "Dusk"),
    ROSE("rose", "Rose"),
    SAND("sand", "Sand"),
    NIGHT("night", "Night")
}

private class Palette(val day: Long, val night: Long, val lights: List<Long>)

private val palettes = mapOf(
    Scene.DAWN to Palette(0xFFFFF4EE, 0xFF1E1720, listOf(0xFFFFB59A, 0xFFF7A1C4, 0xFFC9B6FF, 0xFFFFD58A)),
    Scene.LAGOON to Palette(0xFFEEF9F8, 0xFF0F1C1E, listOf(0xFF7FDCD3, 0xFF4FB3BF, 0xFF9AD0FF, 0xFFB8F0D8)),
    Scene.FOREST to Palette(0xFFF1F6EE, 0xFF121A14, listOf(0xFFA8D58C, 0xFF5FA374, 0xFFD8EBA0, 0xFF9CC3B0)),
    Scene.DUSK to Palette(0xFFF7F0F8, 0xFF17131F, listOf(0xFFA78BFA, 0xFFFFA36B, 0xFFE879B9, 0xFF7C8CF8)),
    Scene.ROSE to Palette(0xFFFFF1F3, 0xFF1F1316, listOf(0xFFFF9FB2, 0xFFFFB4A2, 0xFFF5C2E7, 0xFFD46A8A)),
    Scene.SAND to Palette(0xFFFBF6EC, 0xFF1C1812, listOf(0xFFF2D2A0, 0xFFE7A27C, 0xFFCFC28A, 0xFFFFE7B8)),
    Scene.NIGHT to Palette(0xFFE9EDF8, 0xFF0B1020, listOf(0xFF4F6BD8, 0xFF5CC8F0, 0xFF8F7BEA, 0xFFB9D4FF))
)

/** The sky by day, for Sky now between morning and evening. */
private val daySky = Palette(0xFFEAF4FF, 0xFF0F1A2A, listOf(0xFF8EC5FF, 0xFFFFE6A0, 0xFFB5DBFF, 0xFF7FB2F0))

/** Where the four lights sit, as the app's own do: x, y and radius in fractions of the window. */
private val spots = listOf(Triple(0.15f, 0.18f, 0.55f), Triple(0.95f, 0.45f, 0.60f), Triple(0.25f, 0.85f, 0.55f), Triple(0.80f, 0.95f, 0.40f))
private val dayStrength = listOf(0.55f, 0.45f, 0.60f, 0.35f)
private val nightStrength = listOf(0.32f, 0.26f, 0.38f, 0.22f)

/** Sky now at [hour]: dawn, day, dusk or night, by the phone's clock. */
private fun skyAt(hour: Int): Palette = when (hour) {
    in 5..7 -> palettes.getValue(Scene.DAWN)
    in 8..16 -> daySky
    in 17..20 -> palettes.getValue(Scene.DUSK)
    else -> palettes.getValue(Scene.NIGHT)
}

/** The light of the background [code] over [base], the app's own when none or unknown. */
internal fun sceneLook(code: String?, base: GlassLook, hour: Int): GlassLook {
    if (code == null || code == Scene.YOURS.code) return base
    val dark = base.dark
    if (code.startsWith(PHOTO)) {
        val lights = PhotoLight.decode(code.removePrefix(PHOTO)).take(4)
        if (lights.isEmpty()) return base
        val total = lights.sumOf { it.share.toDouble() }.toFloat().coerceAtLeast(0.01f)
        var r = 0f; var g = 0f; var b = 0f
        lights.forEach { l ->
            r += ((l.rgb shr 16) and 0xFF) / 255f * l.share / total
            g += ((l.rgb shr 8) and 0xFF) / 255f * l.share / total
            b += (l.rgb and 0xFF) / 255f * l.share / total
        }
        val mean = Color(r, g, b)
        val ground = lerp(mean, if (dark) Color(0xFF101114) else Color.White, 0.86f)
        val halos = lights.map { l ->
            Halo(l.x, l.y, 0.35f + 0.45f * l.share, Color(0xFF000000 or l.rgb.toLong()).copy(alpha = if (dark) 0.40f else 0.62f))
        }
        return GlassLook(dark, ground, halos, base.zoneTint, base.floatTint, base.accentTint)
    }
    val scene = Scene.entries.firstOrNull { it.code == code } ?: return base
    val palette = if (scene == Scene.SKY) skyAt(hour) else palettes[scene] ?: return base
    val halos = palette.lights.mapIndexed { i, c ->
        val (x, y, radius) = spots[i]
        Halo(x, y, radius, Color(c).copy(alpha = (if (dark) nightStrength else dayStrength)[i]))
    }
    return GlassLook(dark, Color(if (dark) palette.night else palette.day), halos, base.zoneTint, base.floatTint, base.accentTint)
}

internal const val PHOTO = "photo:"

private fun hourNow() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

/**
 * The conversation's light for [code], following the hour for Sky now
 * (one wake of a coroutine an hour, while the conversation is shown).
 */
@Composable
internal fun rememberSceneLook(code: String?, base: GlassLook?): GlassLook? {
    base ?: return null
    var hour by remember { mutableIntStateOf(hourNow()) }
    if (code == Scene.SKY.code) LaunchedEffect(Unit) {
        while (true) {
            val now = Calendar.getInstance()
            val left = (60 - now.get(Calendar.MINUTE)) * 60_000L - now.get(Calendar.SECOND) * 1000L
            delay(left.coerceAtLeast(1000L))
            hour = hourNow()
        }
    }
    return remember(code, base, hour) { sceneLook(code, base, hour) }
}

/** The main colours of a picture, read small (40 pixels across) and let go at once. */
private fun photoLight(context: Context, uri: Uri): String? = runCatching {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = 40f / max(info.size.width, info.size.height)
        decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
    }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val lights = PhotoLight.of(pixels, bitmap.width, bitmap.height)
    bitmap.recycle()
    if (lights.isEmpty()) null else PHOTO + PhotoLight.encode(lights)
}.getOrNull()

/**
 * The backgrounds to choose from, each a small picture of a conversation
 * in its light; a photo gives its colours, never itself.
 */
@Composable
internal fun BackgroundSheet(current: String?, base: GlassLook, theirPhoto: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    fun fromPhoto(uri: Uri) {
        reading = true
        scope.launch {
            val code = withContext(Dispatchers.IO) { photoLight(context, uri) }
            reading = false
            if (code != null) {
                haptics.done()
                onPick(code)
            } else haptics.reject()
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::fromPhoto) }
    val hour = remember { hourNow() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        ZoneSurface(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Background", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 3
                ) {
                    var order = 0
                    Scene.entries.forEach { scene ->
                        val chosen = (current ?: Scene.YOURS.code) == scene.code
                        SceneTile(sceneLook(scene.code, base, hour), scene.label, chosen, order++) {
                            haptics.tick()
                            onPick(if (scene == Scene.YOURS) null else scene.code)
                        }
                    }
                    val photoChosen = current?.startsWith(PHOTO) == true
                    SceneTile(if (photoChosen) sceneLook(current, base, hour) else null, if (reading) "Reading…" else "From a photo", photoChosen, order++) {
                        haptics.tick()
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    theirPhoto?.let { photo ->
                        SceneTile(null, "Their photo", false, order++, icon = false) {
                            haptics.tick()
                            fromPhoto(Uri.parse(photo))
                        }
                    }
                }
            }
        }
    }
}

/** One background as a small conversation in its light; [look] null shows a photo's place. */
@Composable
private fun SceneTile(look: GlassLook?, label: String, chosen: Boolean, order: Int, icon: Boolean = true, onClick: () -> Unit) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order * 30L)
        pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f))
    }
    val accent = MaterialTheme.colorScheme.primary
    val bubble = if (look?.dark ?: false) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.75f)
    val shape = RoundedCornerShape(18.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(84.dp).graphicsLayer {
            scaleX = 0.8f + 0.2f * pop.value
            scaleY = 0.8f + 0.2f * pop.value
            alpha = pop.value.coerceIn(0f, 1f)
        }
    ) {
        Box(
            Modifier
                .size(84.dp, 112.dp)
                .clip(shape)
                .then(if (chosen) Modifier.border(2.5.dp, accent, shape) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape))
                .clickable(onClick = onClick)
                .drawBehind {
                    if (look != null) {
                        drawGround(look, look.ground)
                        // Two bubbles, one each side.
                        drawRoundRect(bubble, Offset(size.width * 0.12f, size.height * 0.30f), Size(size.width * 0.55f, size.height * 0.14f), CornerRadius(20f))
                        drawRoundRect(accent.copy(alpha = 0.35f), Offset(size.width * 0.36f, size.height * 0.55f), Size(size.width * 0.52f, size.height * 0.14f), CornerRadius(20f))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (look == null) Icon(if (icon) AppIcons.Photo else AppIcons.Image, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * A short wash of the new light over the conversation when its
 * background changes, so the change is seen rather than snapped.
 */
@Composable
internal fun SceneWash(look: GlassLook?) {
    val wash = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(look) }
    LaunchedEffect(look) {
        if (look == null || shown == null || look === shown) { shown = look; return@LaunchedEffect }
        shown = look
        wash.snapTo(0.55f)
        wash.animateTo(0f, tween(650))
    }
    val ground = look?.ground ?: return
    if (wash.value > 0f) Box(Modifier.fillMaxSize().drawBehind { drawRect(ground.copy(alpha = wash.value)) })
}

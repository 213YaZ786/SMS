package com.yaz.sms.ui.component

import com.yaz.sms.ui.glass.LocalGlass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog of the app: Material's AlertDialog, made of glass when glass is on,
 * like every zone. The fill and the tonal elevation give way to the glass,
 * whose rim draws the edge. Every dialog goes through here.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ZoneAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    properties: DialogProperties = DialogProperties()
) {
    val glass = LocalGlass.current
    // Material's BasicAlertDialog gives the window, the 280..560 dp width and the pane title
    // TalkBack reads (AlertDialog.kt, BasicAlertDialog:
    // https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AlertDialog.kt).
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        // In its own window the pane cannot see the app: Android blurs what lies
        // behind it instead, and the pane is a clear sheet of glass over that.
        modifier = if (glass == null) modifier else modifier.dialogGlass(shape, glass),
        properties = properties
    ) {
        if (glass != null) BlurBehind()
        ZoneAlertContent(
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = if (glass == null) AlertDialogDefaults.containerColor else Color.Transparent,
            tonalElevation = if (glass == null) AlertDialogDefaults.TonalElevation else 0.dp,
            iconContentColor = AlertDialogDefaults.iconContentColor,
            titleContentColor = if (glass == null) AlertDialogDefaults.titleContentColor else MaterialTheme.colorScheme.onSurface,
            textContentColor = if (glass == null) AlertDialogDefaults.textContentColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Material 3's AlertDialogContent (AlertDialog.kt of
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AlertDialog.kt):
 * the same 24 dp padding, 16 dp under the icon and the title, 24 dp under the text, the same
 * styles (headlineSmall, bodyMedium, labelLarge) and content colours; the text slot keeps
 * weight(1f, fill = false) so a long list scrolls. Changed, as iOS's alerts (the user,
 * 2026-10-28, "text must be centered"): icon, title and text centred, and the buttons in one
 * row sharing the whole width equally (a confirm alone takes it all), where Material packs
 * them at the end.
 */
@Composable
private fun ZoneAlertContent(
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    shape: Shape,
    containerColor: Color,
    tonalElevation: Dp,
    iconContentColor: Color,
    titleContentColor: Color,
    textContentColor: Color
) {
    val centred = TextAlign.Center
    Surface(shape = shape, color = containerColor, tonalElevation = tonalElevation) {
        Column(Modifier.padding(24.dp)) {
            icon?.let { slot ->
                CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                    Box(Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) { slot() }
                }
            }
            title?.let { slot ->
                CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                    ProvideTextStyle(LocalTextStyle.current.merge(MaterialTheme.typography.headlineSmall).copy(textAlign = centred)) {
                        Box(Modifier.padding(bottom = 16.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) { slot() }
                    }
                }
            }
            text?.let { slot ->
                CompositionLocalProvider(LocalContentColor provides textContentColor) {
                    ProvideTextStyle(LocalTextStyle.current.merge(MaterialTheme.typography.bodyMedium).copy(textAlign = centred)) {
                        Box(
                            Modifier.weight(weight = 1f, fill = false).padding(bottom = 24.dp).fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter
                        ) { slot() }
                    }
                }
            }
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(LocalTextStyle.current.merge(MaterialTheme.typography.labelLarge)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGap)) {
                        dismissButton?.let { slot -> Box(Modifier.weight(1f), propagateMinConstraints = true) { slot() } }
                        Box(Modifier.weight(1f), propagateMinConstraints = true) { confirmButton() }
                    }
                }
            }
        }
    }
}

/**
 * The window of the dialog blurs the screen behind it (Android 12 and later,
 * where the phone allows it), with a lighter dim, so the pane over it reads
 * as glass. Where blur is off (battery saver, an old GPU), the plain dim stays.
 * Blur and dim from Kyant0/AndroidLiquidGlass's dialog (Apache-2.0,
 * https://github.com/Kyant0/AndroidLiquidGlass app/src/commonMain/kotlin/com/kyant/backdrop/catalog/destinations/DialogContent.kt):
 * blur 16 dp by day, 8 by night; dim 0.23 by day, 0.56 by night.
 */
@Composable
fun BlurBehind(radiusDp: Int? = null) {
    val dark = LocalGlass.current?.dark ?: false
    val radius = radiusDp ?: if (dark) 8 else 16
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        if (window != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = with(density) { radius.dp.roundToPx() }
                dimAmount = if (dark) 0.56f else 0.23f
            }
        }
        onDispose { }
    }
}

/** A dialog's pane: a clear tint over the blurred screen, its rim soft. Tint from Kyant0/AndroidLiquidGlass's DialogContent.kt containerColor. */
fun Modifier.dialogGlass(shape: Shape, look: com.yaz.sms.ui.glass.GlassLook): Modifier = this.drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, if (look.dark) Color(0xFF121212).copy(alpha = 0.4f) else Color(0xFFFAFAFA).copy(alpha = 0.6f))
    drawOutline(outline, com.yaz.sms.ui.glass.rimBrush(look, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f * density))
}

/**
 * The pane of a dialog of its own (a sheet of choices in a Dialog): the
 * same clear glass over the blurred screen as [ZoneAlertDialog].
 */
@Composable
fun DialogPane(shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), content: @Composable () -> Unit) {
    val look = LocalGlass.current
    if (look != null) BlurBehind()
    androidx.compose.foundation.layout.Box(
        if (look != null) Modifier.dialogGlass(shape, look)
        else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}


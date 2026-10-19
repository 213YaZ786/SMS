package com.yaz.sms.feature.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * The status and navigation bar icons of a full screen page drawn in its own dialog window, dark on a light
 * page and light on a dark one: a dialog's window does not take the activity's. Android's recipe,
 * WindowInsetsControllerCompat.isAppearanceLightStatusBars / isAppearanceLightNavigationBars
 * (https://developer.android.com/reference/androidx/core/view/WindowInsetsControllerCompat), on the window
 * Compose's Dialog gives through DialogWindowProvider (as ZoneDialog's BlurBehind reaches it).
 */
@Composable
fun PageBars() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    DisposableEffect(view, light) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
        onDispose { }
    }
}

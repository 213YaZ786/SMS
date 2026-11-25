package com.yaz.sms.core.security

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * While a screen shows keys or an invite code: its windows (the dialog's and the activity's) are
 * secure (no screenshot, no screen recording, hidden in recents) and other apps' overlay windows
 * are hidden (tapjacking: a window laid over the screen to read or steer it).
 *
 * Multiple callers can request security on the same window concurrently; the flag is only cleared
 * once every caller has released its hold, and never when the window was secure before the first.
 *
 * Ported from Signal-Android's TemporaryScreenshotSecurity (AGPL-3.0), its holds counted per window:
 * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/components/TemporaryScreenshotSecurity.kt#TemporaryScreenshotSecurity
 * and Rethink's applyOverlayWindowGuard (Apache-2.0), the SecurityException caught:
 * https://raw.githubusercontent.com/celzero/rethink-app/master/app/src/main/java/com/celzero/bravedns/ui/BaseActivity.kt#applyOverlayWindowGuard
 *
 * not ported: bindToViewLifecycleOwner (a Fragment's lifecycle, none here: only dialogs hold the guard)
 * not ported: bind(activity) (an Activity's lifecycle, none here: only dialogs hold the guard)
 * not ported: onResume (LifecycleObserver, serves the two lifecycle binds above)
 * not ported: onPause (LifecycleObserver, serves the two lifecycle binds above)
 * not ported: SignalStore.settings.isScreenSecurityEnabled (this app keeps the window's own FLAG_SECURE state, wasSecure)
 */
object ScreenGuard {

    private val activeHolds = WeakHashMap<Window, Int>()
    private val wasSecure = WeakHashMap<Window, Boolean>()

    /** Held for as long as the composable stays in the dialog's content. */
    @Composable
    fun bind() {
        val view = LocalView.current
        val activity = LocalActivity.current
        DisposableEffect(view, activity) {
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window
                ?: ((view.parent as? View)?.parent as? DialogWindowProvider)?.window
            val windows = listOfNotNull(dialogWindow, activity?.window).distinct()
            windows.forEach(::acquire)

            onDispose {
                windows.forEach(::release)
            }
        }
    }

    private fun acquire(window: Window) {
        val previous = activeHolds[window] ?: 0
        activeHolds[window] = previous + 1
        if (previous == 0) {
            wasSecure[window] = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            applyOverlayWindowGuard(window, true)
        }
    }

    private fun release(window: Window) {
        val next = ((activeHolds[window] ?: 0) - 1).coerceAtLeast(0)
        if (next == 0) {
            activeHolds.remove(window)
            if (wasSecure.remove(window) != true) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            applyOverlayWindowGuard(window, false)
        } else {
            activeHolds[window] = next
        }
    }

    /**
     * Blocks other apps' overlay windows (API 31+) while the screen is shown, preventing tapjacking of
     * the keys and the invite code. The permission HIDE_OVERLAY_WINDOWS is granted at install.
     */
    private fun applyOverlayWindowGuard(window: Window, hide: Boolean) {
        try {
            window.setHideOverlayWindows(hide)
        } catch (e: SecurityException) {
            android.util.Log.e("ScreenGuard", "err hiding overlay windows")
        }
    }
}

/** The calling dialog's keys or code stay off screenshots and under no other app's overlay. */
@Composable
fun ScreenGuard() = ScreenGuard.bind()

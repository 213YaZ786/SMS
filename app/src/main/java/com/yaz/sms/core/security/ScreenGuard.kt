package com.yaz.sms.core.security

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
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
    private val wasFiltering = WeakHashMap<Window, Boolean>()

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
            // Taps refused while another app's window covers this one, as Super Productivity's WebViewBlockActivity
            // sets it on its dialogs' decorView:
            // https://raw.githubusercontent.com/super-productivity/super-productivity/master/android/app/src/main/java/com/superproductivity/superproductivity/webview/WebViewBlockActivity.kt#WebViewBlockActivity
            wasFiltering[window] = window.decorView.filterTouchesWhenObscured
            window.decorView.filterTouchesWhenObscured = true
        }
    }

    /**
     * True while a hold is active on [window]: the flag stays on and [isScreenSecurityEnabled] is what its release restores.
     * https://raw.githubusercontent.com/signalapp/Signal-Android/main/core/ui/src/main/java/org/signal/core/ui/WindowExtensions.kt#initializeScreenshotSecurity
     */
    fun keepsFlag(window: Window, isScreenSecurityEnabled: Boolean): Boolean {
        if ((activeHolds[window] ?: 0) == 0) return false
        wasSecure[window] = isScreenSecurityEnabled
        return true
    }

    private fun release(window: Window) {
        val next = ((activeHolds[window] ?: 0) - 1).coerceAtLeast(0)
        if (next == 0) {
            activeHolds.remove(window)
            if (wasSecure.remove(window) != true) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            applyOverlayWindowGuard(window, false)
            window.decorView.filterTouchesWhenObscured = wasFiltering.remove(window) == true
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

/**
 * What a node marks as sensitive data (the keys, the invite code) is given only to an accessibility tool: Compose's
 * createNodeInfo reads `semanticsNode.config.getOrNull(IsSensitiveData)` and, when set and the request does not come
 * from an accessibility tool (isRequestFromAccessibilityTool), returns nothing; otherwise it calls
 * `info.setAccessibilityDataSensitive(isSensitiveData)` (Android 14+).
 * https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat.android.kt#createNodeInfo
 * not ported: createNodeInfo's reading side (Compose itself runs it on every node that carries the property)
 */
fun Modifier.isSensitiveData(): Modifier = semantics { isSensitiveData = true }

/**
 * Initializes screenshot security on the window based on the Settings switch (Block screenshots; on Android 12 also
 * Hide in recents): FLAG_SECURE added or cleared. While a hold of [ScreenGuard] is active the flag stays on and the
 * switch's answer is what the hold restores on release.
 * https://raw.githubusercontent.com/signalapp/Signal-Android/main/core/ui/src/main/java/org/signal/core/ui/WindowExtensions.kt#initializeScreenshotSecurity
 * not ported: enableEdgeToEdge (the activities call androidx.activity's own)
 */
fun Window.initializeScreenshotSecurity(isScreenSecurityEnabled: Boolean) {
    if (ScreenGuard.keepsFlag(this, isScreenSecurityEnabled)) return
    if (isScreenSecurityEnabled) {
        addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** The calling dialog's keys or code stay off screenshots and under no other app's overlay. */
@Composable
fun ScreenGuard() = ScreenGuard.bind()

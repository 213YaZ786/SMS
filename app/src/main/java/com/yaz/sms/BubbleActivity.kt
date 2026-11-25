package com.yaz.sms

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.yaz.sms.core.security.initializeScreenshotSecurity
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.feature.thread.ThreadScreen
import com.yaz.sms.navigation.ReadableScroll
import com.yaz.sms.ui.theme.AppSurface
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** A conversation in a floating bubble, over the other apps. Not exported: only its own notification opens it. */
class BubbleActivity : ComponentActivity() {
    private val settings: SettingsStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thread = intent.getLongExtra(MainActivity.EXTRA_THREAD, -1L).takeIf { it >= 0 }
        val address = intent.getStringExtra(MainActivity.EXTRA_ADDRESS).orEmpty()
        // The bubble shows messages too: Block screenshots (and on Android 12 Hide in recents) as MainActivity, Signal's
        // https://raw.githubusercontent.com/signalapp/Signal-Android/main/core/ui/src/main/java/org/signal/core/ui/WindowExtensions.kt#initializeScreenshotSecurity
        lifecycleScope.launch {
            settings.settings.map { it.blockScreenshots || (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && it.hideInRecents) }
                .distinctUntilChanged().collect { isScreenSecurityEnabled ->
                    window.initializeScreenshotSecurity(isScreenSecurityEnabled)
                }
        }
        setContent {
            AppSurface {
                ReadableScroll { ThreadScreen(threadId = thread, address = address, draft = "", onBack = { finish() }, bubble = true) }
            }
        }
    }
}

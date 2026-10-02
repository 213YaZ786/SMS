package com.sms.app.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sms.app.navigation.LocalReadableInset
import com.sms.app.ui.component.FloatingAction
import com.sms.app.ui.component.FloatingFrame
import com.sms.app.ui.component.FloatingTop
import com.sms.app.ui.component.LocalDockPadding
import com.sms.app.ui.icon.AppIcons

/**
 * The main screen's frame. The content takes the whole screen, top to
 * bottom; its name, the way to Settings, the setup steps while any is
 * left and its own [controls] (search, filters) float over it in glass.
 */
@Composable
fun TabFrame(
    title: String,
    onOpenSettings: () -> Unit,
    controls: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val inset = LocalReadableInset.current
    FloatingFrame(
        bottom = LocalDockPadding.current + 16.dp,
        top = {
            FloatingTop(title = title, trailing = { FloatingAction(AppIcons.Settings, "Settings", onOpenSettings) })
            // Until SMS can take messages, the steps to get there come first.
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) { SetupZone() }
            controls?.invoke()
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(horizontal = inset)) { content(padding) }
    }
}

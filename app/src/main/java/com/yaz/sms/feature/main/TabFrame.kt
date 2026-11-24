package com.yaz.sms.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yaz.sms.navigation.LocalReadableInset
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingFrame
import com.yaz.sms.ui.component.FloatingTop
import com.yaz.sms.ui.component.LocalDockPadding
import com.yaz.sms.ui.icon.AppIcons

/**
 * The main screen's frame. The content takes the whole screen, top to
 * bottom; its name, the way to Settings, the setup steps while any is
 * left and its own [controls] (search, filters) float over it in glass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabFrame(
    title: String?,
    onOpenSettings: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    /** In the title's place, the screen's own buttons on the same line (FloatingTop's center slot). */
    center: (@Composable () -> Unit)? = null,
    controls: (@Composable () -> Unit)? = null,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    /** The content's own scroll state: a drag on the header scrolls it (FloatingFrame's `scroll`). */
    scroll: androidx.compose.foundation.gestures.ScrollableState? = null,
    /** True while the top must stay (a selection taken, the search open): it never slides away. */
    pinTop: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    val inset = LocalReadableInset.current
    // Material 3's enterAlwaysScrollBehavior (https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AppBar.kt#enterAlwaysScrollBehavior):
    // the top slides away with the list going down and comes back at the first move up. canScroll is the list's own:
    // a list that fits (nothing to scroll either way) never moves it, nor does a pinned top.
    val density = LocalDensity.current
    val headerPx = remember { mutableIntStateOf(0) }
    val statusPx = WindowInsets.statusBars.getTop(density)
    val state = rememberTopAppBarState(initialHeightOffsetLimit = -1f, initialHeightOffset = 0f, initialContentOffset = 0f)
    val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state,
        canScroll = { !pinTop && scroll != null && (scroll.canScrollForward || scroll.canScrollBackward) }
    )
    // The limit is the top's own height with the status bar, as the bar itself would measure it.
    SideEffect { if (headerPx.intValue > 0) state.heightOffsetLimit = -(headerPx.intValue + statusPx).toFloat() }
    // At the list's top, or while pinned, the top is always there (a jump back to the top, a search closed).
    LaunchedEffect(scroll, pinTop) {
        snapshotFlow { (pinTop || scroll?.canScrollBackward == false) && state.heightOffset != 0f }
            .collect { if (it) state.heightOffset = 0f }
    }
    // The soft edge under the top goes with it once more than half of it has left.
    val shown by remember { derivedStateOf { state.heightOffset > state.heightOffsetLimit / 2f } }
    FloatingFrame(
        scroll = scroll,
        bottom = LocalDockPadding.current + 16.dp,
        softEdge = shown,
        top = {
            Box(
                Modifier
                    .onSizeChanged { headerPx.intValue = it.height }
                    .graphicsLayer { translationY = state.heightOffset }
            ) {
                Column {
                    FloatingTop(title = title, leading = leading, center = center, trailing = { FloatingAction(AppIcons.Settings, "Settings", onOpenSettings) })
                    // Until SMS can take messages, the steps to get there come first.
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) { SetupZone() }
                    controls?.invoke()
                }
            }
        },
        overlay = overlay
    ) { padding ->
        // The list takes the whole width: its grid of cards fills a wide window (Android's "Feed" layout); a
        // single column keeps itself to LINE_WIDTH, centred. The behaviour's nested scroll sits on the list.
        Box(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection)) { content(padding) }
    }
}

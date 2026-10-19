package com.yaz.sms.feature.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.yaz.sms.ui.glass.GlassLook

/**
 * The same glass as [look], tinted with [tint] instead of the zone's own fill: a pane of coloured glass
 * (a call in green, an action in the accent). Dialer's rememberTinted
 * (https://github.com/213YaZ786/Dialer/blob/main/app/src/main/java/com/yaz/dialer/feature/call/IncomingChoice.kt,
 * line 278), as its feature/recents/NumberParts.kt CallBar gives a glass its tint.
 */
@Composable
internal fun rememberTinted(look: GlassLook?, tint: Color?): GlassLook? = remember(look, tint) {
    if (look == null || tint == null) {
        null
    } else {
        GlassLook(look.dark, look.ground, look.halos, zoneTint = tint, floatTint = tint, accentTint = look.accentTint)
    }
}

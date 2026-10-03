package com.yaz.sms.feature.thread

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.sms.core.link.LinkCleaner
import com.yaz.sms.core.link.LinkPreview
import com.yaz.sms.ui.component.FloatingPane
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.launch

/**
 * Under a message with a sound link: a small capsule, Preview; a tap and
 * this phone fetches the page (the site sees this phone then, so never by
 * itself) and the capsule opens into a card with its picture and title,
 * a tap on which opens the link.
 */
@Composable
internal fun LinkPreviewCapsule(url: String) {
    val clean = remember(url) { LinkCleaner.clean(url) }
    var preview by remember(clean) { mutableStateOf(LinkPreview.cached(clean)) }
    var loading by remember(clean) { mutableStateOf(false) }
    var failed by remember(clean) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val uri = LocalUriHandler.current
    AnimatedContent(
        targetState = preview,
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() },
        label = "preview"
    ) { shown ->
        if (shown == null) {
            if (failed) return@AnimatedContent
            val pop = remember { Animatable(0.6f) }
            LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f)) }
            FloatingPane(
                shape = CircleShape,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp).graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    alpha = ((pop.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                },
                onClick = {
                    if (loading) return@FloatingPane
                    haptics.tick()
                    loading = true
                    scope.launch {
                        val got = LinkPreview.fetch(clean)
                        loading = false
                        if (got == null) {
                            failed = true
                            haptics.reject()
                        } else {
                            preview = got
                            haptics.done()
                        }
                    }
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    else Icon(AppIcons.Link, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Preview", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            FloatingPane(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp),
                onClick = {
                    haptics.tick()
                    runCatching { uri.openUri(shown.url) }
                }
            ) {
                Column {
                    shown.picture?.let { picture ->
                        Image(
                            picture.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.91f).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        )
                    }
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(shown.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        shown.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(shown.site, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

package com.yaz.sms.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yaz.sms.BuildConfig
import com.yaz.sms.core.sms.OpenRequests
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.feature.compose.NewMessageScreen
import com.yaz.sms.feature.conversations.ConversationsScreen
import com.yaz.sms.feature.main.WelcomeScreen
import com.yaz.sms.feature.settings.SettingsScreen
import com.yaz.sms.feature.thread.ThreadScreen
import com.yaz.sms.ui.component.LocalDockPadding
import com.yaz.sms.ui.component.UpdatePrompt
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.glass.LocalGlass
import com.yaz.sms.ui.glass.LocalGlassBackdrop
import com.yaz.sms.ui.glass.glassFloating
import com.yaz.sms.ui.glass.glassGround
import com.yaz.sms.ui.glass.glassSource
import com.yaz.sms.ui.glass.rememberGlassBackdrop
import com.yaz.sms.ui.icon.AppIcons
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun SmsApp() {
    val navController = rememberNavController()
    // The only owner of the window insets: screens below draw under the bars
    // and take them as padding themselves. Transparent, because the page's
    // ground with its ambient light is painted once under the whole app.
    Scaffold(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { _ ->
        SmsNavHost(navController)
    }
}

@Composable
private fun SmsNavHost(navController: NavHostController) {
    val requests: OpenRequests = koinInject()
    val pending by requests.pending.collectAsState()
    // A notification or another app asked for a conversation.
    LaunchedEffect(pending) {
        val asked = pending ?: return@LaunchedEffect
        requests.consume()
        navController.popBackStack(Routes.MAIN, inclusive = false)
        if (asked.threadId == null && asked.address == null) {
            navController.navigate(Routes.new(asked.text))
        } else {
            navController.navigate(Routes.thread(asked.threadId, asked.address, asked.text))
        }
    }

    // A chat invite from outside: the question, then the new conversation.
    val invite by requests.invite.collectAsState()
    invite?.let { link ->
        com.yaz.sms.feature.compose.JoinQuestion(link, onDismiss = { requests.invite.value = null }) { address ->
            requests.invite.value = null
            requests.open(com.yaz.sms.core.sms.OpenRequest(null, address, null))
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        // Opening scales up from slightly small, going back scales down, the
        // same motion as the other apps.
        enterTransition = { scaleIn(initialScale = 0.94f, animationSpec = tween(NAV_MS)) + fadeIn(animationSpec = tween(NAV_MS)) },
        exitTransition = { fadeOut(animationSpec = tween(NAV_MS)) },
        popEnterTransition = { fadeIn(animationSpec = tween(NAV_MS)) },
        popExitTransition = { scaleOut(targetScale = 0.94f, animationSpec = tween(NAV_MS)) + fadeOut(animationSpec = tween(NAV_MS)) },
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.MAIN) {
            Main(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenThread = { thread, address -> navController.navigate(Routes.thread(thread, address)) },
                onNew = { navController.navigate(Routes.new()) }
            )
        }
        composable(
            Routes.THREAD,
            arguments = listOf(
                navArgument("t") { type = NavType.LongType; defaultValue = -1L },
                navArgument("a") { type = NavType.StringType; defaultValue = "" },
                navArgument("x") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            ReadableScroll {
                ThreadScreen(
                    threadId = entry.arguments?.getLong("t")?.takeIf { it >= 0 },
                    address = entry.arguments?.getString("a").orEmpty(),
                    draft = entry.arguments?.getString("x").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
        }
        composable(
            Routes.NEW,
            arguments = listOf(navArgument("x") { type = NavType.StringType; defaultValue = "" })
        ) { entry ->
            val text = entry.arguments?.getString("x").orEmpty()
            ReadableScroll {
                NewMessageScreen(
                    onBack = { navController.popBackStack() },
                    onPick = { address ->
                        navController.popBackStack()
                        navController.navigate(Routes.thread(null, address, text))
                    }
                )
            }
        }
        composable(Routes.SETTINGS) {
            ReadableScroll {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
    // After the NavHost, so it takes the back gesture before the NavHost's
    // predictive pop can.
    PlainBack(navController)
}

/** The conversations, the way to a new message floating over them, the first launch page until closed. */
@Composable
private fun Main(onOpenSettings: () -> Unit, onOpenThread: (Long, String) -> Unit, onNew: () -> Unit) {
    val store: SettingsStore = koinInject()
    var showWelcome by rememberSaveable { mutableStateOf(!store.current.welcomeSeen) }
    val settings by store.settings.collectAsState()
    if (!showWelcome && !BuildConfig.DEBUG) UpdatePrompt(settings.updates, BuildConfig.VERSION_NAME)

    val look = LocalGlass.current
    val backdrop = rememberGlassBackdrop()
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDockPadding provides navigationBar + 72.dp) {
            Box(Modifier.fillMaxSize().then(if (look != null) Modifier.glassSource(backdrop, look) else Modifier)) {
                ReadableScroll { ConversationsScreen(onOpenSettings = onOpenSettings, onOpenThread = onOpenThread) }
            }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop.takeIf { look != null }) {
            MovableComposeButton(onClick = onNew, above = 16.dp)
        }
        if (showWelcome) {
            Surface(
                Modifier.fillMaxSize().glassGround(LocalGlass.current, MaterialTheme.colorScheme.background),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Readable {
                    WelcomeScreen(onStart = {
                        showWelcome = false
                        store.update { it.copy(welcomeSeen = true) }
                    })
                }
            }
        }
    }
}

/**
 * The way to a new message: a round pane of glass washed with the accent.
 * A tap opens it; held, it lifts and follows the finger anywhere, and
 * stays where it is let go, kept for next time. Until moved it sits at
 * the thumb's side, [above] the bottom edge.
 */
@Composable
private fun MovableComposeButton(onClick: () -> Unit, above: Dp) {
    val haptics = rememberHaptics()
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val look = LocalGlass.current
    val backdrop = LocalGlassBackdrop.current
    val density = LocalDensity.current
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        val side = with(density) { ButtonSize.toPx() }
        val roomX = (constraints.maxWidth - side).coerceAtLeast(0f)
        val roomY = (constraints.maxHeight - side).coerceAtLeast(0f)
        val usual = with(density) { Offset(roomX - 8.dp.toPx(), roomY - above.toPx()) }
        // A place saved before the pill at the bottom came stays above it.
        val saved = (if (settings.composeX >= 0f) Offset(settings.composeX * roomX, settings.composeY * roomY) else null)?.let { Offset(it.x, minOf(it.y, usual.y)) }
        var dragging by remember { mutableStateOf<Offset?>(null) }
        val at = dragging ?: saved ?: usual
        val current by rememberUpdatedState(at)
        val lift by animateFloatAsState(if (dragging != null) 1.14f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "lift")
        val base = Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .size(ButtonSize)
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .clip(CircleShape)
        Box(
            contentAlignment = androidx.compose.ui.Alignment.Center,
            modifier = when {
                look != null && backdrop != null -> base.glassFloating(backdrop, CircleShape, look, tint = look.accentTint, lens = 1.4f)
                else -> base.background(MaterialTheme.colorScheme.primaryContainer)
            }
                .semantics {
                    role = Role.Button
                    contentDescription = "New message"
                }
                .pointerInput(roomX, roomY) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val up = withTimeoutOrNull(longPress) { waitForUpOrCancellation() }
                        if (up != null) {
                            haptics.firm()
                            onClick()
                            return@awaitEachGesture
                        }
                        haptics.firm()
                        var where = current
                        dragging = where
                        drag(down.id) { change ->
                            val d = change.positionChange()
                            change.consume()
                            where = Offset((where.x + d.x).coerceIn(0f, roomX), (where.y + d.y).coerceIn(0f, roomY))
                            dragging = where
                        }
                        haptics.tick()
                        val placed = where
                        store.update {
                            it.copy(
                                composeX = if (roomX > 0f) placed.x / roomX else 1f,
                                composeY = if (roomY > 0f) placed.y / roomY else 1f
                            )
                        }
                        dragging = null
                    }
                }
        ) {
            Icon(AppIcons.NewChat, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

private val ButtonSize = 64.dp

/** Long enough to be read as motion, short enough not to be waited on. */
private const val NAV_MS = 260

/*
 * The welcome guide as tiles, after FlorisBoard's SetupScreen: every step is read from the system (what is held,
 * allowed) and shown done or not, the tiles are Modules' SetupTiles. Laid out as Language's guide.
 * not ported: Flow.awaitTransition (waits for an IME state to change; the tiles are read again on each resume instead)
 * not ported: SetupLinks (FlorisBoard's help and privacy links; the guide of SMS has no page for them)
 * not ported: SetupScreen (kept under another name: WelcomeScreen and WelcomeContent below, the steps being the tiles)
 *
 * Copyright (C) 2021-2025 The FlorisBoard Contributors, Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0), translated from
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt#SetupScreen
 */
package com.yaz.sms.feature.main

import android.app.role.RoleManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.sms.core.chat.ChatLife
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.core.update.StoreLink
import com.yaz.sms.core.update.Updates
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.ui.component.BoldButton
import com.yaz.sms.ui.component.LoadingMark
import com.yaz.sms.ui.component.SetupTile
import com.yaz.sms.ui.component.SetupTileDialog
import com.yaz.sms.ui.component.SetupTileGrid
import com.yaz.sms.ui.component.leftText
import com.yaz.sms.ui.component.requiredDone
import com.yaz.sms.ui.component.requiredLeft
import com.yaz.sms.ui.icon.AppIcons
import org.koin.compose.koinInject

/**
 * The few choices worth making, the best one already set, as tiles: each is read from the system again each time the
 * screen comes back (SetupScreen reads isFlorisBoardEnabled, isFlorisBoardSelected and hasNotificationPermission,
 * and sets the current step from them), and turns done by itself. Only a role, an important permission and what
 * leaves the phone are asked; everything else keeps its best default.
 * Ported from rememberSetupTiles of
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#rememberSetupTiles
 * Steps: the messaging app (required), notifications, the encrypted chat, the name and photo shared with it, updates
 * (only without Store). Not ported: the microphone, engine, voice input, voice keyboard and languages tiles (Language's
 * own steps, SMS has none of them); the notification permission launcher (SMS's rememberSetup already holds it).
 */
@Composable
internal fun rememberSetupTiles(setup: Setup = rememberSetup()): List<SetupTile> {
    val context = LocalContext.current
    val store: SettingsStore = koinInject()
    val chat: RichChat = koinInject()
    val settings by store.settings.collectAsState()
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val canInstall = remember(checks) { Updates.canInstall(context) }
    // When Store installs the app's updates, the app asks nothing about them.
    val viaStore = remember(checks) { StoreLink.viaStore(context) }

    return listOfNotNull(
        SetupTile(
            id = "role", name = "Messaging app", title = "Messaging app", icon = AppIcons.Message,
            state = when {
                setup.roleHeld -> "SMS"
                setup.roleBlocked -> "Not chosen, open settings"
                else -> "Not chosen"
            },
            summary = "SMS sends and receives your messages once it is your messaging app. " +
                if (setup.roleBlocked) "Android no longer asks, so its default apps page opens." else "Tap to choose SMS.",
            done = setup.roleHeld, required = true, error = setup.roleBlocked,
            onClick = { setup.run(SetupStep.ROLE) }
        ),
        SetupTile(
            id = "notifications", name = "Notifications", title = "Notifications", icon = AppIcons.NotificationsOn,
            state = when {
                setup.notificationsAllowed -> "Allowed"
                setup.notificationsRefused -> "Refused, open settings"
                else -> "Not allowed"
            },
            summary = "To see new messages and answer them. " +
                if (setup.notificationsRefused) "It was refused, so Android's settings page for SMS opens." else "Tap to allow it.",
            done = setup.notificationsAllowed, required = false, error = setup.notificationsRefused,
            onClick = { setup.run(SetupStep.NOTIFICATIONS) }
        ),
        SetupTile(
            id = "chat", name = "Encrypted chat", title = "Encrypted chat", icon = AppIcons.Lock,
            state = if (settings.richChat) "On" else "Off",
            summary = "With people who use SMS too: end-to-end encrypted, read receipts, reactions, photos in full quality, " +
                "over the internet through a chatmail relay. Its messages arrive while SMS is open: nothing runs with the app closed. " +
                "Tap to turn it on or off.",
            // A choice: On or Off is the user's answer, so the tile is done either way.
            done = true, required = false,
            onClick = {
                val on = !settings.richChat
                store.update { it.copy(richChat = on) }
                if (!on) chat.stop()
            }
        ),
        // The user's own card to the people of the encrypted chat, as iOS shares name and photo.
        if (settings.richChat) SetupTile(
            id = "card", name = "Name and photo", title = "Share your name and photo", icon = AppIcons.Person,
            state = if (settings.shareCard) "Shared" else "Not shared",
            summary = "Your card in Android's contacts (Me) goes with your encrypted messages; theirs arrive in the Contacts app, " +
                "to apply or ignore. Tap to share it or not.",
            // A choice: shared or not is the user's answer, so the tile is done either way.
            done = true, required = false,
            onClick = { store.update { it.copy(shareCard = !settings.shareCard) } }
        ) else null,
        if (!viaStore) SetupTile(
            id = "updates", name = "Updates", title = "Updates", icon = AppIcons.Update,
            state = if (canInstall) "Allowed" else "Installs not allowed",
            summary = "SMS installs its new versions, checked on GitHub. It needs Android's leave to install. Tap to allow it.",
            done = canInstall, required = false,
            onClick = { if (!canInstall) Updates.allowInstalls(context) }
        ) else null
    )
}

/**
 * The guide: the logo, the title, how many steps are left, the tiles, then Start (Done when reopened from Settings),
 * which waits for the required ones. Laid out as SetupScreen: header, steps, footer with the one button.
 * Ported from SetupScreen#SetupScreen of
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt#SetupScreen
 * and WelcomeScreen of
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#WelcomeScreen
 */
@Composable
fun WelcomeScreen(reopened: Boolean = false, onStart: () -> Unit) {
    val context = LocalContext.current
    val setup = rememberSetup()
    val steps = rememberSetupTiles(setup)
    // The tile whose long press is open (its id), "" when none.
    var explained by rememberSaveable { mutableStateOf("") }

    // Android's own question comes up by itself on arriving here, once;
    // the tile keeps its tap for whoever dismissed it.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked && setup.step == SetupStep.ROLE && !setup.roleBlocked) {
            asked = true
            setup.askRole()
        }
    }

    WelcomeContent(steps, reopened, onStart = {
        // The role held: the chat engine wakes now, not at the next opening (ChatLife.wake checks the chat is on).
        if (context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)) {
            ChatLife.wake()
        }
        onStart()
    }) { explained = it.id }

    steps.firstOrNull { it.id == explained }?.let { tile ->
        SetupTileDialog(tile, onDismiss = { explained = "" })
    }
}

/**
 * What the guide draws from the tiles it is given: the layout of SetupScreen (header, steps, footer with the one
 * button), split from [WelcomeScreen] as Language's WelcomeContent is, so that a picture can be taken with fixed
 * tiles, without the system checks. Ported from
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#WelcomeContent
 */
@Composable
internal fun WelcomeContent(tiles: List<SetupTile>, reopened: Boolean, onStart: () -> Unit, onExplain: (SetupTile) -> Unit = {}) {
    val steps = tiles
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(40.dp))
            // The app's own mark, large and ringing, as the guide before the tiles.
            LoadingMark(size = 160.dp)
            Spacer(Modifier.height(20.dp))
            // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
            Text("Welcome to SMS", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(steps.leftText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            SetupTileGrid(steps, onExplain)

            Spacer(Modifier.height(32.dp))
            // Without the SMS role the app cannot work: Start waits for it (the required tile), as AOSP Messaging's
            // PermissionCheckActivity keeps its Next until the permissions are granted.
            val ready = steps.requiredDone()
            BoldButton(filled = true, enabled = ready, onClick = onStart) { Text(if (reopened) "Done" else "Start") }
            if (!ready) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Still needed: " + steps.requiredLeft().joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

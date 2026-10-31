package com.yaz.sms.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.sms.core.update.Updates
import com.yaz.sms.ui.component.BoldButton
import com.yaz.sms.ui.component.LoadingMark
import com.yaz.sms.ui.icon.AppIcons

/**
 * The one page shown at the first launch: what SMS needs to be the
 * messaging app, done from here, and why it uses the internet at all, which is only
 * to update itself. Each zone turns into a tick once done.
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    val setup = rememberSetup()
    // Installing from the app is allowed in Android's own page, so it is
    // read again whenever the user comes back from there.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val canInstall = remember(checks) { Updates.canInstall(context) }

    // Android's own question comes up by itself on arriving here, once;
    // the zone keeps its button for whoever dismissed it.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked && setup.step == SetupStep.ROLE && !setup.roleBlocked) {
            asked = true
            setup.askRole()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Spacer(Modifier.height(40.dp))
        // The app's own mark, large and ringing, as on the other apps' first page.
        LoadingMark(size = 160.dp)
        Spacer(Modifier.height(20.dp))
        // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
        Text("Welcome to SMS", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "A messaging app with no account, no tracking and no ads.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        // What is on by default can be turned off right here.
        val store: com.yaz.sms.data.settings.SettingsStore = org.koin.compose.koinInject()
        val settings by store.settings.collectAsState()
        val step = setup.step
        WelcomeZone(
            icon = AppIcons.Message,
            title = step?.title ?: "SMS is your messaging app",
            message = step?.message ?: "Your messages and the links to write to someone open in SMS.",
            done = step == null,
            action = step?.let(setup::action),
            onAction = { step?.let(setup.run) },
            warning = step?.let(setup::warning)
        )
        // Nothing to bring over: Android keeps the messages for every messaging app.
        Spacer(Modifier.height(16.dp))
        WelcomeZone(
            icon = AppIcons.Done,
            title = "Already here",
            message = "Your SMS and MMS are Android's: those of the app you used before are here. RCS chats stay in the app that had them.",
            done = true,
            action = null,
            onAction = {}
        )
        Spacer(Modifier.height(16.dp))
        WelcomeZone(
            icon = AppIcons.Lock,
            title = "Encrypted chat",
            message = "With people who use SMS too: end-to-end encrypted, read receipts, reactions, photos in full quality, over the internet through a chatmail relay. Its messages arrive while SMS is open: nothing runs with the app closed.",
            done = true,
            action = null,
            onAction = {},
            toggle = settings.richChat,
            onToggle = { on -> store.update { it.copy(richChat = on) } }
        )
        Spacer(Modifier.height(16.dp))
        WelcomeZone(
            icon = AppIcons.Warning,
            title = "Links checked",
            message = "Links not recognized are pointed out before they open, also against a public list of dangerous sites, updated daily.",
            done = true,
            action = null,
            onAction = {},
            toggle = settings.checkLinks,
            onToggle = { on -> store.update { it.copy(checkLinks = on) } }
        )
        // The user's own card to the people of the encrypted chat, as iOS shares name and photo.
        if (settings.richChat) {
            Spacer(Modifier.height(16.dp))
            WelcomeZone(
                icon = AppIcons.Person,
                title = "Share your name and photo",
                message = "Your card in Android's contacts (Me) goes with your encrypted messages; theirs arrive in the Contacts app, to apply or ignore.",
                done = true,
                action = null,
                onAction = {},
                toggle = settings.shareCard,
                onToggle = { on -> store.update { it.copy(shareCard = on) } }
            )
        }
        Spacer(Modifier.height(16.dp))
        WelcomeZone(
            icon = AppIcons.Update,
            title = "Automatic updates",
            message = "SMS installs its new versions, checked on GitHub.",
            done = canInstall,
            action = "Allow updates".takeIf { !canInstall },
            onAction = { Updates.allowInstalls(context) }
        )

        Spacer(Modifier.height(32.dp))
        // Without the SMS role the app cannot work: Start waits for it, as AOSP Messaging's PermissionCheckActivity
        // keeps its Next until the permissions are granted
        // https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/ui/PermissionCheckActivity.java
        val needsRole = step == SetupStep.ROLE
        if (needsRole) {
            Text(
                "Make SMS your messaging app to start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
        }
        BoldButton(filled = true, enabled = !needsRole, onClick = onStart) { Text("Start") }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** One thing to know or to do, with its button until it is done, then a tick. */
@Composable
private fun WelcomeZone(
    icon: ImageVector,
    title: String,
    message: String,
    done: Boolean,
    action: String?,
    onAction: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    /** On by default, turned off here: a switch by the title. */
    toggle: Boolean? = null,
    onToggle: (Boolean) -> Unit = {},
    /** A line that stays while a step is refused, in the error colour. */
    warning: String? = null
) {
    val haptics = com.yaz.sms.ui.component.rememberHaptics()
    // A zone means a tap (the user, 2026-10-28): the text and its buttons or switch lie straight on the page, as
    // Element X's NotificationsOptInView lays its text and buttons (element-hq/element-x-android, AGPL-3.0:
    // https://github.com/element-hq/element-x-android/blob/develop/features/ftue/impl/src/main/kotlin/io/element/android/features/ftue/impl/notifications/NotificationsOptInView.kt).
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done && toggle != false) AppIcons.CheckCircle else icon,
                contentDescription = if (done) "Done" else null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
            toggle?.let { on ->
                com.yaz.sms.ui.component.GlassSwitch(checked = on, onCheckedChange = {
                    haptics.tick()
                    onToggle(it)
                })
            }
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra?.invoke()
        warning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        if (!done && action != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                BoldButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

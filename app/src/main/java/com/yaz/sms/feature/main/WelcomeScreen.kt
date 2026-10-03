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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.yaz.sms.ui.component.ZoneSurface
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
        if (!asked && setup.step == SetupStep.ROLE) {
            asked = true
            setup.run(SetupStep.ROLE)
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
        Text("Welcome to SMS", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
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
            action = step?.action,
            onAction = { step?.let(setup.run) }
        )
        // Moved from the old SMS: everything came over; the old one can go.
        val oldThere = remember(checks) { com.yaz.sms.core.handover.Handover.oldInstalled(context) }
        if (oldThere) {
            Spacer(Modifier.height(16.dp))
            WelcomeZone(
                icon = AppIcons.Delete,
                title = "The old SMS",
                message = "Your messages, chats and settings are here now. Remove the old app.",
                done = false,
                action = "Remove it",
                onAction = { com.yaz.sms.core.handover.Handover.removeOld(context) }
            )
        }
        Spacer(Modifier.height(16.dp))
        WelcomeZone(
            icon = AppIcons.Lock,
            title = "Encrypted chat",
            message = "With people who use SMS too: end-to-end encrypted, read receipts, reactions, photos in full quality, over the internet through a chatmail relay. A silent notification keeps it connected; it can be hidden.",
            // Read again on coming back from Android's page.
            done = !settings.richChat || remember(checks) {
                context.getSystemService(android.app.NotificationManager::class.java)
                    .getNotificationChannel(com.yaz.sms.core.chat.ChatService.CHANNEL)?.importance == android.app.NotificationManager.IMPORTANCE_NONE
            },
            action = "Hide its notification",
            onAction = { com.yaz.sms.core.chat.ChatService.hideNotice(context) },
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
        // Voice messages written out: the model for this phone already chosen, fetched on a tap.
        val transcriber: com.yaz.sms.core.voice.Transcriber = org.koin.compose.koinInject()
        if (transcriber.available) {
            val model by transcriber.model.state.collectAsState()
            Spacer(Modifier.height(16.dp))
            WelcomeZone(
                icon = AppIcons.TextFormat,
                title = "Voice messages written out",
                message = when (val m = model) {
                    is com.yaz.sms.core.voice.SpeechModel.State.Fetching -> "Getting the speech model · ${(m.done * 100).toInt()}%"
                    com.yaz.sms.core.voice.SpeechModel.State.Ready -> "On this phone only, any language."
                    else -> "On this phone only, any language. The speech model is fetched once."
                },
                done = !settings.transcribeVoice || model == com.yaz.sms.core.voice.SpeechModel.State.Ready || model is com.yaz.sms.core.voice.SpeechModel.State.Fetching,
                action = "Get it",
                onAction = { transcriber.model.fetch() },
                toggle = settings.transcribeVoice,
                onToggle = { on ->
                    store.update { it.copy(transcribeVoice = on) }
                    if (!on && model is com.yaz.sms.core.voice.SpeechModel.State.Fetching) transcriber.model.cancel()
                },
                extra = if (settings.transcribeVoice && model !is com.yaz.sms.core.voice.SpeechModel.State.Fetching) ({
                    com.yaz.sms.feature.thread.ModelChoices(transcriber.model)
                }) else null
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
        BoldButton(filled = true, onClick = onStart) { Text("Start") }
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
    onToggle: (Boolean) -> Unit = {}
) {
    val haptics = com.yaz.sms.ui.component.rememberHaptics()
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (done && toggle != false) AppIcons.CheckCircle else icon,
                    contentDescription = if (done) "Done" else null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                toggle?.let { on ->
                    androidx.compose.material3.Switch(checked = on, onCheckedChange = {
                        haptics.tick()
                        onToggle(it)
                    })
                }
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra?.invoke()
            if (!done && action != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    BoldButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}

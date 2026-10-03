package com.yaz.sms.feature.settings

import com.yaz.sms.core.dial.SalesCalls
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingFrame
import com.yaz.sms.ui.component.FloatingTop
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import org.koin.compose.koinInject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaz.sms.BuildConfig
import com.yaz.sms.core.update.UpdateMode
import com.yaz.sms.core.update.Updates
import com.yaz.sms.data.settings.ThemeMode
import com.yaz.sms.navigation.LocalReadableInset
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import com.yaz.sms.ui.theme.TEXT_SCALES
import com.yaz.sms.ui.theme.textScaleLabel
import org.koin.androidx.compose.koinViewModel

private enum class OpenDialog { NONE, THEME, TEXT_SIZE, UPDATES, RELAY, UNDO, START }

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var dialog by rememberSaveable { mutableStateOf(OpenDialog.NONE) }

    // Scrolls at full width, rows pushed in by the readable inset, so the
    // margins of a tablet scroll like the rest. See ReadableScroll.
    FloatingFrame(
        bottom = 0.dp,
        top = { FloatingTop("Settings", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalReadableInset.current)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))

            Section("Encrypted chat") {
                val chat: com.yaz.sms.core.chat.RichChat = org.koin.compose.koinInject()
                val status by chat.status.collectAsState()
                SwitchRow(
                    title = "Encrypted chat with SMS users",
                    summary = if (settings.richChat) status else "Messages go as SMS and MMS only.",
                    checked = settings.richChat,
                    onChange = { on ->
                        viewModel.setRichChat(on)
                        if (on) com.yaz.sms.core.chat.ChatService.startIfWanted(context, viewModel.store)
                        else {
                            com.yaz.sms.core.chat.ChatService.stop(context)
                            chat.stop()
                        }
                    }
                )
                val changes by chat.changes.collectAsState()
                val relays by androidx.compose.runtime.produceState(emptyList<String>(), status, changes) { value = chat.relays() }
                if (settings.richChat) SwitchRow(
                    title = "Share your name and photo",
                    summary = "Your card in Android's contacts (Me) goes with your encrypted messages.",
                    checked = settings.shareCard,
                    onChange = viewModel::setShareCard
                )
                if (settings.richChat) SettingRow(
                    title = "Relays",
                    summary = when {
                        relays.isEmpty() -> if (settings.relay.isBlank()) "Automatic: the fastest, with backups" else settings.relay
                        relays.size == 1 -> relays[0]
                        else -> relays[0] + " · backups: " + relays.drop(1).joinToString(", ")
                    },
                    onClick = { dialog = OpenDialog.RELAY }
                )
                // Android asks for a notification while the chat stays connected; it can be hidden.
                if (settings.richChat) SettingRow(
                    title = "Hide the connection notification",
                    summary = "The chat keeps receiving; only the notification goes",
                    onClick = {
                        open(
                            context,
                            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.yaz.sms.core.chat.ChatService.CHANNEL)
                        )
                    }
                )
            }

            Section("Messages") {
                SettingRow(
                    title = "Opens on",
                    summary = START_FILTERS.firstOrNull { it.first == settings.startFilter }?.second ?: "All",
                    onClick = { dialog = OpenDialog.START }
                )
                SettingRow(
                    title = "Undo send",
                    summary = if (settings.undoSeconds == 0) "Off" else "${settings.undoSeconds} seconds to take a message back",
                    onClick = { dialog = OpenDialog.UNDO }
                )
                SettingRow(
                    title = "Notifications",
                    summary = "Sound, vibration and how they show",
                    onClick = { open(context, Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                )
                // Only where the country keeps ranges for sales.
                val country = remember {
                    val phone = context.getSystemService(android.telephony.TelephonyManager::class.java)
                    listOf(phone?.networkCountryIso, phone?.simCountryIso, java.util.Locale.getDefault().country)
                        .firstOrNull { !it.isNullOrBlank() }?.lowercase()
                }
                if (country in SalesCalls.countries) {
                    SwitchRow(
                        title = "Quiet sales messages",
                        summary = "Messages from numbers reserved for sales come without a notification, unless saved in your contacts.",
                        checked = settings.quietSales,
                        onChange = viewModel::setQuietSales
                    )
                }
                SwitchRow(
                    title = "Check links",
                    summary = "Links in messages are also looked up in a public list of dangerous sites, downloaded once a day. No link leaves the phone.",
                    checked = settings.checkLinks,
                    onChange = viewModel::setCheckLinks
                )
                // Where the phone can run the speech model (64-bit ARM).
                val transcriber: com.yaz.sms.core.voice.Transcriber = org.koin.compose.koinInject()
                if (transcriber.available) {
                    SwitchRow(
                        title = "Transcribe voice messages",
                        summary = "Their words written under them, on the phone itself, never sent anywhere.",
                        checked = settings.transcribeVoice,
                        onChange = viewModel::setTranscribeVoice
                    )
                    if (settings.transcribeVoice) com.yaz.sms.feature.thread.SpeechModelPanel(
                        transcriber.model,
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
                SettingRow(
                    title = "Blocked numbers",
                    summary = "The list Android keeps for every app",
                    onClick = { open(context, context.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }
                )
                SettingRow(
                    title = "Messaging app",
                    summary = "The app Android uses for messages",
                    onClick = { open(context, Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                )
            }

            Section("Appearance") {
                SettingRow(
                    title = "Theme",
                    summary = themeLabel(settings.themeMode),
                    onClick = { dialog = OpenDialog.THEME }
                )
                SwitchRow(
                    title = "Pure black",
                    summary = "Black background in dark mode.",
                    checked = settings.pureBlack,
                    onChange = viewModel::setPureBlack
                )
                SwitchRow(
                    title = "Hide in recent apps",
                    summary = "The app's preview stays blank in recent apps.",
                    checked = settings.hideInRecents,
                    onChange = viewModel::setHideInRecents
                )
                SwitchRow(
                    title = "Glass effects",
                    summary = "Buttons and panes in liquid glass.",
                    checked = settings.glass,
                    onChange = viewModel::setGlass
                )
                SettingRow(
                    title = "Text size",
                    summary = textScaleLabel(settings.textScale) + ", on top of Android's font size",
                    onClick = { dialog = OpenDialog.TEXT_SIZE }
                )
            }

            Section("About") {
                SettingRow(
                    title = "Updates",
                    summary = updatesLabel(settings.updates),
                    onClick = { dialog = OpenDialog.UPDATES }
                )
                SettingRow(
                    title = "SMS ${BuildConfig.VERSION_NAME}",
                    summary = "A phone app with no account, no tracking and no ads.",
                    onClick = null
                )
                SettingRow(
                    title = "Encrypted chat engine",
                    summary = "chatmail core, Mozilla Public License 2.0",
                    onClick = { uriHandler.openUri("https://github.com/chatmail/core") }
                )
                SettingRow(
                    title = "Animated emoji",
                    summary = "Noto Animated Emoji by Google, CC BY 4.0",
                    onClick = { uriHandler.openUri("https://googlefonts.github.io/noto-emoji-animation/") }
                )
                SettingRow(
                    title = "Speech engine",
                    summary = "whisper.cpp and OpenAI's Whisper model, MIT License",
                    onClick = { uriHandler.openUri("https://github.com/ggml-org/whisper.cpp") }
                )
                SettingRow(
                    title = "Source code",
                    summary = "github.com/213YaZ786/SMS",
                    onClick = { uriHandler.openUri("https://github.com/213YaZ786/SMS") }
                )
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    when (dialog) {
        OpenDialog.THEME -> ChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.themeMode,
            onSelect = viewModel::setTheme,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.TEXT_SIZE -> ChoiceDialog(
            title = "Text size",
            options = TEXT_SCALES.map { it to textScaleLabel(it) },
            selected = settings.textScale,
            onSelect = viewModel::setTextScale,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.UPDATES -> ChoiceDialog(
            title = "Updates",
            options = UpdateMode.entries.map { it to updatesLabel(it) },
            selected = settings.updates,
            onSelect = { mode ->
                viewModel.setUpdates(mode)
                // Installing needs Android's leave, asked when chosen.
                if (mode == UpdateMode.INSTALL && !Updates.canInstall(context)) Updates.allowInstalls(context)
            },
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.RELAY -> {
            val chat: com.yaz.sms.core.chat.RichChat = org.koin.compose.koinInject()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            ChoiceDialog(
                title = "Relays",
                // The ones the chat uses now are marked.
                options = run {
                    val used by androidx.compose.runtime.produceState(emptyList<String>()) { value = chat.relays() }
                    listOf("" to "Automatic: the fastest, with backups") + com.yaz.sms.core.chat.Relays.known(context).map { host ->
                        host to if (used.any { it.endsWith(host) || it == host }) "$host · in use" else host
                    }
                },
                selected = settings.relay,
                onSelect = { host ->
                    viewModel.setRelay(host)
                    // A relay picked by hand joins the profile at once.
                    if (host.isNotBlank()) scope.launch { chat.addRelay(host) }
                },
                onDismiss = { dialog = OpenDialog.NONE }
            )
        }
        OpenDialog.UNDO -> ChoiceDialog(
            title = "Undo send",
            options = listOf(0, 2, 4, 6, 10).map { it to if (it == 0) "Off" else "$it seconds" },
            selected = settings.undoSeconds,
            onSelect = viewModel::setUndoSeconds,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.START -> ChoiceDialog(
            title = "Opens on",
            options = START_FILTERS,
            selected = settings.startFilter,
            onSelect = viewModel::setStartFilter,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.NONE -> Unit
    }
}

/** The sections of the list the app may open on. */
private val START_FILTERS = listOf("ALL" to "All", "UNREAD" to "Unread", "UNKNOWN" to "Unknown", "LATER" to "Later", "ARCHIVED" to "Archived")

/** A titled group of rows on one rounded zone. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    // Centred and at title size: a heading names what the zone below holds.
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 10.dp)
    )
    ZoneSurface(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingRow(
    title: String,
    summary: String?,
    onClick: (() -> Unit)?,
    quiet: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val haptics = rememberHaptics()
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = summary?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // Every row answers with a tick; a switch row answers with the
        // switch's own feel instead, so it is quiet here.
        modifier = if (onClick != null) {
            Modifier.clickable {
                if (!quiet) haptics.tick()
                onClick()
            }
        } else {
            Modifier
        }
    )
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val change = { on: Boolean ->
        haptics.toggle(on)
        onChange(on)
    }
    SettingRow(
        title = title,
        summary = summary,
        quiet = true,
        onClick = if (enabled) ({ change(!checked) }) else null,
        trailing = { Switch(checked = checked, onCheckedChange = change, enabled = enabled) }
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // A long list scrolls inside the dialog, never past the screen.
            val tall = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.55f).dp
            Column(Modifier.heightIn(max = tall).verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


/** The first of these Android screens the phone has: newer ones first, the general one last. */
private fun openFirst(context: Context, vararg actions: String) {
    for (action in actions) {
        if (runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/** An Android settings screen; some phones leave one out, then nothing happens. */
private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Same as the system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun updatesLabel(mode: UpdateMode): String = when (mode) {
    UpdateMode.OFF -> "Off"
    UpdateMode.NOTIFY -> "Notify me"
    UpdateMode.INSTALL -> "Install automatically"
}

/** Public chatmail relays to choose from; the profile lives on one, chats work across all. */

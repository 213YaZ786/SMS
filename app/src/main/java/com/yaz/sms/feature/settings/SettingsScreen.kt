package com.yaz.sms.feature.settings

import com.yaz.sms.core.dial.SalesCalls
import com.yaz.sms.ui.component.FloatingAction
import com.yaz.sms.ui.component.FloatingFrame
import com.yaz.sms.ui.component.FloatingTop
import com.yaz.sms.ui.component.LargeTitle
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.yaz.sms.ui.component.BoldButton
import com.yaz.sms.ui.component.ChoiceDialog
import com.yaz.sms.ui.component.GlassRadio
import com.yaz.sms.ui.component.GlassSwitch
import com.yaz.sms.ui.component.QuietButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.sms.BuildConfig
import com.yaz.sms.core.update.UpdateMode
import com.yaz.sms.core.update.Updates
import com.yaz.sms.data.settings.ThemeMode
import com.yaz.sms.navigation.LocalReadableInset
import com.yaz.sms.navigation.WidthClass
import com.yaz.sms.ui.component.SettingsActionTile
import com.yaz.sms.ui.component.SettingsFoldedSections
import com.yaz.sms.ui.component.SettingsHeaderSpacer
import com.yaz.sms.ui.component.SettingsListDetail
import com.yaz.sms.ui.component.SettingsSection
import com.yaz.sms.ui.component.SettingsSectionTitle
import com.yaz.sms.ui.component.SettingsSwitchTile
import com.yaz.sms.ui.component.SettingsTile
import com.yaz.sms.ui.component.SettingsTileDialogs
import com.yaz.sms.ui.component.SettingsTileGrid
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import com.yaz.sms.ui.theme.TEXT_SCALES
import com.yaz.sms.ui.theme.textScaleLabel
import org.koin.androidx.compose.koinViewModel

private enum class OpenDialog { NONE, THEME, TEXT_SIZE, UPDATES, RELAY, UNDO, SWIPE_LEFT, SWIPE_RIGHT, OWN_NUMBER, REACTION_FORMAT }

/** The groups of settings: one page after another on a phone, a list and the chosen one's page on a wide window. */
private val CHAT = SettingsSection("chat", "Encrypted chat", AppIcons.Lock)
private val MESSAGES = SettingsSection("messages", "Messages", AppIcons.Message)
private val APPEARANCE = SettingsSection("appearance", "Appearance", AppIcons.Palette)
private val ABOUT = SettingsSection("about", "About", AppIcons.Info)
private val SECTIONS = listOf(CHAT, MESSAGES, APPEARANCE, ABOUT)

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var dialog by rememberSaveable { mutableStateOf(OpenDialog.NONE) }
    // The tile whose long press is open (its id), "" when none.
    var tileId by rememberSaveable { mutableStateOf("") }
    var sectionId by rememberSaveable { mutableStateOf(CHAT.id) }
    val section = SECTIONS.first { it.id == sectionId }
    // On a phone every section starts folded; the one open stays open across a turn of the screen.
    var openId by rememberSaveable { mutableStateOf("") }
    val openSection = SECTIONS.firstOrNull { it.id == openId }
    // The page's scroll, held here so a drag on the header drives it (FloatingFrame's `scroll`): the folded
    // list on a phone, the chosen section's page on a wide window (it starts at the top again for each section).
    val compactScroll = rememberScrollState()
    val detailScroll = rememberSaveable(sectionId, saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    // What Android gives as the SIM's number, read once (OwnNumbers.sim): shown under "My number" and prefilled in its field.
    val simNumber = remember { com.yaz.sms.core.mms.OwnNumbers.sim(context).firstOrNull() }

    val chat: com.yaz.sms.core.chat.RichChat = org.koin.compose.koinInject()
    val chatStatus by chat.status.collectAsState()
    val changes by chat.changes.collectAsState()
    val relays by androidx.compose.runtime.produceState(emptyList<String>(), chatStatus, changes) { value = chat.relays() }
    // Only where the country keeps ranges for sales.
    val country = remember { SalesCalls.country(context) }
    // Shown only where the Voice app is on the phone (a button whose target app is missing hides).
    val transcriber: com.yaz.sms.core.voice.Transcriber = org.koin.compose.koinInject()

    val tiles: Map<SettingsSection, List<SettingsSwitchTile>> = mapOf(
        CHAT to listOf(
            SettingsSwitchTile(
                id = "chat", name = "Encrypted chat", title = "Encrypted chat with SMS users", icon = AppIcons.Lock,
                summary = if (settings.richChat) chatStatus else "Messages go as SMS and MMS only.",
                checked = settings.richChat,
                onChange = { on ->
                    viewModel.setRichChat(on)
                    if (on) com.yaz.sms.core.chat.ChatLife.wake() else chat.stop()
                }
            ),
            SettingsSwitchTile(
                id = "card", name = "Name and photo", title = "Share your name and photo", icon = AppIcons.Person,
                summary = "Your card in Android's contacts (Me) goes with your encrypted messages.",
                checked = settings.shareCard, enabled = settings.richChat, onChange = viewModel::setShareCard
            ),
            // Delta Chat Android's pref_read_receipts and its explanation, in the engine's meaning
            // (Config::MdnsEnabled: receipts "sent and requested"): off, none are sent and none are seen.
            SettingsSwitchTile(
                id = "receipts", name = "Read receipts", title = "Send read receipts", icon = AppIcons.DoneAll,
                summary = "Off: you stop seeing others' receipts too.",
                checked = settings.readReceipts, enabled = settings.richChat,
                onChange = { on ->
                    viewModel.setReadReceipts(on)
                    chat.applyReadReceipts(on)
                }
            )
        ),
        MESSAGES to listOfNotNull(
            SettingsSwitchTile(
                id = "spam", name = "Spam protection", title = "Spam protection", icon = AppIcons.Report,
                summary = "Spam goes to Spam, without a notification.",
                checked = settings.spamProtection, onChange = viewModel::setSpamProtection
            ),
            if (country in SalesCalls.countries) SettingsSwitchTile(
                id = "sales", name = "Quiet sales", title = "Quiet sales messages", icon = AppIcons.NotificationsOff,
                summary = "Messages from numbers reserved for sales come without a notification, unless saved in your contacts.",
                checked = settings.quietSales, onChange = viewModel::setQuietSales
            ) else null,
            SettingsSwitchTile(
                id = "links", name = "Check links", title = "Check links", icon = AppIcons.Link,
                summary = "Links in messages are also looked up in a public list of dangerous sites, downloaded once a day. No link leaves the phone.",
                checked = settings.checkLinks, onChange = viewModel::setCheckLinks
            ),
            if (transcriber.available) SettingsSwitchTile(
                id = "voice", name = "Transcribe voice", title = "Transcribe voice messages", icon = AppIcons.Mic,
                summary = "By the Voice app, on the phone.",
                checked = settings.transcribeVoice, onChange = viewModel::setTranscribeVoice
            ) else null,
            SettingsSwitchTile(
                id = "mms", name = "Download pictures", title = "Download picture messages", icon = AppIcons.Download,
                summary = "A picture message downloads itself when it arrives; off, it waits for a tap in the conversation.",
                checked = settings.autoMmsDownload, onChange = viewModel::setAutoMmsDownload
            ),
            SettingsSwitchTile(
                id = "roaming", name = "When roaming", title = "Also when roaming", icon = AppIcons.Language,
                summary = "Abroad too, where data can cost more.",
                checked = settings.autoMmsRoaming, enabled = settings.autoMmsDownload, onChange = viewModel::setAutoMmsRoaming
            ),
            SettingsSwitchTile(
                id = "strangers", name = "Unknown media", title = "Show media from unknown numbers", icon = AppIcons.Image,
                summary = "Off, a photo, a video or a sound from a number in no contact waits for a tap.",
                checked = settings.showStrangerMedia, onChange = viewModel::setShowStrangerMedia
            )
        ),
        APPEARANCE to listOf(
            SettingsSwitchTile(
                id = "black", name = "Pure black", title = "Pure black", icon = AppIcons.Night,
                summary = "Black background in dark mode.",
                checked = settings.pureBlack, onChange = viewModel::setPureBlack
            ),
            SettingsSwitchTile(
                id = "recents", name = "Hide in recents", title = "Hide in recent apps", icon = AppIcons.VisibilityOff,
                summary = "The app's preview stays blank in recent apps.",
                checked = settings.hideInRecents, onChange = viewModel::setHideInRecents
            ),
            SettingsSwitchTile(
                id = "glass", name = "Glass effects", title = "Glass effects", icon = AppIcons.AutoAwesome,
                summary = "Buttons and panes in liquid glass.",
                checked = settings.glass, onChange = viewModel::setGlass
            )
        )
    )

    // What opens a choice, a page or a link: tiles of the same grid, the value as the second line.
    val actions: Map<SettingsSection, List<SettingsActionTile>> = mapOf(
        CHAT to listOfNotNull(
            if (settings.richChat) SettingsActionTile(
                id = "relays", name = "Relays", title = "Relays", icon = AppIcons.Dns,
                value = relays.firstOrNull() ?: settings.relay.ifBlank { "Automatic" },
                summary = when {
                    relays.isEmpty() -> if (settings.relay.isBlank()) "Automatic: the fastest, with backups" else settings.relay
                    relays.size == 1 -> relays[0]
                    else -> relays[0] + ", backups " + relays.drop(1).joinToString(", ")
                },
                onClick = { dialog = OpenDialog.RELAY }
            ) else null
        ),
        MESSAGES to run {
            val left = com.yaz.sms.core.sms.SwipeAction.of(settings.swipeLeft, com.yaz.sms.core.sms.SwipeAction.ARCHIVE).label
            val right = com.yaz.sms.core.sms.SwipeAction.of(settings.swipeRight, com.yaz.sms.core.sms.SwipeAction.DELETE).label
            val number = settings.ownNumber.ifBlank { simNumber ?: "Not set" }
            val reaction = reactionFormatLabel(settings.reactionFormat)
            listOf(
                SettingsActionTile(
                    id = "swipeLeft", name = "Swipe left", title = "Swipe left", icon = AppIcons.SwipeLeft,
                    value = left, summary = left, onClick = { dialog = OpenDialog.SWIPE_LEFT }
                ),
                SettingsActionTile(
                    id = "swipeRight", name = "Swipe right", title = "Swipe right", icon = AppIcons.SwipeRight,
                    value = right, summary = right, onClick = { dialog = OpenDialog.SWIPE_RIGHT }
                ),
                SettingsActionTile(
                    id = "undo", name = "Undo send", title = "Undo send", icon = AppIcons.Undo,
                    value = if (settings.undoSeconds == 0) "Off" else "${settings.undoSeconds} s",
                    summary = if (settings.undoSeconds == 0) "Off" else "${settings.undoSeconds} seconds to take a message back",
                    onClick = { dialog = OpenDialog.UNDO }
                ),
                // The number Android may not give: a group never splits for want of it (OwnNumbers).
                SettingsActionTile(
                    id = "number", name = "My number", title = "My number", icon = AppIcons.Smartphone,
                    value = number, summary = number, onClick = { dialog = OpenDialog.OWN_NUMBER }
                ),
                SettingsActionTile(
                    id = "reactions", name = "Reactions", title = "Reactions sent to others", icon = AppIcons.Mood,
                    value = reaction, summary = reaction, onClick = { dialog = OpenDialog.REACTION_FORMAT }
                ),
                SettingsActionTile(
                    id = "notifications", name = "Notifications", title = "Notifications", icon = AppIcons.NotificationsOn,
                    value = "Sound, vibration", summary = "Sound, vibration and how they show",
                    onClick = { open(context, Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                ),
                SettingsActionTile(
                    id = "blocked", name = "Blocked numbers", title = "Blocked numbers", icon = AppIcons.Block,
                    value = "Android's list", summary = "The list Android keeps for every app",
                    onClick = { open(context, context.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }
                ),
                SettingsActionTile(
                    id = "default", name = "Messaging app", title = "Messaging app", icon = AppIcons.Message,
                    value = "Default apps", summary = "The app Android uses for messages",
                    onClick = { open(context, Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                )
            )
        },
        APPEARANCE to listOf(
            SettingsActionTile(
                id = "theme", name = "Theme", title = "Theme", icon = AppIcons.Brightness,
                value = themeLabel(settings.themeMode), summary = themeLabel(settings.themeMode),
                onClick = { dialog = OpenDialog.THEME }
            ),
            SettingsActionTile(
                id = "textSize", name = "Text size", title = "Text size", icon = AppIcons.FormatSize,
                value = textScaleLabel(settings.textScale),
                summary = textScaleLabel(settings.textScale) + ", on top of Android's font size",
                onClick = { dialog = OpenDialog.TEXT_SIZE }
            )
        ),
        ABOUT to listOf(
            SettingsActionTile(
                id = "updates", name = "Updates", title = "Updates", icon = AppIcons.Update,
                value = updatesLabel(settings.updates), summary = updatesLabel(settings.updates),
                onClick = { dialog = OpenDialog.UPDATES }
            ),
            SettingsActionTile(
                id = "version", name = "SMS", title = "SMS ${BuildConfig.VERSION_NAME}", icon = AppIcons.Info,
                value = BuildConfig.VERSION_NAME, summary = "A messaging app with no account, no tracking and no ads."
            ),
            SettingsActionTile(
                id = "engine", name = "Chat engine", title = "Encrypted chat engine", icon = AppIcons.Memory,
                value = "chatmail core", summary = "chatmail core, Mozilla Public License 2.0",
                onClick = { uriHandler.openUri("https://github.com/chatmail/core") }
            ),
            SettingsActionTile(
                id = "emoji", name = "Animated emoji", title = "Animated emoji", icon = AppIcons.Mood,
                value = "Noto, CC BY 4.0", summary = "Noto Animated Emoji by Google, CC BY 4.0",
                onClick = { uriHandler.openUri("https://googlefonts.github.io/noto-emoji-animation/") }
            ),
            SettingsActionTile(
                id = "source", name = "Source code", title = "Source code", icon = AppIcons.Code,
                value = "github.com/213YaZ786/SMS", summary = "github.com/213YaZ786/SMS",
                onClick = { uriHandler.openUri("https://github.com/213YaZ786/SMS") }
            )
        )
    )

    // One section's page: its name, then its tiles: the switches first, then what opens something.
    val page: @Composable ColumnScope.(SettingsSection) -> Unit = { which ->
        SettingsSectionTitle(which.title)
        val list: List<SettingsTile> = tiles[which].orEmpty() + actions[which].orEmpty()
        if (list.isNotEmpty()) SettingsTileGrid(list) { tileId = it.id }
    }

    // The window decides: a list and a page side by side from Medium up, as a phone held upright
    // keeps one page. The panes take the whole width, so the readable inset is dropped.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = WidthClass.of(maxWidth) != WidthClass.COMPACT
        CompositionLocalProvider(LocalReadableInset provides if (wide) 0.dp else LocalReadableInset.current) {
            FloatingFrame(
                bottom = 0.dp,
                scroll = if (wide) detailScroll else compactScroll,
                top = { FloatingTop("Settings", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) },
                largeTitle = LargeTitle(
                    "Settings",
                    status = if (settings.richChat) "Encrypted chat, ${chatStatus.lowercase()}" else "SMS and MMS only"
                )
            ) { padding ->
                if (wide) {
                    SettingsListDetail(
                        padding = padding,
                        sections = SECTIONS,
                        section = section,
                        onSection = { sectionId = it.id },
                        detailScroll = detailScroll,
                        detail = { page(section) }
                    )
                } else {
                    // Scrolls at full width, rows pushed in by the readable inset, so the
                    // margins of a tablet scroll like the rest. See ReadableScroll.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(compactScroll)
                            .padding(horizontal = LocalReadableInset.current)
                    ) {
                        SettingsHeaderSpacer(padding)
                        Spacer(Modifier.height(24.dp))
                        SettingsFoldedSections(SECTIONS, open = openSection, onOpen = { openId = it?.id.orEmpty() }) { which ->
                            val list: List<SettingsTile> = tiles[which].orEmpty() + actions[which].orEmpty()
                            if (list.isNotEmpty()) SettingsTileGrid(list) { tileId = it.id }
                        }
                        Spacer(Modifier.height(24.dp))
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }

    (tiles.values.flatten() + actions.values.flatten()).firstOrNull { it.id == tileId }?.let { tile ->
        SettingsTileDialogs(tile, onDismiss = { tileId = "" })
    }

    when (dialog) {
        OpenDialog.THEME -> ChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.themeMode,
            onPick = viewModel::setTheme,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.TEXT_SIZE -> ChoiceDialog(
            title = "Text size",
            options = TEXT_SCALES.map { it to textScaleLabel(it) },
            selected = settings.textScale,
            onPick = viewModel::setTextScale,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.UPDATES -> ChoiceDialog(
            title = "Updates",
            options = UpdateMode.entries.map { it to updatesLabel(it) },
            selected = settings.updates,
            onPick = { mode ->
                viewModel.setUpdates(mode)
                // Installing needs Android's leave, asked when chosen.
                if (mode == UpdateMode.INSTALL && !Updates.canInstall(context)) Updates.allowInstalls(context)
            },
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.RELAY -> {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            ChoiceDialog(
                title = "Relays",
                // The ones the chat uses now are marked.
                options = run {
                    val used by androidx.compose.runtime.produceState(emptyList<String>()) { value = chat.relays() }
                    listOf("" to "Automatic: the fastest, with backups") + com.yaz.sms.core.chat.Relays.known(context).map { host ->
                        host to if (used.any { it.endsWith(host) || it == host }) "$host (in use)" else host
                    }
                },
                selected = settings.relay,
                onPick = { host ->
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
            onPick = viewModel::setUndoSeconds,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.SWIPE_LEFT -> ChoiceDialog(
            title = "Swipe left",
            options = com.yaz.sms.core.sms.SwipeAction.entries.map { it.name to it.label },
            selected = settings.swipeLeft,
            onPick = viewModel::setSwipeLeft,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.SWIPE_RIGHT -> ChoiceDialog(
            title = "Swipe right",
            options = com.yaz.sms.core.sms.SwipeAction.entries.map { it.name to it.label },
            selected = settings.swipeRight,
            onPick = viewModel::setSwipeRight,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.OWN_NUMBER -> OwnNumberDialog(
            current = settings.ownNumber.ifBlank { simNumber.orEmpty() },
            onSave = viewModel::setOwnNumber,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.REACTION_FORMAT -> ChoiceDialog(
            title = "Reactions sent to others",
            options = com.yaz.sms.data.settings.ReactionFormat.entries.map { it to reactionFormatLabel(it) },
            selected = settings.reactionFormat,
            onPick = viewModel::setReactionFormat,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.NONE -> Unit
    }
}

/**
 * The user's number written by hand, as AOSP Messaging's "Phone number" preference (an EditTextPreference
 * of its MMS settings, mms_phone_number_pref_key, https://android.googlesource.com/platform/packages/apps/Messaging/):
 * the field holds the SIM's number until changed; saved empty, only the SIMs' numbers count. Laid out as
 * GroupNameDialog (feature/thread/ThreadScreen.kt): a zone dialog, one field, Save and Cancel.
 */
@Composable
private fun OwnNumberDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var number by remember { mutableStateOf(current) }
    val valid = number.isBlank() || number.count { it.isDigit() } >= MIN_DIGITS
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My number") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = number,
                onValueChange = { number = it.filter { c -> c.isDigit() || c in "+ ()-." }.take(24) },
                singleLine = true,
                isError = !valid,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                placeholder = { Text("+33 6 12 34 56 78") }
            )
        },
        confirmButton = {
            BoldButton(filled = true, enabled = valid, onClick = {
                onSave(number.trim())
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Fewest digits a number written by hand has (a shorter one would match short codes). */
private const val MIN_DIGITS = 6

private fun reactionFormatLabel(format: com.yaz.sms.data.settings.ReactionFormat): String = when (format) {
    com.yaz.sms.data.settings.ReactionFormat.AUTOMATIC -> "Automatic"
    com.yaz.sms.data.settings.ReactionFormat.GOOGLE -> "Google Messages"
    com.yaz.sms.data.settings.ReactionFormat.IPHONE -> "iPhone (readable text)"
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

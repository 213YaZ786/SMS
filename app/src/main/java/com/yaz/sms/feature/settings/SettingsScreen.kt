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
import com.yaz.sms.ui.component.ZoneAlertDialog
import com.yaz.sms.ui.component.ZoneSurface
import com.yaz.sms.ui.component.rememberHaptics
import com.yaz.sms.ui.icon.AppIcons
import com.yaz.sms.ui.theme.TEXT_SCALES
import com.yaz.sms.ui.theme.textScaleLabel
import org.koin.androidx.compose.koinViewModel

private enum class OpenDialog { NONE, THEME, TEXT_SIZE, UPDATES, RELAY, UNDO, SWIPE_LEFT, SWIPE_RIGHT, OWN_NUMBER, REACTION_FORMAT }

/** The groups of settings: one page after another on a phone, a list and the chosen one's page on a wide window. */
private enum class SettingsSection(val title: String, val icon: ImageVector) {
    CHAT("Encrypted chat", AppIcons.Lock),
    MESSAGES("Messages", AppIcons.Message),
    APPEARANCE("Appearance", AppIcons.Palette),
    ABOUT("About", AppIcons.Info)
}

/** What a tile of Settings shows: an icon, a short name, and under it its state or value (Quick Settings' tile). */
private sealed interface SettingsTile {
    val id: String
    val name: String
    val title: String
    val icon: ImageVector
    val summary: String
}

/**
 * A setting that is only a switch, shown as a tile. [name] is the short name on the tile, [title] the full
 * one (TalkBack, the dialog), [summary] the explanation the long press shows. A tile that [enabled] is false
 * depends on another setting: it shows, dimmed, and does not toggle.
 */
private class SwitchTile(
    override val id: String,
    override val name: String,
    override val title: String,
    override val icon: ImageVector,
    override val summary: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val onChange: (Boolean) -> Unit
) : SettingsTile

/**
 * A setting that opens a choice, a page or a link, shown as a tile: [value] is its current value, the second
 * line (Quick Settings' secondary label, AOSP SystemUI QSTileViewImpl); [summary] the full text a long press
 * shows. Without [onClick] (the version) a tap shows the summary too.
 */
private class ActionTile(
    override val id: String,
    override val name: String,
    override val title: String,
    override val icon: ImageVector,
    val value: String,
    override val summary: String,
    val onClick: (() -> Unit)? = null
) : SettingsTile

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var dialog by rememberSaveable { mutableStateOf(OpenDialog.NONE) }
    // The tile whose long press is open (its id), "" when none.
    var tileId by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf(SettingsSection.CHAT) }
    // On a phone every section starts folded; the one open stays open across a turn of the screen.
    var openSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    // The page's scroll, held here so a drag on the header drives it (FloatingFrame's `scroll`): the folded
    // list on a phone, the chosen section's page on a wide window (it starts at the top again for each section).
    val compactScroll = rememberScrollState()
    val detailScroll = rememberSaveable(section, saver = androidx.compose.foundation.ScrollState.Saver) {
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

    val tiles: Map<SettingsSection, List<SwitchTile>> = mapOf(
        SettingsSection.CHAT to listOf(
            SwitchTile(
                id = "chat", name = "Encrypted chat", title = "Encrypted chat with SMS users", icon = AppIcons.Lock,
                summary = if (settings.richChat) chatStatus else "Messages go as SMS and MMS only.",
                checked = settings.richChat,
                onChange = { on ->
                    viewModel.setRichChat(on)
                    if (on) com.yaz.sms.core.chat.ChatLife.wake() else chat.stop()
                }
            ),
            SwitchTile(
                id = "card", name = "Name and photo", title = "Share your name and photo", icon = AppIcons.Person,
                summary = "Your card in Android's contacts (Me) goes with your encrypted messages.",
                checked = settings.shareCard, enabled = settings.richChat, onChange = viewModel::setShareCard
            ),
            // Delta Chat Android's pref_read_receipts and its explanation, in the engine's meaning
            // (Config::MdnsEnabled: receipts "sent and requested"): off, none are sent and none are seen.
            SwitchTile(
                id = "receipts", name = "Read receipts", title = "Send read receipts", icon = AppIcons.DoneAll,
                summary = "Off: you stop seeing others' receipts too.",
                checked = settings.readReceipts, enabled = settings.richChat,
                onChange = { on ->
                    viewModel.setReadReceipts(on)
                    chat.applyReadReceipts(on)
                }
            )
        ),
        SettingsSection.MESSAGES to listOfNotNull(
            SwitchTile(
                id = "spam", name = "Spam protection", title = "Spam protection", icon = AppIcons.Report,
                summary = "Spam goes to Spam, without a notification.",
                checked = settings.spamProtection, onChange = viewModel::setSpamProtection
            ),
            if (country in SalesCalls.countries) SwitchTile(
                id = "sales", name = "Quiet sales", title = "Quiet sales messages", icon = AppIcons.NotificationsOff,
                summary = "Messages from numbers reserved for sales come without a notification, unless saved in your contacts.",
                checked = settings.quietSales, onChange = viewModel::setQuietSales
            ) else null,
            SwitchTile(
                id = "links", name = "Check links", title = "Check links", icon = AppIcons.Link,
                summary = "Links in messages are also looked up in a public list of dangerous sites, downloaded once a day. No link leaves the phone.",
                checked = settings.checkLinks, onChange = viewModel::setCheckLinks
            ),
            if (transcriber.available) SwitchTile(
                id = "voice", name = "Transcribe voice", title = "Transcribe voice messages", icon = AppIcons.Mic,
                summary = "By the Voice app, on the phone.",
                checked = settings.transcribeVoice, onChange = viewModel::setTranscribeVoice
            ) else null,
            SwitchTile(
                id = "mms", name = "Download pictures", title = "Download picture messages", icon = AppIcons.Download,
                summary = "A picture message downloads itself when it arrives; off, it waits for a tap in the conversation.",
                checked = settings.autoMmsDownload, onChange = viewModel::setAutoMmsDownload
            ),
            SwitchTile(
                id = "roaming", name = "When roaming", title = "Also when roaming", icon = AppIcons.Language,
                summary = "Abroad too, where data can cost more.",
                checked = settings.autoMmsRoaming, enabled = settings.autoMmsDownload, onChange = viewModel::setAutoMmsRoaming
            ),
            SwitchTile(
                id = "strangers", name = "Unknown media", title = "Show media from unknown numbers", icon = AppIcons.Image,
                summary = "Off, a photo, a video or a sound from a number in no contact waits for a tap.",
                checked = settings.showStrangerMedia, onChange = viewModel::setShowStrangerMedia
            )
        ),
        SettingsSection.APPEARANCE to listOf(
            SwitchTile(
                id = "black", name = "Pure black", title = "Pure black", icon = AppIcons.Night,
                summary = "Black background in dark mode.",
                checked = settings.pureBlack, onChange = viewModel::setPureBlack
            ),
            SwitchTile(
                id = "recents", name = "Hide in recents", title = "Hide in recent apps", icon = AppIcons.VisibilityOff,
                summary = "The app's preview stays blank in recent apps.",
                checked = settings.hideInRecents, onChange = viewModel::setHideInRecents
            ),
            SwitchTile(
                id = "glass", name = "Glass effects", title = "Glass effects", icon = AppIcons.AutoAwesome,
                summary = "Buttons and panes in liquid glass.",
                checked = settings.glass, onChange = viewModel::setGlass
            )
        )
    )

    // What opens a choice, a page or a link: tiles of the same grid, the value as the second line.
    val actions: Map<SettingsSection, List<ActionTile>> = mapOf(
        SettingsSection.CHAT to listOfNotNull(
            if (settings.richChat) ActionTile(
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
        SettingsSection.MESSAGES to run {
            val left = com.yaz.sms.core.sms.SwipeAction.of(settings.swipeLeft, com.yaz.sms.core.sms.SwipeAction.ARCHIVE).label
            val right = com.yaz.sms.core.sms.SwipeAction.of(settings.swipeRight, com.yaz.sms.core.sms.SwipeAction.DELETE).label
            val number = settings.ownNumber.ifBlank { simNumber ?: "Not set" }
            val reaction = reactionFormatLabel(settings.reactionFormat)
            listOf(
                ActionTile(
                    id = "swipeLeft", name = "Swipe left", title = "Swipe left", icon = AppIcons.SwipeLeft,
                    value = left, summary = left, onClick = { dialog = OpenDialog.SWIPE_LEFT }
                ),
                ActionTile(
                    id = "swipeRight", name = "Swipe right", title = "Swipe right", icon = AppIcons.SwipeRight,
                    value = right, summary = right, onClick = { dialog = OpenDialog.SWIPE_RIGHT }
                ),
                ActionTile(
                    id = "undo", name = "Undo send", title = "Undo send", icon = AppIcons.Undo,
                    value = if (settings.undoSeconds == 0) "Off" else "${settings.undoSeconds} s",
                    summary = if (settings.undoSeconds == 0) "Off" else "${settings.undoSeconds} seconds to take a message back",
                    onClick = { dialog = OpenDialog.UNDO }
                ),
                // The number Android may not give: a group never splits for want of it (OwnNumbers).
                ActionTile(
                    id = "number", name = "My number", title = "My number", icon = AppIcons.Smartphone,
                    value = number, summary = number, onClick = { dialog = OpenDialog.OWN_NUMBER }
                ),
                ActionTile(
                    id = "reactions", name = "Reactions", title = "Reactions sent to others", icon = AppIcons.Mood,
                    value = reaction, summary = reaction, onClick = { dialog = OpenDialog.REACTION_FORMAT }
                ),
                ActionTile(
                    id = "notifications", name = "Notifications", title = "Notifications", icon = AppIcons.NotificationsOn,
                    value = "Sound, vibration", summary = "Sound, vibration and how they show",
                    onClick = { open(context, Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                ),
                ActionTile(
                    id = "blocked", name = "Blocked numbers", title = "Blocked numbers", icon = AppIcons.Block,
                    value = "Android's list", summary = "The list Android keeps for every app",
                    onClick = { open(context, context.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }
                ),
                ActionTile(
                    id = "default", name = "Messaging app", title = "Messaging app", icon = AppIcons.Message,
                    value = "Default apps", summary = "The app Android uses for messages",
                    onClick = { open(context, Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                )
            )
        },
        SettingsSection.APPEARANCE to listOf(
            ActionTile(
                id = "theme", name = "Theme", title = "Theme", icon = AppIcons.Brightness,
                value = themeLabel(settings.themeMode), summary = themeLabel(settings.themeMode),
                onClick = { dialog = OpenDialog.THEME }
            ),
            ActionTile(
                id = "textSize", name = "Text size", title = "Text size", icon = AppIcons.FormatSize,
                value = textScaleLabel(settings.textScale),
                summary = textScaleLabel(settings.textScale) + ", on top of Android's font size",
                onClick = { dialog = OpenDialog.TEXT_SIZE }
            )
        ),
        SettingsSection.ABOUT to listOf(
            ActionTile(
                id = "updates", name = "Updates", title = "Updates", icon = AppIcons.Update,
                value = updatesLabel(settings.updates), summary = updatesLabel(settings.updates),
                onClick = { dialog = OpenDialog.UPDATES }
            ),
            ActionTile(
                id = "version", name = "SMS", title = "SMS ${BuildConfig.VERSION_NAME}", icon = AppIcons.Info,
                value = BuildConfig.VERSION_NAME, summary = "A messaging app with no account, no tracking and no ads."
            ),
            ActionTile(
                id = "engine", name = "Chat engine", title = "Encrypted chat engine", icon = AppIcons.Memory,
                value = "chatmail core", summary = "chatmail core, Mozilla Public License 2.0",
                onClick = { uriHandler.openUri("https://github.com/chatmail/core") }
            ),
            ActionTile(
                id = "emoji", name = "Animated emoji", title = "Animated emoji", icon = AppIcons.Mood,
                value = "Noto, CC BY 4.0", summary = "Noto Animated Emoji by Google, CC BY 4.0",
                onClick = { uriHandler.openUri("https://googlefonts.github.io/noto-emoji-animation/") }
            ),
            ActionTile(
                id = "source", name = "Source code", title = "Source code", icon = AppIcons.Code,
                value = "github.com/213YaZ786/SMS", summary = "github.com/213YaZ786/SMS",
                onClick = { uriHandler.openUri("https://github.com/213YaZ786/SMS") }
            )
        )
    )

    // One section's page: its name, then its tiles: the switches first, then what opens something.
    val page: @Composable ColumnScope.(SettingsSection) -> Unit = { which ->
        SectionTitle(which.title)
        val list: List<SettingsTile> = tiles[which].orEmpty() + actions[which].orEmpty()
        if (list.isNotEmpty()) TileGrid(list) { tileId = it.id }
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
                    ListDetail(
                        padding = padding,
                        section = section,
                        onSection = { section = it },
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
                        HeaderSpacer(padding)
                        Spacer(Modifier.height(24.dp))
                        FoldedSections(open = openSection, onOpen = { openSection = it }) { which ->
                            val list: List<SettingsTile> = tiles[which].orEmpty() + actions[which].orEmpty()
                            if (list.isNotEmpty()) TileGrid(list) { tileId = it.id }
                        }
                        Spacer(Modifier.height(24.dp))
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }

    (tiles.values.flatten() + actions.values.flatten()).firstOrNull { it.id == tileId }?.let { tile ->
        when (tile) {
            is SwitchTile -> TileDialog(tile, onDismiss = { tileId = "" })
            is ActionTile -> InfoDialog(tile, onDismiss = { tileId = "" })
        }
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
 * The room the floating header takes. It shrinks as the large title collapses, so it is read when the
 * spacer is laid out: reading it in composition would redo the whole page on every frame of the scroll.
 */
@Composable
private fun HeaderSpacer(padding: PaddingValues) {
    Spacer(Modifier.layout { _, constraints -> layout(constraints.minWidth, padding.calculateTopPadding().roundToPx()) {} })
}

/**
 * List-detail on a wide window: the sections as a list on the left, the chosen one's page on the right,
 * each pane scrolling on its own. The pattern of Android's large-screen canonical layout "list-detail" as the
 * Reply sample draws it (ReplyInboxScreen: a TwoPane of the list and the opened item when the content type is
 * DUAL_PANE, one page otherwise),
 * https://github.com/android/compose-samples/blob/main/Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt
 * and androidx's ListDetailPaneScaffold
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/material3/adaptive/adaptive-layout/src/commonMain/kotlin/androidx/compose/material3/adaptive/layout/ListDetailPaneScaffold.kt)
 * for the roles, a library this build does not carry. The list takes a third (Reply splits in halves; a list of
 * four names needs less).
 */
@Composable
private fun ListDetail(
    padding: PaddingValues,
    section: SettingsSection,
    onSection: (SettingsSection) -> Unit,
    detailScroll: androidx.compose.foundation.ScrollState,
    detail: @Composable ColumnScope.() -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 8.dp)
        ) {
            HeaderSpacer(padding)
            Spacer(Modifier.height(24.dp))
            SettingsSection.entries.forEach { SectionListRow(it, selected = it == section) { onSection(it) } }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        // The page starts at the top again for each section.
        key(section) {
            Column(
                Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .verticalScroll(detailScroll)
            ) {
                HeaderSpacer(padding)
                detail()
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/**
 * A section in the list of a wide window: its icon and name, the chosen one washed with the accent.
 * On a phone the same row heads a folded section: [expanded] says whether its tiles are open under it,
 * and TalkBack reads it as a button that is expanded or collapsed (a state description, as Compose's
 * accessibility guide gives a toggling control:
 * https://developer.android.com/develop/ui/compose/accessibility/semantics).
 */
@Composable
private fun SectionListRow(section: SettingsSection, selected: Boolean, expanded: Boolean? = null, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    ZoneSurface(
        shape = RoundedCornerShape(20.dp),
        accent = selected,
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                if (expanded == null) this.selected = selected
                else {
                    role = Role.Button
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
            }
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(section.icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/**
 * A phone's Settings: the sections as the wide window's list shows them, all folded; a tap unfolds that
 * section's tiles right under its row (the others fold, so one is open at a time; a second tap folds it).
 * The tiles slide open with AnimatedVisibility's expandVertically + fadeIn, Compose's recipe for content that
 * appears under a header
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/animation/animation/src/commonMain/kotlin/androidx/compose/animation/AnimatedVisibility.kt,
 * https://developer.android.com/develop/ui/compose/animation/composables-modifiers#animatedvisibility).
 */
@Composable
private fun FoldedSections(
    open: SettingsSection?,
    onOpen: (SettingsSection?) -> Unit,
    tilesOf: @Composable (SettingsSection) -> Unit
) {
    Column {
        SettingsSection.entries.forEach { which ->
            val expanded = which == open
            Box(Modifier.padding(horizontal = 16.dp)) {
                SectionListRow(which, selected = expanded, expanded = expanded) { onOpen(if (expanded) null else which) }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                // Room around the grid so the tiles' soft shadows are not cut while it slides.
                Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) { tilesOf(which) }
            }
        }
    }
}

/** A section's name, centred, on the page: it does nothing, so it has no zone. */
@Composable
private fun SectionTitle(title: String) {
    // Centred and at title size: a heading names what lies below.
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 10.dp)
    )
}

/**
 * The tiles of a section, in equal cells, 2 columns on a phone upright, 3 then 4 as the
 * room grows (a cell is at least 160 dp). The cells of Android's Quick Settings: AOSP SystemUI's TileLayout
 * (equal cells, the column count from the window's size class),
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/qs/TileLayout.java
 * A last row that is not full is centred, so no side is left empty.
 */
@Composable
private fun TileGrid(tiles: List<SettingsTile>, onExplain: (SettingsTile) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val columns = (maxWidth / 160.dp).toInt().coerceIn(2, 4)
        // The cell is a column's share of the width, whatever the row holds.
        val cell = (maxWidth - 12.dp * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(columns).forEach { row ->
                // The tiles of a row are as tall as the tallest (a value on two lines).
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    row.forEach { tile ->
                        val tileModifier = Modifier.width(cell).fillMaxHeight()
                        when (tile) {
                            is SwitchTile -> SwitchTileView(tile, tileModifier) { onExplain(tile) }
                            is ActionTile -> ActionTileView(tile, tileModifier) { onExplain(tile) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One switch as a tile of glass, as a Quick Settings tile: the icon over the name, the state under it,
 * a tap toggles, a long press opens the explanation. Android's QSTileViewImpl (icon, label, secondary
 * label; the long press for details; TalkBack reads a Switch, checked or not, with its state description),
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSTileViewImpl.kt
 * Active tiles are washed with the accent (ZoneSurface accent). The tap target is the transparent
 * inner area, as Modules' glass note asks; TalkBack gets a switch with its full name and its state.
 */
@Composable
private fun SwitchTileView(tile: SwitchTile, modifier: Modifier = Modifier, onExplain: () -> Unit) {
    val haptics = rememberHaptics()
    val on = tile.checked
    val state = if (on) "On" else "Off"
    TileFace(
        icon = tile.icon,
        name = tile.name,
        state = state,
        on = on,
        modifier = modifier,
        dimmed = !tile.enabled,
        onClick = {
            if (tile.enabled) {
                haptics.toggle(!on)
                tile.onChange(!on)
            }
        },
        onLongClick = {
            haptics.firm()
            onExplain()
        },
        semantics = {
            contentDescription = tile.title
            role = Role.Switch
            toggleableState = ToggleableState(on)
            stateDescription = if (tile.enabled) state else "$state, unavailable"
        }
    )
}

/**
 * One setting that opens something, as a tile of the same look: the icon, the name, its current value as the
 * second line (QSTileViewImpl's secondary label), never "On" and never in the accent: only a switch that is on
 * is. A tap does what the row's tap did, a long press shows the full text. TalkBack gets a button with the full
 * name and the value as its state.
 */
@Composable
private fun ActionTileView(tile: ActionTile, modifier: Modifier = Modifier, onExplain: () -> Unit) {
    val haptics = rememberHaptics()
    TileFace(
        icon = tile.icon,
        name = tile.name,
        state = tile.value,
        on = false,
        modifier = modifier,
        dimmed = false,
        onClick = {
            haptics.tick()
            val open = tile.onClick
            if (open != null) open() else onExplain()
        },
        onLongClick = {
            haptics.firm()
            onExplain()
        },
        semantics = {
            contentDescription = tile.title
            role = Role.Button
            stateDescription = tile.value
        }
    )
}

/**
 * The look of a tile, a Quick Settings tile: the icon over the name, the state or value under it on one or
 * two lines. Android's QSTileViewImpl (icon, label, secondary label; the long press for details),
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSTileViewImpl.kt
 * Active tiles ([on]) are washed with the accent (ZoneSurface accent). The tap target is the transparent
 * inner area, as Modules' glass note asks.
 */
@Composable
private fun TileFace(
    icon: ImageVector,
    name: String,
    state: String,
    on: Boolean,
    modifier: Modifier,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    semantics: androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit
) {
    // SystemUI's own sizes (res/values/dimens.xml: qs_tile_height 80dp, qs_corner_radius 28dp): a tile is
    // as tall as its content needs, not square, so a section fits on one screen; our icon stands over the
    // name instead of beside it, hence 96 dp at least.
    val shape = RoundedCornerShape(28.dp)
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    ZoneSurface(shape = shape, accent = on, modifier = modifier.heightIn(min = 96.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .combinedClickable(
                    onLongClickLabel = "Explain",
                    onLongClick = onLongClick,
                    onClick = onClick
                )
                .semantics(mergeDescendants = true, properties = semantics)
                .graphicsLayer { alpha = if (dimmed) 0.38f else 1f }
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (on) androidx.compose.material3.LocalContentColor.current else quiet
            )
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                state,
                style = MaterialTheme.typography.labelSmall,
                color = if (on) androidx.compose.material3.LocalContentColor.current else quiet,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {}
            )
        }
    }
}

/** An action tile's long press (or the version's tap): the setting's full name and its full text. */
@Composable
private fun InfoDialog(tile: ActionTile, onDismiss: () -> Unit) {
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tile.title) },
        text = { Text(tile.summary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { BoldButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** A tile's long press: the setting's full name and explanation, and its switch. */
@Composable
private fun TileDialog(tile: SwitchTile, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    val change = { on: Boolean ->
        haptics.toggle(on)
        tile.onChange(on)
    }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tile.title) },
        text = {
            Column {
                Text(tile.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = tile.enabled) { change(!tile.checked) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (tile.checked) "On" else "Off")
                    GlassSwitch(checked = tile.checked, onCheckedChange = change, enabled = tile.enabled)
                }
            }
        },
        confirmButton = { BoldButton(onClick = onDismiss) { Text("Done") } }
    )
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

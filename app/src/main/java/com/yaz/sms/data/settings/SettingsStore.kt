package com.yaz.sms.data.settings

import android.content.Context
import com.yaz.sms.core.common.writeTextAtomically
import com.yaz.sms.core.update.UpdateMode
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Settings(
    /**
     * What happens when a newer version is out, checked once when the app
     * opens. Installing by default: the first launch page says so, and that
     * this one request is the app's only use of the internet.
     */
    val updates: UpdateMode = UpdateMode.INSTALL,
    /** A picture message announced by the network downloads itself: off by default since 2026-11-25, each waits for a tap. */
    val autoMmsDownload: Boolean = false,
    /** It downloads itself abroad too (roaming data can cost). */
    val autoMmsRoaming: Boolean = false,
    /**
     * Photos, films and sounds from a number in no contact shown at once,
     * as from a contact (still decoded in the isolated process); off, each
     * waits for a tap. Off by default since 2026-11-25, also for a file saved before without
     * the key (security pass: a sender nobody knows reaches no decoder before a tap). Why off:
     * Signal's AttachmentUtil.isAutoDownloadPermitted, nothing automatic from an untrusted sender
     * ("Not allowing download due to untrusted conversation"):
     * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/util/AttachmentUtil.kt#isAutoDownloadPermitted
     */
    val showStrangerMedia: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** True black instead of dark grey in dark mode. */
    val pureBlack: Boolean = false,
    /** Zones and floating controls in liquid glass, over a soft light in the wallpaper's colours. */
    val glass: Boolean = true,
    /** Multiplier on every text style, one of the steps in ui.theme.TEXT_SCALES. */
    val textScale: Float = 1f,
    /** The first launch page was closed. */
    val welcomeSeen: Boolean = false,
    /** The app's picture in the recent apps screen stays blank: no messages to be seen there. */
    val hideInRecents: Boolean = true,
    /** No screenshot or screen recording shows the app (FLAG_SECURE, as Signal's Screen security): on by default since 2026-11-25. */
    val blockScreenshots: Boolean = true,
    /** Where the user left the new message button, fractions of its room; below 0, its usual place. */
    val composeX: Float = -1f,
    val composeY: Float = -1f,
    /** The little show of the new message button moving was seen. */
    val composeHintSeen: Boolean = false,
    /** What a conversation swiped to the left, and to the right, does (SwipeAction names). */
    val swipeLeft: String = "ARCHIVE",
    val swipeRight: String = "DELETE",
    /** A vibration of their own for some people, by the last nine digits of their number. */
    val signatures: Map<String, String> = emptyMap(),
    /** Seconds a message waits before it goes, to take it back with a tap; 0 sends at once. */
    val undoSeconds: Int = 4,
    /** Seconds new encrypted chats' messages last after they are read (Signal's default timer for new chats); 0 keeps them. */
    val vanishDefault: Int = 2_419_200,
    /** Encrypted chat with other SMS users, over a chatmail relay. */
    val richChat: Boolean = true,
    /**
     * The chatmail relay chosen by hand; empty for automatic: the one that
     * answers fastest, and others added behind it in case it fails.
     */
    val relay: String = "",
    /** Messages from the ranges kept for sales are kept apart, without a notification. */
    val quietSales: Boolean = false,
    /**
     * Spam goes to Spam, away from the list and silent: a sender the user reported, or a message
     * the classifier trained on the user's labels judges spam (SpamJudge); Google Messages'
     * Spam protection.
     */
    val spamProtection: Boolean = true,
    /** Conversations pinned on top, by thread id. */
    val pinned: List<Long> = emptyList(),
    /** Conversations put away, thread id to when: out of the list until a newer message comes. */
    val archived: Map<Long, Long> = emptyMap(),
    /** Names given to group conversations, by thread id. */
    val groupNames: Map<Long, String> = emptyMap(),
    /** Links are also looked up in a public list of dangerous sites, fetched once a day. */
    val checkLinks: Boolean = true,
    /** Conversations without notifications, by thread id, until when (Long.MAX_VALUE: until turned back on). */
    val silenced: Map<Long, Long> = emptyMap(),
    /** To answer cards already shown once (Lists.key), so each shows once. */
    val answerSeen: Set<String> = emptySet(),
    /** A conversation's background, by thread id: a scene's code or a photo's colours. */
    val backgrounds: Map<Long, String> = emptyMap(),
    /** Voice messages written out under them, on the phone only. */
    val transcribeVoice: Boolean = true,
    /** The speech model chosen; empty: the one recommended for this phone. */
    val speechModel: String = "",
    /** The user's name, photo and look go with the encrypted chat's messages (Android's "Me" contact): off by default since 2026-11-25. */
    val shareCard: Boolean = false,
    /**
     * Read receipts of the encrypted chat: sent, and so seen from the others (the engine's
     * mdns_enabled; Delta Chat Android's pref_read_receipts, see ReadReceipts): off by default since 2026-11-25.
     */
    val readReceipts: Boolean = false,
    /** Live locations being shared: the engine's chat id to when the sharing ends (ms). The engine keeps the period itself. */
    val liveUntil: Map<Int, Long> = emptyMap(),
    /**
     * The user's own number when the SIM gives none or a wrong one, written by hand: kept out of a
     * group's people so that a group never splits (OwnNumbers). AOSP Messaging's "Phone number"
     * setting (PhoneUtils.getSelfRawNumber(allowOverride)), Google Messages' Settings, Advanced.
     * Empty: only the SIMs' numbers.
     */
    val ownNumber: String = "",
    /**
     * How a reaction to a message goes out by SMS or MMS: as Google Messages writes it, as the iPhone's words,
     * or in the format last received in that conversation (Quik's "Reaction send format", PR #846).
     */
    val reactionFormat: ReactionFormat = ReactionFormat.AUTOMATIC,
    /**
     * The encrypted call's media runs in the system WebView (Vanadium on GrapheneOS, kept current by the OS) on
     * Delta Chat's call page, instead of the native WebRTC library in this process (core/call/WebCallMedia).
     * On by default, the safer engine; off falls back to the native library in this process.
     */
    val callsInWebView: Boolean = true,
    /**
     * The last step of the secure defaults run on this file (Signal's ApplicationMigrations lastSeenVersion):
     * 0 for a file written before the steps existed; each step runs once, in order.
     */
    val defaultsVersion: Int = 0
)

@Serializable
enum class ReactionFormat { AUTOMATIC, GOOGLE, IPHONE }

/**
 * Small preference file, plain JSON written atomically, like the other apps.
 * Nothing here is a secret, and none of it leaves the device.
 */
class SettingsStore(context: Context) {

    companion object {
        /**
         * Signal's ApplicationMigrations.CURRENT_VERSION: the last step of the secure defaults.
         * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/migrations/ApplicationMigrations.java#ApplicationMigrations
         * Not ported: MigrationJob classes and the Version constants, JobManager and its queue, the
         * ApplicationMigrationService and its progress screen, isUpdate, onApplicationInitialized,
         * getMigrationJobForVersion, resolveMigrationJobFor, getMigrationsToRun, runMigrations (jobs,
         * a lot of them: ours is one step on a small file read at once, no service or job queue).
         */
        const val CURRENT_VERSION = 1

        /**
         * One number per step, as Signal's Version.
         * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/migrations/ApplicationMigrations.java#L46-L47
         */
        object Version { const val SECURE_DEFAULTS = 1 }

        /** Step 1 of getMigrationJobs (L347-L356 of the same file): the most secure value of each, once. */
        fun getMigrationJobs(settings: Settings, lastSeenVersion: Int): Settings {
            var migrationJobs = settings
            if (lastSeenVersion < Version.SECURE_DEFAULTS) {
                migrationJobs = migrationJobs.copy(
                    blockScreenshots = true,
                    autoMmsDownload = false,
                    shareCard = false,
                    readReceipts = false,
                    showStrangerMedia = false,
                    vanishDefault = 2_419_200
                )
            }
            return migrationJobs
        }
    }

    private val file = File(context.filesDir, "settings.json")
    // Defaults are written, so a choice that equals today's default stays written if the default moves.
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    private fun load(): Settings {
        if (!file.exists()) return Settings(defaultsVersion = CURRENT_VERSION)
        val read = runCatching { json.decodeFromString<Settings>(file.readText()) }
            .getOrDefault(Settings(defaultsVersion = CURRENT_VERSION))
        val migrated = onApplicationCreate(read)
        if (migrated != read) runCatching { file.writeTextAtomically(json.encodeToString(migrated)) }
        return migrated
    }

    /**
     * Each step runs once, in order, from the lastSeenVersion; a file at CURRENT_VERSION runs none.
     * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/migrations/ApplicationMigrations.java#L233-L320
     * Not ported: onApplicationCreate's JobManager, UI blocking and MigrationCompleteEvent parts, getUiBlockingMigrationStatus,
     * isUiBlockingMigrationRunning, isUpdate, isLegacyUpdate (jobs and a service: ours is one step on a small file).
     */
    private fun onApplicationCreate(settings: Settings): Settings {
        val migrationJobs = getMigrationJobs(settings, settings.defaultsVersion)
        return migrationJobs.copy(defaultsVersion = CURRENT_VERSION)
    }

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(updated)) }
    }
}

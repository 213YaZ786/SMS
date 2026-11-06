package com.yaz.sms.feature.main

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.yaz.sms.ui.component.AUTOMATED_RESULT_THRESHOLD_MILLIS
import com.yaz.sms.ui.component.BoldButton

/** What SMS needs before it can take messages, in the order it is asked. */
internal enum class SetupStep(val title: String, val message: String, val action: String, val refuse: String) {
    ROLE(
        "Make SMS your messaging app",
        "To send and receive your messages with it.",
        "Choose SMS",
        "Not now"
    ),
    NOTIFICATIONS(
        "Allow notifications",
        "To see new messages and answer them.",
        "Allow",
        "Don't allow"
    )
}

/**
 * The step still missing, or null when SMS can take messages, read again
 * each time the app comes back, since each can be changed in Android at
 * any time; [run] to start a step, [askRole] to put Android's question once
 * on arriving (it never opens a page by itself).
 *
 * As AOSP Messaging's PermissionCheckActivity
 * https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/ui/PermissionCheckActivity.java
 * an answer back in under 250 ms is Android refusing without asking: the button then leads to Android's own page
 * (the default apps for the role, the app's notification settings), where the user decides.
 */
internal class Setup(
    val step: SetupStep?,
    val run: (SetupStep) -> Unit,
    val askRole: () -> Unit,
    /** Android no longer shows the question for the role. */
    val roleBlocked: Boolean,
    /** Notifications were asked and refused. */
    val notificationsRefused: Boolean,
    /** The step the card on the main screen shows: [step] without the ones the user refused. */
    val cardStep: SetupStep? = step,
    /** "Not now" or "Don't allow" on a step: remembered for good, the card moves on at once. */
    val refuse: (SetupStep) -> Unit = {},
    /** SMS holds the SMS role, read from the system at each return (the guide's first tile). */
    val roleHeld: Boolean = false,
    /** Notifications are allowed in Android, read from the system at each return (the guide's second tile). */
    val notificationsAllowed: Boolean = false,
) {
    /** The label of [step]'s button: Android's page once it no longer asks. */
    fun action(step: SetupStep): String = when {
        step == SetupStep.ROLE && roleBlocked -> "Open settings"
        step == SetupStep.NOTIFICATIONS && notificationsRefused -> "Open settings"
        else -> step.action
    }

    /** What stays true while the step is refused, or null. */
    fun warning(step: SetupStep): String? = when {
        step == SetupStep.ROLE && roleBlocked -> "Android no longer asks. Choose SMS in its settings."
        step == SetupStep.NOTIFICATIONS && notificationsRefused -> "New messages will arrive without a notification."
        else -> null
    }
}

@Composable
internal fun rememberSetup(): Setup {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    var roleBlocked by rememberSaveable { mutableStateOf(false) }
    var roleAskedAt by remember { mutableLongStateOf(0L) }
    var roleByHand by remember { mutableStateOf(false) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val held = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        // Back at once without the role: Android did not show its dialog.
        if (!held && SystemClock.elapsedRealtime() - roleAskedAt < AUTOMATED_RESULT_THRESHOLD_MILLIS) {
            roleBlocked = true
            if (roleByHand) openDefaultApps(context)
        }
        checks++
    }
    fun askRole(byHand: Boolean) {
        roleByHand = byHand
        roleAskedAt = SystemClock.elapsedRealtime()
        role.launch(context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS))
    }
    var refused by remember(checks) { mutableStateOf(context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).getBoolean(NOTIFICATIONS_REFUSED, false)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).edit().putBoolean(NOTIFICATIONS_REFUSED, true).apply()
            refused = true
        }
        checks++
    }
    // Granted since, here or in Android's page: the refusal is forgotten, as Element X's opt-in ends once the permission
    // is granted (notificationsPermissionsState.permissionGranted).
    // https://raw.githubusercontent.com/element-hq/element-x-android/develop/features/ftue/impl/src/main/kotlin/io/element/android/features/ftue/impl/notifications/NotificationsOptInPresenter.kt#L73-L78
    LaunchedEffect(checks) {
        val permissionGranted = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (permissionGranted && refused) {
            context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).edit().putBoolean(NOTIFICATIONS_REFUSED, false).apply()
            refused = false
        }
    }
    // Read here so that every card on screen follows a refusal made on another at once.
    val refusals = refusalCount.intValue
    val step = remember(checks) { nextStep(context, skipRefused = false) }
    val cardStep = remember(checks, refusals) { nextStep(context, skipRefused = true) }
    // Each tile of the guide reads the system again at every resume, as FlorisBoard's SetupScreen reads
    // isFlorisBoardEnabled and hasNotificationPermission.
    val roleHeld = remember(checks) {
        context.getSystemService(RoleManager::class.java).let { !it.isRoleAvailable(RoleManager.ROLE_SMS) || it.isRoleHeld(RoleManager.ROLE_SMS) }
    }
    val notificationsAllowed = remember(checks) { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    return Setup(step, cardStep = cardStep, roleHeld = roleHeld, notificationsAllowed = notificationsAllowed, refuse = { refusedStep ->
        refuse(context, refusedStep)
        checks++
    }, run = { wanted ->
        when (wanted) {
            SetupStep.ROLE -> if (roleBlocked) openDefaultApps(context) else askRole(true)
            SetupStep.NOTIFICATIONS ->
                // Refused once: Android will not be asked again here, its own page is where the user decides.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !refused) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings(context)
                }
        }
    }, askRole = { askRole(false) }, roleBlocked = roleBlocked, notificationsRefused = refused)
}

/** Steps refused so far: read by every card on screen, so that all follow a refusal at once. */
private val refusalCount = mutableIntStateOf(0)

/**
 * The step a refusing button sets aside for good, one key per step. Element X's "Not now" on its notification
 * opt-in, NotNowClicked, stores the refusal so the opt-in never shows again (element-hq/element-x-android, AGPL-3.0,
 * https://github.com/element-hq/element-x-android/blob/develop/features/ftue/impl/src/main/kotlin/io/element/android/features/ftue/impl/notifications/NotificationsOptInPresenter.kt);
 * stored as SharedPreferences.Editor.putBoolean.
 */
private fun refuse(context: Context, step: SetupStep) {
    context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).edit().putBoolean(REFUSED + step.name, true).apply()
    refusalCount.intValue++
}

private fun refused(context: Context, step: SetupStep): Boolean =
    context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).getBoolean(REFUSED + step.name, false)

/**
 * The step still missing, shown on the main screen until SMS can take
 * messages: when the first launch page was skipped, or something was turned
 * off in Android since. Laid straight on the page (a zone means a tap), with a button to refuse the step: Element X's
 * opt-in view lays its text and its "Not now" and confirm buttons the same way (NotificationsOptInView.kt, same repo).
 */
@Composable
fun SetupZone(modifier: Modifier = Modifier) {
    val setup = rememberSetup()
    val step = setup.cardStep ?: return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(horizontal = 24.dp, vertical = 22.dp)
    ) {
        // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
        Text(step.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(
            step.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        setup.warning(step)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoldButton(filled = false, onClick = { setup.refuse(step) }) { Text(step.refuse) }
            BoldButton(filled = true, onClick = { setup.run(step) }) { Text(setup.action(step)) }
        }
    }
}

private fun nextStep(context: Context, skipRefused: Boolean): SetupStep? {
    fun open(step: SetupStep) = !(skipRefused && refused(context, step))
    val roles = context.getSystemService(RoleManager::class.java)
    if (roles.isRoleAvailable(RoleManager.ROLE_SMS) && !roles.isRoleHeld(RoleManager.ROLE_SMS) && open(SetupStep.ROLE)) return SetupStep.ROLE
    val notifications = context.getSystemService(NotificationManager::class.java)
    if (!notifications.areNotificationsEnabled() && open(SetupStep.NOTIFICATIONS)) return SetupStep.NOTIFICATIONS
    return null
}

private const val SETUP = "setup"
private const val NOTIFICATIONS_REFUSED = "notifications_refused"
private const val REFUSED = "refused_"

/**
 * Android's default apps page, where the messaging app is chosen when the role is no longer asked: Settings'
 * ACTION_MANAGE_DEFAULT_APPS_SETTINGS
 * https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_DEFAULT_APPS_SETTINGS
 * started as openNotificationSettings below starts its page.
 */
private fun openDefaultApps(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
}

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }
}

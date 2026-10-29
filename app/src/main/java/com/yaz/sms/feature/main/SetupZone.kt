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
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.yaz.sms.ui.component.ZoneSurface

/** What SMS needs before it can take messages, in the order it is asked. */
internal enum class SetupStep(val title: String, val message: String, val action: String) {
    ROLE(
        "Make SMS your messaging app",
        "To send and receive your messages with it.",
        "Choose SMS"
    ),
    NOTIFICATIONS(
        "Allow notifications",
        "To see new messages and answer them.",
        "Allow"
    ),
    BUBBLES(
        "New messages in bubbles",
        "A conversation floats over any app as a bubble, to answer without leaving what you do. Android asks you to allow it once.",
        "Allow bubbles"
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
    val step = remember(checks) { nextStep(context) }
    return Setup(step, run = { wanted ->
        when (wanted) {
            SetupStep.ROLE -> if (roleBlocked) openDefaultApps(context) else askRole(true)
            SetupStep.NOTIFICATIONS ->
                // Refused once: Android will not be asked again here, its own page is where the user decides.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !refused) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings(context)
                }
            SetupStep.BUBBLES -> {
                // Asked once: whatever is chosen on Android's page stands.
                context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).edit().putBoolean(BUBBLES_ASKED, true).apply()
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        }
    }, askRole = { askRole(false) }, roleBlocked = roleBlocked, notificationsRefused = refused)
}

/** The step a "Not now" sets aside for good (only an optional one). */
internal fun skip(context: Context, step: SetupStep) {
    if (step == SetupStep.BUBBLES) context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).edit().putBoolean(BUBBLES_ASKED, true).apply()
}

/**
 * The step still missing, shown on the main screen until SMS can take
 * messages: when the first launch page was skipped, or something was turned
 * off in Android since.
 */
@Composable
fun SetupZone(modifier: Modifier = Modifier) {
    val setup = rememberSetup()
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }
    val step = setup.step ?: return
    if (dismissed && step == SetupStep.BUBBLES) return

    ZoneSurface(
        // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
        shape = ShapeDefaults.ExtraLarge,
        modifier = modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Text(step.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
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
                if (step == SetupStep.BUBBLES) {
                    BoldButton(filled = false, onClick = { skip(context, step); dismissed = true }) { Text("Not now") }
                }
                BoldButton(filled = true, onClick = { setup.run(step) }) { Text(setup.action(step)) }
            }
        }
    }
}

private fun nextStep(context: Context): SetupStep? {
    val roles = context.getSystemService(RoleManager::class.java)
    if (roles.isRoleAvailable(RoleManager.ROLE_SMS) && !roles.isRoleHeld(RoleManager.ROLE_SMS)) return SetupStep.ROLE
    val notifications = context.getSystemService(NotificationManager::class.java)
    if (!notifications.areNotificationsEnabled()) return SetupStep.NOTIFICATIONS
    // Android lets an app's messages bubble only once the user allows it.
    if (notifications.bubblePreference != NotificationManager.BUBBLE_PREFERENCE_ALL &&
        !context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).getBoolean(BUBBLES_ASKED, false)
    ) return SetupStep.BUBBLES
    return null
}

private const val SETUP = "setup"
private const val BUBBLES_ASKED = "bubbles_asked"
private const val NOTIFICATIONS_REFUSED = "notifications_refused"

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
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )
}

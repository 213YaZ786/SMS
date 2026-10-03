package com.yaz.sms.feature.main

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
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
 * any time; and [run] to start a step.
 */
internal class Setup(val step: SetupStep?, val run: (SetupStep) -> Unit)

@Composable
internal fun rememberSetup(): Setup {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { checks++ }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused now or before: Android will not ask again, its own page is
        // the only way left, and the user decides there.
        if (!granted) openNotificationSettings(context)
        checks++
    }
    val step = remember(checks) { nextStep(context) }
    return Setup(step) { wanted ->
        when (wanted) {
            SetupStep.ROLE -> role.launch(
                context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
            )
            SetupStep.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
    }
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
        shape = RoundedCornerShape(24.dp),
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
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == SetupStep.BUBBLES) {
                    BoldButton(filled = false, onClick = { skip(context, step); dismissed = true }) { Text("Not now") }
                }
                BoldButton(filled = true, onClick = { setup.run(step) }) { Text(step.action) }
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

private fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )
}

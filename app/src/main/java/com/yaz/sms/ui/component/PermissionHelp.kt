package com.yaz.sms.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Asking a permission that Android may stop asking: [rememberPermissionAsk] returns the function that asks, and when
 * the answer is a refusal for good, a small dialog says so and offers Open settings (the app's details page) instead
 * of doing nothing. As AOSP Messaging's PermissionCheckActivity
 * https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/src/com/android/messaging/ui/PermissionCheckActivity.java
 * (a result back in under 250 ms, AUTOMATED_RESULT_THRESHOLD_MILLLIS, is Android refusing without asking; its
 * Settings button opens ACTION_APPLICATION_DETAILS_SETTINGS on "package:" + the package name), and as Android's
 * permission docs: shouldShowRequestPermissionRationale is false after a refusal that will not be asked again
 * https://developer.android.com/training/permissions/requesting#explain
 *
 * [title] and [message] say what is off and what it is for; [onGranted] runs when any of [permissions] is granted
 * (location: coarse or fine), [onRefused] on any refusal.
 */
@Composable
fun rememberPermissionAsk(
    permissions: Array<String>,
    title: String,
    message: String,
    onRefused: () -> Unit = {},
    onGranted: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val refused by rememberUpdatedState(onRefused)
    var askedAt by remember { mutableLongStateOf(0L) }
    var blocked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            granted()
        } else {
            refused()
            val automatic = SystemClock.elapsedRealtime() - askedAt < AUTOMATED_RESULT_THRESHOLD_MILLIS
            val activity = context.owningActivity()
            val again = activity != null && permissions.any { activity.shouldShowRequestPermissionRationale(it) }
            if (automatic || !again) blocked = true
        }
    }
    if (blocked) {
        val haptics = rememberHaptics()
        ZoneAlertDialog(
            onDismissRequest = { blocked = false },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                BoldButton(filled = true, onClick = {
                    haptics.tick()
                    blocked = false
                    openAppSettings(context)
                }) { Text("Open settings") }
            },
            dismissButton = { QuietButton(onClick = { blocked = false }) { Text("Not now") } }
        )
    }
    return {
        askedAt = SystemClock.elapsedRealtime()
        launcher.launch(permissions)
    }
}

/** The app's details page in Android's settings, where its permissions are. */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** AOSP Messaging's AUTOMATED_RESULT_THRESHOLD_MILLLIS: a faster answer is Android's, not the user's. */
const val AUTOMATED_RESULT_THRESHOLD_MILLIS = 250L

private tailrec fun Context.owningActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.owningActivity()
    else -> null
}

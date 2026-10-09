package com.yaz.sms.core.chat

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.app.role.RoleManager
import android.os.Bundle
import com.yaz.sms.core.call.CallBook
import com.yaz.sms.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The chat runs only while SMS is on screen, while a call goes on, and for
 * the moments a message needs to leave; then its engine stops, so nothing
 * of the chat runs with the app closed. What came in meanwhile arrives when
 * SMS opens.
 */
object ChatLife : Application.ActivityLifecycleCallbacks, KoinComponent {

    private val chat: RichChat by inject()
    private val settings: SettingsStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Application

    @Volatile private var shown = 0
    @Volatile private var holdUntil = 0L
    private var settling: Job? = null

    /** Messages going out get this long to leave before the engine stops anyway. */
    private const val SEND_CAP = 2 * 60 * 1000L
    private const val GRACE = 5_000L

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(this)
        // The always-on connection's notification channel of earlier versions.
        runCatching { application.getSystemService(NotificationManager::class.java).deleteNotificationChannel("chat_quiet") }
    }

    override fun onActivityStarted(activity: Activity) {
        if (shown++ == 0) wake()
    }

    override fun onActivityStopped(activity: Activity) {
        if (--shown == 0) settle()
    }

    /** On screen: the chat connects and fetches what came in while the app was closed. */
    fun wake() {
        synchronized(this) { settling?.cancel() }
        if (!settings.current.richChat) return
        if (app.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) != true) return
        scope.launch { if (chat.start()) chat.tend() }
    }

    /** Keeps the chat up [ms] longer off screen, for an invite's key exchange. */
    fun hold(ms: Long) {
        holdUntil = maxOf(holdUntil, System.currentTimeMillis() + ms)
        settle()
    }

    /** Off screen: once what was sent has left and no call goes on, the engine stops. */
    fun settle() = synchronized(this) {
        if (shown > 0) return
        settling?.cancel()
        settling = scope.launch {
            withTimeoutOrNull(SEND_CAP) { chat.outgoing.first { it.isEmpty() } }
            CallBook.call.first { it == null }
            delay(maxOf(GRACE, holdUntil - System.currentTimeMillis()))
            if (shown == 0) chat.stop()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

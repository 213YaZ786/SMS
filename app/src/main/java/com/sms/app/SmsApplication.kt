package com.sms.app

import android.app.Application
import com.sms.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SmsApplication : Application(), com.sms.app.core.handover.Handover.Host {

    /** Before the new SMS takes the files: the chat's engine stops writing them, for good here. */
    override fun beforeHandover() {
        runCatching { org.koin.java.KoinJavaComponent.get<com.sms.app.core.chat.RichChat>(com.sms.app.core.chat.RichChat::class.java).stop() }
        com.sms.app.core.chat.ChatService.stop(this)
    }

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@SmsApplication)
            modules(appModule)
        }
        // What was only passing through (photos cleaned before sending, a shot
        // just taken, files opened in another app) does not pile up over the years.
        Thread {
            val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            listOf("shared", "camera", "outgoing").forEach { dir ->
                java.io.File(cacheDir, dir).walkBottomUp().filter { it.lastModified() < dayAgo && it.name != dir }.forEach { it.delete() }
            }
        }.apply { isDaemon = true }.start()
        // The public list of dangerous sites follows its setting: fetched when on, deleted when off.
        val settings: com.sms.app.data.settings.SettingsStore = org.koin.java.KoinJavaComponent.get(com.sms.app.data.settings.SettingsStore::class.java)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            settings.settings.map { it.checkLinks }.distinctUntilChanged().collect { on ->
                if (on) com.sms.app.core.link.BadHosts.refresh(this@SmsApplication) else com.sms.app.core.link.BadHosts.forget(this@SmsApplication)
            }
        }
    }
}

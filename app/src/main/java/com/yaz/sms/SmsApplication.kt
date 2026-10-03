package com.yaz.sms

import android.app.Application
import com.yaz.sms.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SmsApplication : Application(), com.yaz.sms.core.handover.Handover.Host {

    /** Before the new SMS takes the files: the chat's engine stops writing them, for good here. */
    override fun beforeHandover() {
        runCatching { org.koin.java.KoinJavaComponent.get<com.yaz.sms.core.chat.RichChat>(com.yaz.sms.core.chat.RichChat::class.java).stop() }
        com.yaz.sms.core.chat.ChatService.stop(this)
    }

    override fun onCreate() {
        super.onCreate()
        // First start of the new SMS: the old one's files come over before anything reads them.
        val tookOver = com.yaz.sms.core.handover.Handover.takeOver(this)
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
        if (tookOver) {
            // The guide again, for what Android asks the new app itself (messaging app, notifications).
            org.koin.java.KoinJavaComponent.get<com.yaz.sms.data.settings.SettingsStore>(com.yaz.sms.data.settings.SettingsStore::class.java)
                .update { it.copy(welcomeSeen = false) }
            // The speech model, large, follows in the background.
            Thread { com.yaz.sms.core.handover.Handover.takeOverSpeech(this) }.apply { isDaemon = true }.start()
        }
        // The public list of dangerous sites follows its setting: fetched when on, deleted when off.
        val settings: com.yaz.sms.data.settings.SettingsStore = org.koin.java.KoinJavaComponent.get(com.yaz.sms.data.settings.SettingsStore::class.java)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            settings.settings.map { it.checkLinks }.distinctUntilChanged().collect { on ->
                if (on) com.yaz.sms.core.link.BadHosts.refresh(this@SmsApplication) else com.yaz.sms.core.link.BadHosts.forget(this@SmsApplication)
            }
        }
    }
}

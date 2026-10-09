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

class SmsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // The isolated picture decoder is a process of this app with no
        // rights at all: nothing of the app starts there.
        if (android.os.Process.isIsolated()) return
        // The Contacts app's private names, asked when a number has none in Android's contacts.
        com.yaz.sms.core.dial.PrivateNames.init(this)
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@SmsApplication)
            modules(appModule)
        }
        com.yaz.sms.core.chat.ChatLife.init(this)
        // What was only passing through (photos cleaned before sending, a shot
        // just taken, files opened in another app) does not pile up over the years.
        Thread {
            val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            listOf("shared", "camera", "outgoing").forEach { dir ->
                java.io.File(cacheDir, dir).walkBottomUp().filter { it.lastModified() < dayAgo && it.name != dir }.forEach { it.delete() }
            }
        }.apply { isDaemon = true }.start()
        // The public list of dangerous sites follows its setting: fetched when on, deleted when off.
        val settings: com.yaz.sms.data.settings.SettingsStore = org.koin.java.KoinJavaComponent.get(com.yaz.sms.data.settings.SettingsStore::class.java)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            settings.settings.map { it.checkLinks }.distinctUntilChanged().collect { on ->
                if (on) com.yaz.sms.core.link.BadHosts.refresh(this@SmsApplication) else com.yaz.sms.core.link.BadHosts.forget(this@SmsApplication)
            }
        }
    }
}

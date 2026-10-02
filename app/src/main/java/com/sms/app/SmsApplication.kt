package com.sms.app

import android.app.Application
import com.sms.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class SmsApplication : Application() {
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
    }
}

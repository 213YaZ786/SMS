package com.sms.app.di

import com.sms.app.core.chat.RichChat
import com.sms.app.core.sms.OpenRequests
import com.sms.app.data.contacts.PhoneBook
import com.sms.app.data.settings.SettingsStore
import com.sms.app.data.sms.Messages
import com.sms.app.feature.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Single composition root. */
val appModule = module {
    single { SettingsStore(androidContext()) }
    single { PhoneBook(androidContext(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    single { Messages(androidContext(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    single { OpenRequests() }
    single { RichChat(androidContext(), CoroutineScope(SupervisorJob() + Dispatchers.IO), get()) }
    single { com.sms.app.core.voice.Transcriber(androidContext(), CoroutineScope(SupervisorJob() + Dispatchers.IO), get()) }
    viewModelOf(::SettingsViewModel)
}

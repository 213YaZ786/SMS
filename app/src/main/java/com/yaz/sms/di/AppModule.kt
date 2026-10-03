package com.yaz.sms.di

import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.core.sms.OpenRequests
import com.yaz.sms.data.contacts.PhoneBook
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.data.sms.Messages
import com.yaz.sms.feature.settings.SettingsViewModel
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
    single { com.yaz.sms.core.voice.Transcriber(androidContext(), CoroutineScope(SupervisorJob() + Dispatchers.IO), get()) }
    viewModelOf(::SettingsViewModel)
}

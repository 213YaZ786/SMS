package com.sms.app.feature.settings

import androidx.lifecycle.ViewModel
import com.sms.app.core.update.UpdateMode
import com.sms.app.data.settings.Settings
import com.sms.app.data.settings.SettingsStore
import com.sms.app.data.settings.ThemeMode
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(val store: SettingsStore) : ViewModel() {

    val settings: StateFlow<Settings> = store.settings

    fun setTheme(mode: ThemeMode) = store.update { it.copy(themeMode = mode) }
    fun setPureBlack(on: Boolean) = store.update { it.copy(pureBlack = on) }
    fun setGlass(on: Boolean) = store.update { it.copy(glass = on) }
    fun setTextScale(scale: Float) = store.update { it.copy(textScale = scale) }
    fun setUpdates(mode: UpdateMode) = store.update { it.copy(updates = mode) }
    fun setHideInRecents(on: Boolean) = store.update { it.copy(hideInRecents = on) }
    fun setQuietSales(on: Boolean) = store.update { it.copy(quietSales = on) }
    fun setRichChat(on: Boolean) = store.update { it.copy(richChat = on) }
    fun setRelay(relay: String) = store.update { it.copy(relay = relay) }
    fun setUndoSeconds(seconds: Int) = store.update { it.copy(undoSeconds = seconds) }
}

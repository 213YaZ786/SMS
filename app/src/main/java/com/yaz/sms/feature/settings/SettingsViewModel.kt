package com.yaz.sms.feature.settings

import androidx.lifecycle.ViewModel
import com.yaz.sms.core.update.UpdateMode
import com.yaz.sms.data.settings.Settings
import com.yaz.sms.data.settings.SettingsStore
import com.yaz.sms.data.settings.ThemeMode
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
    // A Boolean preference set from a switch, as Fossify Messages' blockUnknownNumbers
    // (https://github.com/FossifyOrg/Messages, helpers/Config.kt and SettingsActivity.kt).
    fun setScreenUnknown(on: Boolean) = store.update { it.copy(screenUnknown = on) }
    fun setAutoMmsDownload(on: Boolean) = store.update { it.copy(autoMmsDownload = on) }
    fun setAutoMmsRoaming(on: Boolean) = store.update { it.copy(autoMmsRoaming = on) }
    // The same setter as the ones above, for Signal-Android's media auto-download choice.
    fun setShowStrangerMedia(on: Boolean) = store.update { it.copy(showStrangerMedia = on) }
    fun setRichChat(on: Boolean) = store.update { it.copy(richChat = on) }
    fun setRelay(relay: String) = store.update { it.copy(relay = relay) }
    fun setUndoSeconds(seconds: Int) = store.update { it.copy(undoSeconds = seconds) }
    fun setSwipeLeft(name: String) = store.update { it.copy(swipeLeft = name) }
    fun setSwipeRight(name: String) = store.update { it.copy(swipeRight = name) }
    fun setCheckLinks(on: Boolean) = store.update { it.copy(checkLinks = on) }
    fun setTranscribeVoice(on: Boolean) = store.update { it.copy(transcribeVoice = on) }
    fun setShareCard(on: Boolean) = store.update { it.copy(shareCard = on) }
    // The same setter as the ones above; the engine takes it through RichChat.applyReadReceipts.
    // Delta Chat Android: ChatsPreferenceFragment.ReadReceiptToggleListener.
    fun setReadReceipts(on: Boolean) = store.update { it.copy(readReceipts = on) }
}

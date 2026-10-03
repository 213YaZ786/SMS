package com.yaz.sms.core.call

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an encrypted call stands. */
enum class Phase { RINGING, CONNECTING, ACTIVE, ENDED }

/** The one encrypted call there may be: who, audio or video, which way, the engine's id. */
data class ChatCall(
    val phone: String,
    val video: Boolean,
    val incoming: Boolean,
    val phase: Phase,
    /** The engine's id of the call, once it has one. */
    val msgId: Int? = null,
    /** An incoming call's offer, until it is answered. */
    val offer: String? = null,
    /** When it became active, for the clock. */
    val since: Long = 0,
    val muted: Boolean = false,
    val cameraOn: Boolean = true,
    val speaker: Boolean = false
)

/**
 * The encrypted calls as Android sees them: declared to Telecom as the
 * calls of a self-managed account, so they ring, take the Bluetooth
 * headset and the lock screen, and give way to a phone call, like any
 * other calling app's.
 */
object CallBook {

    private val _call = MutableStateFlow<ChatCall?>(null)
    val call: StateFlow<ChatCall?> = _call.asStateFlow()

    /** The Telecom side of the call in progress. */
    @Volatile var connection: ChatConnection? = null

    fun update(change: (ChatCall) -> ChatCall) {
        _call.value?.let { _call.value = change(it) }
    }

    fun set(call: ChatCall?) {
        _call.value = call
    }

    fun handle(context: Context) = PhoneAccountHandle(ComponentName(context, ChatConnectionService::class.java), "encrypted-chat")

    /** The account Telecom files the encrypted calls under, made once. */
    fun register(context: Context) {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        val account = PhoneAccount.builder(handle(context), "SMS")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_VIDEO_CALLING or PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING)
            .setShortDescription("Encrypted calls")
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
            // In Android's call history, so Dialer's Recents hold them with the other calls.
            .setExtras(Bundle().apply { putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, true) })
            .build()
        runCatching { telecom.registerPhoneAccount(account) }
    }

    /** An encrypted call to [phone]: Telecom first, the media once it says yes. */
    fun place(context: Context, phone: String, video: Boolean): Boolean {
        forgetStale()
        if (_call.value != null || !CallLine.dialerShowsCalls(context)) return false
        register(context)
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        if (!telecom.isOutgoingCallPermitted(handle(context))) return false
        set(ChatCall(phone, video, incoming = false, phase = Phase.CONNECTING))
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
            putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
        }
        return runCatching { telecom.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, phone, null), extras) }
            .onFailure { set(null) }.isSuccess
    }

    /** A call kept here that Telecom no longer holds (it ended it on its side) is let go. */
    private fun forgetStale() {
        if (_call.value != null && connection == null) _call.value = null
    }

    /** A call comes in over the chat: it rings unless one is already going. */
    fun ring(context: Context, msgId: Int, phone: String, offer: String, video: Boolean): Boolean {
        forgetStale()
        if (_call.value != null || !CallLine.dialerShowsCalls(context)) return false
        register(context)
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        if (!telecom.isIncomingCallPermitted(handle(context))) return false
        set(ChatCall(phone, video, incoming = true, phase = Phase.RINGING, msgId = msgId, offer = offer))
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(PhoneAccount.SCHEME_TEL, phone, null))
            putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
        }
        return runCatching { telecom.addNewIncomingCall(handle(context), extras) }.onFailure {
            set(null)
        }.isSuccess
    }

    /** The call is over, whoever ended it. */
    fun end() {
        _call.value?.let { _call.value = it.copy(phase = Phase.ENDED) }
        connection = null
    }
}

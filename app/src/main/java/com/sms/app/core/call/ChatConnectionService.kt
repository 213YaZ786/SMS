package com.sms.app.core.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.sms.app.core.chat.RichChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Telecom's way in to the encrypted calls. Only Telecom may bind it (the
 * manifest asks BIND_TELECOM_CONNECTION_SERVICE).
 */
class ChatConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val call = CallBook.call.value
        if (call == null || call.incoming) return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        return ChatConnection(applicationContext).apply {
            setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            videoState = if (call.video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
            setDialing()
            CallBook.connection = this
            CallLine.start(applicationContext)
        }
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        // Another call holds the line: this one does not go.
        CallBook.end()
    }

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val call = CallBook.call.value
        if (call == null || !call.incoming) return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        return ChatConnection(applicationContext).apply {
            setAddress(android.net.Uri.fromParts("tel", call.phone, null), TelecomManager.PRESENTATION_ALLOWED)
            videoState = if (call.video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
            setRinging()
            CallBook.connection = this
        }
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        // Telecom will not ring now (a phone call, do not disturb): it is missed.
        CallBook.call.value?.let { CallNotices.missed(applicationContext, it.phone, it.video) }
        CallBook.call.value?.msgId?.let { id -> ChatConnection.endInEngine(applicationContext, id) }
        CallBook.set(null)
    }
}

/** One encrypted call, as Telecom sees it. */
class ChatConnection(private val context: Context) : Connection(), KoinComponent {

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_MUTE
        audioModeIsVoip = true
    }

    /**
     * Dialer's call screen shows it; the line rings, as Telecom rings for a
     * SIM (a self-managed line plays its own ringtone).
     */
    override fun onShowIncomingCallUi() = Ringer.start(context)

    /** Silenced from Dialer's screen, or the phone turned face down. */
    override fun onSilence() = Ringer.stop()

    /** Answered on Dialer's screen, a headset or a watch. */
    override fun onAnswer(videoState: Int) {
        Ringer.stop()
        // Answered is active for Telecom at once; the voice joins a moment later.
        setActive()
        CallLine.answer(context)
    }

    override fun onAnswer() = onAnswer(VideoProfile.STATE_AUDIO_ONLY)

    override fun onReject() = hangUp(DisconnectCause.REJECTED)

    override fun onDisconnect() = hangUp(DisconnectCause.LOCAL)

    override fun onAbort() = hangUp(DisconnectCause.LOCAL)

    /** Telecom let go of it on its side (a failed answer, a time limit): it ends here too. */
    override fun onStateChanged(state: Int) {
        if (state == STATE_DISCONNECTED && CallBook.connection === this) {
            CallBook.call.value?.msgId?.let { endInEngine(context, it) }
            CallBook.end()
            CallBook.set(null)
            CallLine.close(context)
        }
    }

    /** Mute and the sound's way, as Dialer's screen sets them through Telecom. */
    @Deprecated("Telecom still calls it on every Android this app runs on")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        val muted = state?.isMuted == true
        CallLine.mute(muted)
        CallBook.update { it.copy(muted = muted, speaker = state?.route == CallAudioState.ROUTE_SPEAKER) }
    }

    /** Ended here: the other side is told through the engine. */
    fun hangUp(cause: Int) {
        Ringer.stop()
        CallBook.call.value?.msgId?.let { endInEngine(context, it) }
        finish(cause)
    }

    /** Ended, whoever ended it: Telecom, the notification and the screen let go. */
    fun finish(cause: Int) {
        Ringer.stop()
        setDisconnected(DisconnectCause(cause))
        destroy()
        CallBook.end()
        CallBook.set(null)
        CallLine.close(context)
    }

    companion object : KoinComponent {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val chat: RichChat by inject()

        fun endInEngine(context: Context, msgId: Int) {
            scope.launch { chat.endCall(msgId) }
        }
    }
}

/**
 * A self-managed call rings by itself: the phone's ringtone and a
 * vibration, as the ringer switch allows.
 */
object Ringer {
    private var tone: Ringtone? = null

    fun start(context: Context) {
        stop()
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            tone = runCatching {
                RingtoneManager.getRingtone(context, RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    isLooping = true
                    play()
                }
            }.getOrNull()
        }
        if (audio?.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            runCatching {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0))
            }
        }
        vibrating = context
    }

    private var vibrating: Context? = null

    fun stop() {
        runCatching { tone?.stop() }
        tone = null
        vibrating?.let { runCatching { it.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel() } }
        vibrating = null
    }
}

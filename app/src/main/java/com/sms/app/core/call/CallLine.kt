package com.sms.app.core.call

import android.content.Context
import android.content.Intent
import android.telecom.DisconnectCause
import androidx.core.content.ContextCompat
import com.sms.app.core.chat.CallSignal
import com.sms.app.core.chat.RichChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The encrypted line behind a call that Dialer shows: the chat carries
 * the offer and the answer, WebRTC the voice. No screen here: answering,
 * hanging up and muting come from Dialer's call screen through Telecom.
 */
object CallLine : KoinComponent {

    private val chat: RichChat by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var media: CallMedia? = null
    @Volatile private var state = ""
    private var watching: Job? = null

    /** Dialer shows the calls of this line; without it, they are not offered. */
    fun dialerShowsCalls(context: Context): Boolean =
        context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage?.startsWith("com.dialer.app") == true

    /** Signals of the chat for the call in progress: its answer, its end. */
    fun watch() {
        if (watching != null) return
        watching = scope.launch {
            chat.calls.collect { signal ->
                val id = CallBook.call.value?.msgId ?: return@collect
                when (signal) {
                    is CallSignal.Accepted -> if (signal.msgId == id) runCatching { media?.accepted(signal.answer) }
                    is CallSignal.Ended -> if (signal.msgId == id) CallBook.connection?.finish(DisconnectCause.REMOTE)
                    is CallSignal.TakenElsewhere -> if (signal.msgId == id) CallBook.connection?.finish(DisconnectCause.ANSWERED_ELSEWHERE)
                    else -> Unit
                }
            }
        }
    }

    /** A call we make: our offer goes through the chat. */
    fun start(context: Context) {
        val call = CallBook.call.value ?: return
        watch()
        keep(context)
        scope.launch {
            runCatching {
                val line = CallMedia(context, chat.iceServers(), call.video, ::onState, ::onSize).also { media = it }
                val id = chat.placeCall(call.phone, line.offer(), call.video) ?: error("not sent")
                CallBook.update { it.copy(msgId = id) }
            }.onFailure { CallBook.connection?.finish(DisconnectCause.ERROR) }
        }
    }

    /** A call we take, answered on Dialer's screen. */
    fun answer(context: Context) {
        val call = CallBook.call.value ?: return
        val offer = call.offer ?: return
        val id = call.msgId ?: return
        watch()
        keep(context)
        CallBook.update { it.copy(phase = Phase.CONNECTING) }
        scope.launch {
            runCatching {
                val line = CallMedia(context, chat.iceServers(), call.video, ::onState, ::onSize).also { media = it }
                check(chat.acceptCall(id, line.answer(offer)))
            }.onFailure { CallBook.connection?.hangUp(DisconnectCause.ERROR) }
        }
    }

    fun mute(on: Boolean) {
        media?.mute(on)
    }

    // What Dialer's screen asks of a video call, through Telecom.
    fun camera(id: String?) = runCatching { media?.camera(id) }
    fun showRemote(surface: android.view.Surface?) = runCatching { media?.showRemote(surface) }
    fun showLocal(surface: android.view.Surface?) = runCatching { media?.showLocal(surface) }

    /** Over, whoever ended it. */
    fun close(context: Context) {
        media?.close()
        media = null
        state = ""
        context.stopService(Intent(context, CallService::class.java))
    }

    /** Each picture's size, told to Dialer through Telecom so it frames them. */
    private fun onSize(remote: Boolean, width: Int, height: Int) {
        val provider = CallBook.connection?.videoProvider as? ChatVideoProvider ?: return
        if (remote) provider.changePeerDimensions(width, height)
        else provider.changeCameraCapabilities(android.telecom.VideoProfile.CameraCapabilities(width, height))
    }

    private fun onState(now: String) {
        state = now
        when (now) {
            "connected" -> {
                CallBook.connection?.setActive()
                CallBook.update { if (it.since == 0L) it.copy(phase = Phase.ACTIVE, since = System.currentTimeMillis()) else it }
            }
            "failed", "closed" -> CallBook.connection?.hangUp(DisconnectCause.ERROR)
            // The other side gone: a short wait for the line to come back, then the call ends.
            "disconnected" -> scope.launch {
                delay(4000)
                if (state == "disconnected") CallBook.connection?.hangUp(DisconnectCause.REMOTE)
            }
        }
    }

    /** The microphone kept while Dialer, not this app, is in front. */
    private fun keep(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
    }
}

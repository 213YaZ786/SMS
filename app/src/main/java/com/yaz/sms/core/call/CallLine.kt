package com.yaz.sms.core.call

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import com.yaz.sms.core.chat.CallSignal
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.core.chat.RpcException
import com.yaz.sms.core.dial.Sisters
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
    private const val TAG = "CallLine"
    @Volatile private var media: CallMedia? = null
    @Volatile private var state = ""
    private var watching: Job? = null

    /** Our Dialer, the phone app, shows the calls of this line; without it, they are not offered. */
    fun dialerShowsCalls(context: Context): Boolean =
        context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
            ?.let { it in Sisters.packages("dialer") && Sisters.isOurs(context, it) } == true

    /**
     * Whether a call can reach the other side now: the active network has the internet, or the engine says it
     * reaches its servers. Delta Chat's CallUtil.startCall asks the user "call requires a connection, continue?"
     * when this is false; the screen that places the call does the same (CallBook.place stays as it is).
     * https://github.com/deltachat/deltachat-android/blob/main/src/main/java/org/thoughtcrime/securesms/calls/CallUtil.java#isNetworkAvailable
     * not ported: startAudioCall (CallBook.place with video false)
     * not ported: startVideoCall (CallBook.place with video true)
     * not ported: startCall (CallBook.place: one call at a time, Dialer's screen opens itself)
     * not ported: getIconFromChat (Dialer draws the caller's face)
     * not ported: getNameFromChat (Telecom shows the number's name)
     * not ported: getIconResByCallEndpoint (Dialer's call screen draws the audio routes)
     * not ported: CallUtil (a class of statics: its functions are above)
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        var networkAvailable = false
        val network = manager.activeNetwork
        if (network != null) {
            val caps = manager.getNetworkCapabilities(network)
            networkAvailable = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val serverConnected = chat.serverConnected
        return networkAvailable || serverConnected
    }

    /**
     * Signals of the chat for the call in progress: its answer, its end. Started when the line rings (CallBook.ring)
     * or a call is placed, so an end while ringing stops the ringer and posts the missed call.
     */
    fun watch(context: Context) {
        if (watching != null) return
        val appContext = context.applicationContext
        watching = scope.launch {
            chat.calls.collect { signal ->
                val id = CallBook.call.value?.msgId ?: return@collect
                when (signal) {
                    is CallSignal.Accepted -> if (signal.msgId == id) runCatching { media?.accepted(signal.answer) }
                    is CallSignal.Ended -> if (signal.msgId == id) onCallEnded(appContext, signal.msgId)
                    is CallSignal.TakenElsewhere -> if (signal.msgId == id) CallBook.connection?.finish(DisconnectCause.ANSWERED_ELSEWHERE)
                    else -> Unit
                }
            }
        }
    }

    /**
     * The other side ended the call [callId], or it was missed: the ringer stops, the connection finishes with
     * REMOTE, and a call that was still ringing (not answered, answerInProgress false) leaves a missed call notice.
     * Delta Chat's CallCoordinator.onCallEnded: findSession, hasNotifiedBackend = true (the engine already knows it
     * ended), shouldNotifyMissed, teardownSession(session, DisconnectCause.REMOTE), then the missed notification.
     * https://github.com/deltachat/deltachat-android/blob/main/src/main/java/org/thoughtcrime/securesms/calls/CallCoordinator.java#onCallEnded
     * not ported: findSession (one call at a time, CallBook.call)
     * not ported: teardownSession (CallConnection.finish does the same: Telecom, notification, screen)
     * not ported: notifyMessage (the notice is CallNotices.missed)
     * not ported: runOnBackground (this runs on the line's own scope)
     * not ported: getNotificationCenter (CallNotices)
     */
    private fun onCallEnded(appContext: Context, callId: Int) {
        val shouldNotifyMissed: Boolean
        val notifyPhone: String
        val notifyVideo: Boolean
        synchronized(this) {
            val session = CallBook.call.value
            if (session == null || session.msgId != callId) {
                Log.w(TAG, "CALL_ENDED matches no tracked call, ignoring")
                return
            }
            CallBook.update { it.copy(hasNotifiedBackend = true) }
            val answerInProgress = session.phase != Phase.RINGING
            shouldNotifyMissed = session.incoming && !answerInProgress && session.msgId != null
            notifyPhone = session.phone
            notifyVideo = session.video
        }
        // The ringer is not Telecom's: it stops with the call, also when no connection was made yet.
        Ringer.stop()
        val connection = CallBook.connection
        if (connection != null) {
            connection.finish(DisconnectCause.REMOTE)
        } else {
            CallBook.end()
            CallBook.set(null)
            close(appContext)
        }
        if (shouldNotifyMissed) CallNotices.missed(appContext, notifyPhone, notifyVideo)
    }

    /** A call we make: our offer goes through the chat. */
    fun start(context: Context) {
        val call = CallBook.call.value ?: return
        watch(context)
        keep(context)
        scope.launch {
            runCatching {
                // Delta Chat fetches the ICE servers and reports the failure ("Failed to initiate call: " + message):
                // without them the call would go on without TURN and fail later with no word.
                val line = CallMedia(context, chat.iceServers(), call.video, ::onState, ::onSize, ::onEffect).also { media = it }
                val id = chat.placeCall(call.phone, line.offer(), call.video)
                CallBook.update { it.copy(msgId = id) }
            }.onFailure { error ->
                CallNotices.failed(context, "Could not start the call", (error as? RpcException)?.message)
                CallBook.connection?.finish(DisconnectCause.ERROR)
            }
        }
    }

    /** A call we take, answered on Dialer's screen. */
    fun answer(context: Context) {
        val call = CallBook.call.value ?: return
        val offer = call.offer ?: return
        val id = call.msgId ?: return
        watch(context)
        keep(context)
        CallBook.update { it.copy(phase = Phase.CONNECTING) }
        scope.launch {
            runCatching {
                val line = CallMedia(context, chat.iceServers(), call.video, ::onState, ::onSize, ::onEffect).also { media = it }
                chat.acceptCall(id, line.answer(offer))
            }.onFailure { error ->
                CallNotices.failed(context, "Could not answer the call", (error as? RpcException)?.message)
                CallBook.connection?.hangUp(DisconnectCause.ERROR)
            }
        }
    }

    fun mute(on: Boolean) {
        media?.mute(on)
    }

    // What Dialer's screen asks of a video call, through Telecom.
    fun camera(id: String?) = runCatching { media?.camera(id) }
    fun showRemote(surface: android.view.Surface?) = runCatching { media?.showRemote(surface) }
    fun showLocal(surface: android.view.Surface?) = runCatching { media?.showLocal(surface) }

    /**
     * A screen effect chosen on Dialer's screen goes to the other side; one
     * from the other side goes to Dialer's screen. Only the effects Dialer
     * plays, and at most one every 1.5 s from the other side.
     */
    fun sendEffect(name: String?) {
        if (name in EFFECTS) media?.sendEffect(name!!)
    }

    @Volatile private var lastEffect = 0L

    private fun onEffect(name: String) {
        if (name !in EFFECTS) return
        val now = System.currentTimeMillis()
        if (now - lastEffect < 1500) return
        lastEffect = now
        CallBook.connection?.sendConnectionEvent(EVENT_EFFECT, android.os.Bundle().apply { putString(EXTRA_EFFECT, name) })
    }

    /** Between Dialer and this line, through Telecom (YAZ.md). */
    const val EVENT_EFFECT = "com.yaz.call.EFFECT"
    const val EXTRA_EFFECT = "effect"
    private val EFFECTS = setOf("FIREWORKS", "CONFETTI", "BALLOONS", "LOVE", "LASERS", "STARS", "CELEBRATION")

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

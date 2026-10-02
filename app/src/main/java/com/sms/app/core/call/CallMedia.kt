package com.sms.app.core.call

import android.content.Context
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * An encrypted call's media, with WebRTC's own Android library: no screen
 * and no page, so it runs while Dialer shows the call. The whole
 * description, candidates included, goes through the chat in one message.
 * WebRTC encrypts the media itself (DTLS-SRTP); the description travels
 * end-to-end encrypted in the chat, so only the two phones know its keys.
 */
class CallMedia(context: Context, iceJson: String, private val onState: (String) -> Unit) {

    private val factory: PeerConnectionFactory
    private val audio: JavaAudioDeviceModule
    private val pc: PeerConnection
    private val track: AudioTrack
    private val gathered = CompletableDeferred<Unit>()

    init {
        init(context)
        audio = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(iceServers(iceJson)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        pc = factory.createPeerConnection(config, Observer()) ?: error("no peer connection")
        val source = factory.createAudioSource(MediaConstraints())
        track = factory.createAudioTrack("voice", source)
        pc.addTrack(track, listOf("call"))
    }

    /** Our offer for a call we make. */
    suspend fun offer(): String {
        val sdp = describe { pc.createOffer(it, MediaConstraints()) }
        setLocal(sdp)
        withTimeoutOrNull(6000) { gathered.await() }
        return pc.localDescription.description
    }

    /** Our answer to a call we take. */
    suspend fun answer(offer: String): String {
        setRemote(SessionDescription(SessionDescription.Type.OFFER, offer))
        val sdp = describe { pc.createAnswer(it, MediaConstraints()) }
        setLocal(sdp)
        withTimeoutOrNull(6000) { gathered.await() }
        return pc.localDescription.description
    }

    /** The other side took our call. */
    suspend fun accepted(answer: String) = setRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))

    fun mute(on: Boolean) {
        track.setEnabled(!on)
    }

    fun close() {
        runCatching { pc.dispose() }
        runCatching { factory.dispose() }
        runCatching { audio.release() }
    }

    private suspend fun describe(make: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { done ->
        make(object : Sdp() {
            override fun onCreateSuccess(sdp: SessionDescription) = done.resume(sdp)
            override fun onCreateFailure(error: String?) = done.resumeWithException(IllegalStateException(error))
        })
    }

    private suspend fun setLocal(sdp: SessionDescription) = suspendCancellableCoroutine { done ->
        pc.setLocalDescription(object : Sdp() {
            override fun onSetSuccess() = done.resume(Unit)
            override fun onSetFailure(error: String?) = done.resumeWithException(IllegalStateException(error))
        }, sdp)
    }

    private suspend fun setRemote(sdp: SessionDescription) = suspendCancellableCoroutine { done ->
        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() = done.resume(Unit)
            override fun onSetFailure(error: String?) = done.resumeWithException(IllegalStateException(error))
        }, sdp)
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = onState(state.name.lowercase())
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    companion object {
        @Volatile private var ready = false

        private fun init(context: Context) {
            if (ready) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            ready = true
        }

        /** The relay's meeting points, from the engine's JSON. */
        private fun iceServers(json: String): List<PeerConnection.IceServer> = runCatching {
            (Json.parseToJsonElement(json) as JsonArray).mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val urls = (o["urls"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: listOfNotNull(o["urls"]?.jsonPrimitive?.contentOrNull)
                PeerConnection.IceServer.builder(urls)
                    .setUsername(o["username"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    .setPassword(o["credential"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    .createIceServer()
            }
        }.getOrDefault(emptyList())
    }
}

package com.yaz.sms.core.call

import android.content.Context
import android.util.Log
import com.yaz.sms.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import android.view.Surface
import org.webrtc.AudioTrack
import org.webrtc.Camera2Capturer
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
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
 * and no page, so it runs while Dialer shows the call; a video call's
 * pictures are drawn into the surfaces Dialer's screen lends through
 * Telecom. The whole
 * description, candidates included, goes through the chat in one message.
 * WebRTC encrypts the media itself (DTLS-SRTP); the description travels
 * end-to-end encrypted in the chat, so only the two phones know its keys.
 */
class CallMedia(
    private val context: Context,
    iceJson: String,
    val video: Boolean,
    private val onState: (String) -> Unit,
    /** The size of each picture as it comes, so Dialer frames it without stretching it. */
    private val onSize: (remote: Boolean, width: Int, height: Int) -> Unit = { _, _, _ -> },
    /** A screen effect the other side sent (its name). */
    private val onEffect: (String) -> Unit = {}
) {

    private val factory: PeerConnectionFactory
    private val audio: JavaAudioDeviceModule
    private val pc: PeerConnection
    private val track: AudioTrack
    // Port of Delta Chat Android's latches (relayCandidateLatch, srflxCandidateLatch, iceGatheringLatch)
    // and flags (hasHostCandidate), GPL-3.0:
    // https://github.com/deltachat/deltachat-android/blob/master/src/main/java/org/thoughtcrime/securesms/webrtc/WebRTCClient.java
    // Changed: CompletableDeferred instead of CountDownLatch, a deferred done is the flag for relay
    // and srflx, the schemes of the servers kept as three booleans read once from the parsed list.
    private val relayCandidate = CompletableDeferred<Unit>()
    private val srflxCandidate = CompletableDeferred<Unit>()
    private val gatheringComplete = CompletableDeferred<Unit>()
    // Gathering complete after a candidate came: final. With GATHER_ONCE, WebRTC stops gathering
    // as soon as a pair is strongly connected (p2p_transport_channel.cc, BSD-3:
    // https://webrtc.googlesource.com/src/+/refs/heads/main/p2p/base/p2p_transport_channel.cc
    // MaybeStopPortAllocatorSessions), as when we answer an offer that already holds the caller's
    // relay: no relay of ours will come. Delta Chat's host-only wait reads the same pair
    // (iceGatheringLatch, hasHostCandidate). A "complete" before any candidate is not taken.
    private val gatheringDone = CompletableDeferred<Unit>()
    @Volatile private var hostCandidate = false
    @Volatile private var answering = false
    private var hasTurnServer = false
    private var hasStunServer = false
    private var noServers = false
    private var effects: DataChannel? = null

    // Video: one GL context for the codecs and the two pictures Dialer lends.
    private val egl: EglBase? = if (video) EglBase.create() else null
    private var videoSource: VideoSource? = null
    private var camera: CameraVideoCapturer? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var localTrack: VideoTrack? = null
    private val remoteView = Screen("remote", remote = true)
    private val localView = Screen("local", remote = false, mirror = true)

    init {
        init(context)
        audio = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).apply {
            egl?.let {
                setVideoEncoderFactory(DefaultVideoEncoderFactory(it.eglBaseContext, true, true))
                setVideoDecoderFactory(DefaultVideoDecoderFactory(it.eglBaseContext))
            }
        }.createPeerConnectionFactory()
        val servers = iceServers(iceJson)
        // Which schemes the servers hold: "turns:" counts as turn (Delta Chat tests "turn:" and "stun:").
        val urls = servers.flatMap { it.urls }
        hasTurnServer = urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
        hasStunServer = urls.any { it.startsWith("stun:") }
        noServers = servers.isEmpty()
        // Debug only. Port of AppRTCMobile's PCObserver logging (BSD-3):
        // https://webrtc.googlesource.com/src/+/refs/heads/main/examples/androidapp/src/org/appspot/apprtc/PeerConnectionClient.java
        // Only the count and the scheme of each url (turn, turns, stun): the host, the user name and
        // the credential name the relay and the account, so they are never logged.
        if (BuildConfig.DEBUG) {
            val schemes = servers.flatMap { it.urls }.joinToString(", ") { it.substringBefore(':') }
            Log.d(TAG, "IceServers: ${servers.size} ($schemes)")
            servers.forEach { Log.d(TAG, "IceServer: user ${it.username.isNotEmpty()}, password ${it.password.isNotEmpty()}") }
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            // Gathering starts with the connection, while it rings, so the answer has its relay at
            // once (Delta Chat Android's rtcConfig, GPL-3.0:
            // https://github.com/deltachat/deltachat-android/blob/master/src/main/java/org/thoughtcrime/securesms/webrtc/WebRTCClient.java).
            iceCandidatePoolSize = 1
        }
        pc = factory.createPeerConnection(config, Observer()) ?: error("no peer connection")
        val source = factory.createAudioSource(MediaConstraints())
        track = factory.createAudioTrack("voice", source)
        pc.addTrack(track, listOf("call"))
        // Screen effects sent during the call: a channel of their own, made the same way on both
        // sides before the offer and the answer, encrypted with the call (DTLS).
        effects = pc.createDataChannel("effects", DataChannel.Init().apply {
            negotiated = true
            id = 0
        })?.also { channel ->
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previous: Long) = Unit
                override fun onStateChange() = Unit
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val data = buffer.data
                    if (buffer.binary || data.remaining() !in 1..32) return
                    val bytes = ByteArray(data.remaining()).also { data.get(it) }
                    onEffect(String(bytes, Charsets.US_ASCII))
                }
            })
        }
        if (video) {
            val vs = factory.createVideoSource(false).also { videoSource = it }
            localTrack = factory.createVideoTrack("camera", vs).also {
                pc.addTrack(it, listOf("call"))
                it.addSink(localView)
            }
        }
    }

    /**
     * The camera Dialer chose (an id of Android's camera list), or none:
     * the other side then gets no picture until one is chosen again.
     */
    fun camera(id: String?) {
        runCatching { camera?.stopCapture() }
        camera?.dispose()
        camera = null
        val source = videoSource ?: return
        if (id == null) return
        val helper = cameraHelper ?: SurfaceTextureHelper.create("camera", egl!!.eglBaseContext).also { cameraHelper = it }
        camera = Camera2Capturer(context, id, null).also {
            it.initialize(helper, context, source.capturerObserver)
            it.startCapture(1280, 720, 30)
        }
        localView.mirror = runCatching {
            context.getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        }.getOrDefault(true)
    }

    /** Where the other side's picture goes, in Dialer's screen. */
    fun showRemote(surface: Surface?) = remoteView.attach(surface)

    /** Where one's own picture goes, the small one in Dialer's screen. */
    fun showLocal(surface: Surface?) = localView.attach(surface)

    /**
     * A picture Dialer lends: frames are drawn into its surface while it
     * is there, and dropped while it is not.
     */
    private inner class Screen(name: String, private val remote: Boolean, var mirror: Boolean = false) : VideoSink {
        private val renderer = EglRenderer(name)
        private var attached = false
        private var size = 0 to 0

        init {
            egl?.let { renderer.init(it.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer()) }
        }

        fun attach(surface: Surface?) {
            if (egl == null) return
            if (attached) renderer.releaseEglSurface { }
            attached = false
            if (surface != null) {
                renderer.setMirror(mirror)
                renderer.createEglSurface(surface)
                attached = true
            }
        }

        override fun onFrame(frame: VideoFrame) {
            val now = frame.rotatedWidth to frame.rotatedHeight
            if (now != size) {
                size = now
                onSize(remote, now.first, now.second)
            }
            if (attached) renderer.onFrame(frame)
        }

        fun release() = runCatching { renderer.release() }
    }

    /** Our offer for a call we make. */
    suspend fun offer(): String {
        val sdp = describe { pc.createOffer(it, MediaConstraints()) }
        setLocal(sdp)
        waitForEnoughIce()
        return pc.localDescription.description
    }

    /** Our answer to a call we take. */
    suspend fun answer(offer: String): String {
        answering = true
        logRemote(offer)
        setRemote(SessionDescription(SessionDescription.Type.OFFER, offer))
        val sdp = describe { pc.createAnswer(it, MediaConstraints()) }
        setLocal(sdp)
        waitForEnoughIce()
        return pc.localDescription.description
    }

    /**
     * Waits for enough candidates before the description is read, because it travels whole in one
     * chat message and no candidate can follow it. Port of Delta Chat Android's waitForEnoughIce(),
     * GPL-3.0 (RELAY_WAIT_MS 10000, SRFLX_WAIT_MS 5000, SRFLX_BURST_DELAY_MS 150, HOST_WAIT_MS 3000):
     * https://github.com/deltachat/deltachat-android/blob/master/src/main/java/org/thoughtcrime/securesms/webrtc/WebRTCClient.java
     * Changed: suspend and withTimeoutOrNull on deferreds instead of CountDownLatch.await and
     * Thread.sleep; the server schemes are read once in init; the debug line says what was waited for;
     * the relay and srflx waits also end when gathering is done (gatheringDone): nothing more comes.
     */
    private suspend fun waitForEnoughIce(): Boolean {
        if (hasTurnServer) {
            val got = withTimeoutOrNull(RELAY_WAIT_MS) { firstOf(relayCandidate, "relay") }
            if (BuildConfig.DEBUG) Log.d(TAG, "Waited for relay: ${got ?: "timeout"}")
            if (got != null) return true
        } else if (hasStunServer) {
            val got = withTimeoutOrNull(SRFLX_WAIT_MS) { firstOf(srflxCandidate, "srflx") }
            if (BuildConfig.DEBUG) Log.d(TAG, "Waited for srflx: ${got ?: "timeout"}")
            if (got == "srflx") {
                // A burst of more srflx candidates follows the first.
                delay(SRFLX_BURST_DELAY_MS)
                return true
            }
            if (got != null) return true
        } else if (noServers) {
            val got = withTimeoutOrNull(HOST_WAIT_MS) { gatheringComplete.await() } != null
            if (BuildConfig.DEBUG) Log.d(TAG, "Waited for gathering complete (host only): $got")
            return hostCandidate
        }
        // Fallback, as Delta Chat: the relay or srflx did not come, gathering complete up to 10 s.
        val got = withTimeoutOrNull(RELAY_WAIT_MS) { gatheringComplete.await() } != null
        if (BuildConfig.DEBUG) Log.d(TAG, "Waited for gathering complete: $got")
        return got
    }

    /**
     * The candidate waited for, or the end of gathering, whichever comes first (the candidate wins a
     * tie: select is biased to its first clause). kotlinx.coroutines' select over Deferred.onAwait,
     * "Selecting deferred values" (Apache-2.0):
     * https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/selects/Select.kt
     */
    private suspend fun firstOf(candidate: CompletableDeferred<Unit>, name: String): String = select {
        candidate.onAwait { name }
        gatheringDone.onAwait { "gathering done" }
    }

    /**
     * Notes each candidate by its type and ends the waits it satisfies. Port of Delta Chat Android's
     * onIceCandidateGathered(), GPL-3.0, same file as above. Changed: the type comes from
     * candidateType(), the deferreds replace the latches, no trickling (GATHER_ONCE).
     */
    private fun onCandidateGathered(sdp: String) {
        when (candidateType(sdp)) {
            "relay" -> relayCandidate.complete(Unit)
            "srflx" -> srflxCandidate.complete(Unit)
            "host" -> hostCandidate = true
        }
        // Answering, WebRTC may say "complete" before it hands over the candidates it already has: the
        // offer's candidates let a pair hold at once, which stops gathering (p2p_transport_channel.cc,
        // OnConnectionStateChange, stop_gather_on_strongly_connected), so a candidate after it is final too.
        // Calling, no pair can hold before the answer: a "complete" before any candidate is not taken.
        if (answering && gatheringComplete.isCompleted) gatheringDone.complete(Unit)
    }

    /** The other side took our call. */
    suspend fun accepted(answer: String) {
        logRemote(answer)
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))
    }

    // Debug only. Same source as the observer below (AppRTCMobile's PeerConnectionClient.java,
    // BSD-3, https://webrtc.googlesource.com/src/+/refs/heads/main/examples/androidapp/src/org/appspot/apprtc/PeerConnectionClient.java).
    // Only the number of remote candidates by type: the SDP and the addresses in it identify the
    // other phone's network, so neither is logged.
    private fun logRemote(sdp: String) {
        if (!BuildConfig.DEBUG) return
        val counts = sdp.lineSequence().filter { it.startsWith("a=candidate") }
            .groupingBy { candidateType(it) }.eachCount()
        Log.d(TAG, "Remote candidates: " + counts.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" })
    }

    fun mute(on: Boolean) {
        track.setEnabled(!on)
    }

    /** A screen effect to the other side, by its name. */
    fun sendEffect(name: String) {
        val channel = effects ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        runCatching { channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(name.toByteArray(Charsets.US_ASCII)), false)) }
    }

    fun close() {
        camera(null)
        runCatching { cameraHelper?.dispose() }
        remoteView.release()
        localView.release()
        effects?.let { runCatching { it.unregisterObserver(); it.close(); it.dispose() } }
        effects = null
        runCatching { pc.dispose() }
        runCatching { factory.dispose() }
        runCatching { audio.release() }
        runCatching { videoSource?.dispose() }
        runCatching { egl?.release() }
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
        // Debug only. Port of AppRTCMobile's PCObserver (BSD-3), which logs each state at debug level:
        // https://webrtc.googlesource.com/src/+/refs/heads/main/examples/androidapp/src/org/appspot/apprtc/PeerConnectionClient.java
        // States are names only; for a candidate just its type and protocol, never the address, port,
        // sdp or ufrag, which identify the network.
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (BuildConfig.DEBUG) Log.d(TAG, "PeerConnectionState: $state")
            onState(state.name.lowercase())
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (BuildConfig.DEBUG) Log.d(TAG, "IceGatheringState: $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                gatheringComplete.complete(Unit)
                if (hostCandidate || relayCandidate.isCompleted || srflxCandidate.isCompleted) gatheringDone.complete(Unit)
            }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            if (BuildConfig.DEBUG) Log.d(TAG, "SignalingState: $state")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (BuildConfig.DEBUG) Log.d(TAG, "IceConnectionState: $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate != null) onCandidateGathered(candidate.sdp)
            if (BuildConfig.DEBUG && candidate != null) {
                val proto = candidate.sdp.split(' ').getOrNull(2) ?: "?"
                Log.d(TAG, "Candidate: ${candidateType(candidate.sdp)} $proto")
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            (receiver?.track() as? VideoTrack)?.addSink(remoteView)
        }
    }

    companion object {
        @Volatile private var ready = false
        private const val TAG = "CallMedia"

        // Delta Chat Android's ICE gathering timeouts (WebRTCClient.java, GPL-3.0, see waitForEnoughIce).
        private const val RELAY_WAIT_MS = 10000L
        private const val SRFLX_WAIT_MS = 5000L
        private const val SRFLX_BURST_DELAY_MS = 150L
        private const val HOST_WAIT_MS = 3000L

        // Debug log helper, from AppRTCMobile's PeerConnectionClient.java (BSD-3), which splits SDP lines
        // on spaces to read them (preferCodec):
        // https://webrtc.googlesource.com/src/+/refs/heads/main/examples/androidapp/src/org/appspot/apprtc/PeerConnectionClient.java
        // The "typ X" token is the candidate grammar of RFC 8839 section 5.1 (host, srflx, prflx, relay).
        // Only that type is kept: the address, port and ufrag around it identify the network.
        private fun candidateType(line: String): String {
            val words = line.split(' ')
            val at = words.indexOf("typ")
            return if (at < 0) "?" else words.getOrNull(at + 1) ?: "?"
        }

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

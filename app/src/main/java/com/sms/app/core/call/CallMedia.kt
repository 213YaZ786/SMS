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
    private val onSize: (remote: Boolean, width: Int, height: Int) -> Unit = { _, _, _ -> }
) {

    private val factory: PeerConnectionFactory
    private val audio: JavaAudioDeviceModule
    private val pc: PeerConnection
    private val track: AudioTrack
    private val gathered = CompletableDeferred<Unit>()

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
        camera(null)
        runCatching { cameraHelper?.dispose() }
        remoteView.release()
        localView.release()
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
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            (receiver?.track() as? VideoTrack)?.addSink(remoteView)
        }
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

package com.yaz.sms.core.call

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yaz.sms.BuildConfig
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A payload for the page's hash, as the preload sends it (`onAnswer=${btoa(e.data.answer)}`, `acceptCall=${btoa(e.data.offer)}`):
 * base64, then URL-encoded with "=" too (calls-webapp's README, "Integrating").
 * https://raw.githubusercontent.com/deltachat/deltachat-desktop/main/packages/target-electron/static/calls-webapp-preload.js#calls
 *
 * not ported: getAvatar (the page has no picture of the caller: call.js answers "")
 * not ported: endCall (window.close(): the host's close() ends the page; the page's own endCall is a PageMessage)
 * not ported: stopRingtoneAndCleanup (Dialer's ringer rings)
 */
object Calls {
    fun btoa(answer: String): String = URLEncoder.encode(Base64.getEncoder().encodeToString(answer.toByteArray()), "UTF-8")
}

/**
 * What the page tells the host, one JSON object a message: the preload's `postMessage` of startCall and acceptCall
 * (`typeof offerPayload !== 'string'` is refused), endCall (its window.close() is the host's close), and call.js's
 * size, effect and state.
 * https://raw.githubusercontent.com/deltachat/deltachat-desktop/main/packages/target-electron/static/calls-webapp-preload.js#calls
 */
data class PageMessage(val type: String, val data: String? = null, val width: Int = 0, val height: Int = 0) {

    companion object {
        private val types = setOf("startCall", "acceptCall", "endCall", "size", "effect", "state")
        private val states = setOf("new", "connecting", "connected", "disconnected", "failed", "closed")

        /** The message, or null for anything unknown or malformed. */
        fun parse(json: String?): PageMessage? {
            if (json == null || json.length > 201000) return null
            val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            fun text(key: String) = runCatching { message[key]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull }.getOrNull()
            fun number(key: String) = runCatching { message[key]?.jsonPrimitive?.takeIf { !it.isString }?.intOrNull }.getOrNull()
            val type = text("type")?.takeIf { it in types } ?: return null
            return when (type) {
                "startCall", "acceptCall" -> text("payload")?.takeIf { it.isNotEmpty() && it.length <= 200000 }?.let { PageMessage(type, it) }
                "size" -> {
                    val width = number("width")
                    val height = number("height")
                    if (width != null && height != null && width in 1..16384 && height in 1..16384) PageMessage(type, null, width, height) else null
                }
                "effect" -> text("name")?.takeIf { it.length in 1..32 }?.let { PageMessage(type, it) }
                "state" -> text("state")?.takeIf { it in states }?.let { PageMessage(type, it) }
                else -> PageMessage(type)
            }
        }
    }
}

/**
 * The call's media in the system WebView (Vanadium on GrapheneOS), behind the setting callsInWebView: the same
 * contract as [CallMedia], which CallLine holds instead of it. The page is Delta Chat's calls-webapp
 * (assets/call, call.js); the WebView is set up as Element X sets up its call WebView, AGPL-3.0:
 * https://raw.githubusercontent.com/element-hq/element-x-android/develop/features/call/impl/src/main/kotlin/io/element/android/features/call/impl/utils/WebViewWidgetMessageInterceptor.kt#WebViewWidgetMessageInterceptor
 * https://raw.githubusercontent.com/element-hq/element-x-android/develop/features/call/impl/src/main/kotlin/io/element/android/features/call/impl/ui/CallScreenView.kt#CallWebView
 * and the commands to the page are the ones of the preload in deltachat-desktop:
 * https://raw.githubusercontent.com/deltachat/deltachat-desktop/main/packages/target-electron/static/calls-webapp-preload.js#calls
 *
 * Changed from Element X: the page's own origin only (WebViewAssetLoader on assets/call, any other request answered
 * by nothing, the page's CSP allows no other load, any navigation off the origin refused); no file or content access;
 * addWebMessageListener only, for the origin of the page, never addJavascriptInterface; the camera and microphone
 * granted only to that origin; no WebView.onPause, no pauseTimers; the picture drawn by [PageDisplay].
 *
 * not ported: CallWebView (a composable: the WebView is not in a screen of ours but in a presentation, PageDisplay)
 * not ported: interceptedMessages (a SharedFlow for Element Call's widget API: our messages are handled at once)
 * not ported: sendMessage (Element X's widget postMessage: ours go through window.callPage)
 * not ported: postMessage (the JavaScript interface's and the widget's: our page posts to callsHost)
 * not ported: onPageStarted (its console shim and its window "message" listener belong to Element Call's widget API)
 * not ported: safeStringify (the console shim's, same reason)
 * not ported: logFn (the console shim's, same reason)
 * not ported: onReceivedError (Element X shows a dialog: here a page that fails ends the call by the offer's timeout)
 * not ported: onReceivedHttpError (same)
 * not ported: onReceivedSslError (no network page is loaded)
 * not ported: addJavascriptInterface (a JavaScript interface reaches every frame of every origin; only the origin-checked
 *   message listener is used)
 * not ported: JavascriptInterface (same reason)
 * not ported: webViewVersionCode, getCurrentWebViewPackage (Element X's Huawei test)
 * not ported: AndroidView, LocalInspectionMode, update, onRelease (Compose, as CallWebView)
 * not ported: userAgent (the page needs none)
 * not ported: databaseEnabled, domStorageEnabled, loadsImagesAutomatically (the page stores nothing)
 * not ported: addBackHandler, JavascriptBackHandlerBridge, onBackPressed, dispatchEscKeyEvent (the page has no back button)
 * not ported: CallScreenViewPreview, InvalidAudioDeviceDialogPreview, InvalidAudioDeviceDialog (Element X's screens)
 * not ported: mapWebkitPermissions, requestPermissions (SMS already holds RECORD_AUDIO and CAMERA)
 * not ported: Timber (Log, debug only)
 */
class WebCallMedia(
    private val context: Context,
    private val iceJson: String,
    val video: Boolean,
    private val onState: (String) -> Unit,
    /** The size of each picture as it comes, so Dialer frames it without stretching it. */
    private val onSize: (remote: Boolean, width: Int, height: Int) -> Unit = { _, _, _ -> },
    /** A screen effect the other side sent (its name). */
    private val onEffect: (String) -> Unit = {}
) {

    private lateinit var webView: WebView
    private var display: PageDisplay? = null
    private val renderTarget = PageDisplay.RenderTarget(PageDisplay.SHORT, PageDisplay.LONG)
    private val assetLoader: WebViewAssetLoader
    @Volatile private var pending: CompletableDeferred<String>? = null
    @Volatile private var closed = false
    // The camera the page starts with, and where the page is.
    @Volatile private var facing = "user"
    @Volatile private var url = ""

    init {
        // Only assets/call is served, on the origin's root.
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.PathHandler { path -> WebViewAssetLoader.AssetsPathHandler(context).handle("call/$path") })
            .build()
        onMain { build() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            error("This WebView cannot receive the page's messages.")
        }
        // Debug builds only: chrome://inspect.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView = WebView(MutableContextWrapper(context))
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        with(webView.settings) {
            javaScriptEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!isOwnOrigin(Uri.parse(url))) return
                this@WebCallMedia.url = url
                // The relays' meeting points, for window.calls.getIceServers.
                sendMessage("onIceServers(${Json.encodeToString(kotlinx.serialization.serializer<String>(), iceJson)})")
            }

            // Any navigation off the page's origin is refused.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isOwnOrigin(request.url)

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // The microphone, and the camera in a video call, for the page's origin; nothing else.
            override fun onPermissionRequest(request: PermissionRequest) {
                if (!isOwnOrigin(request.origin)) {
                    request.deny()
                    return
                }
                val resources = request.resources.filter {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && video)
                }
                if (resources.isEmpty()) request.deny() else request.grant(resources.toTypedArray())
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (BuildConfig.DEBUG) Log.d(TAG, "page: ${consoleMessage.message()}")
                return true
            }
        }
        // The page's messages: only from the main frame of its own origin (Element X's WebMessageListener,
        // with the origin rule that "*" left open).
        WebViewCompat.addWebMessageListener(webView, LISTENER_NAME, setOf(ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame && isOwnOrigin(sourceOrigin)) onMessageReceived(PageMessage.parse(message.data))
        }
        display = PageDisplay.create(context, webView, renderTarget, PageDisplay.SHORT, PageDisplay.LONG, onSize)
            ?: error("No private display for the call's page.")
        onSize(true, PageDisplay.SHORT, PageDisplay.LONG)
    }

    private fun onMessageReceived(message: PageMessage?) {
        when (message?.type) {
            "startCall", "acceptCall" -> pending?.complete(message.data.orEmpty())
            "endCall" -> if (!closed) onState("closed")
            "size" -> onSize(true, message.width, message.height)
            "effect" -> onEffect(message.data.orEmpty())
            "state" -> if (!closed) onState(message.data.orEmpty())
        }
    }

    /** Our offer for a call we make: the page's #startCall, resumed on window.calls.startCall(sdp). */
    suspend fun offer(): String {
        val result = CompletableDeferred<String>().also { pending = it }
        onMain { webView.loadUrl(pageUrl("startCall")) }
        return withTimeout(PAGE_WAIT_MS) { result.await() }
    }

    /** Our answer to a call we take: the page's #acceptCall=, resumed on window.calls.acceptCall(sdp). */
    suspend fun answer(offer: String): String {
        val result = CompletableDeferred<String>().also { pending = it }
        onMain { webView.loadUrl(pageUrl("acceptCall=" + Calls.btoa(offer))) }
        return withTimeout(PAGE_WAIT_MS) { result.await() }
    }

    /** The other side took our call: #onAnswer= on the page already loaded (the preload's location.hash). */
    suspend fun accepted(answer: String) {
        onMain { webView.evaluateJavascript("window.location.hash = 'onAnswer=${Calls.btoa(answer)}'", null) }
    }

    fun mute(on: Boolean) = sendMessage("mute($on)")

    /** A screen effect to the other side, by its name. */
    fun sendEffect(name: String) = sendMessage("sendEffect(${Json.encodeToString(kotlinx.serialization.serializer<String>(), name)})")

    /**
     * The camera Dialer chose (an id of Android's camera list), or none. Front or back, as getUserMedia's
     * facingMode; the page swaps its video track (call.js, camera). Before the page loads, it only starts with it.
     */
    fun camera(id: String?) {
        val face = id?.let {
            runCatching {
                val lens = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING)
                if (lens == CameraCharacteristics.LENS_FACING_BACK) "environment" else "user"
            }.getOrDefault("user")
        }
        if (face != null) facing = face
        if (url.isNotEmpty()) sendMessage("camera(${if (face == null) "null" else "'$face'"})")
    }

    /** Where the other side's picture goes, in Dialer's screen: the page's display draws into it. */
    fun showRemote(surface: Surface?) {
        onMain { display?.showRemote(surface) }
    }

    /**
     * Dialer's preview Surface is not used: the page draws our own camera itself, small and mirrored inside the other
     * side's picture. Dialer's session clears its preview and its swap for these calls.
     */
    fun showLocal(surface: Surface?) = Unit

    /** The device turned (Telecom's onSetDeviceOrientation): the display is resized as Flutter's resize31. */
    fun deviceOrientation(degrees: Int) {
        onMain { display?.setDeviceOrientation(degrees) }
    }

    fun close() {
        if (closed) return
        closed = true
        pending?.cancel()
        runCatching {
            onMain {
                display?.dispose()
                display = null
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
    }

    /** window.callPage.<script> in the page (WebView calls run on the main thread). */
    private fun sendMessage(script: String) {
        if (closed) return
        runCatching { onMain { webView.evaluateJavascript("window.callPage.$script", null) } }
    }

    /** The README's flags: no video at all for an audio call. */
    private fun pageUrl(message: String): String {
        val flags = (if (video) "" else "disableVideoCompletely&") + "facing=$facing"
        return "$ORIGIN/index.html?$flags#$message"
    }

    private fun <T> onMain(message: () -> T): T =
        if (Looper.myLooper() == Looper.getMainLooper()) message()
        else runBlocking(Dispatchers.Main) { message() }

    companion object {
        private const val TAG = "WebCallMedia"
        /** The name of the object the page posts to (call.js, callsHost). */
        const val LISTENER_NAME = "callsHost"
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        private const val PAGE_WAIT_MS = 60000L

        /** The page's origin, and no other. */
        fun isOwnOrigin(uri: Uri?): Boolean =
            uri != null && uri.scheme == "https" && uri.host == HOST && uri.port == -1
    }
}

package com.sms.app.feature.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.telecom.DisconnectCause
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sms.app.core.call.CallBook
import com.sms.app.core.call.CallService
import com.sms.app.core.call.ChatCall
import com.sms.app.core.call.Phase
import com.sms.app.core.call.Ringer
import com.sms.app.core.chat.CallSignal
import com.sms.app.core.chat.RichChat
import com.sms.app.core.dial.ContactLookup
import com.sms.app.core.dial.Numbers
import com.sms.app.ui.component.ContactAvatar
import com.sms.app.ui.component.FloatingPane
import com.sms.app.ui.component.rememberHaptics
import com.sms.app.ui.icon.AppIcons
import com.sms.app.ui.theme.AppSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.android.ext.android.inject

/**
 * The encrypted call: the other side's video or face, and glass controls
 * over it. The media run in a page of this app's own (assets/call.html)
 * in a WebView locked down to that page alone.
 */
class CallActivity : ComponentActivity() {

    private val chat: RichChat by inject()
    private var web: WebView? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private val level = mutableFloatStateOf(0f)
    @Volatile private var lastState = ""

    private val askMedia = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) go() else {
            Toast.makeText(this, "The call needs the microphone.", Toast.LENGTH_LONG).show()
            CallBook.connection?.hangUp(DisconnectCause.LOCAL) ?: CallBook.set(null)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (intent.action == ACTION_OUTGOING) {
            val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
            val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
            if (phone.isBlank() || !CallBook.place(this, phone, video)) {
                Toast.makeText(this, "The call cannot be made now.", Toast.LENGTH_LONG).show()
                CallBook.set(null)
                finish()
                return
            }
            withMedia()
        } else if (CallBook.call.value == null) {
            finish()
            return
        } else if (intent.action == ACTION_ANSWER) {
            answer()
        }

        // What the chat says of this call.
        lifecycleScope.launch {
            chat.calls.collect { signal ->
                val id = CallBook.call.value?.msgId ?: return@collect
                when (signal) {
                    is CallSignal.Accepted -> if (signal.msgId == id) js("accepted(${JSONObject.quote(signal.answer)})")
                    is CallSignal.Ended -> if (signal.msgId == id) CallBook.connection?.finish(DisconnectCause.REMOTE) ?: CallBook.end()
                    is CallSignal.TakenElsewhere -> if (signal.msgId == id) CallBook.connection?.finish(DisconnectCause.ANSWERED_ELSEWHERE) ?: CallBook.end()
                    else -> Unit
                }
            }
        }
        lifecycleScope.launch {
            CallBook.call.collect { call ->
                if (call == null || call.phase == Phase.ENDED) {
                    js("hangup()")
                    delay(900)
                    CallBook.set(null)
                    finish()
                }
            }
        }

        setContent {
            AppSurface {
                val call by CallBook.call.collectAsState()
                call?.let { CallScreen(it, level.floatValue, ::answer, ::control) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_ANSWER) answer()
    }

    /** The microphone (and camera for video) granted, then the media start. */
    private fun withMedia() {
        val call = CallBook.call.value ?: return
        val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (call.video) Manifest.permission.CAMERA else null)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) go() else askMedia.launch(needed.toTypedArray())
    }

    private fun answer() {
        val call = CallBook.call.value ?: return
        if (!call.incoming || call.phase != Phase.RINGING) return
        Ringer.stop()
        CallBook.update { it.copy(phase = Phase.CONNECTING) }
        withMedia()
    }

    /** The call's media start in the page: an offer for a call made, an answer for one taken. */
    private fun go() {
        val call = CallBook.call.value ?: return
        runCatching { ContextCompat.startForegroundService(this, Intent(this, CallService::class.java)) }
        lifecycleScope.launch {
            val ice = JSONObject.quote(chat.iceServers())
            val start = if (call.incoming) "answer($ice, ${JSONObject.quote(call.offer.orEmpty())}, ${call.video})" else "call($ice, ${call.video})"
            whenReady { js(start) }
        }
    }

    private fun whenReady(action: () -> Unit) {
        if (ready) action() else pending = action
    }

    private fun js(code: String) {
        runOnUiThread { web?.evaluateJavascript(code, null) }
    }

    private fun control(what: String, on: Boolean) {
        when (what) {
            "mute" -> {
                CallBook.update { it.copy(muted = on) }
                js("mute($on)")
            }
            "camera" -> {
                CallBook.update { it.copy(cameraOn = on) }
                js("camera($on)")
            }
            "flip" -> js("flip()")
            "speaker" -> {
                CallBook.update { it.copy(speaker = on) }
                CallBook.connection?.speaker(on)
            }
            "hangup" -> CallBook.connection?.hangUp(if (CallBook.call.value?.phase == Phase.RINGING) DisconnectCause.REJECTED else DisconnectCause.LOCAL)
                ?: CallBook.end()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun makeWeb(context: Context): WebView = WebView(context).apply {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webViewClient = object : WebViewClient() {
            // Nowhere to go from the page, nothing to load from anywhere.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                // The page itself loads; any other request is refused.
                val url = request?.url?.toString().orEmpty()
                if (request?.isForMainFrame == true && (url.startsWith("data:") || url == BASE)) return null
                return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
            }
        }
        webChromeClient = object : WebChromeClient() {
            // Debug builds only: the page's console, to see a call fail. Release builds say nothing.
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                if (com.sms.app.BuildConfig.DEBUG) android.util.Log.d("CallPage", message?.message().orEmpty())
                return true
            }
            // Only this page, only the microphone and camera, only during a call.
            override fun onPermissionRequest(request: PermissionRequest) {
                val allowed = setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val asked = request.resources.filter { it in allowed }
                if (request.origin.toString().startsWith(BASE) && CallBook.call.value != null && asked.isNotEmpty()) request.grant(asked.toTypedArray())
                else request.deny()
            }
        }
        addJavascriptInterface(Bridge(), "Native")
        val page = context.assets.open("call.html").bufferedReader().use { it.readText() }
        loadDataWithBaseURL(BASE, page, "text/html", "utf-8", null)
        web = this
    }

    override fun onDestroy() {
        web?.run {
            evaluateJavascript("hangup()", null)
            removeJavascriptInterface("Native")
            destroy()
        }
        web = null
        super.onDestroy()
    }

    /** What the page may tell the app, and nothing else; each piece checked. */
    private inner class Bridge {
        @JavascriptInterface
        fun ready() = runOnUiThread {
            ready = true
            pending?.invoke()
            pending = null
        }

        @JavascriptInterface
        fun offer(sdp: String) {
            if (sdp.length > MAX_SDP) return
            val call = CallBook.call.value ?: return
            lifecycleScope.launch {
                val id = chat.placeCall(call.phone, sdp, call.video)
                if (id == null) CallBook.connection?.finish(DisconnectCause.ERROR) ?: CallBook.end()
                else CallBook.update { it.copy(msgId = id) }
            }
        }

        @JavascriptInterface
        fun answer(sdp: String) {
            if (sdp.length > MAX_SDP) return
            val id = CallBook.call.value?.msgId ?: return
            lifecycleScope.launch {
                if (chat.acceptCall(id, sdp)) CallBook.connection?.setActive()
                else CallBook.connection?.finish(DisconnectCause.ERROR) ?: CallBook.end()
            }
        }

        @JavascriptInterface
        fun state(state: String) = runOnUiThread {
            lastState = state
            when (state) {
                "connected" -> {
                    CallBook.connection?.setActive()
                    CallBook.update { if (it.since == 0L) it.copy(phase = Phase.ACTIVE, since = System.currentTimeMillis()) else it }
                }
                "failed", "closed" -> CallBook.connection?.hangUp(DisconnectCause.ERROR) ?: CallBook.end()
                // The other side gone: a short wait for the line to come back, then the call ends.
                "disconnected" -> lifecycleScope.launch {
                    delay(4000)
                    if (lastState == "disconnected") CallBook.connection?.hangUp(DisconnectCause.REMOTE) ?: CallBook.end()
                }
            }
        }

        @JavascriptInterface
        fun level(value: Float) {
            level.floatValue = value.coerceIn(0f, 1f)
        }

        @JavascriptInterface
        fun error(message: String) = runOnUiThread {
            if (com.sms.app.BuildConfig.DEBUG) android.util.Log.d("CallPage", "error: ${message.take(200)}")
            CallBook.connection?.hangUp(DisconnectCause.ERROR) ?: CallBook.end()
        }
    }

    companion object {
        const val ACTION_SHOW = "com.sms.app.call.SHOW"
        const val ACTION_ANSWER = "com.sms.app.call.ANSWER"
        const val ACTION_OUTGOING = "com.sms.app.call.OUTGOING"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_VIDEO = "video"
        /** The page's own made-up origin: secure, so the browser lets it use the microphone. */
        const val BASE = "https://call.sms.invalid/"
        private const val MAX_SDP = 64 * 1024

        fun start(context: Context, phone: String, video: Boolean) {
            context.startActivity(
                Intent(context, CallActivity::class.java).setAction(ACTION_OUTGOING)
                    .putExtra(EXTRA_PHONE, phone).putExtra(EXTRA_VIDEO, video)
            )
        }
    }
}

private val Answer = Color(0xFF2E9E5B)
private val HangUp = Color(0xFFD93F3F)

/**
 * The call's screen. Audio: the face, and rings leaving it as the other
 * side speaks. Video: the other side full screen, oneself in a corner to
 * move with a finger; the controls float in glass over it.
 */
@Composable
private fun CallScreen(
    call: ChatCall,
    level: Float,
    onAnswer: () -> Unit,
    control: (String, Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as CallActivity
    val haptics = rememberHaptics()
    val name = remember(call.phone) { ContactLookup.nameOf(context, call.phone) ?: Numbers.format(context, call.phone) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { activity.makeWeb(it) }, modifier = Modifier.fillMaxSize())

        if (!call.video || call.phase != Phase.ACTIVE) {
            VoiceFace(name, level, call.phase == Phase.ACTIVE, Modifier.align(Alignment.Center))
        }

        // Who, and how the call stands.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
        ) {
            FloatingPane(shape = CircleShape) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
            Spacer(Modifier.height(8.dp))
            FloatingPane(shape = CircleShape) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(AppIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(statusOf(call), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)
        ) {
            if (call.incoming && call.phase == Phase.RINGING) {
                RoundButton(AppIcons.CallEnd, "Decline", HangUp, 72) {
                    haptics.reject()
                    control("hangup", true)
                }
                Spacer(Modifier.width(48.dp))
                RoundButton(if (call.video) AppIcons.Videocam else AppIcons.Call, "Answer", Answer, 72) {
                    haptics.firm()
                    onAnswer()
                }
            } else {
                RoundButton(if (call.muted) AppIcons.MicOff else AppIcons.Mic, if (call.muted) "Unmute" else "Mute", if (call.muted) MaterialTheme.colorScheme.primary else null) {
                    haptics.tick()
                    control("mute", !call.muted)
                }
                if (call.video) {
                    RoundButton(if (call.cameraOn) AppIcons.Videocam else AppIcons.VideocamOff, if (call.cameraOn) "Camera off" else "Camera on", null) {
                        haptics.tick()
                        control("camera", !call.cameraOn)
                    }
                    RoundButton(AppIcons.CameraSwitch, "Switch camera", null) {
                        haptics.tick()
                        control("flip", true)
                    }
                }
                RoundButton(AppIcons.Speaker, if (call.speaker) "Earpiece" else "Speaker", if (call.speaker) MaterialTheme.colorScheme.primary else null) {
                    haptics.tick()
                    control("speaker", !call.speaker)
                }
                RoundButton(AppIcons.CallEnd, "Hang up", HangUp, 64) {
                    haptics.reject()
                    control("hangup", true)
                }
            }
        }
    }
}

@Composable
private fun statusOf(call: ChatCall): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase == Phase.ACTIVE) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val kind = if (call.video) "Encrypted video call" else "Encrypted call"
    return when (call.phase) {
        Phase.RINGING -> if (call.incoming) kind else "$kind · ringing"
        Phase.CONNECTING -> "$kind · connecting"
        Phase.ACTIVE -> {
            val s = ((now - call.since) / 1000).coerceAtLeast(0)
            "$kind · %d:%02d".format(s / 60, s % 60)
        }
        Phase.ENDED -> "Call ended"
    }
}

/** The face, and rings leaving it as the other side speaks (or slowly while it rings). */
@Composable
private fun VoiceFace(name: String, level: Float, live: Boolean, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val loudness by animateFloatAsState(level, tween(120), label = "voice")
    val waves = rememberInfiniteTransition(label = "waves")
    val t by waves.animateFloat(0f, 1f, infiniteRepeatable(tween(if (live) 1600 else 2400)), label = "t")
    Box(modifier.size(320.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = 66.dp.toPx()
            val strength = if (live) 0.25f + loudness * 1.2f else 0.35f
            for (i in 0 until 2) {
                val p = (t + i * 0.5f) % 1f
                drawCircle(
                    accent.copy(alpha = (1f - p) * 0.55f * strength.coerceAtMost(1f)),
                    radius = base + p * base * (0.6f + strength),
                    style = Stroke(2.dp.toPx())
                )
            }
        }
        ContactAvatar(name, null, 120.dp)
    }
}

/**
 * A round control in glass. Answer, decline and hang up are filled with
 * their colour, their icon white; the others take the accent when on.
 */
@Composable
private fun RoundButton(icon: ImageVector, label: String, tint: Color?, size: Int = 60, onClick: () -> Unit) {
    val filled = tint == Answer || tint == HangUp
    FloatingPane(shape = CircleShape, onClick = onClick, modifier = Modifier.size(size.dp)) {
        Box(
            Modifier.size(size.dp).then(if (filled) Modifier.background(tint!!.copy(alpha = 0.88f), CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = if (filled) Color.White else tint ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.size((size * 0.42f).dp))
        }
    }
}

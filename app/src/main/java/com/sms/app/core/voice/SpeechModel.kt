package com.sms.app.core.voice

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The speech model whisper.cpp reads: OpenAI's Whisper (MIT), as published
 * for whisper.cpp by its author. Not in the app: fetched once, only when
 * the user asks, from a pinned revision, and kept only when its SHA-256 is
 * the one written here. A newer model comes with an update of the app,
 * which then offers it and lets the old one go.
 */
class SpeechModel(private val context: Context, private val scope: CoroutineScope, private val settings: com.sms.app.data.settings.SettingsStore) {

    /** One model the app knows, pinned. */
    data class Pin(val name: String, val label: String, val quality: String, val bytes: Long, val sha256: String) {
        val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$REVISION/$name"
    }

    sealed interface State {
        data object Missing : State
        data class Fetching(val done: Float) : State
        data object Ready : State
        data object Failed : State
    }

    private val dir = File(context.filesDir, "speech")

    /** Small for phones with room to run it, base for the others. */
    val recommended: Pin = run {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (memory.totalMem >= 5L * 1024 * 1024 * 1024) SMALL else BASE
    }

    /** The user's choice, else the recommended one. */
    val pin: Pin get() = PINS.firstOrNull { it.name == settings.current.speechModel } ?: recommended

    /** Another model chosen: fetched when asked, the other one goes once it is there. */
    fun choose(choice: Pin) {
        if (choice == pin) return
        job?.cancel()
        settings.update { it.copy(speechModel = choice.name) }
        _state.value = if (file.exists()) State.Ready else State.Missing
    }

    val file: File get() = File(dir, pin.name)

    private val _state = MutableStateFlow<State>(if (file.exists()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    init {
        // A model this version no longer knows (an update pinned another) and broken fetches go.
        scope.launch(Dispatchers.IO) { dir.listFiles()?.filter { f -> PINS.none { it.name == f.name } }?.forEach { it.delete() } }
    }

    /** Over a connection that costs by the megabyte (mobile data), the user is told first. */
    fun metered(): Boolean = runCatching { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)

    /** Fetches the model, checked before it is kept. */
    fun fetch() {
        if (_state.value is State.Fetching || _state.value == State.Ready) return
        _state.value = State.Fetching(0f)
        job = scope.launch(Dispatchers.IO) {
            dir.mkdirs()
            val part = File(dir, pin.name + ".part")
            val ok = runCatching {
                val connection = (URL(pin.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                try {
                    check(connection.responseCode == 200)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var read = 0L
                    var shown = -1
                    connection.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(256 * 1024)
                            while (isActive) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                read += n
                                check(read <= pin.bytes)
                                digest.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                                val percent = (read * 100 / pin.bytes).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    _state.value = State.Fetching(read.toFloat() / pin.bytes)
                                }
                            }
                        }
                    }
                    val sum = digest.digest().joinToString("") { "%02x".format(it) }
                    read == pin.bytes && sum == pin.sha256
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (ok && part.renameTo(file)) {
                dir.listFiles()?.filter { it.name != pin.name }?.forEach { it.delete() }
                _state.value = State.Ready
            } else {
                part.delete()
                _state.value = State.Failed
            }
        }
    }

    fun cancel() {
        job?.cancel()
        File(dir, pin.name + ".part").delete()
        _state.value = State.Missing
    }

    /** Frees the room: the model goes, fetched again when asked. */
    fun remove() {
        cancel()
        file.delete()
    }

    companion object {
        /** The revision of ggerganov/whisper.cpp on Hugging Face the sums below belong to. */
        const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
        val SMALL = Pin("ggml-small-q5_1.bin", "Small", "More accurate", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb")
        val BASE = Pin("ggml-base-q5_1.bin", "Base", "Quicker", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898")
        val PINS = listOf(SMALL, BASE)
    }
}

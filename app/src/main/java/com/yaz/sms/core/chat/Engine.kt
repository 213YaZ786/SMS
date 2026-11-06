package com.yaz.sms.core.chat

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Something the engine reports: a message in, a message read, a reaction. */
data class ChatEvent(val accountId: Int, val kind: String, val data: JsonObject)

class RpcException(message: String) : Exception(message)

/**
 * The chat engine, chatmail core's deltachat-rpc-server (MPL-2.0), run as
 * a program from where Android unpacked it, spoken to in JSON-RPC over
 * its standard input and output. Nothing it writes goes to any log.
 */
class Engine(private val context: Context, private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var pump: Job? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val ids = AtomicInteger()

    /**
     * The events kept for the one reader (RichChat), in order. Delta Chat's DcEventCenter.handleEvent passes
     * every event on to its observers; here the log events nobody reads are not queued (LOG_KINDS) and the
     * buffer is large, so the ones that matter (IncomingMsg, MsgDeleted, MsgDelivered, MsgFailed) are not
     * pushed out. If a burst still overflows it, the oldest goes and [lost] counts it: the reader is told by an
     * EventChannelOverflow event (the engine's own, jsonrpc types/events.rs EventChannelOverflow { n }) and
     * catches up from the engine's database.
     * https://github.com/deltachat/deltachat-android/blob/main/src/main/java/org/thoughtcrime/securesms/connect/DcEventCenter.java#handleEvent
     * not ported: addObserver, addMultiAccountObserver, removeObserver, removeObservers, sendToObservers (the
     * flow below is the one observer list), captureNextError, endCaptureNextError (no toast to hold back),
     * handleLogging (nothing the engine says is logged here: it may carry addresses).
     */
    private val lost = AtomicLong()
    private val queue = Channel<ChatEvent>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST) { lost.incrementAndGet() }
    val events: Flow<ChatEvent> = queue.receiveAsFlow()

    val running: Boolean get() = process?.isAlive == true

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val program = File(context.applicationInfo.nativeLibraryDir, "libchatmail.so")
        if (!program.exists()) return false
        val started = runCatching {
            ProcessBuilder(program.path).apply {
                environment()["DC_ACCOUNTS_PATH"] = File(context.filesDir, "chat").path
                environment()["RUST_LOG"] = "off"
            }.start()
        }.getOrNull() ?: return false
        process = started
        writer = started.outputStream.bufferedWriter()
        // Its error output is read and dropped: it may carry addresses.
        Thread { runCatching { val drop = ByteArray(8192); while (started.errorStream.read(drop) >= 0) Unit } }.apply { isDaemon = true }.start()
        Thread { read(started.inputStream.bufferedReader()) }.apply { isDaemon = true }.start()
        pump = scope.launch(Dispatchers.IO) {
            while (isActive && running) {
                val event = runCatching { call("get_next_event") }.getOrNull() as? JsonObject
                // A failed call is retried after a pause, never in a loop that holds a core.
                if (event == null) { kotlinx.coroutines.delay(2000); continue }
                val payload = event["event"] as? JsonObject ?: continue
                val kind = payload["kind"]?.jsonPrimitive?.content ?: continue
                // Info, Imap*, Smtp*, Warning and the blob events are the engine's log: not queued.
                if (kind in LOG_KINDS) continue
                queue.trySend(ChatEvent(event["contextId"]?.jsonPrimitive?.int ?: 0, kind, payload))
                // Something was pushed out of the queue: the reader is told, as the engine tells us when its own channel overflows.
                val skipped = lost.getAndSet(0)
                if (skipped > 0) {
                    queue.trySend(ChatEvent(0, OVERFLOW, buildJsonObject { put("kind", OVERFLOW); put("n", skipped) }))
                }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        pump?.cancel()
        runCatching { writer?.close() }
        process?.destroy()
        process = null
        pending.values.forEach { it.completeExceptionally(RpcException("stopped")) }
        pending.clear()
    }

    /** Calls [method] with [params] and returns its result. */
    suspend fun call(method: String, vararg params: Any?): JsonElement = withContext(Dispatchers.IO) {
        if (!running && !start()) throw RpcException("engine not available")
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonElement>()
        pending[id] = answer
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", JsonArray(params.map(::element)))
        }
        synchronized(this@Engine) {
            val out = writer ?: throw RpcException("engine not available")
            out.write(request.toString())
            out.newLine()
            out.flush()
        }
        // Waiting for the next event may take long; anything else answers quickly.
        if (method == "get_next_event") answer.await() else withTimeout(120_000) { answer.await() }
    }

    private fun read(reader: BufferedReader) {
        runCatching {
            while (true) {
                val line = reader.readLine() ?: break
                val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val id = message["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                val waiting = pending.remove(id) ?: continue
                val error = message["error"] as? JsonObject
                if (error != null) {
                    waiting.completeExceptionally(RpcException(error["message"]?.jsonPrimitive?.content ?: "error"))
                } else {
                    waiting.complete(message["result"] ?: JsonNull)
                }
            }
        }
        pending.values.forEach { it.completeExceptionally(RpcException("engine stopped")) }
        pending.clear()
    }

    companion object {
        /** Room for a long burst: a thread of messages arriving after days away. */
        private const val EVENT_BUFFER = 2048
        const val OVERFLOW = "EventChannelOverflow"
        /** The engine's log events, jsonrpc types/events.rs: nothing here reads them. */
        private val LOG_KINDS = setOf(
            "Info", "Warning", "Error", "SmtpConnected", "ImapConnected", "SmtpMessageSent",
            "ImapMessageDeleted", "ImapInboxIdle", "NewBlobFile", "DeletedBlobFile"
        )
    }

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::element))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to element(it.value) })
        else -> JsonPrimitive(value.toString())
    }
}

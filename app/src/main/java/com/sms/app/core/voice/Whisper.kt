package com.sms.app.core.voice

/** whisper.cpp, built with the app for 64-bit ARM phones. */
internal object Whisper {

    /** Whether the engine is in this build for this phone. */
    val loaded: Boolean = runCatching { System.loadLibrary("smswhisper"); true }.getOrDefault(false)

    /** A model opened from [model], the CPU variants found in [libDir]; 0 when it cannot be read. */
    @JvmStatic external fun open(libDir: String, model: String): Long

    /** The words in [pcm] (16 kHz mono, -1 to 1) as UTF-8, in whatever language they are spoken. */
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, threads: Int): ByteArray?

    @JvmStatic external fun close(handle: Long)
}

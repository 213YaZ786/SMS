package com.yaz.sms.core.voice

/**
 * whisper.cpp (MIT, https://github.com/ggml-org/whisper.cpp) and the Silero voice detector that ships with it, built
 * with the app for 64-bit phones (arm64-v8a, and x86_64 for computers' emulators). The methods are the app's own
 * bridge (cpp/whisper_jni.cpp), registered when the library loads. From SMS's core/voice/Whisper.kt.
 */
object Whisper {

    /**
     * A word with the time it begins and ends, in milliseconds from the start of the sound written out: what a segment
     * of whisper.h holds (text, whisper_full_get_segment_t0 and t1, counted in 10 ms and brought to ms here)
     * https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/include/whisper.h
     */
    data class Word(val text: String, val startMs: Long, val endMs: Long)

    /** Whether the engine is in this build for this phone. */
    val loaded: Boolean = runCatching { System.loadLibrary("smswhisper"); true }.getOrDefault(false)

    /** A model opened from [model], the CPU variants found in [libDir]; 0 when it cannot be read. */
    @JvmStatic external fun open(libDir: String, model: String): Long

    /**
     * The words in [pcm] (16 kHz mono, -1 to 1) as UTF-8, in [language] (a code whisper knows, else "auto": found from
     * the sound itself), never translated. [live]: dictation, which leaves out sound effects written as words.
     * [prompt]: words the speech likely holds, whisper's initial prompt (cli.cpp's --prompt), or null.
     */
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, threads: Int, language: String, live: Boolean, prompt: String?): ByteArray?

    /** The words in [pcm], in whatever language they are spoken (SMS's voice messages). */
    fun transcribe(handle: Long, pcm: FloatArray, threads: Int): ByteArray? = transcribe(handle, pcm, threads, "auto", false, null)

    /**
     * The same, word by word: UTF-8 lines "t0<TAB>t1<TAB>text<LF>", one per word, t0 and t1 in milliseconds from the
     * start of [pcm] (parse them with [parseWords]). whisper.cpp's word-level timestamps, cli.cpp's -ml 1
     * https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/cli/cli.cpp (experimental in whisper.cpp, README).
     */
    @JvmStatic external fun transcribeWords(handle: Long, pcm: FloatArray, threads: Int, language: String, live: Boolean, prompt: String?): ByteArray?

    /**
     * The words of a [transcribeWords] answer, trimmed, empty ones dropped. A piece of punctuation alone (a segment
     * of its own: the README's -ml 1 listing shows a lone ",") is joined to the word before it, as the Android
     * standard wants a word with its adjacent punctuation (RecognitionPart)
     * https://developer.android.com/reference/android/speech/RecognitionPart
     * A line that does not read is skipped; an end before its start is brought to the start.
     */
    fun parseWords(bytes: ByteArray): List<Word> {
        val words = ArrayList<Word>()
        for (line in String(bytes, Charsets.UTF_8).split('\n')) {
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) continue
            val start = parts[0].toLongOrNull() ?: continue
            val end = (parts[1].toLongOrNull() ?: continue).coerceAtLeast(start)
            val text = parts[2].trim()
            if (text.isEmpty()) continue
            val last = words.lastOrNull()
            if (last != null && text.none { it.isLetterOrDigit() }) {
                words[words.size - 1] = Word(last.text + text, last.startMs, maxOf(last.endMs, end))
            } else {
                words += Word(text, start, end)
            }
        }
        return words
    }

    /**
     * The language heard in the last sound written out with [handle] (its code, "fr"), or null: cli.cpp's
     * whisper_lang_str(whisper_full_lang_id(ctx)) https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/cli/cli.cpp
     */
    @JvmStatic external fun heardLanguage(handle: Long): String?

    @JvmStatic external fun close(handle: Long)

    /** Stops the work in hand at its next step (a dictation ended); [on] false lets the next one run. */
    @JvmStatic external fun abort(on: Boolean)

    /** Whether whisper knows [language] (its two-letter code, "fr"). */
    @JvmStatic external fun knows(language: String): Boolean

    /** The voice detector opened from [model] (Silero, ggml form); 0 when it cannot be read. */
    @JvmStatic external fun vadOpen(libDir: String, model: String): Long

    /** The probability of speech in each 512-sample window of [pcm] (16 kHz mono), the detector keeping its memory. */
    @JvmStatic external fun vadProbs(handle: Long, pcm: FloatArray): FloatArray?

    /** The detector's memory cleared, between two utterances. */
    @JvmStatic external fun vadReset(handle: Long)

    @JvmStatic external fun vadClose(handle: Long)
}

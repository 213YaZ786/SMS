package com.yaz.sms.data.settings

import android.content.Context
import com.yaz.sms.core.common.writeTextAtomically
import com.yaz.sms.core.spam.ComplementNaiveBayes
import com.yaz.sms.core.spam.SpamFilter
import com.yaz.sms.data.sms.Box
import com.yaz.sms.data.sms.Message
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A message the user labelled: SpamBlocker's BayesSample (db/BayesTable.kt), its text, its class and its hash. */
@Serializable
data class SpamSample(val hash: Int, val spam: Boolean, val text: String)

/**
 * What the file holds: the labelled samples, oldest first, and the senders reported (true) or
 * cleared (false) by SpamJudge.senderKey.
 */
@Serializable
data class SpamFile(val samples: List<SpamSample> = emptyList(), val senders: Map<String, Boolean> = emptyMap())

/**
 * The rules of the spam file, pure. The samples are SpamBlocker's BayesTable (MIT,
 * https://github.com/aj3423/SpamBlocker, app/src/main/java/spam/blocker/db/BayesTable.kt:
 * a hash of the message's time and text, its content, its class; findByHash) and the
 * labelling is LocalAI.kt's updateCategory (ui/setting/quick/LocalAI.kt: a message with no record
 * is added, one with a record of the other class is changed).
 * Capped as StarStore.Stars are, so the file stays small: the newest samples are kept.
 */
object Spams {
    /** The most samples kept; the file stays under about 400 KB. */
    const val MAX_SAMPLES = 1000

    /** The most characters of a message kept: the start of a text says what it is. */
    const val MAX_TEXT = 400

    /** The most senders kept. */
    const val MAX_SENDERS = 5000

    /** A key is a name or the last nine digits of a number: short, and never empty. */
    private const val KEY_MAX = 64

    /** The most received messages of a conversation taken when it is reported. */
    const val MAX_PER_REPORT = 30

    /** SpamBlocker's bayesSampleHash (db/BayesTable.kt): the time and the text, hashed together. */
    fun hash(date: Long, text: String): Int = "${date}_$text".hashCode()

    /** The received messages worth learning from: words, newest [MAX_PER_REPORT], as (hash, text). */
    fun received(messages: List<Message>): List<Pair<Int, String>> =
        messages.filter { it.box == Box.RECEIVED && it.body.isNotBlank() }
            .takeLast(MAX_PER_REPORT)
            .map { hash(it.date, it.body) to it.body.take(MAX_TEXT) }

    /**
     * LocalAI.kt's updateCategory for one message: absent, it is added; present in the other
     * class, it is changed in place; present in this class, nothing moves. Past the cap the
     * oldest sample goes.
     */
    fun label(samples: List<SpamSample>, hash: Int, text: String, spam: Boolean): List<SpamSample> {
        if (text.isBlank()) return samples
        val i = samples.indexOfFirst { it.hash == hash }
        if (i >= 0) return if (samples[i].spam == spam) samples else samples.toMutableList().also { it[i] = it[i].copy(spam = spam) }
        val added = samples + SpamSample(hash, spam, text.take(MAX_TEXT))
        return if (added.size > MAX_SAMPLES) added.takeLast(MAX_SAMPLES) else added
    }

    /** The word on a sender: reported, cleared, or (null) forgotten. A key that is no key leaves it as it is. */
    fun sender(senders: Map<String, Boolean>, key: String, spam: Boolean?): Map<String, Boolean> = when {
        key.isBlank() || key.length > KEY_MAX -> senders
        spam == null -> senders - key
        senders[key] == spam -> senders
        key !in senders && senders.size >= MAX_SENDERS -> senders
        else -> senders + (key to spam)
    }

    /** A file read back: what is no sample or no key is dropped, the counts capped. */
    fun parse(text: String, json: Json): SpamFile = runCatching {
        val file = json.decodeFromString<SpamFile>(text)
        SpamFile(
            file.samples.filter { it.text.isNotBlank() }.map { it.copy(text = it.text.take(MAX_TEXT)) }.takeLast(MAX_SAMPLES),
            file.senders.filter { (k, _) -> k.isNotBlank() && k.length <= KEY_MAX }.entries.take(MAX_SENDERS).associate { it.key to it.value }
        )
    }.getOrDefault(SpamFile())

    /**
     * The classifier trained on the samples: SpamBlocker's TrainingDialog.trainCNB
     * (ui/setting/quick/LocalAI.kt: a fresh model trained on the whole table).
     */
    fun train(samples: List<SpamSample>): ComplementNaiveBayes =
        ComplementNaiveBayes().also { cnb -> cnb.train(samples.map { it.text to it.spam }) }
}

/**
 * The spam file: what the user reported and cleared, and the messages that taught the
 * classifier, in a small file of their own written atomically, the way StarStore keeps the
 * starred messages (data/settings/StarStore.kt: a StateFlow over the loaded file, the write made
 * beside it then moved over it by core/common/AtomicWrite.kt). Nothing here leaves the device.
 */
class SpamStore(context: Context) {

    private val file = File(context.filesDir, "spam.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _data = MutableStateFlow(load())

    /** The samples and the senders. */
    val data: StateFlow<SpamFile> = _data.asStateFlow()

    private val training = Any()
    private var built: Pair<SpamFile, SpamFilter>? = null
    private var trained: Pair<List<SpamSample>, com.yaz.sms.core.spam.ComplementNaiveBayes>? = null
    private var plain: SpamFilter? = null

    private fun load(): SpamFile =
        if (!file.exists()) SpamFile() else Spams.parse(runCatching { file.readText() }.getOrDefault(""), json)

    /** The write of StarStore.update: the new value in the flow, then the file replaced at once. */
    @Synchronized
    private fun update(transform: (SpamFile) -> SpamFile) {
        val updated = transform(_data.value)
        if (updated == _data.value) return
        _data.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(SpamFile.serializer(), updated)) }
    }

    /**
     * The filter for [of] (the model trained on its samples, kept until they change): a few
     * milliseconds to a few tenths of a second, so off the main thread. With [withModel] false
     * only the senders answer, which costs nothing.
     */
    fun filter(of: SpamFile = _data.value, withModel: Boolean = true): SpamFilter {
        val bare = bare(of.senders)
        if (!withModel || of.samples.isEmpty()) return bare
        // The model is trained once for a set of samples (SpamBlocker's LocalAI.kt trainCNB, kept as
        // its screen keeps its `cnb` in a remember), under its own lock: a read of the senders
        // never waits for it.
        synchronized(training) {
            val last = built
            if (last != null && last.first == of) return last.second
            val model = trained?.takeIf { it.first == of.samples }?.second ?: Spams.train(of.samples).also { trained = of.samples to it }
            return SpamFilter(of.senders, model).also { built = of to it }
        }
    }

    /**
     * The filter of the senders alone, the same object while they do not change, so a list is not
     * sorted again for nothing; guarded by the monitor as StarStore.update is
     * (data/settings/StarStore.kt: @Synchronized over the flow's value).
     */
    @Synchronized
    private fun bare(senders: Map<String, Boolean>): SpamFilter =
        plain?.takeIf { it.senders == senders } ?: SpamFilter(senders).also { plain = it }

    /** Report spam (Google Messages): the sender is reported, the conversation's received messages teach the classifier. */
    fun report(key: String, received: List<Pair<Int, String>>) = update { f ->
        var samples = f.samples
        received.forEach { (hash, text) -> samples = Spams.label(samples, hash, text, spam = true) }
        f.copy(samples = samples, senders = Spams.sender(f.senders, key, true))
    }

    /** Not spam (Google Messages): the sender is cleared, the messages teach the classifier they are fine. */
    fun notSpam(key: String, received: List<Pair<Int, String>>) = update { f ->
        var samples = f.samples
        received.forEach { (hash, text) -> samples = Spams.label(samples, hash, text, spam = false) }
        f.copy(samples = samples, senders = Spams.sender(f.senders, key, false))
    }
}

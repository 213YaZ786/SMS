package com.yaz.sms.data.settings

import android.content.Context
import com.yaz.sms.core.common.writeTextAtomically
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An emoji and how many times the user sent it as a reaction. */
@Serializable
data class ReactionUse(val emoji: String, val total: Int)

/** What the file holds: the newest used first. */
@Serializable
data class ReactionUses(val items: List<ReactionUse> = emptyList())

/**
 * The reactions the user sends most, pure.
 *
 * [add] and [mostUsed] are Element Web's recent emoji (AGPL-3.0 OR GPL-3.0,
 * https://github.com/element-hq/element-web, apps/web/src/emojipicker/recent.ts): each use takes the emoji out
 * of the list, adds one to its total and puts it first, the list kept to 100; the most used come by a stable
 * sort on the total, so the newest decides between equal totals. FlorisBoard's EmojiHistory
 * (https://github.com/florisboard/florisboard, app/src/main/kotlin/dev/patrickgold/florisboard/ime/media/emoji/EmojiHistory.kt,
 * Apache-2.0) keeps its history in one small list with a size cap and a lock around each change, as here; it
 * orders by recency only and holds no count, so the count is Element Web's.
 *
 * The reaction bar's defaults are the order of Unicode's emoji frequency ranking
 * (https://home.unicode.org/emoji/emoji-frequency/, "Ranked Order by Median Frequency": 😂 and ❤️ in group 0,
 * 🙏 😭 in 2, 👍 in 3, 😮 in 6), for the six reactions people use most in answers.
 */
object ReactionHistory {

    /** Element Web's STORAGE_LIMIT. */
    const val STORAGE_LIMIT = 100

    /** The slots of the long press bar. */
    const val SLOTS = 6

    /** Unicode's ranking, among the reactions of messages. */
    val DEFAULTS = listOf("😂", "❤️", "🙏", "😭", "👍", "😮")

    /** recent.ts add(): the emoji out of the list, its total one more, first. */
    fun add(uses: List<ReactionUse>, emoji: String): List<ReactionUse> {
        if (emoji.isEmpty() || emoji.length > 32) return uses
        val old = uses.firstOrNull { it.emoji == emoji }
        val entry = ReactionUse(emoji, (old?.total ?: 0) + 1)
        return (listOf(entry) + uses.filter { it.emoji != emoji }).take(STORAGE_LIMIT)
    }

    /** recent.ts get(limit): a stable sort on the total, descending, the first [limit]. */
    fun mostUsed(uses: List<ReactionUse>, limit: Int = SLOTS): List<String> =
        uses.sortedByDescending { it.total }.take(limit).map { it.emoji }

    /**
     * The bar from left to right: [slots] emoji, the most used at the right end next to "+", the least used at the
     * left. The user's most used take the slots first, those left are filled by [DEFAULTS] in their order of
     * frequency, no emoji twice; each use changes the counts and so the order and who stays.
     */
    fun bar(uses: List<ReactionUse>, slots: Int = SLOTS): List<String> =
        (mostUsed(uses, slots) + DEFAULTS).distinct().take(slots).reversed()

    /** A file read back: what is no emoji entry is dropped, the count capped. */
    fun parse(text: String, json: Json): List<ReactionUse> = runCatching {
        json.decodeFromString<ReactionUses>(text).items
            .filter { it.emoji.isNotEmpty() && it.emoji.length <= 32 && it.total > 0 }
            .distinctBy { it.emoji }
            .take(STORAGE_LIMIT)
    }.getOrDefault(emptyList())
}

/**
 * The reactions the user sent, counted, in a small file of their own written atomically, the way StarStore keeps
 * its set (data/settings/StarStore.kt and core/common/AtomicWrite.kt). Nothing here leaves the device.
 */
class ReactionStore(context: Context) {

    private val file = File(context.filesDir, "reactions-used.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _uses = MutableStateFlow(load())

    /** The newest used first, with their totals. */
    val uses: StateFlow<List<ReactionUse>> = _uses.asStateFlow()

    private fun load(): List<ReactionUse> =
        if (!file.exists()) emptyList() else ReactionHistory.parse(runCatching { file.readText() }.getOrDefault(""), json)

    /** The user sent [emoji] as a reaction. */
    @Synchronized
    fun used(emoji: String) {
        val updated = ReactionHistory.add(_uses.value, emoji)
        if (updated == _uses.value) return
        _uses.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(ReactionUses.serializer(), ReactionUses(updated))) }
    }
}

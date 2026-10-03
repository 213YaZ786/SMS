package com.sms.app.core.sms

/**
 * A poll in the encrypted chat: plain text anyone can read (📊 and the
 * question, then each choice behind its number), and each vote a reaction
 * with that number on it. The chat keeps one reaction per person, so a
 * vote can be changed or taken back, and every member sees the same count.
 */
object Polls {

    const val MAX = 6
    private const val MARK = "📊"

    /** The numbers that are the votes: 1️⃣ to 6️⃣. */
    val keys: List<String> = (1..MAX).map { "$it️⃣" }

    data class Poll(val question: String, val options: List<String>)

    fun write(question: String, options: List<String>): String {
        val kept = options.map { it.trim().replace('\n', ' ') }.filter { it.isNotEmpty() }.take(MAX)
        return "$MARK ${question.trim().replace('\n', ' ')}\n" + kept.mapIndexed { i, o -> "${keys[i]} $o" }.joinToString("\n")
    }

    /** The poll [text] is, or null: the mark and a question, then two choices or more in order, nothing else. */
    fun read(text: String): Poll? {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val head = lines.firstOrNull() ?: return null
        if (!head.startsWith(MARK)) return null
        val question = head.removePrefix(MARK).trim()
        if (question.isEmpty()) return null
        val options = lines.drop(1).mapIndexed { i, line ->
            if (i >= MAX) return null
            val bare = "${i + 1}⃣"
            val rest = when {
                line.startsWith(keys[i]) -> line.removePrefix(keys[i])
                line.startsWith(bare) -> line.removePrefix(bare)
                else -> return null
            }.trim()
            rest.ifEmpty { return null }
        }
        return if (options.size >= 2) Poll(question, options) else null
    }

    /** Which choice a reaction is a vote for, or -1. */
    fun choiceOf(reaction: String): Int {
        val bare = reaction.replace("️", "")
        return keys.indexOfFirst { it.replace("️", "") == bare }
    }

    /** Votes per choice from the reactions' counts. */
    fun tally(counts: Map<String, Int>, choices: Int): List<Int> {
        val votes = IntArray(choices)
        counts.forEach { (emoji, n) -> choiceOf(emoji).takeIf { it in 0 until choices }?.let { votes[it] += n } }
        return votes.toList()
    }
}

package com.yaz.sms.core.sms

/**
 * Effects a message is sent with: on its bubble (slammed down, loud,
 * gentle, hidden under ink until touched) or over the whole screen
 * (fireworks, confetti, balloons…). Over the encrypted chat the effect
 * travels with the words as a few zero-width characters at their end,
 * which other apps show as nothing; an ordinary SMS carries none (one such
 * character would double its price), but words like "happy birthday" bring
 * their effect on both sides, as they do elsewhere.
 */
object Effects {

    enum class Effect(val screen: Boolean) {
        SLAM(false), LOUD(false), GENTLE(false), INK(false),
        FIREWORKS(true), CONFETTI(true), BALLOONS(true), LOVE(true), LASERS(true), STARS(true), CELEBRATION(true), ECHO(true), SPOTLIGHT(true)
    }

    // An invisible start, then the effect's number in two bits a character.
    private const val START = '⁤'
    private val BITS = charArrayOf('​', '‌', '‍', '⁠')

    /** [text] carrying [effect], invisibly. */
    fun mark(text: String, effect: Effect): String {
        val n = effect.ordinal
        return text + START + BITS[(n shr 4) and 3] + BITS[(n shr 2) and 3] + BITS[n and 3]
    }

    /** The words without the mark, and the effect they carry, if any. */
    fun read(text: String): Pair<String, Effect?> {
        val at = text.lastIndexOf(START)
        if (at < 0 || text.length - at != 4) return text to null
        val digits = text.substring(at + 1).map { BITS.indexOf(it) }
        if (digits.any { it < 0 }) return text to null
        val n = (digits[0] shl 4) or (digits[1] shl 2) or digits[2]
        return text.substring(0, at) to Effect.entries.getOrNull(n)
    }

    /** The words only, for anything that shows a message without playing it. */
    fun plain(text: String): String = read(text).first

    // Words that bring an effect with them, in the languages most texts come in.
    private val words: List<Pair<Effect, Regex>> = listOf(
        Effect.BALLOONS to "happy birthday|joyeux anniversaire|bon anniversaire|feliz cumpleaños|feliz cumpleanos|alles gute zum geburtstag|buon compleanno|feliz aniversário|feliz aniversario|parabéns pelo aniversário|gefeliciteerd met je verjaardag|fijne verjaardag|wszystkiego najlepszego|doğum günün kutlu olsun|с днём рождения|с днем рождения",
        Effect.FIREWORKS to "happy new year|bonne année|bonne annee|feliz año nuevo|feliz ano nuevo|frohes neues jahr|felice anno nuovo|buon anno|feliz ano novo|gelukkig nieuwjaar|szczęśliwego nowego roku|mutlu yıllar|с новым годом",
        Effect.CONFETTI to "congratulations|congrats|félicitations|felicitations|felicidades|enhorabuena|herzlichen glückwunsch|glückwunsch|congratulazioni|complimenti|parabéns|parabens|gefeliciteerd|gratulacje|tebrikler|поздравляю",
        Effect.CELEBRATION to "happy lunar new year|joyeux nouvel an chinois|bonne fête|bonne fete|feliz día|frohes fest|buona festa",
        Effect.LASERS to "pew pew",
        Effect.LOVE to "i love you|je t'aime|je t’aime|te quiero|te amo|ich liebe dich|ti amo|ik hou van je|kocham cię|seni seviyorum|я тебя люблю"
    ).map { (e, w) -> e to Regex("(?<![\\p{L}])($w)(?![\\p{L}])", RegexOption.IGNORE_CASE) }

    /** The screen effect [text]'s own words bring, if any. */
    fun fromWords(text: String): Effect? = words.firstOrNull { (_, r) -> r.containsMatchIn(text) }?.first
}

package com.sms.app.core.sms

import android.app.Person
import android.content.Context
import android.view.textclassifier.ConversationAction
import android.view.textclassifier.ConversationActions
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import com.sms.app.data.sms.Box
import com.sms.app.data.sms.Message
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Short answers to the last message received, from Android's own text
 * engine on the phone (the one Android's keyboards and messaging apps
 * use): in the conversation's language, nothing leaves the phone. A phone
 * whose engine has none gives none.
 */
object Replies {

    private val other = Person.Builder().setKey("other").build()

    /** Up to three replies to the end of [messages] (oldest first): call off the main thread. */
    fun suggest(context: Context, messages: List<Message>): List<String> = runCatching {
        val last = messages.lastOrNull { it.body.isNotBlank() } ?: return emptyList()
        if (last.box != Box.RECEIVED) return emptyList()
        val talk = messages.filter { it.body.isNotBlank() }.takeLast(8).map { m ->
            ConversationActions.Message.Builder(if (m.box == Box.RECEIVED) other else ConversationActions.Message.PERSON_USER_SELF)
                .setText(m.body.take(500))
                .setReferenceTime(ZonedDateTime.ofInstant(Instant.ofEpochMilli(m.date), ZoneId.systemDefault()))
                .build()
        }
        val request = ConversationActions.Request.Builder(talk)
            .setHints(listOf(ConversationActions.Request.HINT_FOR_IN_APP))
            .setMaxSuggestions(3)
            .setTypeConfig(
                TextClassifier.EntityConfig.Builder()
                    .setIncludedTypes(listOf(ConversationAction.TYPE_TEXT_REPLY))
                    .includeTypesFromTextClassifier(false)
                    .build()
            )
            .build()
        val classifier = context.getSystemService(TextClassificationManager::class.java).textClassifier
        classifier.suggestConversationActions(request).conversationActions
            .mapNotNull { it.textReply?.toString()?.trim()?.takeIf { r -> r.isNotEmpty() } }
            .distinct()
            .take(3)
            // Android's engine serves few apps but its own: the common cases are answered here.
            .ifEmpty { ReplyRules.suggest(last.body, java.util.Locale.getDefault().language) }
    }.getOrDefault(emptyList())
}

/**
 * Answers for the messages people get most, in the message's own language
 * (read from the message, else the phone's) when the app has words in it:
 * a thank-you, "call me", "how are you", a meeting, a yes-or-no question,
 * a hello. Anything else gets nothing rather than something beside the point.
 */
object ReplyRules {

    private enum class Kind { THANKS, CALL_ME, HOW_ARE_YOU, MEETING, QUESTION, HELLO }

    // Everyday words, and the words of the messages answered here, that tell a language.
    private val words = mapOf(
        "en" to "the you are is to and do can will what we i it for my your have thanks thank hello hi hey how call me tomorrow see lunch still got",
        "fr" to "le la les tu vous est et je pour on de des pas que ce merci salut coucou bonjour appelle moi demain ça va se voit hier soir colis reçu à",
        "es" to "el la los tú usted es y yo para que de no se lo gracias muchas por todo hola llámame mañana qué cómo estás",
        "de" to "der die das du sie ist und ich für nicht wir es zu danke hallo ruf mich morgen hast bekommen wie geht's",
        "it" to "il la le tu lei è e io per che di non ci grazie mille ciao chiamami domani cena stai",
        "pt" to "o a os tu você é e eu para que de não se obrigado obrigada pela oi olá amanhã ajuda tudo bem",
        "nl" to "de het een je jij is en ik voor niet we bedankt hoi bel morgen eten gaat"
    ).mapValues { it.value.split(' ').toSet() }

    /** The language of [text], from its everyday words; [fallback] when it says too little. */
    fun language(text: String, fallback: String): String {
        val tokens = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        val scores = words.mapValues { (_, set) -> tokens.count { it in set } }
        val top = scores.values.maxOrNull() ?: 0
        val tied = scores.filterValues { it == top && top > 0 }.keys
        return when {
            tied.size == 1 -> tied.first()
            fallback in tied || (tied.isEmpty() && fallback in words) -> fallback
            tied.isNotEmpty() -> tied.first()
            else -> "en"
        }
    }

    private val patterns: List<Pair<Kind, Regex>> = listOf(
        Kind.THANKS to "\\b(thanks|thank you|thx|merci|gracias|danke|grazie|obrigad[oa]|valeu|bedankt|dank je|dank u)\\b",
        Kind.CALL_ME to "(call me|give me a call|appelle[- ]moi|rappelle[- ]moi|tu peux m'appeler|llámame|llamame|ruf mich an|chiamami|me liga|me ligue|bel me)",
        Kind.HOW_ARE_YOU to "(how are you|how's it going|ça va ?|ca va ?|comment vas[- ]tu|comment ça va|qué tal|que tal|cómo estás|wie geht'?s|wie geht es dir|come stai|come va|tudo bem|como vai|hoe gaat het|alles goed)",
        Kind.HELLO to "^\\s*(hi|hello|hey|salut|bonjour|coucou|hola|hallo|servus|ciao|olá|ola|oi|hoi)\\b[\\s!.,👋]*$"
    ).map { (k, p) -> k to Regex(p, setOf(RegexOption.IGNORE_CASE)) }

    // The app's words are English until its translation (many languages, done for every
    // text at once): until then, answers are offered to messages written in English only.
    private val answers: Map<String, Map<Kind, List<String>>> = mapOf(
        "en" to mapOf(
            Kind.THANKS to listOf("You're welcome!", "Anytime 😊"),
            Kind.CALL_ME to listOf("I'll call you", "Can't talk right now"),
            Kind.HOW_ARE_YOU to listOf("Good, and you?", "All good 😊"),
            Kind.MEETING to listOf("Sounds good!", "Works for me", "I can't make it"),
            Kind.QUESTION to listOf("Yes", "No", "I'll let you know"),
            Kind.HELLO to listOf("Hi! 👋", "Hey, how are you?")
        )
    )

    fun suggest(text: String, phoneLanguage: String): List<String> {
        val t = text.trim()
        if (t.isEmpty() || t.length > 300 || Codes.find(t) != null) return emptyList()
        val kind = patterns.firstOrNull { (_, r) -> r.containsMatchIn(t) }?.first
            ?: if (Finds.appointment(t) != null) Kind.MEETING
            else if (t.endsWith("?") || t.endsWith("？")) Kind.QUESTION
            else null
        kind ?: return emptyList()
        // A question about a meeting is a meeting.
        val asked = if (kind == Kind.QUESTION && Finds.appointment(t) != null) Kind.MEETING else kind
        return answers[language(t, phoneLanguage)]?.get(asked).orEmpty()
    }
}

package com.yaz.sms.core.sms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A conversation to bring forward, asked from outside the screen: a
 * notification, another app writing to someone ([text] only fills the
 * message, the user sends), text shared to choose who gets it.
 */
data class OpenRequest(val threadId: Long?, val address: String?, val text: String?)

class OpenRequests {
    /** A conversation to open on its encryption's keys (the Contacts app's "verified" mark), by number. */
    val keysFor = MutableStateFlow<String?>(null)

    /**
     * A message (its uid) the open conversation scrolls to once, set by the starred
     * list; the same one-shot request the keys use, cleared when taken.
     */
    val showMessage = MutableStateFlow<String?>(null)

    /** A chat invite link from outside (a code read by the camera, a link tapped): asked, never joined unasked. */
    val invite = MutableStateFlow<String?>(null)

    /**
     * Pictures or films shared from another app (as AOSP Messaging's
     * ShareIntentActivity takes them): they fill the chosen conversation's
     * composer, the user sends. Copies of the app's own, taken once.
     */
    val sharedMedia = MutableStateFlow<List<com.yaz.sms.core.mms.Attachment>>(emptyList())

    /**
     * The address of the conversation [sharedMedia] is for: the number the
     * share named, else the one picked. Only that conversation takes it,
     * never one already open behind (AOSP Messaging hands the draft to the
     * conversation picked, ShareIntentActivity.onConversationClick).
     */
    val sharedTo = MutableStateFlow<String?>(null)

    private val _pending = MutableStateFlow<OpenRequest?>(null)
    val pending: StateFlow<OpenRequest?> = _pending.asStateFlow()

    fun open(request: OpenRequest) {
        _pending.value = request
    }

    fun consume() {
        _pending.value = null
    }

    companion object {
        private const val MAX_TEXT = 5_000

        /** The numbers of an sms:/smsto: link, kept to what can be dialled. */
        fun addressesOf(raw: String?): List<String> =
            raw.orEmpty().substringBefore('?').split(',', ';')
                .map { part -> part.filter { it.isDigit() || it == '+' }.take(32) }
                .filter { it.isNotEmpty() }

        /** A text handed by another app, cut to a sane length. */
        fun textOf(raw: CharSequence?): String? = raw?.toString()?.take(MAX_TEXT)?.takeIf { it.isNotBlank() }
    }
}

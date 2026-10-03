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

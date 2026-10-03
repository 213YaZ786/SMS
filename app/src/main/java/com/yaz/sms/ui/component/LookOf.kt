package com.yaz.sms.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.yaz.sms.core.dial.ContactLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The look the Contacts app keeps for whoever has [number], read off the main thread; null until read or without. */
@Composable
fun rememberLook(number: String?): State<ContactLook.Look?> {
    val context = LocalContext.current
    return produceState<ContactLook.Look?>(null, number) {
        value = number?.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.IO) { ContactLook.ofNumber(context, it) } }
    }
}

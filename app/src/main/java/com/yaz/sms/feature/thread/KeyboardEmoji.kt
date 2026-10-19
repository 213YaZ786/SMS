package com.yaz.sms.feature.thread

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp

/**
 * Our Keyboard app as the phone's current keyboard, and the word that makes it open on its emoji page.
 *
 * The current keyboard is read as HeliBoard reads its own state, UncachedInputMethodManagerUtils.isThisImeCurrent
 * (https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/utils/UncachedInputMethodManagerUtils.java):
 * Settings.Secure.DEFAULT_INPUT_METHOD, a flattened component name. Its package must be Keyboard's and signed as
 * this app, compared as the phone kit's Sisters.isOurs does (PackageManager.checkSignatures,
 * https://developer.android.com/reference/android/content/pm/PackageManager#checkSignatures(java.lang.String,%20java.lang.String));
 * the debug package counts in a debug build only.
 */
object KeyboardEmoji {

    /** The word Keyboard reads in EditorInfo.privateImeOptions to open on its emoji page (Keyboard's EmojiFirst). */
    const val OPTION = "com.yaz.keyboard.emoji"

    private fun packages(debug: Boolean) = if (debug) listOf("com.yaz.keyboard", "com.yaz.keyboard.debug") else listOf("com.yaz.keyboard")

    /** Whether [pkg] is a package of Keyboard in this kind of build. */
    fun isKeyboardPackage(pkg: String?, debug: Boolean): Boolean = pkg != null && pkg in packages(debug)

    /** The package of a flattened component name, or null. */
    fun packageOf(flattened: String?): String? = flattened?.let { ComponentName.unflattenFromString(it)?.packageName }

    /** Whether the phone's current keyboard is Keyboard, ours and signed with our key. */
    fun isCurrent(context: Context): Boolean {
        val pkg = packageOf(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
        if (!isKeyboardPackage(pkg, com.yaz.sms.BuildConfig.DEBUG)) return false
        return runCatching { context.packageManager.checkSignatures(context.packageName, pkg!!) == PackageManager.SIGNATURE_MATCH }
            .getOrDefault(false)
    }

    /**
     * The first emoji of [text], or null when it starts with none. The first user-perceived character
     * (extended grapheme cluster, UAX #29, so a flag, a skin tone or a family stays whole) from Android's ICU
     * BreakIterator character instance (https://developer.android.com/reference/android/icu/text/BreakIterator),
     * kept when it holds an emoji by the Unicode properties Emoji_Presentation or Extended_Pictographic
     * (UProperty, https://developer.android.com/reference/android/icu/lang/UProperty) or is a keycap sequence
     * (UTS #51, a digit, # or * with U+20E3).
     */
    fun firstEmoji(text: String): String? {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return null
        val rest = text.substring(start)
        val breaks = android.icu.text.BreakIterator.getCharacterInstance()
        breaks.setText(rest)
        val cluster = rest.substring(0, breaks.next().takeIf { it > 0 } ?: return null)
        val keycap = cluster.endsWith("⃣") && cluster.first() in "0123456789#*"
        val pictograph = cluster.codePoints().anyMatch { cp ->
            android.icu.lang.UCharacter.hasBinaryProperty(cp, android.icu.lang.UProperty.EMOJI_PRESENTATION) ||
                android.icu.lang.UCharacter.hasBinaryProperty(cp, android.icu.lang.UProperty.EXTENDED_PICTOGRAPHIC)
        }
        return cluster.takeIf { keycap || pictograph }
    }
}

/**
 * The "+" of the reaction bar with our Keyboard: the keyboard opens at once on its emoji page over the conversation
 * and the first emoji tapped is the pick. Nothing shows but the keyboard: the field that receives the emoji is
 * 1 dp wide and fully transparent, a focus target only, the way HeliBoard's GestureDataScreen hides its own
 * ("Box(Modifier.size(1.dp).alpha(0f)) { // box hides the field, but we can still interact with it", with
 * KeyboardOptions(platformImeOptions = PlatformImeOptions(...)) and a FocusRequester asked in a LaunchedEffect),
 * https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/settings/screens/gesturedata/GestureDataScreen.kt
 * and the same options in keyboard/emoji/EmojiSearchActivity.kt. It lives in the screen, outside the list, so it
 * outlasts the message that asked (a list item leaves the composition when the keyboard shrinks the list).
 *
 * It closes without a pick on a tap anywhere else (a transparent layer over the conversation), on Back (Compose's
 * BackHandler, https://developer.android.com/develop/ui/compose/system/back; the keyboard's own Back first hides it,
 * which closes this too, the IME insets going from shown to hidden) or when the field loses focus. At the end the focus
 * is cleared and the keyboard hidden (FocusManager.clearFocus and SoftwareKeyboardController.hide,
 * https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior), so no field of the
 * screen takes it over.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardEmojiTarget(onPick: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf("") }
    // Ends once, whatever comes first: a pick, a tap outside, Back, the keyboard hidden, the focus lost.
    val ended = remember { booleanArrayOf(false) }
    fun end(emoji: String?) {
        if (ended[0]) return
        ended[0] = true
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        if (emoji != null) onPick(emoji) else onClose()
    }
    var hadFocus by remember { mutableStateOf(false) }
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) {
        if (imeUp) imeSeen = true else if (imeSeen) end(null)
    }
    BackHandler { end(null) }
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close") { end(null) }
    ) {
        Box(Modifier.size(1.dp).alpha(0f).clearAndSetSemantics { }) {
            BasicTextField(
                value = text,
                onValueChange = { typed ->
                    val emoji = KeyboardEmoji.firstEmoji(typed)
                    if (emoji != null) end(emoji) else text = typed.takeLast(40)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, platformImeOptions = PlatformImeOptions(KeyboardEmoji.OPTION)),
                modifier = Modifier.focusRequester(focus).onFocusChanged { state ->
                    if (state.isFocused) hadFocus = true else if (hadFocus) end(null)
                }
            )
        }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}

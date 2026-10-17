package com.yaz.sms.core.sms

/**
 * The one-time code in a message, when it carries one. Ported from AOSP ExtServices,
 * NotificationOtpDetectionHelper (Apache-2.0,
 * https://android.googlesource.com/platform/packages/modules/ExtServices/+/refs/heads/main/java/src/android/ext/services/notification/NotificationOtpDetectionHelper.java):
 * the START / OTP_CHAR / FIND_DIGIT / OTP_CHARS / END / ALL_OTP expressions as they are, the
 * false positives that are longer than a code (dashed dates, phone numbers with a space), the
 * three lowercase letters no code has, and the context words that must stand within 50
 * characters of the code, in the same sentence or the one before. This app's own earlier
 * detector required a context word anywhere in the message and left dates, amounts and
 * phone numbers alone by lookarounds; ExtServices' is the more exact (a word must be near the
 * code, a code must start and end like one), so it replaces it; what the old one knew and the
 * new did not is kept: the context words in seven languages and more (the AOSP list is English
 * only; it asks the TextClassifier for the language, here the words of every language are
 * looked for together), amounts in other currencies and the fullwidth punctuation of Chinese
 * and Japanese texts after a code.
 *
 * Differences from AOSP, each for a reason of this app: `\b` is replaced by explicit
 * lookarounds (since Java 19 `\b` is ASCII only by default, so the same pattern would read
 * Cyrillic differently on a JVM test and on a phone); a gap of 0 characters is allowed between
 * a context word and a code, as in "验证码903311"; coupon codes ("Code promo SOLDES20") join
 * the list of longer false positives.
 */
object Codes {

    private val Flags = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE, RegexOption.MULTILINE)

    /** AOSP: MAX_SENSITIVE_TEXT_LEN, the text read for a code. */
    private const val MAX_TEXT = 600

    // The expressions below are NotificationOtpDetectionHelper's, same names.
    private const val START = "(^|(?<=((^|[^0-9])\\s)|[>(\"'=\\[\\p{IsIdeographic}]|[^0-9]:))"
    private const val OTP_CHAR = "([0-9\\p{IsAlphabetic}&&[^\\p{IsIdeographic}]])"
    private const val OTP_CHAR_WITH_DASH = "($OTP_CHAR-?)"
    private const val FIND_DIGIT = "(?=$OTP_CHAR_WITH_DASH{0,7}\\d)"
    private const val OTP_CHARS = "($OTP_CHAR_WITH_DASH{4,7}$OTP_CHAR)"

    // AOSP's END, with the fullwidth marks that end a code in Chinese and Japanese texts.
    private const val END = "(?=\\s[^0-9]|\$|\\p{IsIdeographic}|[.?!,)'\\]\"，。！？；：、）】」』])"
    private const val FOUR_DIGITS = "(\\d{4})"
    private const val FIVE_TO_EIGHT_ALPHANUM_AT_LEAST_ONE_NUM = "($FIND_DIGIT$OTP_CHARS)"
    private const val SIX_DIGITS_WITH_SPACE = "(\\d{3}\\s\\d{3})"
    private const val ALL_OTP = "$START($FIVE_TO_EIGHT_ALPHANUM_AT_LEAST_ONE_NUM|$FOUR_DIGITS|$SIX_DIGITS_WITH_SPACE)$END"

    private val Otp = Regex(ALL_OTP, Flags)

    // AOSP: DATE_WITH_DASHES and PHONE_WITH_SPACE, the false positives longer than a code.
    private const val DATE_WITH_DASHES = "([0-3]?\\d-[0-3]?\\d-([12]\\d)?\\d\\d)"
    private const val PHONE_WITH_SPACE = "(\\(?\\d{3}\\)?(-|\\s)?\\d{3}(-|\\s)?\\d{4})"

    // This app's addition to that list, a coupon is not a code: a word of discounts, then the
    // coupon, so that "Code promo SOLDES20" has no one-time code in it.
    private const val COUPON = "(promo|coupon|voucher|discount|r[ée]duction|descuento|rabatt|sconto|desconto|korting|offre|offer|oferta|angebot)" +
        "\\p{L}*[\\s:]{1,3}((code|kode|codice|c[óo]digo)[\\s:]{1,3})?[0-9\\p{IsAlphabetic}-]{4,12}"

    private val FalsePositiveLonger = Regex("$START($DATE_WITH_DASHES|$PHONE_WITH_SPACE)$END|$COUPON", Flags)

    // AOSP: THREE_LOWERCASE, no known code has three lowercase letters in a row (case kept here).
    private val ThreeLowercase = Regex("\\p{Ll}{3}")

    // Words that stand next to a code. English: ENGLISH_CONTEXT_WORDS of AOSP; the other languages
    // are this app's own list (the earlier detector's words, widened), in regular expressions.
    private val Latin = listOf(
        // English
        """pin""", """pass[-\s]?(?:code|word)""", """TAN""", """otp""", """2fa""", """(?:two|2)[-\s]?factor""",
        """log[-\s]?in""", """sign[-\s]?in""", """auth(?:enticat(?:e|ion))?""", """code""", """secret""",
        """verif(?:y|ication)""", """one(?:\s|-)?time""", """access""", """validat(?:e|ion)""", """confirm(?:ation)?""",
        """security""",
        // Français
        """mot de passe""", """v[ée]rifi(?:cation|er|ez)""", """connexion""", """authentification""",
        """s[ée]curit[ée]""", """usage unique""", """identifiant""", """valid(?:er|ez|ation)""", """acc[èe]s""",
        // Español
        """c[óo]digo""", """clave""", """contrase[ñn]a""", """verific(?:aci[óo]n|ar)""", """acceso""",
        """autenticaci[óo]n""", """un solo uso""", """uso [úu]nico""", """seguridad""", """validaci[óo]n""",
        """confirmaci[óo]n""",
        // Deutsch
        """kode""", """best[äa]tigungs-?code""", """sicherheits-?code""", """einmal-?code""", """einmal-?passwort""",
        """verifizierungs-?code""", """freigabe-?code""", """aktivierungs-?code""", """passwort""", """kennwort""",
        """m?tan""", """verifizierung""", """best[äa]tigung""", """anmeldung""", """zugang(?:scode)?""",
        """sicherheit""", """freigabe""",
        // Italiano
        """codice""", """password""", """verifica""", """accesso""", """autenticazione""", """monouso""",
        """una tantum""", """sicurezza""", """conferma""", """convalida""",
        // Português
        """senha""", """verifica[çc][ãa]o""", """autentica[çc][ãa]o""", """seguran[çc]a""", """confirma[çc][ãa]o""",
        """valida[çc][ãa]o""",
        // Nederlands
        """verificatie(?:code)?""", """inlog-?code""", """wachtwoord""", """bevestigings-?code""",
        """beveiligings-?code""", """activatie-?code""", """pincode""", """eenmalige?""", """toegang""",
        """authenticatie""", """bevestiging""", """veiligheid""",
        // Others the earlier detector knew
        """kod""", """has[łl]o""", """weryfikacyjny""", """do[ğg]rulama""", """[şs]ifre""", """engangskode""",
        """l[öo]senord""", """vahvistuskoodi""", """код""", """пароль""", """رمز""", """कोड""", """ओटीपी"""
    )

    // Written without spaces: no boundary stands between two ideographs.
    private val Ideographic = listOf("验证码", "驗證碼", "認証コード", "確認コード", "인증번호", "验证", "驗證", "确认码")

    // In place of \b, which is ASCII only on a recent JVM and Unicode on Android.
    private const val BEFORE = "(?<![\\p{L}\\p{N}\\p{M}_])"
    private const val AFTER = "(?![\\p{L}\\p{N}\\p{M}_])"

    /**
     * AOSP: createDictionaryRegex and findContextWordWithCode. The word, then a code within 50
     * characters, in the same sentence or the one after the word; or a code, then the word within
     * 50 characters in the same sentence. The words are joined once (AOSP repeats the whole
     * expression for each word; the result is the same). The gap before a code is 0 to 50 here.
     */
    private val Context: Regex by lazy {
        val words = "(?:$BEFORE(?:${Latin.joinToString("|")})$AFTER|${Ideographic.joinToString("|")})"
        val wordBeforeOtpInSameOrPreviousSentence = "($words(?=.{0,50}$ALL_OTP)[^.?!]*[.?!]?[^.?!]*$ALL_OTP)"
        val wordAfterOtpSameSentence = "($ALL_OTP)[^.!?]{1,50}$words"
        Regex("($wordBeforeOtpInSameOrPreviousSentence|$wordAfterOtpSameSentence)", Flags)
    }

    /**
     * AOSP: containsOtp(text, checkForFalsePositives = true) with the language specific
     * expression, and the code that was found. A code is dropped when a longer false positive
     * holds it or when it has three lowercase letters in a row; one of those left must stand in
     * a context word's reach. A code of digits comes without its dashes and spaces.
     */
    fun find(text: String): String? {
        val t = if (text.length > MAX_TEXT) text.substring(0, MAX_TEXT) else text
        if (t.none(Char::isDigit)) return null
        val found = Otp.findAll(t).toList()
        if (found.isEmpty()) return null
        val longer = FalsePositiveLonger.findAll(t).map { it.range }.toList()
        val codes = found.filterNot { m ->
            longer.any { it.first <= m.range.first && m.range.last <= it.last } ||
                ThreeLowercase.containsMatchIn(m.value)
        }
        if (codes.isEmpty()) return null
        for (region in Context.findAll(t)) {
            for (m in codes) {
                if (m.range.first >= region.range.first && m.range.last <= region.range.last) return clean(m.value)
            }
        }
        return null
    }

    fun isCode(text: String): Boolean = find(text) != null

    private fun clean(value: String): String =
        if (value.all { it.isDigit() || it == '-' || it.isWhitespace() }) value.filter(Char::isDigit) else value
}

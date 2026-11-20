package com.voiceflowkeyboard.ime;

import java.util.regex.Pattern;

/**
 * Runs of Unicode white space, matched the same way on the phone and in JVM tests.
 *
 * <p>Android's regex engine is ICU, not the JDK's: it refuses the {@code (?U)} flag
 * ({@code UNICODE_CHARACTER_CLASS}) with a PatternSyntaxException, and its plain
 * {@code \s} is not the JDK's either. 0.8.2 compiled {@code "(?U)\\s+"} in a static
 * field, so the word lists' first use threw ExceptionInInitializerError on every sync
 * and the phone never reported to the NAS. JVM tests (Robolectric included) use the
 * JDK engine, so they never saw it.
 *
 * <p>This class lists the Unicode White_Space characters explicitly, the set the JDK's
 * {@code (?U)\s} matches, so both engines give the same result.
 */
final class WhiteSpace {
    /** Every Unicode White_Space code point, as a character class body. */
    static final String CLASS = "\\t\\n\\u000B\\f\\r\\u0020\\u0085\\u00A0\\u1680"
            + "\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";

    /** One or more white space characters. */
    static final Pattern RUN = Pattern.compile("[" + CLASS + "]+");

    private WhiteSpace() {
    }
}

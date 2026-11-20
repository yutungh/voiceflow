package com.voiceflowkeyboard.ime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language handling for dictated text that mixes Chinese and English.
 *
 * <ul>
 *   <li>{@link #transcriptInput} frames the transcript for a voice-style transform and,
 *       when it holds Chinese, restates the keep-the-language rule right next to it.</li>
 *   <li>{@link #translatedAway} spots a transform that translated the speaker's words
 *       (the Work style turning Chinese into English), so the caller can retry or keep
 *       the transcript instead.</li>
 *   <li>{@link #normalizeMixedScript} puts a space between an English word and a Chinese
 *       character, and gives an English clause English punctuation.</li>
 * </ul>
 */
final class DictationText {
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z][A-Za-z'’-]*");

    /** A transform must keep at least this share of each script the source used. */
    private static final int KEEP_DIVISOR = 4;
    /** Below this much of a script, a share test means nothing. */
    private static final int MIN_HAN = 4;
    private static final int MIN_LATIN_WORDS = 4;
    /** An English run inside a Chinese clause needs this many words to count as its own clause. */
    private static final int ENGLISH_CLAUSE_AFTER_CHINESE = 4;

    static final String STRICT_LANGUAGE_NOTE = "Language check: an earlier attempt translated part of this transcript. "
            + "Return every part in the language it was spoken: Chinese stays Chinese in Simplified characters, English stays English. "
            + "Do not translate any word, phrase or sentence.";

    private DictationText() {
    }

    /**
     * The user turn of a voice-style transform.
     *
     * @param keepLanguage false for the fun styles, which are defined in English
     * @param strict      true on the retry after {@link #translatedAway} caught a translation
     */
    static String transcriptInput(String transcript, boolean keepLanguage, boolean strict) {
        String text = transcript == null ? "" : transcript;
        if (!keepLanguage) {
            return "Transcript:\n" + text;
        }
        if (!containsHan(text)) {
            // English speech that came back with Chinese in it: the retry still says so.
            return strict ? STRICT_LANGUAGE_NOTE + "\n\nTranscript:\n" + text : "Transcript:\n" + text;
        }
        String header = "Transcript (it contains Chinese: keep every Chinese part in Chinese and every English part in English; do not translate):\n";
        return strict ? STRICT_LANGUAGE_NOTE + "\n\n" + header + text : header + text;
    }

    /**
     * Whether {@code output} dropped most of a script the speaker used, which means the
     * transform translated instead of cleaning up. Short sources are never flagged.
     */
    static boolean translatedAway(String source, String output) {
        String src = source == null ? "" : source;
        String out = output == null ? "" : output;
        int hanSource = countHan(src);
        if (hanSource >= MIN_HAN && countHan(out) * KEEP_DIVISOR < hanSource) {
            return true;
        }
        int latinSource = countLatinWords(src);
        return latinSource >= MIN_LATIN_WORDS && countLatinWords(out) * KEEP_DIVISOR < latinSource;
    }

    /**
     * 0.8.4: the same, with credits for the tidy-up's word list ({@link DriftCheck#credit}):
     * whether there is enough of a script to judge is decided on the transcript as it was
     * ({@code original}), the share on the texts with the credited words taken out.
     */
    static boolean translatedAway(String original, String source, String output) {
        String src = source == null ? "" : source;
        String out = output == null ? "" : output;
        if (countHan(original) >= MIN_HAN && countHan(out) * KEEP_DIVISOR < countHan(src)) {
            return true;
        }
        return countLatinWords(original) >= MIN_LATIN_WORDS && countLatinWords(out) * KEEP_DIVISOR < countLatinWords(src);
    }

    static boolean containsHan(String text) {
        return countHan(text) > 0;
    }

    static int countHan(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isHan(cp)) {
                count++;
            }
            i += Character.charCount(cp);
        }
        return count;
    }

    static int countLatinWords(String text) {
        if (text == null) {
            return 0;
        }
        Matcher matcher = LATIN_WORD.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Spacing and punctuation where Chinese and English meet:
     * <ul>
     *   <li>a full-width ，。？！；： that ends an English clause becomes its ASCII form
     *       followed by a space ("update，不然" becomes "update, 不然"); one that ends a
     *       Chinese clause, including a short English name inside it ("我用了 iPhone，很好"),
     *       stays full-width;</li>
     *   <li>an English letter directly beside a Chinese character gets one space between
     *       ("should先" becomes "should 先").</li>
     * </ul>
     * Text with no Chinese is returned unchanged.
     */
    static String normalizeMixedScript(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        boolean hasFullWidth = false;
        for (int i = 0; i < text.length(); i++) {
            if (asciiFor(text.charAt(i)) != 0) {
                hasFullWidth = true;
                break;
            }
        }
        if (!containsHan(text) && !hasFullWidth) {
            return text;
        }
        return spaceBetweenScripts(englishClausePunctuation(text));
    }

    private static String englishClausePunctuation(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            char ascii = asciiFor(c);
            if (ascii != 0 && endsEnglishClause(text, i)) {
                // Drop spaces before the mark, then write it with one space after.
                while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
                    out.setLength(out.length() - 1);
                }
                out.append(ascii);
                int next = i + 1;
                while (next < text.length() && text.charAt(next) == ' ') {
                    next++;
                }
                if (next < text.length() && !Character.isWhitespace(text.charAt(next)) && !isClosingMark(text.charAt(next))) {
                    out.append(' ');
                }
                i = next;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Whether the full-width mark at {@code index} ends a clause written in English. */
    private static boolean endsEnglishClause(String text, int index) {
        int j = index - 1;
        while (j >= 0 && text.charAt(j) == ' ') {
            j--;
        }
        if (j < 0 || !isAsciiLetterOrDigit(text.charAt(j))) {
            return false;
        }
        int start = j;
        boolean stoppedAtHan = false;
        while (start >= 0) {
            int cp = text.codePointBefore(start + 1);
            if (isHan(cp)) {
                stoppedAtHan = true;
                break;
            }
            if (isClauseBoundary((char) cp)) {
                break;
            }
            start -= Character.charCount(cp);
        }
        int words = countLatinWords(text.substring(start + 1, j + 1));
        return stoppedAtHan ? words >= ENGLISH_CLAUSE_AFTER_CHINESE : words >= 1;
    }

    private static String spaceBetweenScripts(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (previous >= 0
                    && ((isAsciiLetter(previous) && isHan(cp)) || (isHan(previous) && isAsciiLetter(cp)))) {
                out.append(' ');
            }
            out.appendCodePoint(cp);
            previous = cp;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static char asciiFor(char c) {
        switch (c) {
            case '，':
                return ',';
            case '。':
                return '.';
            case '？':
                return '?';
            case '！':
                return '!';
            case '；':
                return ';';
            case '：':
                return ':';
            default:
                return 0;
        }
    }

    private static boolean isClauseBoundary(char c) {
        return c == '\n' || c == '.' || c == '!' || c == '?' || c == ',' || c == ';' || c == ':'
                || c == '，' || c == '。' || c == '？' || c == '！' || c == '；' || c == '：' || c == '、';
    }

    private static boolean isClosingMark(char c) {
        return c == ')' || c == '）' || c == '"' || c == '”' || c == '\'' || c == '’' || c == '」' || c == '』';
    }

    private static boolean isHan(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    private static boolean isAsciiLetter(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }
}
